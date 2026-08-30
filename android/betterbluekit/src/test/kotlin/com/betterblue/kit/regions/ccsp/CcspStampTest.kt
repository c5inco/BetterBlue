package com.betterblue.kit.regions.ccsp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * The CCSP `Stamp` header is base64(authCfb XOR "appId:unixSeconds"). The
 * expected values here are computed independently in the test (a plain XOR
 * over the decoded cfb) so a regression in the production implementation
 * can't quietly redefine "correct".
 */
class CcspStampTest {

    private val hyundaiAppId = "014d2225-8495-4735-812d-2616334fd15d"
    private val hyundaiCfb = "RFtoRq/vDXJmRndoZaZQyfOot7OrIqGVFj96iY2WL3yyH5Z/pUvlUhqmCxD2t+D65SQ="
    private val kiaAppId = "a2b8469b-30a3-4361-8e13-6fceea8fbe74"
    private val kiaCfb = "wLTVxwidmH8CfJYBWSnHD6E0huk0ozdiuygB4hLkM5XCgzAL1Dk5sE36d/bx5PFMbZs="

    /** Independent reference implementation of the documented scheme. */
    private fun expectedStamp(appId: String, cfb: String, epochSeconds: Long): String {
        val message = "$appId:$epochSeconds".toByteArray(Charsets.UTF_8)
        val cfbBytes = Base64.getDecoder().decode(cfb)
        val count = minOf(cfbBytes.size, message.size)
        val xored = ByteArray(count) { i -> (cfbBytes[i].toInt() xor message[i].toInt()).toByte() }
        return Base64.getEncoder().encodeToString(xored)
    }

    @Test
    fun `hyundai europe stamp golden`() {
        val epoch = 1_700_000_000L
        assertEquals(
            expectedStamp(hyundaiAppId, hyundaiCfb, epoch),
            CcspStamp.generateStamp(hyundaiAppId, hyundaiCfb, epoch),
        )
    }

    @Test
    fun `kia europe stamp golden`() {
        val epoch = 1_700_000_000L
        assertEquals(
            expectedStamp(kiaAppId, kiaCfb, epoch),
            CcspStamp.generateStamp(kiaAppId, kiaCfb, epoch),
        )
    }

    @Test
    fun `stamp round-trips back to the appId and timestamp`() {
        // XOR is its own inverse, so decoding the stamp against the cfb must
        // reproduce the "appId:epoch" message the server validates.
        val epoch = 1_712_345_678L
        val stamp = CcspStamp.generateStamp(hyundaiAppId, hyundaiCfb, epoch)
        val stampBytes = Base64.getDecoder().decode(stamp)
        val cfbBytes = Base64.getDecoder().decode(hyundaiCfb)
        val recovered = ByteArray(stampBytes.size) { i ->
            (stampBytes[i].toInt() xor cfbBytes[i].toInt()).toByte()
        }
        assertEquals("$hyundaiAppId:$epoch", recovered.toString(Charsets.UTF_8))
    }

    @Test
    fun `stamp changes every second`() {
        // A fresh stamp per request is required — the server validates the
        // embedded timestamp window.
        val first = CcspStamp.generateStamp(hyundaiAppId, hyundaiCfb, 1_700_000_000L)
        val second = CcspStamp.generateStamp(hyundaiAppId, hyundaiCfb, 1_700_000_001L)
        assertNotEquals(first, second)
    }

    @Test
    fun `brands produce different stamps for the same instant`() {
        val epoch = 1_700_000_000L
        assertNotEquals(
            CcspStamp.generateStamp(hyundaiAppId, hyundaiCfb, epoch),
            CcspStamp.generateStamp(kiaAppId, kiaCfb, epoch),
        )
    }

    @Test
    fun `unparseable cfb falls back to the bare message`() {
        val epoch = 1_700_000_000L
        val stamp = CcspStamp.generateStamp(hyundaiAppId, "not-base64!!", epoch)
        assertEquals("$hyundaiAppId:$epoch", Base64.getDecoder().decode(stamp).toString(Charsets.UTF_8))
    }

    @Test
    fun `stamp is valid base64`() {
        val stamp = CcspStamp.generateStamp(hyundaiAppId, hyundaiCfb, 1_700_000_000L)
        assertTrue(Base64.getDecoder().decode(stamp).isNotEmpty())
    }
}
