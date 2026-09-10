package ru.zapasli.backend.auth

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.zapasli.backend.config.AuthSettings
import ru.zapasli.backend.platform.ApiException
import ru.zapasli.backend.platform.ApiFieldError
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.UUID

interface AuthService {
    suspend fun register(request: RegisterRequest, client: ClientContext): AuthSession

    suspend fun login(request: LoginRequest, client: ClientContext): AuthSession

    suspend fun refresh(request: RefreshRequest, client: ClientContext): AuthSession

    suspend fun logout(request: LogoutRequest)

    suspend fun currentUser(userId: UUID): AuthUser
}

class DefaultAuthService(
    private val repository: AuthRepository,
    private val passwordHasher: PasswordHasher,
    private val accessTokens: AccessTokenService,
    private val refreshTokens: RefreshTokenService,
    private val settings: AuthSettings,
    private val now: () -> Instant = Instant::now,
) : AuthService {
    private val dummyHash: String by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        passwordHasher.hash("not-a-real-user-password")
    }

    override suspend fun register(request: RegisterRequest, client: ClientContext): AuthSession {
        val validated = validateRegistration(request)
        val passwordHash = hashPassword(request.password)
        val issuedRefresh = refreshTokens.issue()
        val user = AuthUser(
            id = UUID.randomUUID(),
            displayName = validated.displayName,
            locale = validated.locale,
        )
        val session = NewSession(
            id = UUID.randomUUID(),
            familyId = UUID.randomUUID(),
            tokenHash = issuedRefresh.hash,
            expiresAt = now().plus(settings.refreshTokenTtl),
            userAgentHash = hashUserAgent(client.userAgent),
        )

        return when (
            repository.register(
                RegistrationCommand(
                    user = user,
                    emailNormalized = validated.email,
                    passwordHash = passwordHash,
                    session = session,
                ),
            )
        ) {
            RegistrationResult.EmailAlreadyRegistered -> throw ApiException(
                status = HttpStatusCode.Conflict,
                code = "EMAIL_ALREADY_REGISTERED",
                message = "Пользователь с таким email уже зарегистрирован",
            )

            is RegistrationResult.Created -> issueSession(user, session.id, issuedRefresh.value)
        }
    }

    override suspend fun login(request: LoginRequest, client: ClientContext): AuthSession {
        val normalizedEmail = normalizeEmail(request.email)
        val credential = normalizedEmail?.let { repository.findCredential(it) }
        val hash = credential?.passwordHash ?: dummyHash
        val passwordIsProcessable = isPasswordProcessable(request.password)
        val passwordMatches = verifyPassword(
            password = if (passwordIsProcessable) request.password else INVALID_PASSWORD_PLACEHOLDER,
            encodedHash = hash,
        ) && passwordIsProcessable
        if (credential == null || !passwordMatches) throw invalidCredentials()

        val issuedRefresh = refreshTokens.issue()
        val session = NewSession(
            id = UUID.randomUUID(),
            familyId = UUID.randomUUID(),
            tokenHash = issuedRefresh.hash,
            expiresAt = now().plus(settings.refreshTokenTtl),
            userAgentHash = hashUserAgent(client.userAgent),
        )
        repository.createSession(credential.user.id, session)
        return issueSession(credential.user, session.id, issuedRefresh.value)
    }

    override suspend fun refresh(request: RefreshRequest, client: ClientContext): AuthSession {
        val currentHash = refreshTokens.hashIfValid(request.refreshToken)
            ?: throw invalidRefreshToken()
        val replacementToken = refreshTokens.issue()
        val replacement = ReplacementSession(
            id = UUID.randomUUID(),
            tokenHash = replacementToken.hash,
            userAgentHash = hashUserAgent(client.userAgent),
        )

        return when (val result = repository.rotateSession(currentHash, replacement, now())) {
            RotationResult.Invalid -> throw invalidRefreshToken()
            RotationResult.ReuseDetected -> throw ApiException(
                status = HttpStatusCode.Unauthorized,
                code = "REFRESH_TOKEN_REUSE_DETECTED",
                message = "Сессия отозвана; выполните вход повторно",
            )

            is RotationResult.Rotated -> issueSession(
                user = result.user,
                sessionId = result.sessionId,
                refreshToken = replacementToken.value,
            )
        }
    }

    override suspend fun logout(request: LogoutRequest) {
        refreshTokens.hashIfValid(request.refreshToken)?.let { tokenHash ->
            repository.revokeSessionFamily(tokenHash, now())
        }
    }

    override suspend fun currentUser(userId: UUID): AuthUser =
        repository.findUser(userId) ?: throw ApiException(
            status = HttpStatusCode.Unauthorized,
            code = "AUTHENTICATION_REQUIRED",
            message = "Требуется повторный вход",
        )

    private fun issueSession(user: AuthUser, sessionId: UUID, refreshToken: String): AuthSession {
        val issuedAccess = accessTokens.issue(user.id, sessionId, now())
        return AuthSession(
            user = user,
            accessToken = issuedAccess.value,
            accessTokenExpiresAt = issuedAccess.expiresAt,
            refreshToken = refreshToken,
        )
    }

    private suspend fun hashPassword(password: String): String = withContext(Dispatchers.Default) {
        passwordHasher.hash(password)
    }

    private suspend fun verifyPassword(password: String, encodedHash: String): Boolean =
        withContext(Dispatchers.Default) { passwordHasher.verify(password, encodedHash) }

    private fun validateRegistration(request: RegisterRequest): ValidatedRegistration {
        val errors = buildList {
            if (normalizeEmail(request.email) == null) add(
                ApiFieldError("email", "INVALID_EMAIL", "Укажите корректный email"),
            )
            if (!isPasswordProcessable(request.password)) {
                add(
                    ApiFieldError(
                        "password",
                        "INVALID_PASSWORD_LENGTH",
                        "Пароль должен содержать от $MIN_PASSWORD_CODE_POINTS до $MAX_PASSWORD_CODE_POINTS символов",
                    ),
                )
            }
            val displayName = request.displayName.trim()
            if (displayName.codePointCount(0, displayName.length) !in 1..MAX_DISPLAY_NAME_CODE_POINTS) {
                add(
                    ApiFieldError(
                        "displayName",
                        "INVALID_DISPLAY_NAME",
                        "Имя должно содержать от 1 до $MAX_DISPLAY_NAME_CODE_POINTS символов",
                    ),
                )
            }
            if (request.locale !in SUPPORTED_LOCALES) add(
                ApiFieldError("locale", "UNSUPPORTED_LOCALE", "Поддерживаются языки ru и en"),
            )
        }
        if (errors.isNotEmpty()) throw ApiException(
            status = HttpStatusCode.UnprocessableEntity,
            code = "VALIDATION_FAILED",
            message = "Проверьте заполненные поля",
            fieldErrors = errors,
        )
        return ValidatedRegistration(
            email = requireNotNull(normalizeEmail(request.email)),
            displayName = request.displayName.trim(),
            locale = request.locale,
        )
    }

    private fun normalizeEmail(value: String): String? {
        val normalized = value.trim().lowercase(Locale.ROOT)
        if (normalized.length !in 3..254 || normalized.any(Char::isISOControl)) return null
        val separator = normalized.lastIndexOf('@')
        if (separator !in 1 until normalized.lastIndex) return null
        val local = normalized.substring(0, separator)
        val domain = normalized.substring(separator + 1)
        if (local.length > 64 || local.startsWith('.') || local.endsWith('.') || ".." in local) return null
        if (!local.matches(EMAIL_LOCAL_PART)) return null
        if (!domain.matches(EMAIL_DOMAIN) || domain.length > 253) return null
        return normalized
    }

    private fun hashUserAgent(value: String?): ByteArray? = value
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.take(MAX_USER_AGENT_LENGTH)
        ?.toByteArray(StandardCharsets.UTF_8)
        ?.let { MessageDigest.getInstance("SHA-256").digest(it) }

    private fun isPasswordProcessable(value: String): Boolean {
        if (value.length > MAX_PASSWORD_UTF16_UNITS) return false
        val codePoints = value.codePointCount(0, value.length)
        return codePoints in MIN_PASSWORD_CODE_POINTS..MAX_PASSWORD_CODE_POINTS &&
            value.toByteArray(StandardCharsets.UTF_8).size <= MAX_PASSWORD_BYTES
    }

    private fun invalidCredentials() = ApiException(
        status = HttpStatusCode.Unauthorized,
        code = "INVALID_CREDENTIALS",
        message = "Неверный email или пароль",
    )

    private fun invalidRefreshToken() = ApiException(
        status = HttpStatusCode.Unauthorized,
        code = "INVALID_REFRESH_TOKEN",
        message = "Refresh token недействителен или истёк",
    )

    private data class ValidatedRegistration(
        val email: String,
        val displayName: String,
        val locale: String,
    )

    companion object {
        private const val MIN_PASSWORD_CODE_POINTS = 12
        private const val MAX_PASSWORD_CODE_POINTS = 128
        private const val MAX_PASSWORD_UTF16_UNITS = MAX_PASSWORD_CODE_POINTS * 2
        private const val MAX_PASSWORD_BYTES = 512
        private const val MAX_DISPLAY_NAME_CODE_POINTS = 80
        private const val MAX_USER_AGENT_LENGTH = 512
        private const val INVALID_PASSWORD_PLACEHOLDER = "invalid-password-placeholder"
        private val SUPPORTED_LOCALES = setOf("ru", "en")
        private val EMAIL_LOCAL_PART = Regex("[a-z0-9.!#$%&'*+/=?^_`{|}~-]+")
        private val EMAIL_DOMAIN = Regex(
            "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+",
        )
    }
}
