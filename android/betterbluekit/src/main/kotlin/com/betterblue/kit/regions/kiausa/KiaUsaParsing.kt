package com.betterblue.kit.regions.kiausa

import com.betterblue.kit.ApiClientBase
import com.betterblue.kit.ApiException
import com.betterblue.kit.json.asArrayOrNull
import com.betterblue.kit.json.asBooleanOrNull
import com.betterblue.kit.json.asDoubleOrNull
import com.betterblue.kit.json.asIntOrNull
import com.betterblue.kit.json.asObjectOrNull
import com.betterblue.kit.json.asStringOrNull
import com.betterblue.kit.json.isJsonBoolean
import com.betterblue.kit.log.BBLogCategory
import com.betterblue.kit.log.BBLogger
import com.betterblue.kit.model.AuthToken
import com.betterblue.kit.model.Distance
import com.betterblue.kit.model.FuelType
import com.betterblue.kit.model.Temperature
import com.betterblue.kit.model.Vehicle
import com.betterblue.kit.model.VehicleStatus
import com.betterblue.kit.util.BluelinkDates
import kotlinx.serialization.json.JsonObject
import okhttp3.Headers
import java.time.Instant

internal fun KiaUsaClient.parseLoginResponse(data: ByteArray, headers: Headers): AuthToken {
    checkForKiaErrors(data)

    // Unlike the other parsers, a garbled login body is an error rather than
    // an empty object — the Swift client threw here too.
    val json =
        try {
            ApiClientBase.json.parseToJsonElement(data.toString(Charsets.UTF_8)) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: run {
            BBLogger.error(BBLogCategory.AUTH, "KiaUSA: Failed to parse login response as JSON")
            throw ApiException.logError("Failed to parse login response", apiName = apiName)
        }

    // Check for MFA requirement: an authUser body carrying payload.otpKey
    // means the server wants an OTP round-trip before it will mint a session.
    val payload = json["payload"].asObjectOrNull()
    val otpKey = payload?.get("otpKey").asStringOrNull()
    if (payload != null && otpKey != null) {
        // OkHttp headers are case-insensitive, so one lookup covers the
        // xid/Xid/XID variants the Swift client probed by hand.
        val xid = headers["xid"] ?: ""
        val hasEmail = payload["hasEmail"].asBooleanOrNull() ?: false
        val hasPhone = payload["hasPhone"].asBooleanOrNull() ?: false
        val email = payload["email"].asStringOrNull()
        val phone = payload["phone"].asStringOrNull()
        val rmTokenExpired = payload["rmTokenExpired"].asBooleanOrNull() ?: false

        var mfaLog = "MFA required - otpKey: $otpKey, xid: $xid"
        mfaLog += " | Contact options - hasEmail: $hasEmail, hasPhone: $hasPhone"
        if (email != null) mfaLog += " | Email: $email"
        if (phone != null) mfaLog += " | Phone: $phone"
        if (rmTokenExpired) mfaLog += " | rmTokenExpired: true"
        BBLogger.info(BBLogCategory.MFA, mfaLog)

        throw ApiException.requiresMfa(
            xid = xid,
            otpKey = otpKey,
            hasEmail = hasEmail,
            hasPhone = hasPhone,
            email = email,
            phone = phone,
            rmTokenExpired = rmTokenExpired,
            apiName = apiName,
        )
    }

    // The session id comes back in the `sid` RESPONSE HEADER — it doubles as
    // the access token for every authorized request.
    val sessionId =
        headers["sid"]
            ?: throw ApiException.logError("Login response missing session ID header", apiName = apiName)

    // Match the Python `hyundai_kia_connect_api` reference, which uses
    // 23 hours. The previous 1-hour value forced ~23x more `authUser`
    // calls than the reference for the same user activity, which
    // appears to be why the rmToken-based re-login eventually trips
    // Kia's anti-fraud heuristics and demands MFA again.
    val validUntil = Instant.now().plusSeconds(KiaUsaClient.LOGIN_TOKEN_LIFETIME_SECONDS)
    BBLogger.info(BBLogCategory.AUTH, "KiaUSA: Authentication completed successfully for user $username")

    // Capture any rotated `rmToken` the server included in the
    // response and hand it back to the caller. Kia hasn't been
    // observed to rotate this on every successful `authUser`, but
    // the cost of capturing is tiny and it's the kind of bug that
    // would otherwise look indistinguishable from a flaky session.
    val rotatedRmToken = headers["rmToken"]
    if (rotatedRmToken != null) {
        BBLogger.info(BBLogCategory.AUTH, "KiaUSA: authUser response carried an updated rmToken — rotating cache")
        config.onRememberMeTokenRotated?.invoke(rotatedRmToken)
    }

    return AuthToken(
        accessToken = sessionId,
        refreshToken = sessionId,
        expiresAt = validUntil,
    )
}

internal fun KiaUsaClient.parseVehiclesResponse(data: ByteArray): List<Vehicle> {
    checkForKiaErrors(data)

    val json = ApiClientBase.parseJsonObject(data)
    val vehicleSummary =
        json["payload"].asObjectOrNull()?.get("vehicleSummary").asArrayOrNull()
            ?: throw ApiException.logError("Invalid vehicles response", apiName = apiName)

    return vehicleSummary.mapNotNull { element ->
        val entry = element.asObjectOrNull() ?: return@mapNotNull null
        val vin = entry["vin"].asStringOrNull() ?: return@mapNotNull null
        val regId = entry["vehicleIdentifier"].asStringOrNull() ?: return@mapNotNull null
        val nickname = entry["nickName"].asStringOrNull() ?: return@mapNotNull null
        val vehicleKey = entry["vehicleKey"].asStringOrNull() ?: return@mapNotNull null
        val generation = entry["genType"].asStringOrNull() ?: return@mapNotNull null
        val fuelType = entry["fuelType"].asIntOrNull() ?: return@mapNotNull null

        Vehicle(
            vin = vin,
            regId = regId,
            model = nickname,
            accountId = accountId,
            fuelType = kiaUsaFuelType(fuelType),
            generation = generation.toIntOrNull() ?: 1,
            odometer = Distance(entry["mileage"].asDoubleOrNull() ?: 0.0, Distance.Units.MILES),
            vehicleKey = vehicleKey,
        )
    }
}

/**
 * Maps Kia USA's `fuelType` integer to our [FuelType] enum.
 *
 * Kia USA uses a different code scheme than [FuelType.fromNumber]
 * (which is calibrated for Hyundai). Confirmed mappings, cross-checked
 * against the Python `hyundai_kia_connect_api` reference and live
 * vehicle data:
 *
 * - `4` → EV (confirmed by 2020 Niro EV and 2024 EV9)
 *
 * Other values (gas / hybrid / PHEV) are not yet confirmed against
 * a real Kia USA response, so we conservatively default to [FuelType.GAS]
 * — the same approach the Python lib takes. If we mis-classify a
 * PHEV here, downstream status fetches that return both `evStatus`
 * and `gasRange` will still surface the gas range correctly.
 */
internal fun kiaUsaFuelType(fuelType: Int): FuelType =
    when (fuelType) {
        4 -> FuelType.ELECTRIC
        else -> FuelType.GAS
    }

internal fun KiaUsaClient.parseVehicleStatusResponse(data: ByteArray, vehicle: Vehicle): VehicleStatus {
    checkForKiaErrors(data)
    val lastVehicleInfo = extractLastVehicleInfo(data)
    val vehicleStatus = extractVehicleStatus(lastVehicleInfo)
    val (trunkOpen, hoodOpen) = parseHoodTrunk(vehicleStatus)

    return VehicleStatus(
        vin = vehicle.vin,
        gasRange = parseGasRange(vehicleStatus),
        evStatus = parseEvStatus(vehicleStatus),
        location = parseLocation(lastVehicleInfo),
        lockStatus = VehicleStatus.LockStatus.fromLocked(vehicleStatus["doorLock"].asBooleanOrNull()),
        climateStatus = parseClimateStatus(vehicleStatus),
        odometer = vehicle.odometer,
        syncDate = parseSyncDate(vehicleStatus),
        battery12V = parseBattery12V(vehicleStatus),
        doorOpen = parseDoorOpen(vehicleStatus),
        trunkOpen = trunkOpen,
        hoodOpen = hoodOpen,
        tirePressureWarning = parseTirePressure(vehicleStatus),
    )
}

private fun KiaUsaClient.extractLastVehicleInfo(data: ByteArray): JsonObject {
    val json = ApiClientBase.parseJsonObject(data)
    return json["payload"]
        .asObjectOrNull()
        ?.get("vehicleInfoList")
        .asArrayOrNull()
        ?.firstOrNull()
        .asObjectOrNull()
        ?.get("lastVehicleInfo")
        .asObjectOrNull()
        ?: throw ApiException.logError("Invalid vehicle status response", apiName = apiName)
}

private fun KiaUsaClient.extractVehicleStatus(lastVehicleInfo: JsonObject): JsonObject =
    lastVehicleInfo["vehicleStatusRpt"]
        .asObjectOrNull()
        ?.get("vehicleStatus")
        .asObjectOrNull()
        ?: throw ApiException.logError("Invalid vehicle status response", apiName = apiName)

private fun parseEvStatus(vehicleStatus: JsonObject): VehicleStatus.EvStatus? {
    val evStatusData = vehicleStatus["evStatus"].asObjectOrNull() ?: JsonObject(emptyMap())
    val batteryStatus = evStatusData["batteryStatus"].asDoubleOrNull() ?: 0.0
    if (batteryStatus <= 0) return null

    val drvDistance = evStatusData["drvDistance"].asArrayOrNull()
    val rangeInfo =
        drvDistance
            ?.firstOrNull()
            .asObjectOrNull()
            ?.get("rangeByFuel")
            .asObjectOrNull()
    val evModeRange = rangeInfo?.get("evModeRange").asObjectOrNull()
    val chargeTimes = evStatusData["remainChargeTime"].asArrayOrNull()
    val chargeTime =
        chargeTimes
            ?.firstOrNull()
            .asObjectOrNull()
            ?.get("value")
            .asIntOrNull() ?: 0

    val evRange =
        Distance(
            length = evModeRange?.get("value").asDoubleOrNull() ?: 0.0,
            units = Distance.Units.fromInt(evModeRange?.get("unit").asIntOrNull() ?: 3),
        )

    val batteryPlugin = evStatusData["batteryPlugin"].asIntOrNull() ?: 0

    // Kia US encodes plugType 0 as DC fast charging and plugType 1
    // as AC (matches hyundai_kia_connect_api). Earlier code had it
    // inverted, which made the in-app AC/DC limits show — and set
    // — swapped vs. Kia Access (issue #41).
    var targetSocAC: Double? = null
    var targetSocDC: Double? = null
    evStatusData["targetSOC"].asArrayOrNull()?.forEach { element ->
        val target = element.asObjectOrNull() ?: return@forEach
        val plugType = target["plugType"].asIntOrNull() ?: return@forEach
        val soc = target["targetSOClevel"].asDoubleOrNull() ?: return@forEach
        when (plugType) {
            0 -> targetSocDC = soc
            1 -> targetSocAC = soc
        }
    }

    return VehicleStatus.EvStatus(
        charging = evStatusData["batteryCharge"].asBooleanOrNull() ?: false,
        chargeSpeed =
            maxOf(
                evStatusData["batteryStndChrgPower"].asDoubleOrNull() ?: 0.0,
                evStatusData["batteryFstChrgPower"].asDoubleOrNull() ?: 0.0,
            ),
        evRange = VehicleStatus.FuelRange(range = evRange, percentage = batteryStatus),
        plugType = VehicleStatus.PlugType.fromBatteryPlugin(batteryPlugin),
        chargeTimeSeconds = 60L * chargeTime,
        targetSocAC = targetSocAC,
        targetSocDC = targetSocDC,
    )
}

private fun parseGasRange(vehicleStatus: JsonObject): VehicleStatus.FuelRange? {
    // Kia uses `fuelLevel: false` (a JSON boolean) as the "no gas
    // tank" signal for pure EVs. A naive numeric coercion would turn
    // that `false` into `0.0`, which would make every Kia EV report a
    // phantom 0% gas range — and (now that `BBVehicle.updateStatus`
    // self-heals fuelType from the status payload's shape) every Kia EV
    // would get mis-classified as a PHEV. Reject booleans explicitly
    // before extracting ([asDoubleOrNull] already does, but keep the
    // check visible to match the Swift source).
    val fuelLevelRaw = vehicleStatus["fuelLevel"] ?: return null
    if (fuelLevelRaw.isJsonBoolean) return null
    val fuelLevel = fuelLevelRaw.asDoubleOrNull() ?: return null

    val distanceToEmptyData = vehicleStatus["distanceToEmpty"].asObjectOrNull() ?: return null
    val gasRangeValue = distanceToEmptyData["value"].asDoubleOrNull() ?: return null
    val gasRangeUnit = distanceToEmptyData["unit"].asIntOrNull() ?: return null

    val gasRangeDistance = Distance(length = gasRangeValue, units = Distance.Units.fromInt(gasRangeUnit))
    return VehicleStatus.FuelRange(range = gasRangeDistance, percentage = fuelLevel)
}

private fun parseLocation(lastVehicleInfo: JsonObject): VehicleStatus.Location {
    val coord = lastVehicleInfo["location"].asObjectOrNull()?.get("coord").asObjectOrNull()
    return VehicleStatus.Location(
        latitude = coord?.get("lat").asDoubleOrNull() ?: 0.0,
        longitude = coord?.get("lon").asDoubleOrNull() ?: 0.0,
    )
}

private fun parseClimateStatus(vehicleStatus: JsonObject): VehicleStatus.ClimateStatus {
    val climate = vehicleStatus["climate"].asObjectOrNull()
    val airTemp = climate?.get("airTemp").asObjectOrNull()
    val heatingAccessory = climate?.get("heatingAccessory").asObjectOrNull()

    return VehicleStatus.ClimateStatus(
        defrostOn = climate?.get("defrost").asBooleanOrNull() ?: false,
        airControlOn = climate?.get("airCtrl").asBooleanOrNull() ?: false,
        steeringWheelHeatingOn = (heatingAccessory?.get("steeringWheel").asIntOrNull() ?: 0) != 0,
        temperature =
            Temperature.fromApi(
                units = airTemp?.get("unit").asIntOrNull(),
                value = airTemp?.get("value").asStringOrNull(),
            ),
    )
}

private fun parseSyncDate(vehicleStatus: JsonObject): Instant? {
    val utcString = vehicleStatus["syncDate"].asObjectOrNull()?.get("utc").asStringOrNull() ?: return null
    // `yyyyMMddHHmmss`, always UTC.
    return BluelinkDates.parseBasic14Utc(utcString)
}

private fun parseHoodTrunk(vehicleStatus: JsonObject): Pair<Boolean?, Boolean?> {
    val doorStatus = vehicleStatus["doorStatus"].asObjectOrNull()
    if (doorStatus != null) {
        val trunk = doorStatus["trunk"].asIntOrNull()
        val hood = doorStatus["hood"].asIntOrNull()
        return Pair(trunk?.let { it != 0 }, hood?.let { it != 0 })
    }
    return Pair(
        vehicleStatus["trunkOpen"].asBooleanOrNull(),
        vehicleStatus["hoodOpen"].asBooleanOrNull(),
    )
}

private fun parseBattery12V(vehicleStatus: JsonObject): Int? {
    vehicleStatus["batteryStatus"]
        .asObjectOrNull()
        ?.get("stateOfCharge")
        .asIntOrNull()
        ?.let { return it }
    vehicleStatus["battery"]
        .asObjectOrNull()
        ?.get("batSoc")
        .asIntOrNull()
        ?.let { return it }
    return null
}

private fun parseDoorOpen(vehicleStatus: JsonObject): VehicleStatus.DoorStatus? {
    val doorData =
        vehicleStatus["doorStatus"].asObjectOrNull()
            ?: vehicleStatus["doorOpen"].asObjectOrNull()
            ?: return null

    return VehicleStatus.DoorStatus(
        frontLeft = (doorData["frontLeft"].asIntOrNull() ?: 0) != 0,
        frontRight = (doorData["frontRight"].asIntOrNull() ?: 0) != 0,
        backLeft = (doorData["backLeft"].asIntOrNull() ?: 0) != 0,
        backRight = (doorData["backRight"].asIntOrNull() ?: 0) != 0,
    )
}

private fun parseTirePressure(vehicleStatus: JsonObject): VehicleStatus.TirePressureWarning? {
    vehicleStatus["tirePressure"].asObjectOrNull()?.let { tireData ->
        return VehicleStatus.TirePressureWarning(
            frontLeft = (tireData["frontLeft"].asIntOrNull() ?: 0) != 0,
            frontRight = (tireData["frontRight"].asIntOrNull() ?: 0) != 0,
            rearLeft = (tireData["rearLeft"].asIntOrNull() ?: 0) != 0,
            rearRight = (tireData["rearRight"].asIntOrNull() ?: 0) != 0,
            all = (tireData["all"].asIntOrNull() ?: 0) != 0,
        )
    }
    vehicleStatus["tirePressureLamp"].asObjectOrNull()?.let { tireData ->
        return VehicleStatus.TirePressureWarning(
            frontLeft = (tireData["tirePressureWarningLampFrontLeft"].asIntOrNull() ?: 0) != 0,
            frontRight = (tireData["tirePressureWarningLampFrontRight"].asIntOrNull() ?: 0) != 0,
            rearLeft = (tireData["tirePressureWarningLampRearLeft"].asIntOrNull() ?: 0) != 0,
            rearRight = (tireData["tirePressureWarningLampRearRight"].asIntOrNull() ?: 0) != 0,
            all = (tireData["tirePressureWarningLampAll"].asIntOrNull() ?: 0) != 0,
        )
    }
    return null
}

// Error handling

/**
 * Translates the `status.errorCode` envelope Kia US wraps around most
 * responses into typed [ApiException]s. A no-op when the body has no error
 * envelope or `errorCode` is 0.
 */
internal fun KiaUsaClient.checkForKiaErrors(data: ByteArray) {
    val json = ApiClientBase.parseJsonObject(data)
    val status = json["status"].asObjectOrNull() ?: return
    val errorCode = status["errorCode"].asIntOrNull() ?: return
    if (errorCode == 0) return

    val errorMessage = status["errorMessage"].asStringOrNull() ?: "Unknown Kia API error"
    val statusCode = status["statusCode"].asIntOrNull() ?: -1
    val errorType = status["errorType"].asIntOrNull() ?: -1
    val messageLower = errorMessage.lowercase()

    if (statusCode == 1 && errorType == 1 && errorCode == 1 &&
        (
            messageLower.contains("valid email") || messageLower.contains("invalid") ||
                messageLower.contains("credential")
        )
    ) {
        throw ApiException.invalidCredentials("Invalid username or password", apiName = apiName)
    }

    if (errorCode == 1005 || errorCode == 1103) {
        throw ApiException.invalidVehicleSession(errorMessage, apiName = apiName)
    }

    if (errorCode == 1003 &&
        (
            messageLower.contains("session key") || messageLower.contains("invalid") ||
                messageLower.contains("expired")
        )
    ) {
        throw ApiException.invalidCredentials("Session Key is either invalid or expired", apiName = apiName)
    }

    if (errorCode == 9789) {
        throw ApiException.kiaInvalidRequest(
            "Kia API is currently unsupported. " +
                "See https://github.com/schmidtwmark/BetterBlueKit/issues/7 for updates",
            apiName = apiName,
        )
    }

    if (errorCode == 429) {
        throw ApiException.serverError("Rate limited", apiName = apiName)
    }

    if (errorCode == 503) {
        throw ApiException.serverError("Service unavailable", apiName = apiName)
    }
}
