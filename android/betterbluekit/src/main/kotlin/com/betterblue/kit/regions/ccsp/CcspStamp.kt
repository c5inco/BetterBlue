package com.betterblue.kit.regions.ccsp

import java.time.Instant
import java.util.Base64

/**
 * The CCSP `Stamp` header: base64 of `authCfb XOR "<appId>:<unixSeconds>"`,
 * where the XOR runs over the shorter of the two byte strings (the message,
 * in practice). Generated fresh per request — the server validates the
 * embedded timestamp window.
 *
 * Ports the canonical scheme from Home Assistant's `hyundai_kia_connect_api`
 * (`_get_stamp`) / bluelinky. The previous HMAC-SHA256-over-ISO8601 form
 * happened to be accepted on read endpoints but was rejected with HTTP 403 on
 * the control endpoints, so remote actions failed across Europe (e.g. NL).
 * Also sent as `pushRegId` during device registration.
 */
object CcspStamp {

    fun generateStamp(
        appId: String,
        authCfb: String,
        epochSeconds: Long = Instant.now().epochSecond,
    ): String {
        val message = "$appId:$epochSeconds".toByteArray(Charsets.UTF_8)
        val cfb = try {
            Base64.getDecoder().decode(authCfb)
        } catch (_: IllegalArgumentException) {
            // Mirrors the Swift fallback: unparseable cfb → base64 of the
            // message alone.
            return Base64.getEncoder().encodeToString(message)
        }
        val count = minOf(cfb.size, message.size)
        val xored = ByteArray(count) { index ->
            (cfb[index].toInt() xor message[index].toInt()).toByte()
        }
        return Base64.getEncoder().encodeToString(xored)
    }
}
