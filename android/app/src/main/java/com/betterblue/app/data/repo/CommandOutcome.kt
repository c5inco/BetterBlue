package com.betterblue.app.data.repo

import com.betterblue.kit.ApiException

/**
 * The result of a vehicle command including its post-command verification.
 *
 * [AwaitingConfirmation] is NOT a failure: the command was accepted upstream
 * but the status polling didn't observe the change in time (the backends —
 * especially Kia US — can take minutes to reflect a state change). UIs must
 * render it as a soft "awaiting confirmation" state, never an error dialog.
 */
sealed interface CommandOutcome {
    data object Confirmed : CommandOutcome
    data object AwaitingConfirmation : CommandOutcome
    data class Failed(val error: ApiException) : CommandOutcome
}
