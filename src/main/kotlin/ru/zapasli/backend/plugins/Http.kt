package ru.zapasli.backend.plugins

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import ru.zapasli.backend.platform.ApiError
import ru.zapasli.backend.platform.ApiErrorResponse
import ru.zapasli.backend.platform.AppDependencies
import ru.zapasli.backend.routes.operationalRoutes
import java.util.UUID

fun Application.configureHttp(dependencies: AppDependencies) {
    install(CallId) {
        retrieveFromHeader(HttpHeaders.XRequestId)
        generate { UUID.randomUUID().toString() }
        verify { value ->
            value.length == 36 && runCatching { UUID.fromString(value) }.isSuccess
        }
        replyToHeader(HttpHeaders.XRequestId)
    }

    install(CallLogging) {
        level = Level.INFO
        callIdMdc("requestId")
    }

    install(DefaultHeaders) {
        header(HttpHeaders.Server, "Zapasli")
        header("X-Content-Type-Options", "nosniff")
        header("Referrer-Policy", "no-referrer")
    }

    install(ContentNegotiation) {
        json(
            Json {
                encodeDefaults = true
                explicitNulls = false
                ignoreUnknownKeys = true
            },
        )
    }

    install(StatusPages) {
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respond(
                status = HttpStatusCode.NotFound,
                message = ApiErrorResponse(
                    error = ApiError(
                        code = "RESOURCE_NOT_FOUND",
                        message = "Ресурс не найден",
                        requestId = call.callId.orEmpty(),
                    ),
                ),
            )
        }

        exception<Throwable> { call, cause ->
            call.application.environment.log.error(
                "Unhandled request failure; requestId={}",
                call.callId,
                cause,
            )
            call.respond(
                status = HttpStatusCode.InternalServerError,
                message = ApiErrorResponse(
                    error = ApiError(
                        code = "INTERNAL_ERROR",
                        message = "Внутренняя ошибка сервера",
                        requestId = call.callId.orEmpty(),
                    ),
                ),
            )
        }
    }

    routing {
        operationalRoutes(dependencies)
    }
}
