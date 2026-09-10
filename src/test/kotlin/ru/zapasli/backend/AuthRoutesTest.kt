package ru.zapasli.backend

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.zapasli.backend.auth.AccessTokenService
import ru.zapasli.backend.auth.AuthService
import ru.zapasli.backend.auth.AuthSession
import ru.zapasli.backend.auth.AuthUser
import ru.zapasli.backend.auth.ClientContext
import ru.zapasli.backend.auth.LoginRequest
import ru.zapasli.backend.auth.LogoutRequest
import ru.zapasli.backend.auth.RefreshRequest
import ru.zapasli.backend.auth.RegisterRequest
import ru.zapasli.backend.config.AuthSettings
import ru.zapasli.backend.database.ReadinessProbe
import ru.zapasli.backend.platform.AppDependencies
import ru.zapasli.backend.platform.AuthModule
import ru.zapasli.backend.platform.BuildInfo
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AuthRoutesTest {
    private val authSettings = AuthSettings(
        jwtSecret = ByteArray(32) { 1 },
        tokenHashSecret = ByteArray(32) { 2 },
        issuer = "zapasli-backend-test",
        audience = "zapasli-android-test",
        accessTokenTtl = Duration.ofMinutes(15),
        refreshTokenTtl = Duration.ofDays(30),
    )
    private val accessTokens = AccessTokenService(authSettings)
    private val user = AuthUser(UUID.randomUUID(), "Илья", "ru")
    private val service = FakeAuthService(user, accessTokens)

    @Test
    fun `registration returns the public auth contract without credentials`() = testApplication {
        application { configureApplication(dependencies()) }

        val response = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.UserAgent, "Zapasli Android test")
            setBody(
                """{"email":"user@example.com","password":"a sufficiently long password","displayName":"Илья","locale":"ru"}""",
            )
        }
        val body = response.bodyAsText()
        val json = Json.parseToJsonElement(body).jsonObject

        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertEquals(user.id.toString(), json.getValue("user").jsonObject.getValue("id").jsonPrimitive.content)
        assertFalse("password" in body.lowercase())
        assertFalse("user@example.com" in body)
    }

    @Test
    fun `unknown request fields are rejected`() = testApplication {
        application { configureApplication(dependencies()) }

        val response = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"email":"user@example.com","password":"a sufficiently long password","displayName":"Илья","admin":true}""",
            )
        }
        val error = Json.parseToJsonElement(response.bodyAsText())
            .jsonObject.getValue("error").jsonObject

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("INVALID_REQUEST", error.getValue("code").jsonPrimitive.content)
    }

    @Test
    fun `me requires a valid access token and follows the error envelope`() = testApplication {
        application { configureApplication(dependencies()) }

        val unauthorized = client.get("/api/v1/me")
        val error = Json.parseToJsonElement(unauthorized.bodyAsText())
            .jsonObject.getValue("error").jsonObject
        assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)
        assertEquals("no-store", unauthorized.headers[HttpHeaders.CacheControl])
        assertEquals("AUTHENTICATION_REQUIRED", error.getValue("code").jsonPrimitive.content)
        assertEquals(
            unauthorized.headers[HttpHeaders.XRequestId],
            error.getValue("requestId").jsonPrimitive.content,
        )

        val accessToken = accessTokens.issue(user.id, UUID.randomUUID(), Instant.now()).value
        val authorized = client.get("/api/v1/me") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
        }
        assertEquals(HttpStatusCode.OK, authorized.status)
        assertEquals(
            user.id.toString(),
            Json.parseToJsonElement(authorized.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content,
        )
    }

    @Test
    fun `registration rate limit returns retry metadata and API error`() = testApplication {
        application { configureApplication(dependencies()) }
        repeat(5) {
            assertEquals(HttpStatusCode.Created, client.register().status)
        }

        val limited = client.register()
        val error = Json.parseToJsonElement(limited.bodyAsText())
            .jsonObject.getValue("error").jsonObject
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertEquals("RATE_LIMITED", error.getValue("code").jsonPrimitive.content)
        assertFalse(limited.headers[HttpHeaders.RetryAfter].isNullOrBlank())
    }

    @Test
    fun `registration rate limit separates client addresses forwarded by the trusted proxy`() = testApplication {
        application { configureApplication(dependencies()) }

        repeat(5) {
            assertEquals(HttpStatusCode.Created, client.register("198.51.100.10").status)
        }

        assertEquals(HttpStatusCode.TooManyRequests, client.register("198.51.100.10").status)
        assertEquals(HttpStatusCode.Created, client.register("198.51.100.11").status)
    }

    private fun dependencies() = AppDependencies(
        readinessProbe = ReadinessProbe { true },
        buildInfo = BuildInfo("test", "test"),
        auth = AuthModule(service, accessTokens),
    )

    private suspend fun io.ktor.client.HttpClient.register(forwardedFor: String? = null) =
        post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            forwardedFor?.let { header("X-Forwarded-For", it) }
            setBody(
                """{"email":"user@example.com","password":"a sufficiently long password","displayName":"Илья"}""",
            )
        }

    private class FakeAuthService(
        private val user: AuthUser,
        private val accessTokens: AccessTokenService,
    ) : AuthService {
        override suspend fun register(request: RegisterRequest, client: ClientContext) = session()
        override suspend fun login(request: LoginRequest, client: ClientContext) = session()
        override suspend fun refresh(request: RefreshRequest, client: ClientContext) = session()
        override suspend fun logout(request: LogoutRequest) = Unit
        override suspend fun currentUser(userId: UUID) = user

        private fun session(): AuthSession {
            val access = accessTokens.issue(user.id, UUID.randomUUID(), Instant.now())
            return AuthSession(user, access.value, access.expiresAt, "zpr_test-refresh-token")
        }
    }
}
