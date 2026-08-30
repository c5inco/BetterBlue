package com.betterblue.kit.regions.ccsp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The dot-path table is shared by Hyundai EU and Kia EU and is the single
 * highest-value artifact in the EU port: a wrong path silently yields a
 * default rather than an error, so every reading is pinned here.
 */
class CcspKeyPathsTest {
    private val ccs2 = CcspKeyPathMap(CcspApiProfile.CCS2)
    private val legacy = CcspKeyPathMap(CcspApiProfile.LEGACY)

    @Test
    fun `ccs2 paths address the state tree`() {
        assertEquals("state.Vehicle", ccs2[CcspResponseKey.VEHICLE_STATE])
        assertEquals("Green.BatteryManagement.BatteryRemain.Ratio", ccs2[CcspResponseKey.SOC])
        assertEquals("Drivetrain.Odometer", ccs2[CcspResponseKey.ODO])
        assertEquals("Cabin.Door.Row1.Driver.Lock", ccs2[CcspResponseKey.LOCK_1L])
        assertEquals("Chassis.Axle.Row1.Left.Tire.PressureLow", ccs2[CcspResponseKey.TPMS_FRONT_LEFT])
    }

    @Test
    fun `legacy paths address the vehicleStatus tree`() {
        assertEquals("vehicleStatusInfo", legacy[CcspResponseKey.VEHICLE_STATE])
        assertEquals("vehicleStatus.evStatus.batteryStatus", legacy[CcspResponseKey.SOC])
        assertEquals("vehicleStatus.doorLock", legacy[CcspResponseKey.LOCK_STATUS])
    }

    @Test
    fun `profile-specific keys are absent from the other profile`() {
        // Per-door lock bits are CCS2-only; the single doorLock flag is legacy-only.
        assertNull(legacy[CcspResponseKey.LOCK_1L])
        assertNull(ccs2[CcspResponseKey.LOCK_STATUS])
    }

    @Test
    fun `legacy range path walks an array index`() {
        // The legacy EV range lives behind drvDistance[0], so the path walker
        // must handle numeric segments.
        val path = legacy[CcspResponseKey.RANGE_TOTAL]
        assertNotNull(path)
        assertTrue(path!!.contains(".0."), "expected an array index in $path")

        val json =
            Json
                .parseToJsonElement(
                    """
                    {"vehicleStatus":{"evStatus":{"drvDistance":[
                      {"rangeByFuel":{"evModeRange":{"value":231,"unit":1}}}
                    ]}}}
                    """.trimIndent(),
                ).jsonObject
        assertEquals(231, ccspNumber(json, path))
        assertEquals(1, ccspNumber(json, legacy[CcspResponseKey.RANGE_UNIT]))
    }

    @Test
    fun `ccs2 lock bits are inverted`() {
        // On CCS2, Lock == 0 means LOCKED — reading it uninverted flips the
        // padlock in the UI for every European car.
        val json =
            Json
                .parseToJsonElement(
                    """{"Cabin":{"Door":{"Row1":{"Driver":{"Lock":0},"Passenger":{"Lock":1}}}}}""",
                ).jsonObject

        assertTrue(ccspBool(json, ccs2[CcspResponseKey.LOCK_1L], inverted = true))
        assertFalse(ccspBool(json, ccs2[CcspResponseKey.LOCK_1R], inverted = true))
        // Without the inversion the reading is exactly backwards.
        assertFalse(ccspBool(json, ccs2[CcspResponseKey.LOCK_1L]))
    }

    @Test
    fun `boolean readings accept bools numbers and strings`() {
        val json =
            Json
                .parseToJsonElement(
                    """{"a":true,"b":1,"c":"true","d":"yes","e":0,"f":"false"}""",
                ).jsonObject
        assertTrue(ccspBool(json, "a"))
        assertTrue(ccspBool(json, "b"))
        assertTrue(ccspBool(json, "c"))
        assertTrue(ccspBool(json, "d"))
        assertFalse(ccspBool(json, "e"))
        assertFalse(ccspBool(json, "f"))
    }

    @Test
    fun `missing paths yield defaults rather than throwing`() {
        val json = Json.parseToJsonElement("""{"present":1}""").jsonObject
        assertFalse(ccspBool(json, "absent.deeply.nested"))
        assertEquals(0.0, ccspDouble(json, "absent"))
        assertNull(ccspNumber(json, "absent"))
        assertNull(ccspAny(json, null))
        assertNull(ccspString(json, ""))
    }

    @Test
    fun `park coordinates are addressable in both profiles`() {
        // The legacy /location/park payload nests under gpsDetail; CCS2 puts
        // coord at the root. Both branches must resolve or the park fallback
        // silently produces (0, 0).
        val legacyPark =
            Json
                .parseToJsonElement(
                    """{"gpsDetail":{"time":"20240315183045","coord":{"lat":52.37,"lon":4.89}}}""",
                ).jsonObject
        assertEquals(52.37, ccspDouble(legacyPark, legacy[CcspResponseKey.PARK_LAT]))
        assertEquals(4.89, ccspDouble(legacyPark, legacy[CcspResponseKey.PARK_LON]))

        val ccs2Park =
            Json
                .parseToJsonElement(
                    """{"time":"20240315183045.000","coord":{"lat":48.85,"lon":2.35}}""",
                ).jsonObject
        assertEquals(48.85, ccspDouble(ccs2Park, ccs2[CcspResponseKey.PARK_LAT]))
        assertEquals(2.35, ccspDouble(ccs2Park, ccs2[CcspResponseKey.PARK_LON]))
    }

    @Test
    fun `date parser accepts both basic-14 forms and epochs`() {
        assertNotNull(CcspDates.parse("20240315183045"))
        assertNotNull(CcspDates.parse("20240315183045.123"))
        assertNotNull(CcspDates.parse("1710527445"))
        assertNotNull(CcspDates.parse("1710527445123"))
        assertNull(CcspDates.parse("nonsense"))
        assertNull(CcspDates.parse(null))
    }
}
