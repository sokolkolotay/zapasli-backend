package ru.zapasli.backend.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import ru.zapasli.backend.platform.AppDependencies

fun Route.operationalRoutes(dependencies: AppDependencies) {
    get("/health/live") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(
            ServiceStatusResponse(
                status = "ok",
                checkedAt = dependencies.now().toString(),
            ),
        )
    }

    get("/health/ready") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        val ready = dependencies.readinessProbe.isReady()
        call.respond(
            status = if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
            message = ServiceStatusResponse(
                status = if (ready) "ready" else "not_ready",
                checkedAt = dependencies.now().toString(),
            ),
        )
    }

    get("/version") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(
            VersionResponse(
                version = dependencies.buildInfo.version,
                commit = dependencies.buildInfo.commit,
            ),
        )
    }

    get("/openapi.json") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondText(
            text = OpenApiDocument.json,
            contentType = ContentType.Application.Json,
        )
    }

    swaggerUI(
        path = "swagger",
        swaggerFile = "openapi/openapi.json",
    )
}

@Serializable
data class ServiceStatusResponse(
    val status: String,
    val service: String = "zapasli-backend",
    val checkedAt: String,
)

@Serializable
data class VersionResponse(
    val service: String = "zapasli-backend",
    val version: String,
    val commit: String,
)

private object OpenApiDocument {
    val json: String by lazy {
        requireNotNull(javaClass.getResourceAsStream("/openapi/openapi.json")) {
            "Bundled OpenAPI document is missing"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
