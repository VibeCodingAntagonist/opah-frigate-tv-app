package app.opah.tv.data.network

enum class OpahErrorCode {
    AUTHENTICATION_EXPIRED,
    INVALID_CREDENTIALS,
    PERMISSION_DENIED,
    RATE_LIMITED,
    NOT_FOUND,
    SERVER_ERROR,
    REDIRECT_REJECTED,
    TLS_FAILURE,
    DNS_FAILURE,
    CONNECTION_REFUSED,
    TIMEOUT,
    INVALID_RESPONSE,
    UNSUPPORTED_SERVER,
    PLAYBACK_FAILURE,
    UNKNOWN,
}

enum class RecoveryAction {
    SIGN_IN,
    RETRY,
    CHECK_CONNECTION,
    CHECK_SERVER_URL,
    USE_DIFFERENT_ACCOUNT,
    NONE,
}

data class OpahFailure(
    val code: OpahErrorCode,
    val userMessage: String,
    val recoveryAction: RecoveryAction,
    val retryable: Boolean,
    val diagnosticCode: String = code.name,
)

open class OpahException(
    val failure: OpahFailure,
    cause: Throwable? = null,
) : Exception(failure.userMessage, cause) {
    constructor(userMessage: String, cause: Throwable? = null) : this(
        OpahFailure(
            code = OpahErrorCode.UNKNOWN,
            userMessage = userMessage,
            recoveryAction = RecoveryAction.NONE,
            retryable = false,
        ),
        cause,
    )

    val userMessage: String get() = failure.userMessage
}

class AuthenticationExpiredException(
    message: String = "The Frigate session is missing or expired. Sign in again.",
) : OpahException(
    OpahFailure(
        code = OpahErrorCode.AUTHENTICATION_EXPIRED,
        userMessage = message,
        recoveryAction = RecoveryAction.SIGN_IN,
        retryable = false,
    ),
)

class InvalidCredentialsException(
    message: String = "Frigate rejected the username or password.",
) : OpahException(
    OpahFailure(
        code = OpahErrorCode.INVALID_CREDENTIALS,
        userMessage = message,
        recoveryAction = RecoveryAction.SIGN_IN,
        retryable = false,
    ),
)

fun Throwable.toOpahFailure(): OpahFailure = when (this) {
    is OpahException -> failure
    else -> OpahFailure(
        code = OpahErrorCode.UNKNOWN,
        userMessage = "An unexpected Opah error occurred.",
        recoveryAction = RecoveryAction.RETRY,
        retryable = true,
    )
}
