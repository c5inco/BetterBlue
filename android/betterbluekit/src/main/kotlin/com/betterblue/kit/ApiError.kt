package com.betterblue.kit

import com.betterblue.kit.log.BBLogCategory
import com.betterblue.kit.log.BBLogger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ApiErrorType {
    @SerialName("general")
    GENERAL,

    @SerialName("invalidVehicleSession")
    INVALID_VEHICLE_SESSION,

    @SerialName("invalidCredentials")
    INVALID_CREDENTIALS,

    @SerialName("serverError")
    SERVER_ERROR,

    @SerialName("invalidPin")
    INVALID_PIN,

    @SerialName("concurrentRequest")
    CONCURRENT_REQUEST,

    @SerialName("failedRetryLogin")
    FAILED_RETRY_LOGIN,

    @SerialName("requiresMFA")
    REQUIRES_MFA,

    @SerialName("kiaInvalidRequest")
    KIA_INVALID_REQUEST,

    @SerialName("regionNotSupported")
    REGION_NOT_SUPPORTED,

    /**
     * The command was accepted, but post-command status polling didn't observe
     * the expected change in time. NOT a command failure — the backends
     * (especially Kia US) can take minutes to reflect a state change, so UIs
     * should render this as a soft "awaiting confirmation" state rather than a
     * failure.
     */
    @SerialName("statusVerificationTimeout")
    STATUS_VERIFICATION_TIMEOUT,
    ;

    /** Human-readable label for UI. */
    val displayLabel: String
        get() =
            when (this) {
                GENERAL -> "Error"
                INVALID_VEHICLE_SESSION -> "Session Expired"
                INVALID_CREDENTIALS -> "Invalid Credentials"
                SERVER_ERROR -> "Server Error"
                INVALID_PIN -> "Invalid PIN"
                CONCURRENT_REQUEST -> "Request In Progress"
                FAILED_RETRY_LOGIN -> "Reauthentication Failed"
                REQUIRES_MFA -> "Verification Required"
                KIA_INVALID_REQUEST -> "Request Rejected"
                REGION_NOT_SUPPORTED -> "Region Not Supported"
                STATUS_VERIFICATION_TIMEOUT -> "Awaiting Confirmation"
            }
}

class ApiException(
    override val message: String,
    val code: Int? = null,
    val apiName: String? = null,
    val errorType: ApiErrorType = ApiErrorType.GENERAL,
    val userInfo: Map<String, String>? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    companion object {
        fun logError(
            message: String,
            code: Int? = null,
            apiName: String? = null,
            errorType: ApiErrorType = ApiErrorType.GENERAL,
            userInfo: Map<String, String>? = null,
            cause: Throwable? = null,
        ): ApiException {
            val error = ApiException(message, code, apiName, errorType, userInfo, cause)
            var logMessage = "${apiName ?: "Unknown"}: $message"
            if (code != null) logMessage += " | Status Code: $code"
            if (errorType != ApiErrorType.GENERAL) logMessage += " | Error Type: $errorType"
            if (userInfo != null) logMessage += " | User Info: $userInfo"
            BBLogger.error(BBLogCategory.API, logMessage)
            return error
        }

        fun requiresMfa(
            xid: String,
            otpKey: String? = null,
            hasEmail: Boolean = false,
            hasPhone: Boolean = false,
            email: String? = null,
            phone: String? = null,
            rmTokenExpired: Boolean = false,
            apiName: String? = null,
        ): ApiException {
            val info =
                buildMap {
                    put("xid", xid)
                    if (otpKey != null) put("otpKey", otpKey)
                    put("hasEmail", if (hasEmail) "true" else "false")
                    put("hasPhone", if (hasPhone) "true" else "false")
                    if (email != null) put("email", email)
                    if (phone != null) put("phone", phone)
                    if (rmTokenExpired) put("rmTokenExpired", "true")
                }
            val message =
                if (rmTokenExpired) {
                    "Session expired - verification required"
                } else {
                    "Multi-Factor Authentication Required"
                }
            return logError(message, apiName = apiName, errorType = ApiErrorType.REQUIRES_MFA, userInfo = info)
        }

        fun invalidVehicleSession(
            message: String = "Invalid vehicle for current session",
            apiName: String? = null,
        ): ApiException =
            logError(message, code = 1005, apiName = apiName, errorType = ApiErrorType.INVALID_VEHICLE_SESSION)

        fun invalidCredentials(
            message: String = "Invalid username or password",
            apiName: String? = null,
        ): ApiException =
            logError(message, code = 401, apiName = apiName, errorType = ApiErrorType.INVALID_CREDENTIALS)

        fun serverError(
            message: String = "Server temporarily unavailable",
            apiName: String? = null,
        ): ApiException =
            logError(message, code = 502, apiName = apiName, errorType = ApiErrorType.SERVER_ERROR)

        fun invalidPin(message: String, apiName: String? = null): ApiException =
            logError(message, apiName = apiName, errorType = ApiErrorType.INVALID_PIN)

        fun concurrentRequest(
            message: String = "Another request is already in progress. Please wait and try again.",
            apiName: String? = null,
        ): ApiException =
            logError(message, code = 502, apiName = apiName, errorType = ApiErrorType.CONCURRENT_REQUEST)

        fun failedRetryLogin(
            message: String = "Failed to reauthenticate",
            apiName: String? = null,
        ): ApiException =
            logError(message, code = 502, apiName = apiName, errorType = ApiErrorType.FAILED_RETRY_LOGIN)

        fun kiaInvalidRequest(
            message: String = "Invalid request",
            apiName: String? = null,
        ): ApiException =
            logError(message, code = 502, apiName = apiName, errorType = ApiErrorType.KIA_INVALID_REQUEST)

        fun regionNotSupported(
            message: String = "This region is not yet supported",
            apiName: String? = null,
        ): ApiException =
            logError(message, apiName = apiName, errorType = ApiErrorType.REGION_NOT_SUPPORTED)

        fun statusVerificationTimeout(
            message: String = "Command sent, but the vehicle hasn't confirmed the change yet",
            apiName: String? = null,
        ): ApiException =
            logError(message, apiName = apiName, errorType = ApiErrorType.STATUS_VERIFICATION_TIMEOUT)
    }
}
