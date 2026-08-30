package com.betterblue.kit.regions.hyundaicanada

// Hyundai Canada status parsing helpers. Mirrors HyundaiCanada+StatusParsing.swift.

import com.betterblue.kit.json.asDoubleOrNull
import com.betterblue.kit.json.asIntOrNull
import com.betterblue.kit.json.asStringOrNull
import com.betterblue.kit.json.isJsonBoolean
import com.betterblue.kit.model.Distance
import com.betterblue.kit.model.FuelType
import com.betterblue.kit.model.Temperature
import com.betterblue.kit.model.Vehicle
import com.betterblue.kit.model.VehicleStatus
import com.betterblue.kit.util.BluelinkDates
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Status parsing helpers

internal fun HyundaiCanadaClient.detectFuelType(vehicleData: JsonObject): FuelType {
    // `evStatus` is the older, more specific signal and must keep winning.
    val evStatus = vehicleData["evStatus"].asStringOrNull()
    if (evStatus != null) {
        val status = evStatus.uppercase()
        return when {
            status.startsWith("E") -> FuelType.ELECTRIC
            status.startsWith("P") -> FuelType.PHEV
            else -> FuelType.GAS
        }
    }

    // `vhcllst` entries carry the powertrain as `fuelKindCode` ("G" gas, "E"
    // electric, "P" plug-in hybrid) — the same codes Hyundai Europe uses.
    // Without this branch every Canadian ICE vehicle fell through to the
    // `ELECTRIC` default below, which hid its fuel range and gave it a phantom
    // charge port (BetterBlue#98).
    when (vehicleData["fuelKindCode"].asStringOrNull()?.uppercase()) {
        "E", "EV" -> return FuelType.ELECTRIC
        "P", "PE", "PHEV" -> return FuelType.PHEV
        "G", "GS", "D" -> return FuelType.GAS
    }

    vehicleData["fuelType"].asIntOrNull()?.let { return FuelType.fromNumber(it) }

    vehicleData["modelName"].asStringOrNull()?.lowercase()?.let { modelName ->
        if (modelName.contains("ev") || modelName.contains("electric")) return FuelType.ELECTRIC
    }

    return FuelType.ELECTRIC
}

/**
 * Canada reports distance units as a JSON boolean (`true` = kilometers),
 * unlike the integer codes elsewhere. Accepts either shape and defaults to
 * kilometers, which is what this region actually serves.
 */
internal fun canadaDistanceUnits(value: JsonElement?): Distance.Units {
    if (value.isJsonBoolean) {
        return if ((value as JsonPrimitive).content == "true") Distance.Units.KILOMETERS else Distance.Units.MILES
    }
    value.asIntOrNull()?.let { return Distance.Units.fromInt(it) }
    return Distance.Units.KILOMETERS
}

internal fun HyundaiCanadaClient.parseCanadaEvStatus(
    statusData: JsonObject,
    vehicle: Vehicle,
): VehicleStatus.EvStatus? {
    if (!vehicle.fuelType.hasElectricCapability) return null
    val evStatusData = statusData["evStatus"] as? JsonObject ?: return null

    val batteryStatus = evStatusData["batteryStatus"].asDoubleOrNull() ?: 0.0
    val chargeTimeMinutes = parseChargeTimeMinutes(evStatusData)
    val batteryPlugin = evStatusData["batteryPlugin"].asIntOrNull() ?: 0
    val charging = evStatusData["batteryCharge"].asJsonBooleanOrNull() ?: false
    val chargeSpeed = parseChargeSpeed(evStatusData)
    val range = parseCanadaEvRange(evStatusData) ?: Distance(0.0, Distance.Units.KILOMETERS)
    val (targetSocAC, targetSocDC) = parseTargetSocs(evStatusData)

    return VehicleStatus.EvStatus(
        charging = charging,
        chargeSpeed = chargeSpeed,
        evRange = VehicleStatus.FuelRange(range = range, percentage = batteryStatus),
        plugType = VehicleStatus.PlugType.fromBatteryPlugin(batteryPlugin),
        chargeTimeSeconds = 60L * chargeTimeMinutes,
        targetSocAC = targetSocAC,
        targetSocDC = targetSocDC,
    )
}

internal fun HyundaiCanadaClient.parseCanadaGasRange(
    statusData: JsonObject,
    vehicle: Vehicle,
): VehicleStatus.FuelRange? {
    if (vehicle.fuelType != FuelType.GAS) return null
    val fuelLevel = statusData["fuelLevel"].asDoubleOrNull() ?: return null

    // Canada names this `dte` ({"unit": true, "value": 314}); the
    // `distanceToEmpty` spelling is kept as a fallback in case some firmware
    // still uses it. Reading only the latter meant gas vehicles never showed a
    // range even though the value was right there in the payload (BetterBlue#98).
    val distanceToEmpty =
        (statusData["dte"] as? JsonObject)
            ?: (statusData["distanceToEmpty"] as? JsonObject)
    if (distanceToEmpty != null) {
        distanceToEmpty["value"].asDoubleOrNull()?.let { value ->
            return VehicleStatus.FuelRange(
                range = Distance(value, canadaDistanceUnits(distanceToEmpty["unit"])),
                percentage = fuelLevel,
            )
        }
    }

    val drvDistance = (statusData["evStatus"] as? JsonObject)?.get("drvDistance") as? JsonArray
    if (drvDistance != null) {
        for (entry in drvDistance) {
            val rangeByFuel = (entry as? JsonObject)?.get("rangeByFuel") as? JsonObject ?: continue
            val totalRange = rangeByFuel["totalAvailableRange"] as? JsonObject ?: continue
            val value = totalRange["value"].asDoubleOrNull() ?: continue
            val unit = totalRange["unit"].asIntOrNull() ?: 1
            return VehicleStatus.FuelRange(
                range = Distance(value, Distance.Units.fromInt(unit)),
                percentage = fuelLevel,
            )
        }
    }

    return null
}

internal fun parseCanadaLocation(statusData: JsonObject): VehicleStatus.Location {
    val vehicleLocation = statusData["vehicleLocation"] as? JsonObject
    val coord =
        (vehicleLocation?.get("coord") as? JsonObject)
            ?: (statusData["coord"] as? JsonObject)

    return VehicleStatus.Location(
        latitude = coord?.get("lat").asDoubleOrNull() ?: 0.0,
        longitude = coord?.get("lon").asDoubleOrNull() ?: 0.0,
    )
}

internal fun parseCanadaClimateStatus(statusData: JsonObject): VehicleStatus.ClimateStatus {
    val airTemp = statusData["airTemp"] as? JsonObject ?: JsonObject(emptyMap())

    return VehicleStatus.ClimateStatus(
        defrostOn = statusData["defrost"].asJsonBooleanOrNull() ?: false,
        airControlOn =
            statusData["airCtrlOn"].asJsonBooleanOrNull()
                ?: statusData["airCtrl"].asJsonBooleanOrNull()
                ?: false,
        steeringWheelHeatingOn = (statusData["steerWheelHeat"].asIntOrNull() ?: 0) != 0,
        temperature =
            parseCanadaAirTemp(
                airTempBlock = airTemp,
                airTempUnitTopLevel = statusData["airTempUnit"].asStringOrNull(),
            ),
    )
}

/**
 * Decodes Hyundai Canada's Gen3 `airTemp` block.
 *
 * Unlike the US API (which returns `"72"` / `"70"` style numeric strings), the
 * Canadian endpoint returns a hex-encoded code, e.g. `"00H"`, `"0EH"`, `"32H"`.
 * The hex byte indexes into a half-degree scale starting at 14°C (matches the
 * ladder used by the in-car HVAC UI and matches the `hyundai_kia_connect_api` /
 * bluelinky decoders):
 *
 *     celsius = (hex_value * 0.5) + 14
 *
 * Missing or unparseable values fall through to the legacy [Temperature.fromApi]
 * path so downstream display code still gets a value (albeit one plausibility
 * checks will likely reject).
 */
private fun parseCanadaAirTemp(airTempBlock: JsonObject, airTempUnitTopLevel: String?): Temperature {
    val rawValue = stringify(airTempBlock["value"])
    val unitField = airTempBlock["unit"].asIntOrNull()

    // Canadian responses carry the human unit as a top-level string ("C"/"F").
    // The nested `unit` field is an index into that unit's scale, *not* the
    // unit itself — many Gen3 payloads ship `unit: 0` regardless of whether
    // the top-level says "C" or "F".
    val units =
        when (airTempUnitTopLevel?.uppercase()) {
            "F" -> Temperature.Units.FAHRENHEIT
            "C" -> Temperature.Units.CELSIUS
            else -> Temperature.Units.fromInt(unitField)
        }

    // HI / LOW map to the HVAC range bounds, which Temperature.MAXIMUM/MINIMUM
    // define in Fahrenheit (62..82). The Temperature constructor does NOT
    // convert, so convert here to the resolved unit — otherwise a
    // Celsius-resolved vehicle stores an impossible 82°C / 62°C that
    // downstream plausibility checks drop.
    if (rawValue == "HI") {
        return Temperature(units, Temperature.Units.FAHRENHEIT.convert(Temperature.MAXIMUM, units))
    }
    if (rawValue == "LOW") {
        return Temperature(units, Temperature.Units.FAHRENHEIT.convert(Temperature.MINIMUM, units))
    }

    // Hex-code path: "00H", "0EH", etc.
    if (rawValue != null && rawValue.length >= 2 && rawValue.uppercase().endsWith("H")) {
        val hex = rawValue.dropLast(1).toIntOrNull(16)
        if (hex != null && hex in 0..255) {
            val celsius = (hex * 0.5) + 14.0
            val value = if (units == Temperature.Units.FAHRENHEIT) celsius * 9.0 / 5.0 + 32.0 else celsius
            return Temperature(units, value)
        }
    }

    // Plain numeric path, for payloads that don't use the hex encoding.
    rawValue?.toDoubleOrNull()?.let { return Temperature(units, it) }

    // Fall back to the legacy parser so we always return something.
    return Temperature.fromApi(units = unitField, value = rawValue)
}

internal fun parseCanadaSyncDate(statusData: JsonObject): java.time.Instant? {
    statusData["dateTime"].asStringOrNull()?.let { dateTime ->
        BluelinkDates.parseIso8601(dateTime)?.let { return it }
    }

    statusData["lastStatusDate"].asStringOrNull()?.let { lastStatusDate ->
        return BluelinkDates.parseBasic14Utc(lastStatusDate)
    }

    return null
}

internal fun parseCanadaBattery12V(statusData: JsonObject): Int? {
    val battery = statusData["battery"] as? JsonObject ?: return null
    return battery["batSoc"].asIntOrNull()
}

internal fun parseCanadaDoorStatus(statusData: JsonObject): VehicleStatus.DoorStatus? {
    val doorData =
        statusData["doorOpen"] as? JsonObject
            ?: statusData["doorStatus"] as? JsonObject
            ?: JsonObject(emptyMap())

    if (doorData.isEmpty()) return null

    return VehicleStatus.DoorStatus(
        frontLeft = (doorData["frontLeft"].asIntOrNull() ?: 0) != 0,
        frontRight = (doorData["frontRight"].asIntOrNull() ?: 0) != 0,
        backLeft = (doorData["backLeft"].asIntOrNull() ?: 0) != 0,
        backRight = (doorData["backRight"].asIntOrNull() ?: 0) != 0,
    )
}

internal fun parseCanadaTrunkOpen(statusData: JsonObject): Boolean? =
    parseOpenStatus(statusData, directKey = "trunkOpen", doorStatusKey = "trunk")

internal fun parseCanadaHoodOpen(statusData: JsonObject): Boolean? =
    parseOpenStatus(statusData, directKey = "hoodOpen", doorStatusKey = "hood")

internal fun parseCanadaTirePressureWarning(statusData: JsonObject): VehicleStatus.TirePressureWarning? {
    val tireData = statusData["tirePressureLamp"] as? JsonObject ?: return null

    fun warning(long: String, short: String): Boolean {
        val value = tireData[long].asIntOrNull() ?: tireData[short].asIntOrNull() ?: 0
        return value != 0
    }

    return VehicleStatus.TirePressureWarning(
        frontLeft = warning("tirePressureWarningLampFrontLeft", "frontLeft"),
        frontRight = warning("tirePressureWarningLampFrontRight", "frontRight"),
        rearLeft = warning("tirePressureWarningLampRearLeft", "rearLeft"),
        rearRight = warning("tirePressureWarningLampRearRight", "rearRight"),
        all = warning("tirePressureWarningLampAll", "all"),
    )
}

// Private helpers

private fun parseCanadaEvRange(evStatusData: JsonObject): Distance? {
    val drvDistance = evStatusData["drvDistance"] as? JsonArray ?: return null
    for (entry in drvDistance) {
        val rangeByFuel = (entry as? JsonObject)?.get("rangeByFuel") as? JsonObject ?: continue
        val evModeRange = rangeByFuel["evModeRange"] as? JsonObject
        val totalRange = rangeByFuel["totalAvailableRange"] as? JsonObject
        val preferred = evModeRange ?: totalRange ?: continue

        val value = preferred["value"].asDoubleOrNull() ?: continue
        val unit = preferred["unit"].asIntOrNull() ?: 1
        return Distance(value, Distance.Units.fromInt(unit))
    }
    return null
}

private fun parseChargeSpeed(evStatusData: JsonObject): Double {
    val batteryPower = evStatusData["batteryPower"] as? JsonObject
    if (batteryPower != null) {
        return maxOf(
            batteryPower["batteryFstChrgPower"].asDoubleOrNull() ?: 0.0,
            batteryPower["batteryStndChrgPower"].asDoubleOrNull() ?: 0.0,
        )
    }

    return maxOf(
        evStatusData["batteryFstChrgPower"].asDoubleOrNull() ?: 0.0,
        evStatusData["batteryStndChrgPower"].asDoubleOrNull() ?: 0.0,
    )
}

private fun parseChargeTimeMinutes(evStatusData: JsonObject): Int {
    val atc = ((evStatusData["remainTime2"] as? JsonObject)?.get("atc")) as? JsonObject
    atc?.get("value").asIntOrNull()?.let { return it }

    val remainChargeTime = evStatusData["remainChargeTime"] as? JsonArray
    return (remainChargeTime?.firstOrNull() as? JsonObject)?.get("value").asIntOrNull() ?: 0
}

private fun parseTargetSocs(evStatusData: JsonObject): Pair<Double?, Double?> {
    val reserveChargeInfos = evStatusData["reservChargeInfos"] as? JsonObject
    val targetSocList =
        (reserveChargeInfos?.get("targetSOClist") as? JsonArray)
            ?: (evStatusData["targetSOC"] as? JsonArray)
            ?: JsonArray(emptyList())

    var targetSocAC: Double? = null
    var targetSocDC: Double? = null

    for (element in targetSocList) {
        val target = element as? JsonObject ?: continue
        val plugType = target["plugType"].asIntOrNull() ?: continue
        val soc = target["targetSOClevel"].asDoubleOrNull() ?: continue
        when (plugType) {
            1 -> targetSocAC = soc
            0 -> targetSocDC = soc
        }
    }

    return targetSocAC to targetSocDC
}

private fun parseOpenStatus(data: JsonObject, directKey: String, doorStatusKey: String): Boolean? {
    data[directKey].asJsonBooleanOrNull()?.let { return it }
    val doorStatus = data["doorStatus"] as? JsonObject ?: JsonObject(emptyMap())
    doorStatus[doorStatusKey].asIntOrNull()?.let { return it != 0 }
    return null
}

private fun stringify(value: JsonElement?): String? = value.asStringOrNull()

/**
 * Strict JSON-boolean read — the analog of Swift's `as? Bool`, which matches
 * only true JSON booleans (never "true" strings or 0/1 numbers). Returns null
 * for anything else so "not reported" stays distinguishable.
 */
internal fun JsonElement?.asJsonBooleanOrNull(): Boolean? {
    if (!isJsonBoolean) return null
    return (this as JsonPrimitive).content == "true"
}

/**
 * Bool-or-int flag read (Swift `parseBoolOrInt`): a JSON boolean is taken
 * as-is; otherwise any numeric value (including numeric strings, matching the
 * Swift `extractNumber` behavior) maps to `!= 0`. Null when absent/unusable.
 */
internal fun parseBoolOrInt(value: JsonElement?): Boolean? {
    value.asJsonBooleanOrNull()?.let { return it }
    value.asIntOrNull()?.let { return it != 0 }
    return null
}
