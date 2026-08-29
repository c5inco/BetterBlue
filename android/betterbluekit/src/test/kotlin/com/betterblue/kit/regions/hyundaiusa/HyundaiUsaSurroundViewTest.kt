package com.betterblue.kit.regions.hyundaiusa

import com.betterblue.kit.ApiClientConfig
import com.betterblue.kit.ApiException
import com.betterblue.kit.model.Brand
import com.betterblue.kit.model.Distance
import com.betterblue.kit.model.FuelType
import com.betterblue.kit.model.Region
import com.betterblue.kit.model.Vehicle
import com.betterblue.kit.supportsSurroundView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.Base64

/**
 * Parsing coverage for Hyundai USA "Find My Car SVM". The payload shape is
 * taken from the MyHyundai app's own bundled sample response
 * (assets/hma/SVMDetails.json) and hyundai_kia_connect_api#1203:
 * `svmDetails[].svmDetail` with a base64 `svmImage`, an `imageSize` array,
 * nested `gpsDetail.coord`, and 0/1 door flags.
 */
class HyundaiUsaSurroundViewTest {

    /** Bytes that merely look like a JPEG — the decoder splits on markers. */
    private fun fakeJpeg(marker: Byte, padding: Int = 8): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
            ByteArray(padding) { marker } +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    private fun makeClient() = HyundaiUsaClient(
        ApiClientConfig(
            region = Region.USA,
            brand = Brand.HYUNDAI,
            username = "test@example.com",
            password = "password123",
            pin = "1234",
            accountId = "00000000-0000-0000-0000-000000000001",
        ),
    )

    private fun makeVehicle() = Vehicle(
        vin = "TESTVIN0000000000",
        regId = "reg",
        model = "PALISADE",
        accountId = "00000000-0000-0000-0000-000000000002",
        fuelType = FuelType.GAS,
        generation = 3,
        odometer = Distance(0.0, Distance.Units.KILOMETERS),
    )

    /**
     * One `svmDetail` shaped like the app's real sample: nested coordinates,
     * 0/1 doors, the timestamp under `gpsDetail.time`, the real 4472×720
     * imageSize.
     */
    private fun makePayload(entries: List<Pair<String, Byte>>): ByteArray {
        val details = entries.joinToString(",") { (time, marker) ->
            """
            {"svmDetail": {
              "sidemirrorOpen": false,
              "trunkOpen": false,
              "doorOpen": {"frontLeft": 0, "frontRight": 1, "backLeft": 0, "backRight": 0},
              "imageSize": [4472, 720, 960, 720, 632, 720],
              "gpsDetail": {
                "coord": {"lat": 42.271284, "alt": 254, "lon": -83.625744, "type": 0},
                "speed": {"value": 0, "unit": 1}, "time": "$time", "head": 93
              },
              "svmImage": "${Base64.getEncoder().encodeToString(fakeJpeg(marker))}"
            }}
            """.trimIndent()
        }
        return """{"svmDetails": [$details]}""".toByteArray()
    }

    @Test
    fun `a capture's imagery and metadata parse`() {
        val captures = makeClient().parseUsaSurroundViewResponse(
            makePayload(listOf("20260826003935" to 0x11)),
            makeVehicle(),
        )

        val capture = captures.first()
        assertEquals("TESTVIN0000000000", capture.vin)
        assertEquals(1, capture.frames.size)
        assertEquals(5, capture.tiles.size) // four fisheye + bird's-eye
        assertEquals(93, capture.heading)
        assertEquals(false, capture.sideMirrorOpen)
        assertEquals(false, capture.trunkOpen)
        assertEquals(true, capture.doorOpen?.frontRight)
        assertEquals(false, capture.doorOpen?.frontLeft)
        assertEquals(42.271284, capture.location?.latitude)
        assertEquals(-83.625744, capture.location?.longitude)
    }

    @Test
    fun `gpsDetail time is read as UTC`() {
        // The `time` has no timezone in it — reading it as local time would
        // shift every capture by the user's offset.
        val capture = makeClient().parseUsaSurroundViewResponse(
            makePayload(listOf("20260826003935" to 0x11)),
            makeVehicle(),
        ).first()

        assertEquals(Instant.parse("2026-08-26T00:39:35Z"), capture.capturedAt)
    }

    @Test
    fun `timestamp falls back to a top-level time when gpsDetail is absent`() {
        val payload = """
            {"svmDetails": [{"svmDetail": {
              "time": "20260826003935",
              "svmImage": "${Base64.getEncoder().encodeToString(fakeJpeg(0x11))}"
            }}]}
        """.trimIndent().toByteArray()

        val capture = makeClient().parseUsaSurroundViewResponse(payload, makeVehicle()).first()
        assertNotNull(capture.capturedAt)
        assertNull(capture.location)
    }

    @Test
    fun `captures are returned newest first`() {
        val captures = makeClient().parseUsaSurroundViewResponse(
            makePayload(
                listOf(
                    "20260713192923" to 0x11,
                    "20260826003935" to 0x22,
                    "20260806221826" to 0x33,
                ),
            ),
            makeVehicle(),
        )

        assertEquals(3, captures.size)
        val timestamps = captures.mapNotNull { it.capturedAt }
        assertEquals(timestamps.sortedDescending(), timestamps)
    }

    @Test
    fun `door and trunk flags tolerate numbers and booleans`() {
        val payload = """
            {"svmDetails": [{"svmDetail": {
              "time": "20260826003935",
              "trunkOpen": 1,
              "sidemirrorOpen": true,
              "doorOpen": {"frontLeft": true, "frontRight": 0, "backLeft": 1, "backRight": false},
              "svmImage": "${Base64.getEncoder().encodeToString(fakeJpeg(0x11))}"
            }}]}
        """.trimIndent().toByteArray()

        val capture = makeClient().parseUsaSurroundViewResponse(payload, makeVehicle()).first()
        assertEquals(true, capture.trunkOpen)
        assertEquals(true, capture.sideMirrorOpen)
        assertEquals(true, capture.doorOpen?.frontLeft)
        assertEquals(false, capture.doorOpen?.frontRight)
        assertEquals(true, capture.doorOpen?.backLeft)
        assertEquals(false, capture.doorOpen?.backRight)
    }

    @Test
    fun `an entry with unusable imagery is skipped not fatal`() {
        val payload =
            """{"svmDetails": [{"svmDetail": {"time": "20260826003935", "svmImage": "bm90LWEtanBlZw=="}}]}"""
                .toByteArray()
        assertTrue(makeClient().parseUsaSurroundViewResponse(payload, makeVehicle()).isEmpty())
    }

    @Test
    fun `a response without svmDetails throws`() {
        assertThrows<ApiException> {
            makeClient().parseUsaSurroundViewResponse("""{"status": "ok"}""".toByteArray(), makeVehicle())
        }
    }

    @Test
    fun `hyundai usa advertises surround view`() {
        assertFalse(makeClient().optionalFeaturesSupported().isEmpty())
        assertTrue(makeClient().supportsSurroundView())
    }
}
