package com.betterblue.kit.regions.hyundaiusa

import com.betterblue.kit.ApiClientBase
import com.betterblue.kit.ApiException
import com.betterblue.kit.json.asBooleanOrNull
import com.betterblue.kit.json.asDoubleOrNull
import com.betterblue.kit.json.asIntOrNull
import com.betterblue.kit.json.asStringOrNull
import com.betterblue.kit.log.BBLogCategory
import com.betterblue.kit.log.BBLogger
import com.betterblue.kit.model.AuthToken
import com.betterblue.kit.model.Distance
import com.betterblue.kit.model.EVTripSummary
import com.betterblue.kit.model.FuelType
import com.betterblue.kit.model.Temperature
import com.betterblue.kit.model.Vehicle
import com.betterblue.kit.model.VehicleStatus
import com.betterblue.kit.util.BluelinkDates
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

internal fun HyundaiUsaClient.parseLoginResponse(data: ByteArray): AuthToken {
    val json = ApiClientBase.parseJsonObject(data)
    val accessToken = json["access_token"].asStringOrNull()
    val refreshToken = json["refresh_token"].asStringOrNull()
    val expiresIn = json["expires_in"].asStringOrNull()?.toIntOrNull()

    if (accessToken == null || refreshToken == null || expiresIn == null) {
        throw ApiException.logError("Invalid login response: ${data.toString(Charsets.UTF_8)}", apiName = apiName)
    }

    BBLogger.info(BBLogCategory.AUTH, "HyundaiUSA: Login successful")
    return AuthToken(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAt = Instant.now().plus(Duration.ofSeconds(expiresIn.toLong())),
    )
}

internal fun HyundaiUsaClient.parseVehiclesResponse(data: ByteArray): List<Vehicle> {
    val json = ApiClientBase.parseJsonObject(data)
    val vehicleArray = json["enrolledVehicleDetails"] as? JsonArray
        ?: throw ApiException.logError("Invalid vehicles response", apiName = apiName)

    return vehicleArray.mapNotNull { vehicleData ->
        val details = (vehicleData as? JsonObject)?.get("vehicleDetails") as? JsonObject ?: return@mapNotNull null
        val vin = details["vin"].asStringOrNull() ?: return@mapNotNull null
        val regId = details["regid"].asStringOrNull() ?: return@mapNotNull null
        val nickname = details["nickName"].asStringOrNull() ?: return@mapNotNull null
        val evStatus = details["evStatus"].asStringOrNull() ?: return@mapNotNull null
        val generation = details["vehicleGeneration"].asStringOrNull() ?: return@mapNotNull null

        Vehicle(
            vin = vin,
            regId = regId,
            model = nickname,
            accountId = accountId,
            fuelType = when (evStatus) {
                "E" -> FuelType.ELECTRIC
                "P" -> FuelType.PHEV
                else -> FuelType.GAS
            },
            generation = generation.toIntOrNull() ?: 1,
            odometer = Distance(details["odometer"].asDoubleOrNull() ?: 0.0, Distance.Units.MILES),
        )
    }
}

internal fun HyundaiUsaClient.parseVehicleStatusResponse(data: ByteArray, vehicle: Vehicle): VehicleStatus {
    val json = ApiClientBase.parseJsonObject(data)
    val statusData = json["vehicleStatus"] as? JsonObject
        ?: throw ApiException.logError("Invalid status response", apiName = apiName)

    val airTemp = statusData["airTemp"] as? JsonObject
    val climateStatus = VehicleStatus.ClimateStatus(
        defrostOn = statusData["defrost"].asBooleanOrNull() ?: false,
        airControlOn = statusData["airCtrlOn"].asBooleanOrNull() ?: false,
        steeringWheelHeatingOn = (statusData["steerWheelHeat"].asIntOrNull() ?: 0) != 0,
        temperature = Temperature.fromApi(
            units = airTemp?.get("unit").asIntOrNull(),
            value = airTemp?.get("value").asStringOrNull(),
        ),
    )

    val syncDate = statusData["dateTime"].asStringOrNull()?.let { BluelinkDates.parseIso8601(it) }
    val battery12V = (statusData["battery"] as? JsonObject)?.get("batSoc").asIntOrNull()

    return VehicleStatus(
        vin = vehicle.vin,
        gasRange = parseGasRange(statusData, vehicle),
        evStatus = parseEvStatus(statusData, vehicle),
        location = parseLocation(statusData),
        lockStatus = VehicleStatus.LockStatus.fromLocked(statusData["doorLock"].asBooleanOrNull()),
        climateStatus = climateStatus,
        odometer = vehicle.odometer,
        syncDate = syncDate,
        battery12V = battery12V,
        doorOpen = parseDoorOpen(statusData),
        trunkOpen = statusData["trunkOpen"].asBooleanOrNull(),
        hoodOpen = statusData["hoodOpen"].asBooleanOrNull(),
        tirePressureWarning = parseTirePressure(statusData),
    )
}

internal fun HyundaiUsaClient.parseCommandResponse(data: ByteArray) {
    val json = ApiClientBase.parseJsonObject(data)
    if (json["isBlueLinkServicePinValid"].asStringOrNull() == "invalid") {
        val remaining = json["remainingAttemptCount"].asStringOrNull() ?: "unknown"
        throw ApiException.invalidPin("Invalid PIN, $remaining attempts remaining", apiName = apiName)
    }
}

internal fun HyundaiUsaClient.parseEvTripSummaryResponse(data: ByteArray): List<EVTripSummary> {
    val json = ApiClientBase.parseJsonObject(data)
    val tripDetails = json["tripdetails"] as? JsonArray
        ?: throw ApiException("Failed to parse trip details", apiName = apiName)

    return tripDetails.mapNotNull { tripElement ->
        val trip = tripElement as? JsonObject ?: return@mapNotNull null
        val distance = trip["distance"].asIntOrNull() ?: return@mapNotNull null
        val odometerDict = trip["odometer"] as? JsonObject ?: return@mapNotNull null
        val odometerValue = odometerDict["value"].asDoubleOrNull() ?: return@mapNotNull null
        val accessories = trip["accessories"].asIntOrNull() ?: return@mapNotNull null
        val totalUsed = trip["totalused"].asIntOrNull() ?: return@mapNotNull null
        val regen = trip["regen"].asIntOrNull() ?: return@mapNotNull null
        val climate = trip["climate"].asIntOrNull() ?: return@mapNotNull null
        val drivetrain = trip["drivetrain"].asIntOrNull() ?: return@mapNotNull null
        val batteryCare = trip["batterycare"].asIntOrNull() ?: return@mapNotNull null
        val startDateString = trip["startdate"].asStringOrNull() ?: return@mapNotNull null
        val durationValue = (trip["duration"] as? JsonObject)?.get("value").asIntOrNull() ?: return@mapNotNull null
        val avgSpeedValue = (trip["avgspeed"] as? JsonObject)?.get("value").asDoubleOrNull() ?: return@mapNotNull null
        val maxSpeedValue = (trip["maxspeed"] as? JsonObject)?.get("value").asDoubleOrNull() ?: return@mapNotNull null
        val startDate = BluelinkDates.parseSqlish(startDateString) ?: return@mapNotNull null

        // The {value, unit} dicts carry the account's unit system (1 = km,
        // otherwise miles); the bare `distance` field shares it.
        val units = Distance.Units.fromInt(odometerDict["unit"].asIntOrNull() ?: 3)

        EVTripSummary(
            distance = Distance(distance.toDouble(), units),
            odometer = Distance(odometerValue, units),
            accessoriesEnergy = accessories,
            totalEnergyUsed = totalUsed,
            regenEnergy = regen,
            climateEnergy = climate,
            drivetrainEnergy = drivetrain,
            batteryCareEnergy = batteryCare,
            startDate = startDate,
            duration = durationValue.seconds,
            avgSpeed = avgSpeedValue,
            maxSpeed = maxSpeedValue,
        )
    }
}

// Status parsing helpers

private fun HyundaiUsaClient.parseEvStatus(statusData: JsonObject, vehicle: Vehicle): VehicleStatus.EvStatus? {
    if (!vehicle.fuelType.hasElectricCapability) return null
    val evStatusData = statusData["evStatus"] as? JsonObject ?: return null

    val ranges = fuelRanges(statusData)
    val evRange: Distance = if (ranges.size == 1) {
        ranges.values.first()
    } else {
        ranges[FuelType.ELECTRIC] ?: return null
    }

    val fuelPercentage = evStatusData["batteryStatus"].asDoubleOrNull() ?: 0.0
    // Trust the API's `unit` field: the backend has been observed mislabelling
    // values, but the canonical Python reference doesn't cross-validate either.
    val atc = ((evStatusData["remainTime2"] as? JsonObject)?.get("atc")) as? JsonObject
    val chargeTimeMinutes = atc?.get("value").asIntOrNull() ?: 0
    val batteryPlugin = evStatusData["batteryPlugin"].asIntOrNull() ?: 0

    val targetSocList = ((evStatusData["reservChargeInfos"] as? JsonObject)?.get("targetSOClist")) as? JsonArray
    var targetSocAC: Double? = null
    var targetSocDC: Double? = null
    targetSocList?.forEach { element ->
        val target = element as? JsonObject ?: return@forEach
        val plugType = target["plugType"].asIntOrNull() ?: return@forEach
        val soc = target["targetSOClevel"].asDoubleOrNull() ?: return@forEach
        when (plugType) {
            1 -> targetSocAC = soc
            0 -> targetSocDC = soc
        }
    }

    return VehicleStatus.EvStatus(
        charging = evStatusData["batteryCharge"].asBooleanOrNull() ?: false,
        chargeSpeed = maxOf(
            evStatusData["batteryStndChrgPower"].asDoubleOrNull() ?: 0.0,
            evStatusData["batteryFstChrgPower"].asDoubleOrNull() ?: 0.0,
        ),
        evRange = VehicleStatus.FuelRange(range = evRange, percentage = fuelPercentage),
        plugType = VehicleStatus.PlugType.fromBatteryPlugin(batteryPlugin),
        chargeTimeSeconds = 60L * chargeTimeMinutes,
        targetSocAC = targetSocAC,
        targetSocDC = targetSocDC,
    )
}

private fun fuelRanges(statusData: JsonObject): Map<FuelType, Distance> {
    val evStatusData = statusData["evStatus"] as? JsonObject ?: return emptyMap()
    val distances = evStatusData["drvDistance"] as? JsonArray ?: return emptyMap()

    val result = mutableMapOf<FuelType, Distance>()
    for (element in distances) {
        val distance = element as? JsonObject ?: continue
        val type = distance["type"].asIntOrNull() ?: 0
        val rangeByFuelData = distance["rangeByFuel"] as? JsonObject ?: continue

        (rangeByFuelData["evModeRange"] as? JsonObject)?.let { evRange ->
            result[FuelType.ELECTRIC] = Distance(
                length = evRange["value"].asDoubleOrNull() ?: 0.0,
                units = Distance.Units.fromInt(evRange["unit"].asIntOrNull() ?: 2),
            )
        }
        (rangeByFuelData["gasModeRange"] as? JsonObject)?.let { gasRange ->
            result[FuelType.GAS] = Distance(
                length = gasRange["value"].asDoubleOrNull() ?: 0.0,
                units = Distance.Units.fromInt(gasRange["unit"].asIntOrNull() ?: 2),
            )
        }
        val totalRange = rangeByFuelData["totalAvailableRange"] as? JsonObject
        if (totalRange != null && result.isEmpty()) {
            result[FuelType.fromNumber(type)] = Distance(
                length = totalRange["value"].asDoubleOrNull() ?: 0.0,
                units = Distance.Units.fromInt(totalRange["unit"].asIntOrNull() ?: 2),
            )
        }
    }
    return result
}

private fun parseGasRange(statusData: JsonObject, vehicle: Vehicle): VehicleStatus.FuelRange? {
    // Pure EVs don't have gas range — skip to avoid misinterpreting dte/fuelLevel
    // fields (fuelLevel is a boolean `false` on EVs and must not coerce to 0).
    if (vehicle.fuelType == FuelType.ELECTRIC) return null
    val percentage = statusData["fuelLevel"].asDoubleOrNull() ?: return null

    // Gas range can be in two places: the evStatus rangeByFuel section or dte
    fuelRanges(statusData)[FuelType.GAS]?.let { gasRange ->
        return VehicleStatus.FuelRange(range = gasRange, percentage = percentage)
    }

    val dte = statusData["dte"] as? JsonObject ?: return null
    val gasRange = dte["value"].asDoubleOrNull() ?: return null
    val units = Distance.Units.fromInt(dte["unit"].asIntOrNull() ?: 2)
    return VehicleStatus.FuelRange(range = Distance(gasRange, units), percentage = percentage)
}

private fun parseLocation(statusData: JsonObject): VehicleStatus.Location {
    val coord = ((statusData["vehicleLocation"] as? JsonObject)?.get("coord")) as? JsonObject
    return VehicleStatus.Location(
        latitude = coord?.get("lat").asDoubleOrNull() ?: 0.0,
        longitude = coord?.get("lon").asDoubleOrNull() ?: 0.0,
    )
}

private fun parseDoorOpen(statusData: JsonObject): VehicleStatus.DoorStatus? {
    val doorData = statusData["doorOpen"] as? JsonObject ?: return null
    return VehicleStatus.DoorStatus(
        frontLeft = (doorData["frontLeft"].asIntOrNull() ?: 0) != 0,
        frontRight = (doorData["frontRight"].asIntOrNull() ?: 0) != 0,
        backLeft = (doorData["backLeft"].asIntOrNull() ?: 0) != 0,
        backRight = (doorData["backRight"].asIntOrNull() ?: 0) != 0,
    )
}

private fun parseTirePressure(statusData: JsonObject): VehicleStatus.TirePressureWarning? {
    val tireData = statusData["tirePressureLamp"] as? JsonObject ?: return null
    return VehicleStatus.TirePressureWarning(
        frontLeft = (tireData["tirePressureWarningLampFrontLeft"].asIntOrNull() ?: 0) != 0,
        frontRight = (tireData["tirePressureWarningLampFrontRight"].asIntOrNull() ?: 0) != 0,
        rearLeft = (tireData["tirePressureWarningLampRearLeft"].asIntOrNull() ?: 0) != 0,
        rearRight = (tireData["tirePressureWarningLampRearRight"].asIntOrNull() ?: 0) != 0,
        all = (tireData["tirePressureWarningLampAll"].asIntOrNull() ?: 0) != 0,
    )
}
