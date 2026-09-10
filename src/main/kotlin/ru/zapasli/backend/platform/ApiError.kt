package ru.zapasli.backend.platform

import kotlinx.serialization.Serializable
import io.ktor.http.HttpStatusCode

@Serializable
data class ApiErrorResponse(
    val error: ApiError,
)

@Serializable
data class ApiError(
    val code: String,
    val message: String,
    val requestId: String,
    val fieldErrors: List<ApiFieldError>? = null,
)

@Serializable
data class ApiFieldError(
    val field: String,
    val code: String,
    val message: String,
)

class ApiException(
    val status: HttpStatusCode,
    val code: String,
    override val message: String,
    val fieldErrors: List<ApiFieldError>? = null,
) : RuntimeException(message)
