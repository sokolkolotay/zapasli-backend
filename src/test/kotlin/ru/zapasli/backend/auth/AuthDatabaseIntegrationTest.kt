package ru.zapasli.backend.auth

import kotlinx.coroutines.runBlocking
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import ru.zapasli.backend.config.AuthSettings
import ru.zapasli.backend.config.DatabaseSettings
import ru.zapasli.backend.database.DatabaseFactory
import ru.zapasli.backend.platform.ApiException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
class AuthDatabaseIntegrationTest {
    @Test
    fun `registration login rotation reuse detection and logout form one secure session lifecycle`() = runBlocking {
        val dataSource = DatabaseFactory.create(
            DatabaseSettings(
                jdbcUrl = postgres.jdbcUrl,
                username = postgres.username,
                password = postgres.password,
                maxPoolSize = 2,
            ),
        )
        dataSource.use {
            DatabaseFactory.migrate(it)
            val settings = authSettings()
            val service = DefaultAuthService(
                repository = JdbcAuthRepository(it),
                passwordHasher = Argon2idPasswordHasher(),
                accessTokens = AccessTokenService(settings),
                refreshTokens = RefreshTokenService(settings.tokenHashSecret),
                settings = settings,
                now = Instant::now,
            )
            val email = "auth-${UUID.randomUUID()}@example.com"
            val password = "a sufficiently long integration password"

            val registered = service.register(
                RegisterRequest(email, password, "Integration User", "en"),
                ClientContext("integration-test"),
            )
            assertEquals("Integration User", service.currentUser(registered.user.id).displayName)

            val duplicate = assertFailsWith<ApiException> {
                service.register(
                    RegisterRequest(email.uppercase(), password, "Duplicate", "en"),
                    ClientContext(null),
                )
            }
            assertEquals("EMAIL_ALREADY_REGISTERED", duplicate.code)

            val invalidLogin = assertFailsWith<ApiException> {
                service.login(
                    LoginRequest(email, "a different incorrect password"),
                    ClientContext(null),
                )
            }
            assertEquals("INVALID_CREDENTIALS", invalidLogin.code)

            val loggedIn = service.login(
                LoginRequest(email, password),
                ClientContext("integration-test"),
            )
            val rotated = service.refresh(
                RefreshRequest(loggedIn.refreshToken),
                ClientContext("integration-test"),
            )
            assertFalse(loggedIn.refreshToken == rotated.refreshToken)

            val reuse = assertFailsWith<ApiException> {
                service.refresh(RefreshRequest(loggedIn.refreshToken), ClientContext(null))
            }
            assertEquals("REFRESH_TOKEN_REUSE_DETECTED", reuse.code)

            val revokedDescendant = assertFailsWith<ApiException> {
                service.refresh(RefreshRequest(rotated.refreshToken), ClientContext(null))
            }
            assertEquals("INVALID_REFRESH_TOKEN", revokedDescendant.code)

            service.logout(LogoutRequest(registered.refreshToken))
            val loggedOut = assertFailsWith<ApiException> {
                service.refresh(RefreshRequest(registered.refreshToken), ClientContext(null))
            }
            assertEquals("INVALID_REFRESH_TOKEN", loggedOut.code)

            it.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT c.password_hash
                    FROM password_credential c
                    WHERE c.user_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, registered.user.id)
                    statement.executeQuery().use { result ->
                        assertTrue(result.next())
                        val encodedHash = result.getString(1)
                        assertTrue(encodedHash.startsWith("\$argon2id\$"))
                        assertFalse(password in encodedHash)
                    }
                }
                connection.prepareStatement(
                    """
                    SELECT token_hash, revoked_at
                    FROM refresh_session
                    WHERE user_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, registered.user.id)
                    statement.executeQuery().use { result ->
                        var sessionCount = 0
                        while (result.next()) {
                            sessionCount += 1
                            assertEquals(32, result.getBytes("token_hash").size)
                            assertTrue(result.getTimestamp("revoked_at") != null)
                        }
                        assertEquals(3, sessionCount)
                    }
                }
            }
        }
    }

    private fun authSettings() = AuthSettings(
        jwtSecret = ByteArray(32) { 1 },
        tokenHashSecret = ByteArray(32) { 2 },
        issuer = "zapasli-backend-test",
        audience = "zapasli-android-test",
        accessTokenTtl = Duration.ofMinutes(15),
        refreshTokenTtl = Duration.ofDays(30),
    )

    companion object {
        @Container
        @JvmField
        val postgres = PostgreSQLContainer(
            DockerImageName.parse("postgres:18.6-alpine"),
        )
    }
}
