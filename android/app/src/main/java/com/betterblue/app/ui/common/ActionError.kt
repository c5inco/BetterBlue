package com.betterblue.app.ui.common

import com.betterblue.kit.ApiErrorType
import com.betterblue.kit.ApiException

/**
 * A failed user action with enough context to render a headline, a summary,
 * and collapsible technical details.
 */
data class ActionError(
    val action: String,
    val error: Throwable,
    val accountId: String? = null,
) {
    val apiException: ApiException? get() = error as? ApiException

    val headline: String get() = "$action failed"

    val summary: String get() = error.message ?: error.toString()

    val typeLabel: String get() = apiException?.errorType?.displayLabel ?: "Error"

    /** Soft state, not a failure — the command was accepted upstream. */
    val isAwaitingConfirmation: Boolean
        get() = apiException?.errorType == ApiErrorType.STATUS_VERIFICATION_TIMEOUT

    val technicalDetails: String
        get() =
            buildString {
                appendLine("Action: $action")
                appendLine("Message: ${error.message}")
                apiException?.let { api ->
                    appendLine("Type: ${api.errorType}")
                    api.code?.let { appendLine("Code: $it") }
                    api.apiName?.let { appendLine("API: $it") }
                    api.userInfo?.let { appendLine("Info: $it") }
                }
                accountId?.let { appendLine("Account: $it") }
                append(
                    error
                        .stackTraceToString()
                        .lineSequence()
                        .take(10)
                        .joinToString("\n"),
                )
            }
}
