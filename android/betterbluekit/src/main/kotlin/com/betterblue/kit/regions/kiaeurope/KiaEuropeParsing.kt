package com.betterblue.kit.regions.kiaeurope

import com.betterblue.kit.ApiClientBase
import com.betterblue.kit.ApiErrorType
import com.betterblue.kit.ApiException
import com.betterblue.kit.json.asDoubleOrNull
import com.betterblue.kit.json.asIntOrNull
import com.betterblue.kit.json.asStringOrNull
import com.betterblue.kit.model.AuthToken
import com.betterblue.kit.model.Distance
import com.betterblue.kit.model.FuelType
import com.betterblue.kit.model.Temperature
import com.betterblue.kit.model.Vehicle
import com.betterblue.kit.model.VehicleMarketOptions
import com.betterblue.kit.model.VehicleStatus
import com.betterblue.kit.regions.ccsp.CcspApiProfile
import com.betterblue.kit.regions.ccsp.CcspDates
import com.betterblue.kit.regions.ccsp.CcspKeyPathMap
import com.betterblue.kit.regions.ccsp.CcspResponseKey
import com.betterblue.kit.regions.ccsp.ccspAny
import com.betterblue.kit.regions.ccsp.ccspBool
import com.betterblue.kit.regions.ccsp.ccspChild
import com.betterblue.kit.regions.ccsp.ccspDouble
import com.betterblue.kit.regions.ccsp.ccspNumber
import com.betterblue.kit.regions.ccsp.ccspString
import com.betterblue.kit.regions.ccsp.ccspStrictInt
import com.betterblue.kit.util.BluelinkDates
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneOffset

// Response parsing for the Kia Europe client, mirroring the Swift `+Parsing`
// extension file. Reuses the shared CCSP key-path tables because CCS2 /
// legacy response shapes are identical between Hyundai EU and Kia EU.

internal fun KiaEuropeClient.parseAuthToken(data: ByteArray, isRefresh: Boolean): AuthToken {
    val json = ApiClientBase.parseJsonObject(data)
    val accessToken = json["access_token"].asStringOrNull()
    val expiresIn = json["expires_in"].asIntOrNull()
    if (accessToken == null || expiresIn == null) {
        throw ApiException(
            "Failed to parse AuthToken response",
            apiName = apiName,
            errorType = ApiErrorType.INVALID_CREDENTIALS,
        )
    }

    val refreshToken = if (isRefresh) {
        json["refresh_token"].asStringOrNull() ?: config.refreshToken ?: ""
    } else {
        config.refreshToken ?: ""
    }

    return AuthToken(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAt = Instant.now().plusSeconds(expiresIn.toLong()),
    )
}

internal fun KiaEuropeClient.parseVehiclesResponse(data: ByteArray): List<Vehicle> {
    val json = ApiClientBase.parseJsonObject(data)
    val vehicleArray = (json["resMsg"] as? JsonObject)?.get("vehicles") as? JsonArray
        ?: throw ApiException.logError("Invalid vehicles response", apiName = apiName)

    return vehicleArray.mapNotNull { element ->
        val vehicleData = element as? JsonObject ?: return@mapNotNull null
        val vehicleId = vehicleData["vehicleId"].asStringOrNull() ?: return@mapNotNull null
        val vin = vehicleData["vin"].asStringOrNull() ?: return@mapNotNull null
        val nickname = vehicleData["nickname"].asStringOrNull()
            ?: vehicleData["vehicleName"].asStringOrNull()
            ?: return@mapNotNull null

        val fuelType = when (ccspString(vehicleData, "type") ?: "") {
            "E", "EV" -> FuelType.ELECTRIC
            "P", "PE" -> FuelType.PHEV
            else -> FuelType.GAS
        }
        val ccs2 = ccspBool(vehicleData, "ccuCCS2ProtocolSupport")

        Vehicle(
            vin = vin,
            regId = vehicleId,
            model = nickname,
            accountId = accountId,
            fuelType = fuelType,
            generation = 2,
            odometer = Distance(0.0, Distance.Units.KILOMETERS),
            marketOptions = VehicleMarketOptions.KiaEurope(ccs2Supported = ccs2),
        )
    }
}

internal fun KiaEuropeClient.parseVehicleStatusResponse(
    data: ByteArray,
    locationData: ByteArray?,
    vehicle: Vehicle,
): VehicleStatus {
    val statusJson = ApiClientBase.parseJsonObject(data)
    val resMsg = statusJson["resMsg"] as? JsonObject
        ?: throw ApiException.logError("Invalid status response", apiName = apiName)

    val parkData: JsonObject = locationData
        ?.let { ApiClientBase.parseJsonObject(it)["resMsg"] as? JsonObject }
        ?: JsonObject(emptyMap())

    val ccs2 = vehicle.marketOptions.ccs2Supported
    val pathMap = CcspKeyPathMap(if (ccs2) CcspApiProfile.CCS2 else CcspApiProfile.LEGACY)
    val vehicleData = ccspChild(resMsg, pathMap[CcspResponseKey.VEHICLE_STATE])

    // CCS2 lastUpdateTime is numeric epoch milliseconds; legacy delivers a
    // "yyyyMMddHHmmss" local-time string. Treating the latter as epoch millis
    // produced year-2612 sync dates.
    val syncDate: Instant =
        ccspString(resMsg, pathMap[CcspResponseKey.SYNC_DATE])
            ?.let { CcspDates.parse(it, BluelinkDates.BERLIN) }
            ?: Instant.ofEpochMilli(ccspDouble(resMsg, pathMap[CcspResponseKey.SYNC_DATE]).toLong())

    val odometer = Distance(
        length = ccspDouble(vehicleData, pathMap[CcspResponseKey.ODO]),
        units = Distance.Units.fromInt(1),
    )

    return VehicleStatus(
        vin = vehicle.vin,
        gasRange = null,
        evStatus = if (vehicle.fuelType.hasElectricCapability) parseEvStatus(vehicleData, pathMap) else null,
        location = parseLocation(vehicleData, parkData, pathMap),
        lockStatus = parseLockStatus(vehicleData, pathMap),
        climateStatus = parseClimateStatus(vehicleData, pathMap),
        odometer = odometer,
        syncDate = syncDate,
        battery12V = ccspDouble(vehicleData, pathMap[CcspResponseKey.BATTERY_12V]).toInt(),
        doorOpen = parseDoorOpen(vehicleData, pathMap),
        trunkOpen = ccspBool(vehicleData, pathMap[CcspResponseKey.TRUNK]),
        hoodOpen = ccspBool(vehicleData, pathMap[CcspResponseKey.HOOD]),
        tirePressureWarning = parseTirePressure(vehicleData, pathMap),
    )
}

private fun parseEvStatus(vehicleState: JsonObject, pathMap: CcspKeyPathMap): VehicleStatus.EvStatus {
    val batterySoc = ccspDouble(vehicleState, pathMap[CcspResponseKey.SOC])
    val remainChargeTime = ccspDouble(vehicleState, pathMap[CcspResponseKey.CHARGE_TIME])
    val plugType = ccspNumber(vehicleState, pathMap[CcspResponseKey.PLUGGED_IN]) ?: 0
    val estimatedRange = ccspDouble(vehicleState, pathMap[CcspResponseKey.RANGE_TOTAL])
    val driveUnit = ccspDouble(vehicleState, pathMap[CcspResponseKey.RANGE_UNIT]).toInt()

    var targetAc: Double? = null
    var targetDc: Double? = null
    val isCharging: Boolean
    if (pathMap.profile == CcspApiProfile.CCS2) {
        targetAc = ccspDouble(vehicleState, pathMap[CcspResponseKey.TARGET_AC])
        targetDc = ccspDouble(vehicleState, pathMap[CcspResponseKey.TARGET_DC])
        isCharging = remainChargeTime > 0
    } else {
        // targetSocList resolves to an ARRAY leaf — read it whole and walk
        // the entries (a dict-only child lookup silently dropped it before).
        val socList = ccspAny(vehicleState, pathMap[CcspResponseKey.TARGET_SOC_LIST]) as? JsonArray
        socList?.forEach { element ->
            val target = element as? JsonObject ?: return@forEach
            val targetPlugType = target["plugType"].asIntOrNull() ?: return@forEach
            val soc = target["targetSOClevel"].asDoubleOrNull() ?: return@forEach
            when (targetPlugType) {
                1 -> targetAc = soc
                0 -> targetDc = soc
            }
        }
        isCharging = ccspBool(vehicleState, pathMap[CcspResponseKey.IS_CHARGING])
    }

    // The EU response keys split charge power into Std + Fst — take whichever
    // has a value (matches the Hyundai EU parser).
    val chargePower = maxOf(
        ccspDouble(vehicleState, pathMap[CcspResponseKey.CHARGE_POWER_STD]),
        ccspDouble(vehicleState, pathMap[CcspResponseKey.CHARGE_POWER_FST]),
    )

    return VehicleStatus.EvStatus(
        charging = isCharging,
        chargeSpeed = chargePower,
        evRange = VehicleStatus.FuelRange(
            range = Distance(estimatedRange, Distance.Units.fromInt(driveUnit)),
            percentage = batterySoc,
        ),
        plugType = VehicleStatus.PlugType.fromBatteryPlugin(plugType),
        chargeTimeSeconds = (60 * remainChargeTime).toLong(),
        targetSocAC = targetAc,
        targetSocDC = targetDc,
    )
}

private fun parseDoorOpen(vehicleState: JsonObject, pathMap: CcspKeyPathMap): VehicleStatus.DoorStatus? {
    ccspStrictInt(vehicleState, pathMap[CcspResponseKey.DOOR_FRONT_LEFT]) ?: return null
    return VehicleStatus.DoorStatus(
        frontLeft = ccspBool(vehicleState, pathMap[CcspResponseKey.DOOR_FRONT_LEFT]),
        frontRight = ccspBool(vehicleState, pathMap[CcspResponseKey.DOOR_FRONT_RIGHT]),
        backLeft = ccspBool(vehicleState, pathMap[CcspResponseKey.DOOR_REAR_LEFT]),
        backRight = ccspBool(vehicleState, pathMap[CcspResponseKey.DOOR_REAR_RIGHT]),
    )
}

private fun parseLockStatus(vehicleState: JsonObject, pathMap: CcspKeyPathMap): VehicleStatus.LockStatus {
    if (pathMap.profile == CcspApiProfile.LEGACY) {
        return VehicleStatus.LockStatus.fromLocked(
            ccspBool(vehicleState, pathMap[CcspResponseKey.LOCK_STATUS]),
        )
    }
    // CCS2 lock bits are INVERTED (0 = locked) — hence inverted = true.
    val driver = ccspBool(vehicleState, pathMap[CcspResponseKey.LOCK_1L], inverted = true)
    val passenger = ccspBool(vehicleState, pathMap[CcspResponseKey.LOCK_1R], inverted = true)
    val backLeft = ccspBool(vehicleState, pathMap[CcspResponseKey.LOCK_2L], inverted = true)
    val backRight = ccspBool(vehicleState, pathMap[CcspResponseKey.LOCK_2R], inverted = true)
    return VehicleStatus.LockStatus.fromLocked(driver && passenger && backLeft && backRight)
}

private fun parseClimateStatus(vehicleState: JsonObject, pathMap: CcspKeyPathMap): VehicleStatus.ClimateStatus {
    val temperature = Temperature.fromApi(
        units = ccspStrictInt(vehicleState, pathMap[CcspResponseKey.TEMP_UNIT]) ?: 0,
        value = ccspString(vehicleState, pathMap[CcspResponseKey.AIR_TEMP]),
    )
    return VehicleStatus.ClimateStatus(
        defrostOn = ccspBool(vehicleState, pathMap[CcspResponseKey.DEFROST_ON]),
        airControlOn = (ccspStrictInt(vehicleState, pathMap[CcspResponseKey.AIRCON_SPEED]) ?: 0) > 0,
        steeringWheelHeatingOn = ccspBool(vehicleState, pathMap[CcspResponseKey.STEERING_WHEEL_HEAT_ON]),
        temperature = temperature,
    )
}

private fun parseLocation(
    vehicleState: JsonObject,
    park: JsonObject,
    pathMap: CcspKeyPathMap,
): VehicleStatus.Location {
    // Both timestamp shapes the backend uses ("yyyyMMddHHmmss" with and
    // without ".SSS") parse via CcspDates. Legacy times are local (vehicle)
    // wall time — read as Europe/Berlin; CCS2 are UTC.
    val zone = if (pathMap.profile == CcspApiProfile.LEGACY) BluelinkDates.BERLIN else ZoneOffset.UTC
    val locationDate = CcspDates.parse(
        ccspString(vehicleState, pathMap[CcspResponseKey.LOCATION_DATE]),
        zone,
    )
    val parkDate = CcspDates.parse(ccspString(park, pathMap[CcspResponseKey.PARK_DATE]), zone)

    val parkLocation = VehicleStatus.Location(
        latitude = ccspDouble(park, pathMap[CcspResponseKey.PARK_LAT]),
        longitude = ccspDouble(park, pathMap[CcspResponseKey.PARK_LON]),
    )
    val statusLocation = VehicleStatus.Location(
        latitude = ccspDouble(vehicleState, pathMap[CcspResponseKey.LOCATION_LAT]),
        longitude = ccspDouble(vehicleState, pathMap[CcspResponseKey.LOCATION_LON]),
    )

    // Prefer the park endpoint when it has coordinates (hasCoordinates treats
    // the (0,0) "no fix" sentinel as absent) and is at least as recent as the
    // (often stale) status-embedded location; fall back to whichever source
    // actually has coordinates.
    if (parkDate != null && locationDate != null && !locationDate.isAfter(parkDate) &&
        parkLocation.hasCoordinates
    ) {
        return parkLocation
    }
    if (statusLocation.hasCoordinates) return statusLocation
    return if (parkLocation.hasCoordinates) parkLocation else statusLocation
}

private fun parseTirePressure(
    vehicleState: JsonObject,
    pathMap: CcspKeyPathMap,
): VehicleStatus.TirePressureWarning? {
    val all = ccspAny(vehicleState, pathMap[CcspResponseKey.TPMS_STATUS]) ?: return null
    return VehicleStatus.TirePressureWarning(
        frontLeft = ccspBool(vehicleState, pathMap[CcspResponseKey.TPMS_FRONT_LEFT]),
        frontRight = ccspBool(vehicleState, pathMap[CcspResponseKey.TPMS_FRONT_RIGHT]),
        rearLeft = ccspBool(vehicleState, pathMap[CcspResponseKey.TPMS_REAR_LEFT]),
        rearRight = ccspBool(vehicleState, pathMap[CcspResponseKey.TPMS_REAR_RIGHT]),
        all = (all.asIntOrNull() ?: 0) != 0,
    )
}
