package com.betterblue.kit.regions.hyundaicanada

import com.betterblue.kit.ApiClientConfig
import com.betterblue.kit.model.Brand
import com.betterblue.kit.model.Distance
import com.betterblue.kit.model.FuelType
import com.betterblue.kit.model.Region
import com.betterblue.kit.model.Vehicle
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Ports the three Canada tests from RegionSpecificTests.swift, plus a
 * table-driven test for the `responseCode` success decoding (which the Swift
 * suite covers only implicitly).
 */
class HyundaiCanadaStatusTest {
    private fun makeClient() =
        HyundaiCanadaClient(
            ApiClientConfig(
                region = Region.CANADA,
                brand = Brand.HYUNDAI,
                username = "",
                password = "",
                pin = "",
                accountId = "00000000-0000-0000-0000-000000000001",
            ),
        )

    private fun makeVehicle() =
        Vehicle(
            vin = "TESTVIN",
            regId = "REG",
            model = "MODEL",
            accountId = "00000000-0000-0000-0000-000000000002",
            fuelType = FuelType.GAS,
            generation = 1,
            odometer = Distance(0.0, Distance.Units.KILOMETERS),
        )

    @Test
    fun `Canada status parsing includes engine acc and remote flags`() {
        val data =
            """
            {
              "responseHeader": {"responseCode": 0},
              "result": {
                "status": {
                  "engine": true,
                  "acc": false,
                  "remoteIgnition": true,
                  "transCond": false,
                  "sleepModeCheck": true,
                  "doorLock": true,
                  "doorOpen": {"frontLeft": 0, "frontRight": 0, "backLeft": 0, "backRight": 0},
                  "trunkOpen": false,
                  "hoodOpen": true,
                  "tirePressureLamp": {"all": 0, "frontLeft": 1, "frontRight": 0, "rearLeft": 0, "rearRight": 0},
                  "battery": {"batSoc": 75},
                  "airTemp": {"value": "22", "unit": 0},
                  "defrost": false,
                  "airCtrlOn": false,
                  "steerWheelHeat": 1,
                  "lastStatusDate": "20260221195224",
                  "washerFluidStatus": true
                }
              }
            }
            """.trimIndent().toByteArray()

        val status = makeClient().parseCanadaVehicleStatusResponse(data, makeVehicle())
        assertEquals(true, status.engineOn)
        assertEquals(false, status.accessoryOn)
        assertEquals(true, status.remoteIgnition)
        assertEquals(false, status.transmissionCondition)
        assertEquals(true, status.sleepMode)
        assertEquals(true, status.washerFluidLow)
    }

    @Test
    fun `Canada location response parsing reads fndmcr coordinates`() {
        val data =
            """
            {
              "responseHeader": {"responseCode": 0},
              "result": {
                "coord": {"lat": 43.6532, "lon": -79.3832}
              }
            }
            """.trimIndent().toByteArray()

        val location = makeClient().parseCanadaLocationResponse(data)
        assertEquals(43.6532, location.latitude)
        assertEquals(-79.3832, location.longitude)
    }

    @Test
    fun `Canada status parsing uses injected vehicle location coordinates`() {
        val data =
            """
            {
              "responseHeader": {"responseCode": 0},
              "result": {
                "status": {
                  "doorLock": true,
                  "airTemp": {"value": "22", "unit": 0},
                  "defrost": false,
                  "airCtrlOn": false,
                  "steerWheelHeat": 0,
                  "lastStatusDate": "20260221195224",
                  "vehicleLocation": {
                    "coord": {"lat": 43.6532, "lon": -79.3832}
                  }
                }
              }
            }
            """.trimIndent().toByteArray()

        val status = makeClient().parseCanadaVehicleStatusResponse(data, makeVehicle())
        assertEquals(43.6532, status.location.latitude)
        assertEquals(-79.3832, status.location.longitude)
    }

    // isCanadaResponseSuccess — one function, table-driven.
    //
    // Hyundai Canada's `responseCode` has been seen as int, string, and (as of
    // mid-2026) JSON boolean — where boolean is INVERTED: false means success.

    @ParameterizedTest(name = "responseCode {0} → success={1}")
    @CsvSource(
        // JSON literal, expected success
        "0,          true", // int zero
        "'\"0\"',    true", // string zero
        "'\"false\"',true", // string false
        "'\"FALSE\"',true", // string false, case-insensitive
        "false,      true", // JSON bool false — INVERTED, means success
        "true,       false", // JSON bool true — INVERTED, means failure
        "1,          false", // int one
        "'\"1\"',    false", // string one
        "'\"true\"', false", // string true
        "2,          false",
        "'\"S\"',    false", // arbitrary string
        "null,       false", // JSON null
    )
    fun `isCanadaResponseSuccess decodes every observed responseCode shape`(literal: String, expected: Boolean) {
        val element = Json.parseToJsonElement(literal)
        assertEquals(expected, isCanadaResponseSuccess(element))
    }

    @Test
    fun `isCanadaResponseSuccess is false for a missing responseCode`() {
        assertFalse(isCanadaResponseSuccess(null))
    }

    @Test
    fun `isCanadaResponseSuccess is false for non-primitive shapes`() {
        assertFalse(isCanadaResponseSuccess(Json.parseToJsonElement("""{"code": 0}""")))
        assertFalse(isCanadaResponseSuccess(Json.parseToJsonElement("""[0]""")))
        assertTrue(isCanadaResponseSuccess(Json.parseToJsonElement("0.0"))) // numeric zero in any spelling
    }
}
