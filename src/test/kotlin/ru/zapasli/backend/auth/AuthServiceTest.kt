package ru.zapasli.backend.auth

import kotlinx.coroutines.runBlocking
import ru.zapasli.backend.config.AuthSettings
import ru.zapasli.backend.platform.ApiException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AuthServiceTest {
    private val instant = Instant.parse("2026-09-10T10:00:00Z")
    private val settings = AuthSettings(
        jwtSecret = ByteArray(32) { 1 },
        tokenHashSecret = ByteArray(32) { 2 },
        issuer = "zapasli-backend-test",
        audience = "zapasli-android-test",
        accessTokenTtl = Duration.ofMinutes(15),
        refreshTokenTtl = Duration.ofDays(30),
    )

    @Test
    fun `registration normalizes identity and never passes a raw refresh token to storage`() = runBlocking {
        val repository = FakeAuthRepository()
        val service = service(repository)

        val result = service.register(
            RegisterRequest(
                email = "  User@Example.COM ",
                password = "a sufficiently long password",
                displayName = "  Илья  ",
                locale = "ru",
            ),
            ClientContext("test-client"),
        )

        val command = assertNotNull(repository.registration)
        assertEquals("user@example.com", command.emailNormalized)
        assertEquals("Илья", command.user.displayName)
        assertEquals("encoded-test-hash", command.passwordHash)
        assertTrue(result.refreshToken.startsWith("zpr_"))
        assertNotEquals(result.refreshToken, command.session.tokenHash.decodeToString())
    }

    @Test
    fun `registration reports every invalid field without touching storage`() = runBlocking {
        val repository = FakeAuthRepository()
        val error = assertFailsWith<ApiException> {
            service(repository).register(
                RegisterRequest("invalid", "short", " ", "de"),
                ClientContext(null),
            )
        }

        assertEquals("VALIDATION_FAILED", error.code)
        assertEquals(setOf("email", "password", "displayName", "locale"), error.fieldErrors?.map { it.field }?.toSet())
        assertTrue(repository.registration == null)
    }

    @Test
    fun `login hides whether an email exists`() = runBlocking {
        val repository = FakeAuthRepository()
        val unknown = assertFailsWith<ApiException> {
            service(repository).login(
                LoginRequest("missing@example.com", "a sufficiently long password"),
                ClientContext(null),
            )
        }
        repository.credential = CredentialRecord(
            user = AuthUser(UUID.randomUUID(), "User", "ru"),
            passwordHash = "encoded-test-hash",
        )
        repository.passwordAccepted = false
        val wrongPassword = assertFailsWith<ApiException> {
            service(repository).login(
                LoginRequest("user@example.com", "another long password"),
                ClientContext(null),
            )
        }

        assertEquals("INVALID_CREDENTIALS", unknown.code)
        assertEquals(unknown.code, wrongPassword.code)
        assertEquals(unknown.message, wrongPassword.message)
    }

    @Test
    fun `refresh token reuse is exposed as a stable security error`() = runBlocking {
        val repository = FakeAuthRepository().apply {
            rotationResult = RotationResult.ReuseDetected
        }
        val refreshTokens = RefreshTokenService(settings.tokenHashSecret)
        val token = refreshTokens.issue().value
        val error = assertFailsWith<ApiException> {
            service(repository, refreshTokens).refresh(
                RefreshRequest(token),
                ClientContext(null),
            )
        }

        assertEquals("REFRESH_TOKEN_REUSE_DETECTED", error.code)
    }

    private fun service(
        repository: FakeAuthRepository,
        refreshTokens: RefreshTokenService = RefreshTokenService(settings.tokenHashSecret),
    ) = DefaultAuthService(
        repository = repository,
        passwordHasher = object : PasswordHasher {
            override fun hash(password: String) = "encoded-test-hash"
            override fun verify(password: String, encodedHash: String) = repository.passwordAccepted
        },
        accessTokens = AccessTokenService(settings),
        refreshTokens = refreshTokens,
        settings = settings,
        now = { instant },
    )

    private class FakeAuthRepository : AuthRepository {
        var registration: RegistrationCommand? = null
        var credential: CredentialRecord? = null
        var passwordAccepted: Boolean = true
        var rotationResult: RotationResult = RotationResult.Invalid

        override suspend fun register(command: RegistrationCommand): RegistrationResult {
            registration = command
            return RegistrationResult.Created(command.user)
        }

        override suspend fun findCredential(emailNormalized: String) = credential

        override suspend fun createSession(userId: UUID, session: NewSession) = Unit

        override suspend fun rotateSession(
            currentTokenHash: ByteArray,
            replacement: ReplacementSession,
            now: Instant,
        ) = rotationResult

        override suspend fun revokeSessionFamily(currentTokenHash: ByteArray, now: Instant) = Unit

        override suspend fun findUser(userId: UUID) = credential?.user
    }
}
