package ru.zapasli.backend.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.postgresql.util.PSQLException
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcAuthRepository(
    private val dataSource: DataSource,
) : AuthRepository {
    override suspend fun register(command: RegistrationCommand): RegistrationResult = databaseCall {
        dataSource.connection.use { connection ->
            try {
                connection.transaction {
                    prepareStatement(
                        """
                        INSERT INTO app_user (id, email_normalized, display_name, locale)
                        VALUES (?, ?, ?, ?)
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, command.user.id)
                        statement.setString(2, command.emailNormalized)
                        statement.setString(3, command.user.displayName)
                        statement.setString(4, command.user.locale)
                        statement.executeUpdate()
                    }

                    prepareStatement(
                        """
                        INSERT INTO password_credential (user_id, password_hash)
                        VALUES (?, ?)
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, command.user.id)
                        statement.setString(2, command.passwordHash)
                        statement.executeUpdate()
                    }

                    insertSession(command.user.id, command.session)
                    RegistrationResult.Created(command.user)
                }
            } catch (error: SQLException) {
                if (error.isConstraintViolation("uq_app_user_email_active")) {
                    RegistrationResult.EmailAlreadyRegistered
                } else {
                    throw error
                }
            }
        }
    }

    override suspend fun findCredential(emailNormalized: String): CredentialRecord? = databaseCall {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT u.id, u.display_name, u.locale, c.password_hash
                FROM app_user u
                JOIN password_credential c ON c.user_id = u.id
                WHERE u.email_normalized = ?
                  AND u.status = 'active'
                  AND u.deleted_at IS NULL
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, emailNormalized)
                statement.executeQuery().use { result ->
                    if (!result.next()) return@databaseCall null
                    CredentialRecord(
                        user = result.readUser(),
                        passwordHash = result.getString("password_hash"),
                    )
                }
            }
        }
    }

    override suspend fun createSession(userId: UUID, session: NewSession): Unit = databaseCall {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO refresh_session (
                    id, user_id, family_id, token_hash, expires_at, user_agent_hash
                )
                SELECT ?, u.id, ?, ?, ?, ?
                FROM app_user u
                WHERE u.id = ? AND u.status = 'active' AND u.deleted_at IS NULL
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, session.id)
                statement.setObject(2, session.familyId)
                statement.setBytes(3, session.tokenHash)
                statement.setTimestamp(4, Timestamp.from(session.expiresAt))
                statement.setNullableBytes(5, session.userAgentHash)
                statement.setObject(6, userId)
                check(statement.executeUpdate() == 1) { "Active auth user no longer exists" }
            }
        }
    }

    override suspend fun rotateSession(
        currentTokenHash: ByteArray,
        replacement: ReplacementSession,
        now: Instant,
    ): RotationResult = databaseCall {
        dataSource.connection.use { connection ->
            connection.transaction {
                val current = findSessionForUpdate(currentTokenHash)
                    ?: return@transaction RotationResult.Invalid

                if (current.revokedAt != null) {
                    if (current.replacedBySessionId != null) {
                        revokeFamily(current.familyId, now)
                        return@transaction RotationResult.ReuseDetected
                    }
                    return@transaction RotationResult.Invalid
                }

                if (!current.expiresAt.isAfter(now)) {
                    revokeSession(current.id, now)
                    return@transaction RotationResult.Invalid
                }

                val user = findActiveUser(current.userId)
                if (user == null) {
                    revokeFamily(current.familyId, now)
                    return@transaction RotationResult.Invalid
                }

                insertSession(
                    userId = current.userId,
                    session = NewSession(
                        id = replacement.id,
                        familyId = current.familyId,
                        tokenHash = replacement.tokenHash,
                        expiresAt = current.expiresAt,
                        userAgentHash = replacement.userAgentHash,
                    ),
                )
                prepareStatement(
                    """
                    UPDATE refresh_session
                    SET revoked_at = ?, last_used_at = ?, replaced_by_session_id = ?
                    WHERE id = ? AND revoked_at IS NULL
                    """.trimIndent(),
                ).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(now))
                    statement.setTimestamp(2, Timestamp.from(now))
                    statement.setObject(3, replacement.id)
                    statement.setObject(4, current.id)
                    check(statement.executeUpdate() == 1) { "Refresh session changed while locked" }
                }

                RotationResult.Rotated(
                    user = user,
                    sessionId = replacement.id,
                    expiresAt = current.expiresAt,
                )
            }
        }
    }

    override suspend fun revokeSessionFamily(currentTokenHash: ByteArray, now: Instant): Unit = databaseCall {
        dataSource.connection.use { connection ->
            connection.transaction {
                val current = findSessionForUpdate(currentTokenHash) ?: return@transaction
                revokeFamily(current.familyId, now)
            }
        }
    }

    override suspend fun findUser(userId: UUID): AuthUser? = databaseCall {
        dataSource.connection.use { connection ->
            connection.findActiveUser(userId)
        }
    }

    private fun Connection.insertSession(userId: UUID, session: NewSession) {
        prepareStatement(
            """
            INSERT INTO refresh_session (
                id, user_id, family_id, token_hash, expires_at, user_agent_hash
            ) VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, session.id)
            statement.setObject(2, userId)
            statement.setObject(3, session.familyId)
            statement.setBytes(4, session.tokenHash)
            statement.setTimestamp(5, Timestamp.from(session.expiresAt))
            statement.setNullableBytes(6, session.userAgentHash)
            statement.executeUpdate()
        }
    }

    private fun Connection.findSessionForUpdate(tokenHash: ByteArray): SessionRecord? =
        prepareStatement(
            """
            SELECT id, user_id, family_id, expires_at, revoked_at, replaced_by_session_id
            FROM refresh_session
            WHERE token_hash = ?
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setBytes(1, tokenHash)
            statement.executeQuery().use { result ->
                if (!result.next()) return@use null
                SessionRecord(
                    id = result.getObject("id", UUID::class.java),
                    userId = result.getObject("user_id", UUID::class.java),
                    familyId = result.getObject("family_id", UUID::class.java),
                    expiresAt = result.getTimestamp("expires_at").toInstant(),
                    revokedAt = result.getTimestamp("revoked_at")?.toInstant(),
                    replacedBySessionId = result.getObject("replaced_by_session_id", UUID::class.java),
                )
            }
        }

    private fun Connection.findActiveUser(userId: UUID): AuthUser? =
        prepareStatement(
            """
            SELECT id, display_name, locale
            FROM app_user
            WHERE id = ? AND status = 'active' AND deleted_at IS NULL
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { result ->
                if (result.next()) result.readUser() else null
            }
        }

    private fun Connection.revokeFamily(familyId: UUID, now: Instant) {
        prepareStatement(
            """
            UPDATE refresh_session
            SET revoked_at = COALESCE(revoked_at, ?)
            WHERE family_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(now))
            statement.setObject(2, familyId)
            statement.executeUpdate()
        }
    }

    private fun Connection.revokeSession(sessionId: UUID, now: Instant) {
        prepareStatement(
            """
            UPDATE refresh_session SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL
            """.trimIndent(),
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(now))
            statement.setObject(2, sessionId)
            statement.executeUpdate()
        }
    }

    private data class SessionRecord(
        val id: UUID,
        val userId: UUID,
        val familyId: UUID,
        val expiresAt: Instant,
        val revokedAt: Instant?,
        val replacedBySessionId: UUID?,
    )
}

private suspend fun <T> databaseCall(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

private fun <T> Connection.transaction(block: Connection.() -> T): T {
    val previousAutoCommit = autoCommit
    autoCommit = false
    return try {
        val result = block()
        commit()
        result
    } catch (error: Throwable) {
        rollback()
        throw error
    } finally {
        autoCommit = previousAutoCommit
    }
}

private fun ResultSet.readUser() = AuthUser(
    id = getObject("id", UUID::class.java),
    displayName = getString("display_name"),
    locale = getString("locale"),
)

private fun java.sql.PreparedStatement.setNullableBytes(index: Int, value: ByteArray?) {
    if (value == null) setNull(index, java.sql.Types.BINARY) else setBytes(index, value)
}

private fun SQLException.isConstraintViolation(constraintName: String): Boolean {
    var current: SQLException? = this
    while (current != null) {
        if (
            current.sqlState == "23505" &&
            ((current as? PSQLException)?.serverErrorMessage?.constraint == constraintName ||
                current.message.orEmpty().contains(constraintName))
        ) {
            return true
        }
        current = current.nextException
    }
    return false
}
