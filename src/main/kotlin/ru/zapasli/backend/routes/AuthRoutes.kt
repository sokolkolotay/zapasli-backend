package ru.zapasli.backend.routes

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import ru.zapasli.backend.auth.ClientContext
import ru.zapasli.backend.auth.LogoutRequest
import ru.zapasli.backend.auth.LoginRequest
import ru.zapasli.backend.auth.RefreshRequest
import ru.zapasli.backend.auth.RegisterRequest
import ru.zapasli.backend.auth.toResponse
import ru.zapasli.backend.platform.ApiException
import ru.zapasli.backend.platform.AuthModule
import java.util.UUID

const val ACCESS_AUTH_PROVIDER = "access-jwt"
val RegisterRateLimit = RateLimitName("auth-register")
val LoginRateLimit = RateLimitName("auth-login")
val RefreshRateLimit = RateLimitName("auth-refresh")

fun Route.authRoutes(module: AuthModule) {
    route("/api/v1") {
        route("/auth") {
            rateLimit(RegisterRateLimit) {
                post("/register") {
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    val session = module.service.register(
                        request = call.receive<RegisterRequest>(),
                        client = call.clientContext(),
                    )
                    call.respond(HttpStatusCode.Created, session.toResponse())
                }
            }

            rateLimit(LoginRateLimit) {
                post("/login") {
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    val session = module.service.login(
                        request = call.receive<LoginRequest>(),
                        client = call.clientContext(),
                    )
                    call.respond(session.toResponse())
                }
            }

            rateLimit(RefreshRateLimit) {
                post("/refresh") {
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    val session = module.service.refresh(
                        request = call.receive<RefreshRequest>(),
                        client = call.clientContext(),
                    )
                    call.respond(session.toResponse())
                }
            }

            rateLimit(RefreshRateLimit) {
                post("/logout") {
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    module.service.logout(call.receive<LogoutRequest>())
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }

        authenticate(ACCESS_AUTH_PROVIDER) {
            get("/me") {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                val subject = call.principal<JWTPrincipal>()?.payload?.subject
                val userId = runCatching { UUID.fromString(subject) }.getOrNull()
                    ?: throw ApiException(
                        status = HttpStatusCode.Unauthorized,
                        code = "AUTHENTICATION_REQUIRED",
                        message = "Требуется повторный вход",
                    )
                call.respond(module.service.currentUser(userId).toResponse())
            }
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.clientContext() = ClientContext(
    userAgent = request.headers[HttpHeaders.UserAgent],
)
