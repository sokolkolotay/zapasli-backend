package ru.zapasli.backend.auth

import java.time.Instant
import java.util.UUID

data class NewSession(
    val id: UUID,
    val familyId: UUID,
    val tokenHash: ByteArray,
    val expiresAt: Instant,
    val userAgentHash: ByteArray?,
)

data class ReplacementSession(
    val id: UUID,
    val tokenHash: ByteArray,
    val userAgentHash: ByteArray?,
)

data class RegistrationCommand(
    val user: AuthUser,
    val emailNormalized: String,
    val passwordHash: String,
    val session: NewSession,
)

data class CredentialRecord(
    val user: AuthUser,
    val passwordHash: String,
)

sealed interface RegistrationResult {
    data object EmailAlreadyRegistered : RegistrationResult

    data class Created(val user: AuthUser) : RegistrationResult
}

sealed interface RotationResult {
    data object Invalid : RotationResult

    data object ReuseDetected : RotationResult

    data class Rotated(
        val user: AuthUser,
        val sessionId: UUID,
        val expiresAt: Instant,
    ) : RotationResult
}

interface AuthRepository {
    suspend fun register(command: RegistrationCommand): RegistrationResult

    suspend fun findCredential(emailNormalized: String): CredentialRecord?

    suspend fun createSession(userId: UUID, session: NewSession)

    suspend fun rotateSession(
        currentTokenHash: ByteArray,
        replacement: ReplacementSession,
        now: Instant,
    ): RotationResult

    suspend fun revokeSessionFamily(currentTokenHash: ByteArray, now: Instant)

    suspend fun findUser(userId: UUID): AuthUser?
}
