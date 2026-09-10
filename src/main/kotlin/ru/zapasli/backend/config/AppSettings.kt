package ru.zapasli.backend.config

import ru.zapasli.backend.platform.BuildInfo
import java.time.Duration
import java.util.Base64

data class AppSettings(
    val httpHost: String,
    val httpPort: Int,
    val database: DatabaseSettings,
    val auth: AuthSettings,
    val buildInfo: BuildInfo,
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): AppSettings {
            val httpPort = environment["HTTP_PORT"]
                ?.toIntOrNull()
                ?.takeIf { it in 1..65535 }
                ?: if (environment.containsKey("HTTP_PORT")) {
                    error("HTTP_PORT must be an integer from 1 to 65535")
                } else {
                    8080
                }

            val maxPoolSize = environment["DATABASE_MAX_POOL_SIZE"]
                ?.toIntOrNull()
                ?.takeIf { it in 2..20 }
                ?: if (environment.containsKey("DATABASE_MAX_POOL_SIZE")) {
                    error("DATABASE_MAX_POOL_SIZE must be an integer from 2 to 20")
                } else {
                    5
                }

            val password = environment["DATABASE_PASSWORD"]
                ?.takeIf(String::isNotBlank)
                ?: error("DATABASE_PASSWORD must be set")

            return AppSettings(
                httpHost = environment["HTTP_HOST"]?.takeIf(String::isNotBlank) ?: "0.0.0.0",
                httpPort = httpPort,
                database = DatabaseSettings(
                    jdbcUrl = environment["DATABASE_URL"]
                        ?.takeIf(String::isNotBlank)
                        ?: "jdbc:postgresql://localhost:5432/zapasli",
                    username = environment["DATABASE_USER"]
                        ?.takeIf(String::isNotBlank)
                        ?: "zapasli",
                    password = password,
                    maxPoolSize = maxPoolSize,
                ),
                auth = AuthSettings(
                    jwtSecret = environment.requiredBase64Secret("AUTH_JWT_SECRET_BASE64"),
                    tokenHashSecret = environment.requiredBase64Secret("AUTH_TOKEN_HASH_SECRET_BASE64"),
                    issuer = environment.safeAuthIdentifier("AUTH_JWT_ISSUER", "zapasli-backend"),
                    audience = environment.safeAuthIdentifier("AUTH_JWT_AUDIENCE", "zapasli-android"),
                    accessTokenTtl = Duration.ofSeconds(
                        environment.boundedLong("AUTH_ACCESS_TOKEN_TTL_SECONDS", 900, 300L..3_600L),
                    ),
                    refreshTokenTtl = Duration.ofDays(
                        environment.boundedLong("AUTH_REFRESH_TOKEN_TTL_DAYS", 30, 1L..90L),
                    ),
                ),
                buildInfo = BuildInfo(
                    version = environment["APP_VERSION"].safeBuildValue("dev"),
                    commit = environment["GIT_COMMIT"].safeBuildValue("unknown"),
                ),
            )
        }
    }
}

class AuthSettings(
    jwtSecret: ByteArray,
    tokenHashSecret: ByteArray,
    val issuer: String,
    val audience: String,
    val accessTokenTtl: Duration,
    val refreshTokenTtl: Duration,
) {
    val jwtSecret: ByteArray = jwtSecret.copyOf()
    val tokenHashSecret: ByteArray = tokenHashSecret.copyOf()

    init {
        require(this.jwtSecret.size >= MIN_SECRET_BYTES)
        require(this.tokenHashSecret.size >= MIN_SECRET_BYTES)
        require(issuer.isNotBlank())
        require(audience.isNotBlank())
        require(!accessTokenTtl.isNegative && !accessTokenTtl.isZero)
        require(!refreshTokenTtl.isNegative && !refreshTokenTtl.isZero)
    }

    override fun toString(): String = "AuthSettings(redacted)"

    companion object {
        const val MIN_SECRET_BYTES = 32
    }
}

class DatabaseSettings(
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val maxPoolSize: Int,
) {
    init {
        require(jdbcUrl.startsWith("jdbc:postgresql://")) {
            "DATABASE_URL must use the jdbc:postgresql:// scheme"
        }
        require(username.isNotBlank()) {
            "DATABASE_USER must not be blank"
        }
        require(password.isNotBlank()) {
            "DATABASE_PASSWORD must not be blank"
        }
        require(maxPoolSize in 2..20) {
            "DATABASE_MAX_POOL_SIZE must be from 2 to 20"
        }
    }

    override fun toString(): String = "DatabaseSettings(redacted)"
}

private fun String?.safeBuildValue(default: String): String =
    this
        ?.trim()
        ?.takeIf { it.matches(Regex("[A-Za-z0-9._+-]{1,64}")) }
        ?: default

private fun Map<String, String>.requiredBase64Secret(name: String): ByteArray {
    val encoded = this[name]?.takeIf(String::isNotBlank) ?: error("$name must be set")
    val decoded = runCatching { Base64.getDecoder().decode(encoded) }
        .getOrElse { error("$name must be valid Base64") }
    require(decoded.size in AuthSettings.MIN_SECRET_BYTES..128) {
        "$name must contain from ${AuthSettings.MIN_SECRET_BYTES} to 128 random bytes"
    }
    return decoded
}

private fun Map<String, String>.safeAuthIdentifier(name: String, default: String): String {
    val value = this[name]?.trim()?.takeIf(String::isNotEmpty) ?: default
    require(value.length <= 128 && value.none(Char::isISOControl)) {
        "$name must contain from 1 to 128 printable characters"
    }
    return value
}

private fun Map<String, String>.boundedLong(
    name: String,
    default: Long,
    range: LongRange,
): Long {
    val raw = this[name] ?: return default
    return raw.toLongOrNull()?.takeIf { it in range }
        ?: error("$name must be an integer from ${range.first} to ${range.last}")
}
