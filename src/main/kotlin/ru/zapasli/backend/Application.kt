package ru.zapasli.backend

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import ru.zapasli.backend.auth.AccessTokenService
import ru.zapasli.backend.auth.Argon2idPasswordHasher
import ru.zapasli.backend.auth.DefaultAuthService
import ru.zapasli.backend.auth.JdbcAuthRepository
import ru.zapasli.backend.auth.RefreshTokenService
import ru.zapasli.backend.config.AppSettings
import ru.zapasli.backend.database.DatabaseFactory
import ru.zapasli.backend.database.DatabaseReadinessProbe
import ru.zapasli.backend.platform.AppDependencies
import ru.zapasli.backend.platform.AuthModule
import ru.zapasli.backend.plugins.configureHttp

fun main() {
    val settings = AppSettings.fromEnvironment()
    val dataSource = DatabaseFactory.create(settings.database)

    try {
        DatabaseFactory.migrate(dataSource)
        val accessTokens = AccessTokenService(settings.auth)
        val authService = DefaultAuthService(
            repository = JdbcAuthRepository(dataSource),
            passwordHasher = Argon2idPasswordHasher(),
            accessTokens = accessTokens,
            refreshTokens = RefreshTokenService(settings.auth.tokenHashSecret),
            settings = settings.auth,
        )

        embeddedServer(
            factory = Netty,
            host = settings.httpHost,
            port = settings.httpPort,
        ) {
            configureApplication(
                dependencies = AppDependencies(
                    readinessProbe = DatabaseReadinessProbe(dataSource),
                    buildInfo = settings.buildInfo,
                    auth = AuthModule(
                        service = authService,
                        accessTokens = accessTokens,
                    ),
                ),
            )
        }.start(wait = true)
    } finally {
        dataSource.close()
    }
}

fun io.ktor.server.application.Application.configureApplication(
    dependencies: AppDependencies,
) {
    configureHttp(dependencies)
}
