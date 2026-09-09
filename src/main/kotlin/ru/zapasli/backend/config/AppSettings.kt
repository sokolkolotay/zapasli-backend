package ru.zapasli.backend.config

import ru.zapasli.backend.platform.BuildInfo

data class AppSettings(
    val httpHost: String,
    val httpPort: Int,
    val database: DatabaseSettings,
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
                buildInfo = BuildInfo(
                    version = environment["APP_VERSION"].safeBuildValue("dev"),
                    commit = environment["GIT_COMMIT"].safeBuildValue("unknown"),
                ),
            )
        }
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
