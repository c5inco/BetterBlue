package com.betterblue.kit.surroundview

import com.betterblue.kit.model.SurroundViewCapture
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class CapturePollerTest {
    private fun capture(at: Instant?, vin: String = "VIN1") =
        SurroundViewCapture(
            vin = vin,
            capturedAt = at,
            frames = listOf(byteArrayOf(1, 2, 3)),
            tiles = emptyList(),
        )

    @Test
    fun `a new capture ends the poll early`() =
        runTest {
            val baseline = capture(Instant.parse("2026-08-01T10:00:00Z"))
            val fresh = capture(Instant.parse("2026-08-01T10:02:00Z"))
            var polls = 0

            val result =
                CapturePoller().run(
                    requestCapture = {},
                    fetchCaptures = {
                        polls += 1
                        // The car uploads on the third poll (90s in).
                        if (polls >= 3) listOf(fresh) else listOf(baseline)
                    },
                    baseline = baseline,
                )

            assertTrue(result is CaptureResult.NewCapture)
            assertEquals(3, polls)
            // Virtual time: three 30s polls, nowhere near the 6-minute deadline.
            assertEquals(90_000, testScheduler.currentTime)
        }

    @Test
    fun `the deadline gives up after six minutes`() =
        runTest {
            val baseline = capture(Instant.parse("2026-08-01T10:00:00Z"))
            var polls = 0

            val result =
                CapturePoller().run(
                    requestCapture = {},
                    fetchCaptures = {
                        polls += 1
                        listOf(baseline)
                    },
                    baseline = baseline,
                )

            assertEquals(CaptureResult.TimedOut, result)
            // 6 minutes at a 30s interval.
            assertEquals(12, polls)
            assertEquals(6.minutes.inWholeMilliseconds, testScheduler.currentTime)
        }

    @Test
    fun `a rejected request never polls`() =
        runTest {
            var polls = 0
            val boom = IllegalStateException("PIN rejected")

            val result =
                CapturePoller().run(
                    requestCapture = { throw boom },
                    fetchCaptures = {
                        polls += 1
                        emptyList()
                    },
                    baseline = null,
                )

            assertTrue(result is CaptureResult.RequestFailed)
            assertEquals(boom, (result as CaptureResult.RequestFailed).error)
            assertEquals(0, polls)
            assertEquals(0, testScheduler.currentTime)
        }

    @Test
    fun `a failed poll is not fatal`() =
        runTest {
            val fresh = capture(Instant.parse("2026-08-01T10:05:00Z"))
            var polls = 0

            val result =
                CapturePoller().run(
                    requestCapture = {},
                    fetchCaptures = {
                        polls += 1
                        // The server 500s twice before the images land.
                        if (polls < 3) throw RuntimeException("server error") else listOf(fresh)
                    },
                    baseline = null,
                )

            assertTrue(result is CaptureResult.NewCapture)
            assertEquals(3, polls)
        }

    @Test
    fun `phases are reported in order`() =
        runTest {
            val phases = mutableListOf<CapturePhase>()
            CapturePoller(timeout = 1.minutes, pollInterval = 30.seconds).run(
                requestCapture = {},
                fetchCaptures = { emptyList() },
                baseline = null,
                onPhase = phases::add,
            )
            assertEquals(listOf(CapturePhase.REQUESTING, CapturePhase.WAITING), phases)
        }

    // Baseline comparison

    @Test
    fun `with no baseline any capture is new`() {
        val poller = CapturePoller()
        assertTrue(poller.hasNewCapture(listOf(capture(Instant.now())), baseline = null))
        assertFalse(poller.hasNewCapture(emptyList(), baseline = null))
    }

    @Test
    fun `an older or identical capture is not new`() {
        val poller = CapturePoller()
        val baseline = capture(Instant.parse("2026-08-01T10:00:00Z"))
        assertFalse(poller.hasNewCapture(listOf(baseline), baseline))
        assertFalse(
            poller.hasNewCapture(listOf(capture(Instant.parse("2026-08-01T09:00:00Z"))), baseline),
        )
    }

    @Test
    fun `an untimestamped capture falls back to identity`() {
        val poller = CapturePoller()
        val baseline = capture(Instant.parse("2026-08-01T10:00:00Z"))
        // Same VIN, no timestamp → same synthesized id → not new.
        assertFalse(poller.hasNewCapture(listOf(capture(null, vin = baseline.vin)), capture(null, vin = baseline.vin)))
        // A different identity counts as new.
        assertTrue(poller.hasNewCapture(listOf(capture(null, vin = "OTHER")), capture(null, vin = "VIN1")))
    }
}
