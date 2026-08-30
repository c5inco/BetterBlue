package com.betterblue.kit.surroundview

import com.betterblue.kit.log.BBLogCategory
import com.betterblue.kit.log.BBLogger
import com.betterblue.kit.model.SurroundViewCapture
import kotlinx.coroutines.delay
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** How a requested surround-view capture ended. */
sealed interface CaptureResult {
    /** The vehicle uploaded a capture newer than the baseline. */
    data class NewCapture(
        val captures: List<SurroundViewCapture>,
    ) : CaptureResult

    /** The deadline passed with nothing new — the car may be asleep. */
    data object TimedOut : CaptureResult

    /** The request itself was rejected; nothing was ever taken. */
    data class RequestFailed(
        val error: Throwable,
    ) : CaptureResult
}

/**
 * Drives a surround-view capture: ask the vehicle to shoot, then poll until
 * new imagery shows up or the deadline passes.
 *
 * Pure orchestration over injected suspend lambdas so the timing behavior is
 * testable without a network or a real clock. Cancelling the calling
 * coroutine (the user dismissing the sheet) abandons the poll — the vehicle
 * still finishes its upload, it just isn't waited on.
 */
class CapturePoller(
    /** The vehicle usually uploads within 1–2 minutes; the headroom covers a slow modem wake. */
    private val timeout: Duration = 6.minutes,
    /** Each poll costs a PIN verification plus a fetch, so keep it coarse. */
    private val pollInterval: Duration = 30.seconds,
) {
    suspend fun run(
        requestCapture: suspend () -> Unit,
        fetchCaptures: suspend () -> List<SurroundViewCapture>,
        baseline: SurroundViewCapture?,
        onPhase: (CapturePhase) -> Unit = {},
    ): CaptureResult {
        onPhase(CapturePhase.REQUESTING)
        try {
            requestCapture()
        } catch (e: Exception) {
            BBLogger.error(BBLogCategory.API, "CapturePoller: capture request failed: $e")
            return CaptureResult.RequestFailed(e)
        }

        onPhase(CapturePhase.WAITING)
        var elapsed = Duration.ZERO
        while (elapsed < timeout) {
            delay(pollInterval)
            elapsed += pollInterval

            val latest =
                try {
                    fetchCaptures()
                } catch (e: Exception) {
                    // A failed poll isn't fatal — the images may simply not be
                    // there yet. Keep waiting and let the deadline decide.
                    BBLogger.debug(BBLogCategory.API, "CapturePoller: poll failed, still waiting: $e")
                    continue
                }

            if (hasNewCapture(latest, baseline)) {
                return CaptureResult.NewCapture(latest)
            }
        }

        return CaptureResult.TimedOut
    }

    /**
     * Whether [latest] contains imagery newer than [baseline]. With no
     * baseline any capture is new; with no timestamp to compare, a changed
     * identity counts.
     */
    fun hasNewCapture(latest: List<SurroundViewCapture>, baseline: SurroundViewCapture?): Boolean {
        val newest = latest.firstOrNull() ?: return false
        if (baseline == null) return true
        val capturedAt: Instant = newest.capturedAt ?: return newest.id != baseline.id
        val baselineAt: Instant = baseline.capturedAt ?: return true
        return capturedAt.isAfter(baselineAt)
    }
}

enum class CapturePhase(
    val message: String,
) {
    REQUESTING("Asking the vehicle to take photos…"),
    WAITING("The vehicle is taking photos and uploading them. This usually takes 1-2 minutes."),
}
