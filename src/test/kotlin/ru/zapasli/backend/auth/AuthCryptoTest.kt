package ru.zapasli.backend.auth

import com.auth0.jwt.exceptions.TokenExpiredException
import ru.zapasli.backend.config.AuthSettings
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AuthCryptoTest {
    @Test
    fun `argon2id hashes use the approved profile and unique salts`() {
        val hasher = Argon2idPasswordHasher()
        val password = "correct horse battery staple"

        val first = hasher.hash(password)
        val second = hasher.hash(password)

        assertTrue(first.startsWith("\$argon2id\$v=19\$m=19456,t=2,p=1\$"))
        assertNotEquals(first, second)
        assertTrue(hasher.verify(password, first))
        assertFalse(hasher.verify("incorrect password", first))
        assertFalse(hasher.verify(password, "not-a-password-hash"))
    }

    @Test
    fun `refresh tokens have stable keyed hashes and reject malformed input`() {
        val tokens = RefreshTokenService(ByteArray(32) { 7 })
        val issued = tokens.issue()

        assertTrue(issued.value.startsWith("zpr_"))
        assertContentEquals(issued.hash, tokens.hashIfValid(issued.value))
        assertTrue(tokens.hashIfValid(issued.value.dropLast(1) + "!") == null)
        assertFalse(issued.value.toByteArray().contentEquals(issued.hash))
    }

    @Test
    fun `access token verifier enforces issuer audience type and expiry`() {
        val service = AccessTokenService(authSettings())
        val now = Instant.now()
        val issued = service.issue(UUID.randomUUID(), UUID.randomUUID(), now)
        val verified = service.verifier.verify(issued.value)

        assertNotNull(verified.subject)
        assertTrue(verified.audience.contains("zapasli-android-test"))

        val expired = service.issue(
            userId = UUID.randomUUID(),
            sessionId = UUID.randomUUID(),
            now = now.minusSeconds(700),
        )
        assertFailsWith<TokenExpiredException> { service.verifier.verify(expired.value) }
    }

    private fun authSettings() = AuthSettings(
        jwtSecret = ByteArray(32) { 1 },
        tokenHashSecret = ByteArray(32) { 2 },
        issuer = "zapasli-backend-test",
        audience = "zapasli-android-test",
        accessTokenTtl = Duration.ofMinutes(5),
        refreshTokenTtl = Duration.ofDays(30),
    )
}
