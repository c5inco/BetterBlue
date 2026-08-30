package com.betterblue.kit.regions.hyundaieurope

import com.betterblue.kit.ApiClientBase
import com.betterblue.kit.ApiErrorType
import com.betterblue.kit.ApiException
import com.betterblue.kit.json.asDoubleOrNull
import com.betterblue.kit.json.asIntOrNull
import com.betterblue.kit.json.asStringOrNull
import com.betterblue.kit.log.BBLogCategory
import com.betterblue.kit.log.BBLogger
import com.betterblue.kit.model.AuthToken
import com.betterblue.kit.model.Distance
import com.betterblue.kit.model.EVTripInfo
import com.betterblue.kit.model.EVTripSummary
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
import com.betterblue.kit.regions.ccsp.ccspStrictInt
import com.betterblue.kit.regions.ccsp.ccspString
import com.betterblue.kit.util.BluelinkDates
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.seconds

// Response parsing for the Hyundai Europe client, mirroring the Swift
// `+Parsing` extension file. Both CCS2 and legacy status shapes resolve
// through the shared CcspKeyPathMap tables.

internal fun HyundaiEuropeClient.parseVehiclesResponse(data: ByteArray): List<Vehicle> {
    val json = ApiClientBase.parseJsonObject(data)
    val vehicleArray =
        (json["resMsg"] as? JsonObject)?.get("vehicles") as? JsonArray
            ?: throw ApiException.logError("Invalid vehicles response", apiName = apiName)

    return vehicleArray.mapNotNull { element ->
        val vehicleData = element as? JsonObject ?: return@mapNotNull null
        val vehicleId = vehicleData["vehicleId"].asStringOrNull() ?: return@mapNotNull null
        val vin = vehicleData["vin"].asStringOrNull() ?: return@mapNotNull null
        val nickname =
            vehicleData["nickname"].asStringOrNull()
                ?: vehicleData["vehicleName"].asStringOrNull()
                ?: return@mapNotNull null

        val fuelType =
            when (ccspString(vehicleData, "type") ?: "") {
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
            generation = 2, // always 2 — there is no such attribute in the EU response
            odometer = Distance(0.0, Distance.Units.KILOMETERS),
            marketOptions = VehicleMarketOptions.HyundaiEurope(ccs2Supported = ccs2),
        )
    }
}

internal fun HyundaiEuropeClient.parseVehicleStatusResponse(
    data: ByteArray,
    locationData: ByteArray?,
    vehicle: Vehicle,
): VehicleStatus {
    val statusJson = ApiClientBase.parseJsonObject(data)
    val resMsg =
        statusJson["resMsg"] as? JsonObject
            ?: throw ApiException.logError("Invalid status response", apiName = apiName)

    // Park data is optional → location from vehicle status is used on error.
    val parkData: JsonObject =
        if (locationData != null) {
            ApiClientBase.parseJsonObject(locationData)["resMsg"] as? JsonObject ?: JsonObject(emptyMap())
        } else {
            BBLogger.warning(BBLogCategory.API, "Failed to parse park data using location from vehicle status")
            JsonObject(emptyMap())
        }

    val ccs2 = vehicle.marketOptions.ccs2Supported
    val pathMap = CcspKeyPathMap(if (ccs2) CcspApiProfile.CCS2 else CcspApiProfile.LEGACY)

    val vehicleData = ccspChild(resMsg, pathMap[CcspResponseKey.VEHICLE_STATE])

    // The sync timestamp string arrives without a zone; legacy backends
    // report Europe/Berlin wall time (CCS2 sends epoch millis, which the
    // parser's epoch fallback handles zone-free).
    val syncDate =
        CcspDates.parse(
            ccspString(resMsg, pathMap[CcspResponseKey.SYNC_DATE]),
            BluelinkDates.BERLIN,
        )

    val odometer =
        Distance(
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
        engineOn = ccspBool(vehicleData, pathMap[CcspResponseKey.ENGINE_ON]),
    )
}

internal fun HyundaiEuropeClient.parseAuthToken(data: ByteArray, isRefresh: Boolean): AuthToken {
    val json = ApiClientBase.parseJsonObject(data)
    val accessToken = json["access_token"].asStringOrNull()
    val expiresIn = json["expires_in"].asIntOrNull()
    val refreshToken = if (isRefresh) json["refresh_token"].asStringOrNull() else config.refreshToken

    if (accessToken == null || expiresIn == null || refreshToken == null) {
        throw ApiException(
            "Failed to parse AuthToken info",
            apiName = apiName,
            errorType = ApiErrorType.INVALID_CREDENTIALS,
        )
    }

    return AuthToken(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAt = Instant.now().plusSeconds(expiresIn.toLong()),
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

    val chargePower =
        maxOf(
            ccspDouble(vehicleState, pathMap[CcspResponseKey.CHARGE_POWER_STD]),
            ccspDouble(vehicleState, pathMap[CcspResponseKey.CHARGE_POWER_FST]),
        )

    return VehicleStatus.EvStatus(
        charging = isCharging,
        chargeSpeed = chargePower,
        evRange =
            VehicleStatus.FuelRange(
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
    val temperature =
        Temperature.fromApi(
            units = ccspStrictInt(vehicleState, pathMap[CcspResponseKey.TEMP_UNIT]) ?: 0,
            value = ccspString(vehicleState, pathMap[CcspResponseKey.AIR_TEMP]) ?: "",
        )

    val airControlOn =
        if (pathMap.profile == CcspApiProfile.LEGACY) {
            ccspBool(vehicleState, pathMap[CcspResponseKey.AIR_CONTROL_ON])
        } else {
            (ccspStrictInt(vehicleState, pathMap[CcspResponseKey.AIRCON_SPEED]) ?: 0) > 0
        }

    return VehicleStatus.ClimateStatus(
        defrostOn = ccspBool(vehicleState, pathMap[CcspResponseKey.DEFROST_ON]),
        airControlOn = airControlOn,
        steeringWheelHeatingOn = ccspBool(vehicleState, pathMap[CcspResponseKey.STEERING_WHEEL_HEAT_ON]),
        temperature = temperature,
    )
}

private fun parseLocation(
    vehicleState: JsonObject,
    park: JsonObject,
    pathMap: CcspKeyPathMap,
): VehicleStatus.Location {
    val locationDateString =
        ccspString(vehicleState, pathMap[CcspResponseKey.LOCATION_DATE]) ?: "20000101010000.000"
    val parkDateString = ccspString(park, pathMap[CcspResponseKey.PARK_DATE]) ?: "20000101020000"

    // Workaround: the CCS2 location embedded in the status is currently
    // stale — normally the status coords would win without a time check.

    // Park date is always in the device's exact zone.
    val parkDate = CcspDates.parse(parkDateString, ZoneId.systemDefault())

    // Status location is currently UTC for CCS2 cars ("Offset" hints at the
    // car's zone); legacy reports Europe/Berlin wall time.
    val locationZone =
        if (pathMap.profile == CcspApiProfile.LEGACY) BluelinkDates.BERLIN else ZoneOffset.UTC
    val locationDate = CcspDates.parse(locationDateString, locationZone)

    // Two candidate sources: the /location/park endpoint and the location
    // embedded in the status response. Either can be empty — park returns no
    // coordinates without a recent park event, and the status-embedded
    // location is often stale/missing. (0, 0) = no fix.
    val parkLocation =
        VehicleStatus.Location(
            latitude = ccspDouble(park, pathMap[CcspResponseKey.PARK_LAT]),
            longitude = ccspDouble(park, pathMap[CcspResponseKey.PARK_LON]),
        )
    val statusLocation =
        VehicleStatus.Location(
            latitude = ccspDouble(vehicleState, pathMap[CcspResponseKey.LOCATION_LAT]),
            longitude = ccspDouble(vehicleState, pathMap[CcspResponseKey.LOCATION_LON]),
        )

    // Prefer the park endpoint when it has coordinates and is at least as
    // recent as the (often stale) status location; fall back to whichever
    // source actually carries coordinates.
    val parkIsNewer =
        if (parkDate != null && locationDate != null) {
            !locationDate.isAfter(parkDate)
        } else {
            parkLocation.hasCoordinates
        }
    if (parkIsNewer && parkLocation.hasCoordinates) return parkLocation
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

// EV trip history parsing

internal fun HyundaiEuropeClient.parseEvTripSummaryResponse(
    data: ByteArray,
    @Suppress("UNUSED_PARAMETER") vehicle: Vehicle,
): List<EVTripSummary> {
    val json = ApiClientBase.parseJsonObject(data)
    val drivingInfoDetail =
        (json["resMsg"] as? JsonObject)?.get("drivingInfoDetail") as? JsonArray
            ?: throw ApiException("Failed to parse EU trip details", apiName = apiName)

    return drivingInfoDetail.mapNotNull { element ->
        val tripData = element as? JsonObject ?: return@mapNotNull null

        // `drivingDate` is a floating calendar day (yyyyMMdd) with no time or
        // zone. Anchoring it at 12:00 UTC keeps it on the intended day in
        // every timezone the app renders it in — pinning it to midnight in a
        // fixed zone put it on the previous day for anyone west of that zone
        // (BetterBlueKit#46). A trip with a malformed date is skipped rather
        // than fabricated.
        val dateString = tripData["drivingDate"].asStringOrNull() ?: return@mapNotNull null
        if (dateString.length != 8) return@mapNotNull null
        val startDate = BluelinkDates.parseDayNoonUtc(dateString) ?: return@mapNotNull null

        val totalPwrCsp = tripData["totalPwrCsp"].asIntOrNull() ?: 0
        val motorPwrCsp = tripData["motorPwrCsp"].asIntOrNull() ?: 0
        val climatePwrCsp = tripData["climatePwrCsp"].asIntOrNull() ?: 0
        val eDPwrCsp = tripData["eDPwrCsp"].asIntOrNull() ?: 0
        val batteryMgPwrCsp = tripData["batteryMgPwrCsp"].asIntOrNull() ?: 0
        val regenPwr = tripData["regenPwr"].asIntOrNull() ?: 0

        // calculativeOdo arrives as a plain number on current backends, but
        // sibling CCS payloads wrap distances as {value, unit} dicts — handle
        // both, defaulting to kilometers (the EU fleet's native unit).
        val odoValue: Double
        val odoUnits: Distance.Units
        val odoDict = tripData["calculativeOdo"] as? JsonObject
        if (odoDict != null) {
            odoValue = ccspDouble(odoDict, "value")
            odoUnits = Distance.Units.fromInt(odoDict["unit"].asIntOrNull() ?: 1)
        } else {
            odoValue = ccspDouble(tripData, "calculativeOdo")
            odoUnits = Distance.Units.fromInt(1)
        }

        EVTripSummary(
            distance = Distance(odoValue, odoUnits),
            odometer = Distance(0.0, odoUnits), // Not provided by EU /drvhistory
            accessoriesEnergy = eDPwrCsp,
            totalEnergyUsed = totalPwrCsp,
            regenEnergy = regenPwr,
            climateEnergy = climatePwrCsp,
            drivetrainEnergy = motorPwrCsp,
            batteryCareEnergy = batteryMgPwrCsp,
            startDate = startDate,
            duration = 0.seconds, // Not provided by EU /drvhistory day summaries
            avgSpeed = 0.0, // Not provided — fetchEvTripInfo(date) has per-trip speeds
            maxSpeed = 0.0, // Not provided — fetchEvTripInfo(date) has per-trip speeds
        )
    }
}

internal fun HyundaiEuropeClient.parseIndividualTripsResponse(data: ByteArray): List<EVTripInfo> {
    val json = ApiClientBase.parseJsonObject(data)
    val dayTripList =
        (json["resMsg"] as? JsonObject)?.get("dayTripList") as? JsonArray
            ?: throw ApiException("Failed to parse EU individual trips", apiName = apiName)

    // No explicit zone: `tripDay`+`tripTime` is a wall-clock reading of when
    // the driver was in the car, and the UI renders it in the device's zone —
    // so interpreting it locally shows the clock time the backend reported.
    // Pinning it to a fixed European zone shifted every trip time by the
    // offset between that zone and the user's (BetterBlueKit#46 fixed the
    // same mistake on the day-level dates).
    val zone = ZoneId.systemDefault()

    val allTrips = mutableListOf<EVTripInfo>()
    for (dayElement in dayTripList) {
        val dayTrip = dayElement as? JsonObject ?: continue
        val dateStr = dayTrip["tripDay"].asStringOrNull() ?: continue
        val tripList = dayTrip["tripList"] as? JsonArray ?: continue

        for (tripElement in tripList) {
            val tripData = tripElement as? JsonObject ?: continue
            // A trip without a parseable start time is dropped rather than
            // stamped with a fabricated date.
            val hhmmss = tripData["tripTime"].asStringOrNull() ?: continue
            val tripDate = BluelinkDates.parseBasic14(dateStr + hhmmss, zone) ?: continue

            val driveTime = tripData["tripDrvTime"].asIntOrNull() ?: 0
            val idleTime = tripData["tripIdleTime"].asIntOrNull() ?: 0

            allTrips.add(
                EVTripInfo(
                    date = tripDate,
                    driveTime = (driveTime * 60).seconds,
                    idleTime = (idleTime * 60).seconds,
                    // Hyundai EU defaults to kilometers.
                    distance = Distance(ccspDouble(tripData, "tripDist"), Distance.Units.KILOMETERS),
                    avgSpeed = ccspDouble(tripData, "tripAvgSpeed"),
                    maxSpeed = ccspDouble(tripData, "tripMaxSpeed"),
                ),
            )
        }
    }
    return allTrips
}
