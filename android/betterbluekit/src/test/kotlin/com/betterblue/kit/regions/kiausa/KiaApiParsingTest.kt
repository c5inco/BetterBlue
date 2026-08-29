package com.betterblue.kit.regions.kiausa

import com.betterblue.kit.ApiClientBase
import com.betterblue.kit.ApiClientConfig
import com.betterblue.kit.json.asBooleanOrNull
import com.betterblue.kit.json.asIntOrNull
import com.betterblue.kit.json.asObjectOrNull
import com.betterblue.kit.json.asStringOrNull
import com.betterblue.kit.model.Brand
import com.betterblue.kit.model.ClimateOptions
import com.betterblue.kit.model.Distance
import com.betterblue.kit.model.FuelType
import com.betterblue.kit.model.Region
import com.betterblue.kit.model.Temperature
import com.betterblue.kit.model.Vehicle
import com.betterblue.kit.model.VehicleCommand
import com.betterblue.kit.supportsMfa
import kotlinx.serialization.json.JsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Ported 1:1 from BetterBlueKit's KiaAPIParsingTests.swift.
//
// Note: Parsing tests were removed on the Swift side when the parsing methods
// became private implementation details of KiaUSAAPIClient. This file retains
// the sample JSON data for documentation and integration testing reference.

/** Sample Kia API JSON responses for documentation and testing reference. */
internal object KiaSampleJson {

    /** Sample vehicle status response from Kia USA API. */
    val vehicleStatus = """
    {
      "payload": {
        "vehicleInfoList": [{
          "lastVehicleInfo": {
            "linkStatus" : 0,
            "vehicleStatusRpt" : {
              "reportDate" : {
                "utc" : "20251003012955",
                "offset" : -7
              },
              "vehicleStatus" : {
                "distanceToEmpty" : {
                  "value" : 279,
                  "unit" : 3
                },
                "doorLock" : true,
                "syncDate" : {
                  "utc" : "20251003012546",
                  "offset" : -7
                },
                "evStatus" : {
                  "batteryPlugin" : 0,
                  "batteryStatus" : 76,
                  "batteryCharge" : false,
                  "drvDistance" : [
                    {
                      "type" : 2,
                      "rangeByFuel" : {
                        "evModeRange" : {
                          "value" : 279,
                          "unit" : 3
                        },
                        "totalAvailableRange" : {
                          "value" : 279,
                          "unit" : 3
                        }
                      }
                    }
                  ],
                  "targetSOC" : [
                    {
                      "targetSOClevel" : 80,
                      "plugType" : 1
                    },
                    {
                      "targetSOClevel" : 80,
                      "plugType" : 0
                    }
                  ]
                },
                "climate" : {
                  "airCtrl" : false,
                  "airTemp" : {
                    "value" : "72",
                    "unit" : 1
                  },
                  "heatingAccessory" : {
                    "steeringWheel" : 0
                  },
                  "defrost" : false
                }
              }
            },
            "location" : {
              "syncDate" : {
                "utc" : "20251003012257",
                "offset" : -4
              },
              "coord" : {
                "lat" : 38.964186,
                "lon" : -84.516544
              }
            }
          }
        }]
      }
    }
    """.trimIndent()

    /** Sample login response requiring MFA. */
    val mfaRequired = """
    {
      "payload": {
        "otpKey": "abc123otpkey",
        "hasEmail": true,
        "hasPhone": true,
        "email": "t***@example.com",
        "phone": "***-***-1234",
        "rmTokenExpired": false
      }
    }
    """.trimIndent()

    /** Sample vehicles list response. */
    val vehiclesList = """
    {
      "payload": {
        "vehicleSummary": [
          {
            "vin": "KNDJ23AU1N7000000",
            "vehicleIdentifier": "REG123456",
            "nickName": "My EV6",
            "vehicleKey": "key123abc",
            "genType": "4",
            "fuelType": 1,
            "mileage": 25000
          }
        ]
      }
    }
    """.trimIndent()

    /** Sample error response. */
    val errorResponse = """
    {
      "status": {
        "statusCode": 1,
        "errorCode": 1003,
        "errorType": 1,
        "errorMessage": "Session Key is invalid or expired"
      }
    }
    """.trimIndent()
}

class KiaUsaClientTest {

    private fun makeKiaUsClient() = KiaUsaClient(
        ApiClientConfig(
            region = Region.USA,
            brand = Brand.KIA,
            username = "test@example.com",
            password = "password123",
            pin = "0000",
            accountId = "00000000-0000-0000-0000-000000000001",
        ),
    )

    private fun makeVehicle() = Vehicle(
        vin = "KNDC3DLC5N0000000",
        regId = "REG",
        model = "EV6",
        accountId = "00000000-0000-0000-0000-000000000002",
        fuelType = FuelType.ELECTRIC,
        generation = 3,
        odometer = Distance(1000.0, Distance.Units.MILES),
        vehicleKey = "vk",
    )

    @Test
    fun `KiaUsaClient initialization`() {
        val client = makeKiaUsClient()

        assertEquals("KiaUSA", client.apiName)
        assertTrue(client.supportsMfa())
    }

    @Test
    fun `KiaUsaClient supports MFA`() {
        val client = makeKiaUsClient()
        assertTrue(client.supportsMfa())
    }

    @Test
    fun `Kia US climate always sends Fahrenheit (unit 1)`() {
        val client = makeKiaUsClient()
        // Celsius preset — must be converted to F + unit 1.
        val options = ClimateOptions(temperature = Temperature(Temperature.Units.CELSIUS, 22.0))

        val body = client.commandBody(VehicleCommand.StartClimate(options))
        val remoteClimate = body["remoteClimate"].asObjectOrNull()
        val airTemp = remoteClimate?.get("airTemp").asObjectOrNull()
        // 22°C ≈ 71.6°F → rounds to 72.
        assertEquals(1, airTemp?.get("unit").asIntOrNull())
        assertEquals("72", airTemp?.get("value").asStringOrNull())
    }

    @Test
    fun `Kia US climate clamps out-of-range temps to LOW HIGH`() {
        val client = makeKiaUsClient()

        val cold = ClimateOptions(temperature = Temperature(Temperature.Units.FAHRENHEIT, 55.0))
        val coldBody = client.commandBody(VehicleCommand.StartClimate(cold))
        val coldTemp = coldBody["remoteClimate"].asObjectOrNull()?.get("airTemp").asObjectOrNull()
        assertEquals("LOW", coldTemp?.get("value").asStringOrNull())

        val hot = ClimateOptions(temperature = Temperature(Temperature.Units.FAHRENHEIT, 90.0))
        val hotBody = client.commandBody(VehicleCommand.StartClimate(hot))
        val hotTemp = hotBody["remoteClimate"].asObjectOrNull()?.get("airTemp").asObjectOrNull()
        assertEquals("HIGH", hotTemp?.get("value").asStringOrNull())
    }

    @Test
    fun `Kia US climate omits heatVentSeat when no seat is set`() {
        val client = makeKiaUsClient()
        val options = ClimateOptions() // all seats default 0, no ventilation
        val body = client.commandBody(VehicleCommand.StartClimate(options))
        val remoteClimate = body["remoteClimate"].asObjectOrNull()
        // Key must be OMITTED ENTIRELY — Kia rejects the whole command when a
        // vehicle without seat climate sees heatVentSeat (even null/empty).
        assertNull(remoteClimate?.get("heatVentSeat"))
    }

    @Test
    fun `Kia US climate includes heatVentSeat when a seat is set`() {
        val client = makeKiaUsClient()
        val options = ClimateOptions(frontLeftSeat = 2) // driver seat heat level 2 (medium heat)
        val body = client.commandBody(VehicleCommand.StartClimate(options))
        val remoteClimate = body["remoteClimate"].asObjectOrNull()
        val seats = remoteClimate?.get("heatVentSeat").asObjectOrNull()
        assertNotNull(seats)
        // Medium heat → type 1 (heat), level 3, step 2.
        val driverSeat = seats?.get("driverSeat").asObjectOrNull()
        assertEquals(1, driverSeat?.get("heatVentType").asIntOrNull())
        assertEquals(3, driverSeat?.get("heatVentLevel").asIntOrNull())
        assertEquals(2, driverSeat?.get("heatVentStep").asIntOrNull())
    }
}

// JSON format documentation tests

class KiaJsonFormatTest {

    @Test
    fun `vehicle status JSON is valid`() {
        val json = ApiClientBase.parseJsonObject(KiaSampleJson.vehicleStatus.toByteArray())

        assertTrue(json.isNotEmpty())
        assertNotNull(json["payload"])
    }

    @Test
    fun `MFA required JSON is valid`() {
        val json = ApiClientBase.parseJsonObject(KiaSampleJson.mfaRequired.toByteArray())

        assertTrue(json.isNotEmpty())
        assertNotNull(json["payload"])

        val payload = json["payload"].asObjectOrNull()
        assertEquals("abc123otpkey", payload?.get("otpKey").asStringOrNull())
        assertEquals(true, payload?.get("hasEmail").asBooleanOrNull())
    }

    @Test
    fun `vehicles list JSON is valid`() {
        val json = ApiClientBase.parseJsonObject(KiaSampleJson.vehiclesList.toByteArray())

        assertTrue(json.isNotEmpty())

        val vehicles = json["payload"].asObjectOrNull()?.get("vehicleSummary") as? JsonArray
        assertEquals(1, vehicles?.size)
        assertEquals("KNDJ23AU1N7000000", vehicles?.firstOrNull().asObjectOrNull()?.get("vin").asStringOrNull())
    }

    @Test
    fun `error response JSON is valid`() {
        val json = ApiClientBase.parseJsonObject(KiaSampleJson.errorResponse.toByteArray())

        assertTrue(json.isNotEmpty())

        val status = json["status"].asObjectOrNull()
        assertEquals(1003, status?.get("errorCode").asIntOrNull())
        assertEquals(true, status?.get("errorMessage").asStringOrNull()?.contains("expired"))
    }
}
