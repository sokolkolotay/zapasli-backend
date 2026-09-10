package ru.zapasli.backend.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import java.util.Base64

class AppSettingsTest {
    @Test
    fun `database password is required`() {
        assertFailsWith<IllegalStateException> {
            AppSettings.fromEnvironment(emptyMap())
        }
    }

    @Test
    fun `settings use safe defaults and never render the password`() {
        val secret = "this-value-must-never-appear"
        val settings = AppSettings.fromEnvironment(
            validEnvironment() + ("DATABASE_PASSWORD" to secret),
        )

        assertEquals(8080, settings.httpPort)
        assertEquals("0.0.0.0", settings.httpHost)
        assertEquals(5, settings.database.maxPoolSize)
        assertFalse(secret in settings.database.toString())
        assertFalse(secret in settings.toString())
        assertFalse(settings.auth.jwtSecret.contentToString() in settings.toString())
    }

    @Test
    fun `auth secrets must contain at least 32 decoded bytes`() {
        assertFailsWith<IllegalArgumentException> {
            AppSettings.fromEnvironment(
                validEnvironment() + (
                    "AUTH_JWT_SECRET_BASE64" to Base64.getEncoder()
                        .encodeToString(ByteArray(31) { 1 })
                    ),
            )
        }
    }

    @Test
    fun `non PostgreSQL JDBC URL is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            AppSettings.fromEnvironment(
                mapOf(
                    "DATABASE_PASSWORD" to "test-only-secret",
                    "DATABASE_URL" to "jdbc:h2:mem:test",
                ),
            )
        }
    }

    private fun validEnvironment(): Map<String, String> = mapOf(
        "DATABASE_PASSWORD" to "test-only-database-password",
        "AUTH_JWT_SECRET_BASE64" to Base64.getEncoder().encodeToString(ByteArray(32) { 1 }),
        "AUTH_TOKEN_HASH_SECRET_BASE64" to Base64.getEncoder().encodeToString(ByteArray(32) { 2 }),
    )
}
