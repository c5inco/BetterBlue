package com.betterblue.kit.regions.hyundaicanada

import com.betterblue.kit.ApiClientConfig
import com.betterblue.kit.model.Brand
import com.betterblue.kit.model.Distance
import com.betterblue.kit.model.FuelType
import com.betterblue.kit.model.Region
import com.betterblue.kit.model.Vehicle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Regression coverage for Hyundai Canada status parsing. Payload shapes are
 * taken from a real Palisade debug export (BetterBlue#98). Ports
 * HyundaiCanadaParsingTests.swift 1:1.
 */
class HyundaiCanadaParsingTest {
    private fun makeClient() =
        HyundaiCanadaClient(
            ApiClientConfig(
                region = Region.CANADA,
                brand = Brand.HYUNDAI,
                username = "test@example.com",
                password = "password123",
                pin = "1234",
                accountId = "00000000-0000-0000-0000-000000000001",
            ),
        )

    private fun json(raw: String): JsonObject = Json.parseToJsonElement(raw) as JsonObject

    private fun makeVehicle(fuelType: FuelType) =
        Vehicle(
            vin = "TESTVIN0000000000",
            regId = "reg",
            model = "PALISADE",
            accountId = "00000000-0000-0000-0000-000000000002",
            fuelType = fuelType,
            generation = 2,
            odometer = Distance(0.0, Distance.Units.KILOMETERS),
        )

    // Fuel type detection

    /**
     * A Canadian gas vehicle exposes its powertrain only as `fuelKindCode`.
     * Before #98 this fell through to the ELECTRIC default, which hid the
     * fuel range and gave a Palisade a phantom CCS1 charge port.
     */
    @Test
    fun `fuelKindCode G is detected as gas not electric`() {
        val vehicleData =
            json(
                """{"fuelKindCode": "G", "genType": "G1", "mainBatteryType": false, "modelName": "PALISADE"}""",
            )
        assertEquals(FuelType.GAS, makeClient().detectFuelType(vehicleData))
    }

    @Test
    fun `fuelKindCode E and P map to electric and phev`() {
        val client = makeClient()
        assertEquals(FuelType.ELECTRIC, client.detectFuelType(json("""{"fuelKindCode": "E"}""")))
        assertEquals(FuelType.PHEV, client.detectFuelType(json("""{"fuelKindCode": "P"}""")))
    }

    /** `evStatus` is the older, more specific signal and must keep winning. */
    @Test
    fun `evStatus still takes precedence over fuelKindCode`() {
        val vehicleData = json("""{"evStatus": "E", "fuelKindCode": "G"}""")
        assertEquals(FuelType.ELECTRIC, makeClient().detectFuelType(vehicleData))
    }

    // Gas range (DTE)

    /**
     * Canada sends `dte`, not `distanceToEmpty`, and reports the unit as a
     * JSON boolean. Reading the wrong key meant gas vehicles showed no range
     * at all even though the value was present (#98).
     */
    @Test
    fun `gas range parses Canada's dte block with a boolean unit`() {
        val statusData = json("""{"fuelLevel": 59, "dte": {"unit": true, "value": 314}}""")

        val range = makeClient().parseCanadaGasRange(statusData, makeVehicle(FuelType.GAS))
        assertNotNull(range)
        assertEquals(314.0, range!!.range.length)
        assertEquals(Distance.Units.KILOMETERS, range.range.units) // unit: true == km in this region
        assertEquals(59.0, range.percentage)
    }

    @Test
    fun `gas range still accepts the distanceToEmpty spelling`() {
        val statusData = json("""{"fuelLevel": 40, "distanceToEmpty": {"unit": 1, "value": 210}}""")

        val range = makeClient().parseCanadaGasRange(statusData, makeVehicle(FuelType.GAS))
        assertNotNull(range)
        assertEquals(210.0, range!!.range.length)
        assertEquals(Distance.Units.KILOMETERS, range.range.units)
    }

    @Test
    fun `gas range is null for an electric vehicle`() {
        val statusData = json("""{"fuelLevel": 59, "dte": {"unit": true, "value": 314}}""")
        assertNull(makeClient().parseCanadaGasRange(statusData, makeVehicle(FuelType.ELECTRIC)))
    }
}
