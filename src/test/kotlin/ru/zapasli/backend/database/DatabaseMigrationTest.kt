package ru.zapasli.backend.database

import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import ru.zapasli.backend.config.DatabaseSettings
import kotlin.test.Test
import kotlin.test.assertEquals

@Testcontainers(disabledWithoutDocker = true)
class DatabaseMigrationTest {
    @Test
    fun `foundation migration is repeatable and creates expected tables`() {
        val dataSource = DatabaseFactory.create(
            DatabaseSettings(
                jdbcUrl = postgres.jdbcUrl,
                username = postgres.username,
                password = postgres.password,
                maxPoolSize = 2,
            ),
        )

        dataSource.use {
            val firstRun = DatabaseFactory.migrate(it)
            val secondRun = DatabaseFactory.migrate(it)

            assertEquals(1, firstRun.migrationsExecuted)
            assertEquals(0, secondRun.migrationsExecuted)

            it.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = 'public'
                      AND table_name IN (
                        'app_user',
                        'password_credential',
                        'refresh_session',
                        'household',
                        'household_member',
                        'household_invite',
                        'storage_location',
                        'idempotency_record'
                      )
                    """.trimIndent(),
                ).use { statement ->
                    statement.executeQuery().use { result ->
                        result.next()
                        assertEquals(8, result.getInt(1))
                    }
                }
            }
        }
    }

    companion object {
        @Container
        @JvmField
        val postgres = PostgreSQLContainer(
            DockerImageName.parse("postgres:18.6-alpine"),
        )
    }
}
