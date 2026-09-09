package ru.zapasli.backend

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.zapasli.backend.database.ReadinessProbe
import ru.zapasli.backend.platform.AppDependencies
import ru.zapasli.backend.platform.BuildInfo
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OperationalRoutesTest {
    private val fixedTime = Instant.parse("2026-09-09T10:15:30Z")

    @Test
    fun `liveness is available without a database`() = testApplication {
        application {
            configureApplication(dependencies(ready = false))
        }

        val response = client.get("/health/live")
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", json.getValue("status").jsonPrimitive.content)
        assertEquals("zapasli-backend", json.getValue("service").jsonPrimitive.content)
        assertEquals(fixedTime.toString(), json.getValue("checkedAt").jsonPrimitive.content)
        assertNotNull(response.headers[HttpHeaders.XRequestId])
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun `readiness reports database availability without connection details`() = testApplication {
        application {
            configureApplication(dependencies(ready = false))
        }

        val response = client.get("/health/ready")
        val body = response.bodyAsText()
        val json = Json.parseToJsonElement(body).jsonObject

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("not_ready", json.getValue("status").jsonPrimitive.content)
        assertTrue("jdbc:" !in body)
        assertTrue("password" !in body.lowercase())
    }

    @Test
    fun `valid client request id is returned unchanged`() = testApplication {
        application {
            configureApplication(dependencies(ready = true))
        }
        val requestId = UUID.randomUUID().toString()

        val response = client.get("/version") {
            header(HttpHeaders.XRequestId, requestId)
        }
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(requestId, response.headers[HttpHeaders.XRequestId])
        assertEquals("0.1.0-test", json.getValue("version").jsonPrimitive.content)
        assertEquals("abcdef1", json.getValue("commit").jsonPrimitive.content)
    }

    @Test
    fun `not found response follows the API error envelope`() = testApplication {
        application {
            configureApplication(dependencies(ready = true))
        }

        val response = client.get("/does-not-exist")
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val error = json.getValue("error").jsonObject

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("RESOURCE_NOT_FOUND", error.getValue("code").jsonPrimitive.content)
        assertEquals(
            response.headers[HttpHeaders.XRequestId],
            error.getValue("requestId").jsonPrimitive.content,
        )
    }

    @Test
    fun `bundled OpenAPI describes every operational endpoint`() = testApplication {
        application {
            configureApplication(dependencies(ready = true))
        }

        val response = client.get("/openapi.json")
        val document = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val paths = document.getValue("paths").jsonObject

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("3.1.0", document.getValue("openapi").jsonPrimitive.content)
        assertTrue("/health/live" in paths)
        assertTrue("/health/ready" in paths)
        assertTrue("/version" in paths)
        assertTrue("/openapi.json" in paths)
        assertTrue("/swagger" in paths)
    }

    @Test
    fun `swagger UI is served from the bundled contract`() = testApplication {
        application {
            configureApplication(dependencies(ready = true))
        }

        val response = client.get("/swagger")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue("swagger" in response.bodyAsText().lowercase())
    }

    private fun dependencies(ready: Boolean) = AppDependencies(
        readinessProbe = ReadinessProbe { ready },
        buildInfo = BuildInfo(
            version = "0.1.0-test",
            commit = "abcdef1",
        ),
        now = { fixedTime },
    )
}
