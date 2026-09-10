package ru.zapasli.backend.plugins

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.header
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import ru.zapasli.backend.platform.ApiError
import ru.zapasli.backend.platform.ApiErrorResponse
import ru.zapasli.backend.platform.ApiException
import ru.zapasli.backend.platform.AppDependencies
import ru.zapasli.backend.routes.ACCESS_AUTH_PROVIDER
import ru.zapasli.backend.routes.LoginRateLimit
import ru.zapasli.backend.routes.RefreshRateLimit
import ru.zapasli.backend.routes.RegisterRateLimit
import ru.zapasli.backend.routes.authRoutes
import ru.zapasli.backend.routes.operationalRoutes
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

fun Application.configureHttp(dependencies: AppDependencies) {
    install(XForwardedHeaders) {
        useFirstProxy()
    }

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
                ignoreUnknownKeys = false
            },
        )
    }

    dependencies.auth?.let { auth ->
        install(Authentication) {
            jwt(ACCESS_AUTH_PROVIDER) {
                realm = "Zapasli API"
                verifier(auth.accessTokens.verifier)
                validate { credential ->
                    val subjectIsUuid = runCatching {
                        UUID.fromString(credential.payload.subject)
                    }.isSuccess
                    val sessionIsUuid = runCatching {
                        UUID.fromString(credential.payload.getClaim("sid").asString())
                    }.isSuccess
                    if (subjectIsUuid && sessionIsUuid) JWTPrincipal(credential.payload) else null
                }
                challenge { _, _ ->
                    call.respondApiError(
                        status = HttpStatusCode.Unauthorized,
                        code = "AUTHENTICATION_REQUIRED",
                        message = "Access token недействителен или истёк",
                    )
                }
            }
        }

        install(RateLimit) {
            register(RegisterRateLimit) {
                rateLimiter(limit = 5, refillPeriod = 1.minutes)
                requestKey { call -> call.request.origin.remoteHost }
            }
            register(LoginRateLimit) {
                rateLimiter(limit = 10, refillPeriod = 1.minutes)
                requestKey { call -> call.request.origin.remoteHost }
            }
            register(RefreshRateLimit) {
                rateLimiter(limit = 30, refillPeriod = 1.minutes)
                requestKey { call -> call.request.origin.remoteHost }
            }
        }
    }

    install(StatusPages) {
        status(HttpStatusCode.TooManyRequests) { call, status ->
            call.respondApiError(
                status = status,
                code = "RATE_LIMITED",
                message = "Слишком много запросов; повторите попытку позже",
            )
        }

        status(HttpStatusCode.NotFound) { call, _ ->
            call.respondApiError(
                status = HttpStatusCode.NotFound,
                code = "RESOURCE_NOT_FOUND",
                message = "Ресурс не найден",
            )
        }

        exception<ApiException> { call, cause ->
            call.respond(
                status = cause.status,
                message = ApiErrorResponse(
                    error = ApiError(
                        code = cause.code,
                        message = cause.message,
                        requestId = call.callId.orEmpty(),
                        fieldErrors = cause.fieldErrors,
                    ),
                ),
            )
        }

        exception<BadRequestException> { call, _ ->
            call.respondApiError(
                status = HttpStatusCode.BadRequest,
                code = "INVALID_REQUEST",
                message = "Некорректный JSON-запрос",
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
        dependencies.auth?.let { authRoutes(it) }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondApiError(
    status: HttpStatusCode,
    code: String,
    message: String,
) {
    response.header(HttpHeaders.CacheControl, "no-store")
    respond(
        status = status,
        message = ApiErrorResponse(
            error = ApiError(
                code = code,
                message = message,
                requestId = callId.orEmpty(),
            ),
        ),
    )
}
