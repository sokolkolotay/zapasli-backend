package ru.zapasli.backend.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import ru.zapasli.backend.config.AuthSettings
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class IssuedAccessToken(
    val value: String,
    val expiresAt: Instant,
)

class AccessTokenService(
    private val settings: AuthSettings,
) {
    private val algorithm = Algorithm.HMAC256(settings.jwtSecret)

    val verifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer(settings.issuer)
        .withAudience(settings.audience)
        .withClaim("typ", ACCESS_TOKEN_TYPE)
        .acceptLeeway(CLOCK_SKEW_SECONDS)
        .build()

    fun issue(userId: UUID, sessionId: UUID, now: Instant): IssuedAccessToken {
        val expiresAt = now.plus(settings.accessTokenTtl)
        val value = JWT.create()
            .withIssuer(settings.issuer)
            .withAudience(settings.audience)
            .withSubject(userId.toString())
            .withJWTId(UUID.randomUUID().toString())
            .withClaim("typ", ACCESS_TOKEN_TYPE)
            .withClaim("sid", sessionId.toString())
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(expiresAt))
            .sign(algorithm)
        return IssuedAccessToken(value, expiresAt)
    }

    companion object {
        const val ACCESS_TOKEN_TYPE = "access"
        private const val CLOCK_SKEW_SECONDS = 5L
    }
}

data class IssuedRefreshToken(
    val value: String,
    val hash: ByteArray,
)

class RefreshTokenService(
    tokenHashSecret: ByteArray,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    private val hashKey = SecretKeySpec(tokenHashSecret.copyOf(), HMAC_ALGORITHM)

    fun issue(): IssuedRefreshToken {
        val entropy = ByteArray(TOKEN_BYTES).also(secureRandom::nextBytes)
        return try {
            val value = TOKEN_PREFIX + encoder.encodeToString(entropy)
            IssuedRefreshToken(value = value, hash = hash(value))
        } finally {
            entropy.fill(0)
        }
    }

    fun hashIfValid(value: String): ByteArray? {
        if (!value.startsWith(TOKEN_PREFIX) || value.length != TOKEN_LENGTH) return null
        val decoded = runCatching { decoder.decode(value.removePrefix(TOKEN_PREFIX)) }.getOrNull()
            ?: return null
        return try {
            if (decoded.size != TOKEN_BYTES) null else hash(value)
        } finally {
            decoded.fill(0)
        }
    }

    private fun hash(value: String): ByteArray = Mac.getInstance(HMAC_ALGORITHM).run {
        init(hashKey)
        doFinal(value.toByteArray(StandardCharsets.US_ASCII))
    }

    companion object {
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private const val TOKEN_PREFIX = "zpr_"
        private const val TOKEN_BYTES = 32
        private const val TOKEN_LENGTH = 47
        private val encoder = Base64.getUrlEncoder().withoutPadding()
        private val decoder = Base64.getUrlDecoder()
    }
}
