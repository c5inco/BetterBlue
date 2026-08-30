package com.betterblue.kit.regions.hyundaicanada

// Hyundai Canada response parsing. Mirrors HyundaiCanada+Parsing.swift.

import com.betterblue.kit.ApiException
import com.betterblue.kit.json.asDoubleOrNull
import com.betterblue.kit.json.asIntOrNull
import com.betterblue.kit.json.asStringOrNull
import com.betterblue.kit.model.AuthToken
import com.betterblue.kit.model.Distance
import com.betterblue.kit.model.Vehicle
import com.betterblue.kit.model.VehicleStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.time.Duration
import java.time.Instant

internal fun HyundaiCanadaClient.parseCanadaLoginResponse(data: ByteArray): AuthToken {
    val json = parseCanadaResponse(data, context = "login")
    val result = json["result"] as? JsonObject
    val token =
        result?.get("token") as? JsonObject
            ?: throw ApiException.logError("Invalid Canada login response", apiName = apiName)
    val accessToken =
        token["accessToken"].asStringOrNull()
            ?: throw ApiException.logError("Invalid Canada login response", apiName = apiName)

    val expiresIn = token["expireIn"].asIntOrNull() ?: 3600
    val refreshToken = token["refreshToken"].asStringOrNull() ?: ""

    return AuthToken(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAt = Instant.now().plus(Duration.ofSeconds(expiresIn.toLong())),
    )
}

internal fun HyundaiCanadaClient.parseCanadaVehiclesResponse(data: ByteArray): List<Vehicle> {
    val json = parseCanadaResponse(data, context = "vehicles")
    val result = json["result"] as? JsonObject
    val vehicles =
        result?.get("vehicles") as? JsonArray
            ?: throw ApiException.logError("Invalid Canada vehicles response", apiName = apiName)

    return vehicles.mapNotNull { element ->
        val vehicleData = element as? JsonObject ?: return@mapNotNull null
        val vin = vehicleData["vin"].asStringOrNull() ?: return@mapNotNull null

        val regId =
            vehicleData["vehicleId"].asStringOrNull()
                ?: vehicleData["regid"].asStringOrNull()
                ?: vehicleData["registrationId"].asStringOrNull()
                ?: vin

        val nickname =
            vehicleData["nickName"].asStringOrNull()
                ?: vehicleData["modelName"].asStringOrNull()
                ?: vehicleData["model"].asStringOrNull()
                ?: vin

        val generation =
            vehicleData["vehicleGeneration"].asIntOrNull()
                ?: vehicleData["genType"].asIntOrNull()
                ?: 3

        val odometerObject = vehicleData["odometer"] as? JsonObject
        val odometerValue =
            vehicleData["odometer"].asDoubleOrNull()
                ?: odometerObject?.get("value").asDoubleOrNull()
                ?: 0.0

        Vehicle(
            vin = vin,
            regId = regId,
            model = nickname,
            accountId = accountId,
            fuelType = detectFuelType(vehicleData),
            generation = generation,
            odometer = Distance(odometerValue, Distance.Units.KILOMETERS),
        )
    }
}

internal fun HyundaiCanadaClient.parseCanadaVehicleStatusResponse(data: ByteArray, vehicle: Vehicle): VehicleStatus {
    val json = parseCanadaResponse(data, context = "status")
    val result =
        json["result"] as? JsonObject
            ?: throw ApiException.logError("Invalid Canada status response", apiName = apiName)

    val statusData =
        result["status"] as? JsonObject
            ?: result["vehicleStatus"] as? JsonObject
            ?: JsonObject(emptyMap())

    val vehicleData = result["vehicle"] as? JsonObject ?: JsonObject(emptyMap())
    val statusOdometer = statusData["odometer"] as? JsonObject
    val vehicleOdometer = vehicleData["odometer"] as? JsonObject

    val odometerValue =
        statusData["odometer"].asDoubleOrNull()
            ?: statusOdometer?.get("value").asDoubleOrNull()
            ?: vehicleData["odometer"].asDoubleOrNull()
            ?: vehicleOdometer?.get("value").asDoubleOrNull()
    val odometer = odometerValue?.let { Distance(it, Distance.Units.KILOMETERS) } ?: vehicle.odometer

    // Additional Canada-only boolean flags.
    val engineOn = parseBoolOrInt(statusData["engine"])
    val accessoryOn = parseBoolOrInt(statusData["acc"])
    val remoteIgnition = statusData["remoteIgnition"].asJsonBooleanOrNull()
    val transmissionCondition = statusData["transCond"].asJsonBooleanOrNull()
    val sleepMode = statusData["sleepModeCheck"].asJsonBooleanOrNull()
    val washerFluidLow = parseBoolOrInt(statusData["washerFluidStatus"])

    return VehicleStatus(
        vin = vehicle.vin,
        gasRange = parseCanadaGasRange(statusData, vehicle),
        evStatus = parseCanadaEvStatus(statusData, vehicle),
        location = parseCanadaLocation(statusData),
        lockStatus = VehicleStatus.LockStatus.fromLocked(statusData["doorLock"].asJsonBooleanOrNull()),
        climateStatus = parseCanadaClimateStatus(statusData),
        odometer = odometer,
        syncDate = parseCanadaSyncDate(statusData),
        battery12V = parseCanadaBattery12V(statusData),
        doorOpen = parseCanadaDoorStatus(statusData),
        trunkOpen = parseCanadaTrunkOpen(statusData),
        hoodOpen = parseCanadaHoodOpen(statusData),
        tirePressureWarning = parseCanadaTirePressureWarning(statusData),
        engineOn = engineOn,
        accessoryOn = accessoryOn,
        remoteIgnition = remoteIgnition,
        transmissionCondition = transmissionCondition,
        sleepMode = sleepMode,
        washerFluidLow = washerFluidLow,
    )
}

internal fun HyundaiCanadaClient.validateCommandResponse(data: ByteArray, context: String) {
    parseCanadaResponse(data, context)
}

internal fun HyundaiCanadaClient.parseCommandAuthResponse(data: ByteArray): String {
    val json = parseCanadaResponse(data, context = "command auth")
    val result = json["result"] as? JsonObject
    return result?.get("pAuth").asStringOrNull()
        ?: throw ApiException.logError("Invalid Canada command auth response", apiName = apiName)
}

internal fun HyundaiCanadaClient.parseCanadaLocationResponse(data: ByteArray): VehicleStatus.Location {
    val json = parseCanadaResponse(data, context = "location")
    val result =
        json["result"] as? JsonObject
            ?: throw ApiException.logError("Invalid Canada location response", apiName = apiName)

    // Three shapes seen from this API: `result.gpsDetail.coord`, `result.coord`
    // (BetterBlueKit#36), and the flat `gpsDetail.coordLat` / `coordLon` pair
    // the surround-view endpoints return. Accept all of them rather than
    // betting on which one a given endpoint uses today.
    val gpsDetail = result["gpsDetail"] as? JsonObject

    val coord = (gpsDetail?.get("coord") as? JsonObject) ?: (result["coord"] as? JsonObject)
    if (coord != null) {
        return VehicleStatus.Location(
            latitude = coord["lat"].asDoubleOrNull() ?: 0.0,
            longitude = coord["lon"].asDoubleOrNull() ?: 0.0,
        )
    }

    val latitude = gpsDetail?.get("coordLat").asDoubleOrNull()
    val longitude = gpsDetail?.get("coordLon").asDoubleOrNull()
    if (latitude != null && longitude != null) {
        return VehicleStatus.Location(latitude = latitude, longitude = longitude)
    }

    throw ApiException.logError("Invalid Canada location response", apiName = apiName)
}
