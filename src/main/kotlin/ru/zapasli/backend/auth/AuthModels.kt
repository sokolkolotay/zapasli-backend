package ru.zapasli.backend.auth

import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    val displayName: String,
    val locale: String = "ru",
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
)

@Serializable
data class RefreshRequest(
    val refreshToken: String,
)

@Serializable
data class LogoutRequest(
    val refreshToken: String,
)

@Serializable
data class UserResponse(
    val id: String,
    val displayName: String,
    val locale: String,
)

@Serializable
data class AuthResponse(
    val user: UserResponse,
    val accessToken: String,
    val accessTokenExpiresAt: String,
    val refreshToken: String,
)

data class AuthUser(
    val id: UUID,
    val displayName: String,
    val locale: String,
)

data class AuthSession(
    val user: AuthUser,
    val accessToken: String,
    val accessTokenExpiresAt: Instant,
    val refreshToken: String,
)

data class ClientContext(
    val userAgent: String?,
)

fun AuthUser.toResponse() = UserResponse(
    id = id.toString(),
    displayName = displayName,
    locale = locale,
)

fun AuthSession.toResponse() = AuthResponse(
    user = user.toResponse(),
    accessToken = accessToken,
    accessTokenExpiresAt = accessTokenExpiresAt.toString(),
    refreshToken = refreshToken,
)
