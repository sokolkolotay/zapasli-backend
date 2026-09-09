package ru.zapasli.backend.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import ru.zapasli.backend.config.DatabaseSettings
import java.sql.Connection
import javax.sql.DataSource

object DatabaseFactory {
    fun create(settings: DatabaseSettings): HikariDataSource {
        val config = HikariConfig().apply {
            jdbcUrl = settings.jdbcUrl
            username = settings.username
            password = settings.password
            maximumPoolSize = settings.maxPoolSize
            minimumIdle = 1
            connectionTimeout = 5_000
            validationTimeout = 2_000
            idleTimeout = 600_000
            maxLifetime = 1_800_000
            poolName = "zapasli-database"

            addDataSourceProperty("ApplicationName", "zapasli-backend")
            addDataSourceProperty("tcpKeepAlive", "true")
            addDataSourceProperty("reWriteBatchedInserts", "true")
        }

        return HikariDataSource(config)
    }

    fun migrate(dataSource: DataSource) =
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .validateMigrationNaming(true)
            .cleanDisabled(true)
            .load()
            .migrate()
}

fun interface ReadinessProbe {
    suspend fun isReady(): Boolean
}

class DatabaseReadinessProbe(
    private val dataSource: DataSource,
) : ReadinessProbe {
    override suspend fun isReady(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            dataSource.connection.use { connection ->
                connection.isHealthy()
            }
        }.getOrDefault(false)
    }

    private fun Connection.isHealthy(): Boolean =
        prepareStatement("SELECT 1").use { statement ->
            statement.queryTimeout = 2
            statement.executeQuery().use { result ->
                result.next() && result.getInt(1) == 1
            }
        }
}
