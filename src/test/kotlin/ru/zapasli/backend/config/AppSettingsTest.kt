package ru.zapasli.backend.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

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
            mapOf("DATABASE_PASSWORD" to secret),
        )

        assertEquals(8080, settings.httpPort)
        assertEquals("0.0.0.0", settings.httpHost)
        assertEquals(5, settings.database.maxPoolSize)
        assertFalse(secret in settings.database.toString())
        assertFalse(secret in settings.toString())
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
}
