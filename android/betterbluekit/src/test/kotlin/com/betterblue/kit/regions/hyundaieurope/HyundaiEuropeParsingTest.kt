package com.betterblue.kit.regions.hyundaieurope

import com.betterblue.kit.ApiClientConfig
import com.betterblue.kit.ApiException
import com.betterblue.kit.model.Brand
import com.betterblue.kit.model.Distance
import com.betterblue.kit.model.FuelType
import com.betterblue.kit.model.Region
import com.betterblue.kit.model.Temperature
import com.betterblue.kit.model.Vehicle
import com.betterblue.kit.model.VehicleMarketOptions
import com.betterblue.kit.model.VehicleStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Parsing coverage for Hyundai Europe (CCSP), across both the CCS2 and the
 * legacy status shapes. The payloads mirror the captured responses in the
 * Swift `HyEuAPIParsingTests` suite.
 */
class HyundaiEuropeParsingTest {

    private fun makeClient() = HyundaiEuropeClient(
        ApiClientConfig(
            region = Region.EUROPE,
            brand = Brand.HYUNDAI,
            username = "test@example.com",
            password = "password123",
            pin = "1234",
            accountId = "00000000-0000-0000-0000-000000000001",
        ),
    )

    private fun makeVehicle(ccs2: Boolean, fuelType: FuelType = FuelType.ELECTRIC) = Vehicle(
        vin = "TESTVIN0000000000",
        regId = "reg-1",
        model = "IONIQ 5",
        accountId = "00000000-0000-0000-0000-000000000002",
        fuelType = fuelType,
        generation = 2,
        odometer = Distance(0.0, Distance.Units.KILOMETERS),
        marketOptions = VehicleMarketOptions.HyundaiEurope(ccs2Supported = ccs2),
    )

    // Vehicle list

    @Test
    fun `vehicle list parses type and ccs2 support`() {
        val payload = """
            {"resMsg":{"vehicles":[
              {"vehicleId":"reg-1","vin":"VIN00000000000001","nickname":"IONIQ 5",
               "type":"EV","ccuCCS2ProtocolSupport":1},
              {"vehicleId":"reg-2","vin":"VIN00000000000002","vehicleName":"TUCSON PHEV",
               "type":"PE","ccuCCS2ProtocolSupport":0}
            ]}}
        """.trimIndent().toByteArray()

        val vehicles = makeClient().parseVehiclesResponse(payload)
        assertEquals(2, vehicles.size)

        assertEquals("VIN00000000000001", vehicles[0].vin)
        assertEquals("IONIQ 5", vehicles[0].model)
        assertEquals(FuelType.ELECTRIC, vehicles[0].fuelType)
        assertTrue(vehicles[0].marketOptions.ccs2Supported)

        // Falls back to vehicleName when nickname is absent.
        assertEquals("TUCSON PHEV", vehicles[1].model)
        assertEquals(FuelType.PHEV, vehicles[1].fuelType)
        assertFalse(vehicles[1].marketOptions.ccs2Supported)
    }

    @Test
    fun `an invalid vehicle list throws`() {
        assertThrows<ApiException> {
            makeClient().parseVehiclesResponse("""{"status":"ok"}""".toByteArray())
        }
    }

    // CCS2 status

    private val ccs2Status = """
        {"resMsg":{"state":{"Vehicle":{
          "Drivetrain":{"Odometer":12345.0,"FuelSystem":{"DTE":{"Total":231,"Unit":1}}},
          "Green":{
            "BatteryManagement":{"BatteryRemain":{"Ratio":81}},
            "ChargingInformation":{
              "Charging":{"RemainTime":45},
              "ConnectorFastening":{"State":1},
              "TargetSoC":{"Standard":80,"Quick":90}
            },
            "Electric":{"SmartGrid":{"RealTimePower":7.4}}
          },
          "Electronics":{"Battery":{"Level":87}},
          "Cabin":{
            "Door":{"Row1":{"Driver":{"Lock":0,"Open":0},"Passenger":{"Lock":0,"Open":1}},
                    "Row2":{"Left":{"Lock":0,"Open":0},"Right":{"Lock":0,"Open":0}}},
            "HVAC":{"Row1":{"Driver":{"Temperature":{"Value":"22.0","Unit":0},
                                       "Blower":{"SpeedLevel":3}}}},
            "SteeringWheel":{"Heat":{"State":1}}
          },
          "Body":{"Trunk":{"Open":0},"Hood":{"Open":0},
                  "Windshield":{"Front":{"Defog":{"State":0}}}},
          "Chassis":{"Axle":{"Row1":{"Left":{"Tire":{"PressureLow":0}},
                                      "Right":{"Tire":{"PressureLow":1}}},
                             "Row2":{"Left":{"Tire":{"PressureLow":0}},
                                      "Right":{"Tire":{"PressureLow":0}}},
                             "Tire":{"PressureLow":0}}},
          "Location":{"GeoCoord":{"Latitude":52.3702,"Longitude":4.8952},"Date":"20240315183045.000"},
          "DrivingReady":0
        }},"lastUpdateTime":1710527445000}}
    """.trimIndent().toByteArray()

    @Test
    fun `ccs2 status parses ev, doors, climate and location`() {
        val status = makeClient().parseVehicleStatusResponse(
            ccs2Status,
            locationData = null,
            vehicle = makeVehicle(ccs2 = true),
        )

        assertEquals("TESTVIN0000000000", status.vin)
        assertEquals(12345.0, status.odometer?.length)
        assertEquals(Distance.Units.KILOMETERS, status.odometer?.units)
        assertEquals(87, status.battery12V)

        val ev = requireNotNull(status.evStatus)
        assertEquals(81.0, ev.evRange.percentage)
        assertEquals(231.0, ev.evRange.range.length)
        assertEquals(80.0, ev.targetSocAC)
        assertEquals(90.0, ev.targetSocDC)

        // CCS2 lock bits are inverted: 0 means locked.
        assertEquals(VehicleStatus.LockStatus.LOCKED, status.lockStatus)

        assertEquals(true, status.doorOpen?.frontRight)
        assertEquals(false, status.doorOpen?.frontLeft)
        assertEquals(true, status.tirePressureWarning?.frontRight)
        assertEquals(true, status.tirePressureWarning?.hasWarning)

        assertEquals(true, status.climateStatus.steeringWheelHeatingOn)
        assertEquals(Temperature.Units.CELSIUS, status.climateStatus.temperature.units)
        assertEquals(22.0, status.climateStatus.temperature.value)

        assertEquals(52.3702, status.location.latitude)
        assertEquals(4.8952, status.location.longitude)
        assertTrue(status.location.hasCoordinates)
    }

    @Test
    fun `ccs2 unlocked state reads through the inversion`() {
        val unlocked = String(ccs2Status).replace("\"Driver\":{\"Lock\":0", "\"Driver\":{\"Lock\":1")
        val status = makeClient().parseVehicleStatusResponse(
            unlocked.toByteArray(),
            locationData = null,
            vehicle = makeVehicle(ccs2 = true),
        )
        assertEquals(VehicleStatus.LockStatus.UNLOCKED, status.lockStatus)
    }

    // Legacy status

    private val legacyStatus = """
        {"resMsg":{"vehicleStatusInfo":{
          "vehicleStatus":{
            "doorLock":true,
            "doorOpen":{"frontLeft":0,"frontRight":0,"backLeft":0,"backRight":0},
            "trunkOpen":false,"hoodOpen":false,
            "airCtrlOn":false,"defrost":false,"steerWheelHeat":0,
            "airTemp":{"value":"20H","unit":0},
            "battery":{"batSoc":85},
            "engine":false,
            "time":"20240315183045",
            "evStatus":{
              "batteryStatus":64,"batteryCharge":true,"batteryPlugin":1,
              "remainTime2":{"atc":{"value":95}},
              "batteryPower":{"batteryStndChrgPower":7.2,"batteryFstChrgPower":0},
              "drvDistance":[{"rangeByFuel":{"evModeRange":{"value":198,"unit":1}}}],
              "reservChargeInfos":{"targetSOClist":[
                {"plugType":1,"targetSOClevel":80},{"plugType":0,"targetSOClevel":90}]}
            },
            "tirePressureLamp":{"tirePressureLampAll":0,"tirePressureLampFL":0,
                                 "tirePressureLampFR":0,"tirePressureLampRL":0,"tirePressureLampRR":0}
          },
          "odometer":{"value":54321,"unit":1},
          "vehicleLocation":{"coord":{"lat":48.8566,"lon":2.3522},"time":"20240315183045"}
        }}}
    """.trimIndent().toByteArray()

    @Test
    fun `legacy status parses through the legacy path table`() {
        val status = makeClient().parseVehicleStatusResponse(
            legacyStatus,
            locationData = null,
            vehicle = makeVehicle(ccs2 = false),
        )

        assertEquals(54321.0, status.odometer?.length)
        assertEquals(85, status.battery12V)
        // Legacy uses the single doorLock flag, NOT the inverted per-door bits.
        assertEquals(VehicleStatus.LockStatus.LOCKED, status.lockStatus)

        val ev = requireNotNull(status.evStatus)
        assertEquals(64.0, ev.evRange.percentage)
        assertEquals(198.0, ev.evRange.range.length)
        assertTrue(ev.charging)
        // batteryPlugin 1 = DC fast charger.
        assertEquals(VehicleStatus.PlugType.DC_CHARGER, ev.plugType)
        assertEquals(80.0, ev.targetSocAC)
        assertEquals(90.0, ev.targetSocDC)

        // "20H" is the legacy hex temperature encoding: (28 + 0x20) * 0.5 is
        // out of range, so it clamps through the documented parser path.
        assertNotNull(status.climateStatus.temperature)

        assertEquals(48.8566, status.location.latitude)
        assertEquals(2.3522, status.location.longitude)
    }

    @Test
    fun `park location wins when it is newer than the status location`() {
        val park = """
            {"resMsg":{"gpsDetail":{"time":"20240316120000","coord":{"lat":51.5074,"lon":-0.1278}}}}
        """.trimIndent().toByteArray()

        val status = makeClient().parseVehicleStatusResponse(
            legacyStatus,
            locationData = park,
            vehicle = makeVehicle(ccs2 = false),
        )
        assertEquals(51.5074, status.location.latitude)
        assertEquals(-0.1278, status.location.longitude)
    }

    @Test
    fun `null island is treated as no fix`() {
        val noFix = String(legacyStatus).replace("\"lat\":48.8566,\"lon\":2.3522", "\"lat\":0,\"lon\":0")
        val status = makeClient().parseVehicleStatusResponse(
            noFix.toByteArray(),
            locationData = null,
            vehicle = makeVehicle(ccs2 = false),
        )
        assertFalse(status.location.hasCoordinates)
    }

    @Test
    fun `a gas vehicle gets no ev status`() {
        val status = makeClient().parseVehicleStatusResponse(
            legacyStatus,
            locationData = null,
            vehicle = makeVehicle(ccs2 = false, fuelType = FuelType.GAS),
        )
        assertEquals(null, status.evStatus)
    }

    @Test
    fun `an invalid status response throws`() {
        assertThrows<ApiException> {
            makeClient().parseVehicleStatusResponse(
                """{"status":"ok"}""".toByteArray(),
                locationData = null,
                vehicle = makeVehicle(ccs2 = true),
            )
        }
    }

    // Auth token

    @Test
    fun `refresh-grant token parses both tokens from the response`() {
        val payload = """
            {"access_token":"acc-123","refresh_token":"ref-456","expires_in":3600}
        """.trimIndent().toByteArray()

        val token = makeClient().parseAuthToken(payload, isRefresh = true)
        assertEquals("acc-123", token.accessToken)
        assertEquals("ref-456", token.refreshToken)
        assertTrue(token.isValid)
    }

    @Test
    fun `password-grant token keeps the configured refresh token`() {
        // The EU password-grant response carries no refresh_token; the one
        // already on the account persists.
        val client = HyundaiEuropeClient(
            ApiClientConfig(
                region = Region.EUROPE,
                brand = Brand.HYUNDAI,
                username = "test@example.com",
                password = "password123",
                refreshToken = "stored-refresh",
                pin = "1234",
                accountId = "00000000-0000-0000-0000-000000000001",
            ),
        )
        val payload = """{"access_token":"acc-123","expires_in":3600}""".toByteArray()

        val token = client.parseAuthToken(payload, isRefresh = false)
        assertEquals("acc-123", token.accessToken)
        assertEquals("stored-refresh", token.refreshToken)
    }
}
