package com.betterblue.kit.regions.ccsp

import com.betterblue.kit.json.asIntOrNull
import com.betterblue.kit.json.atPath
import com.betterblue.kit.json.isJsonBoolean
import com.betterblue.kit.util.BluelinkDates
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Which response shape a CCSP (EU "Connected Car Service Platform") vehicle
 * speaks: the older Gen5W/"legacy" envelope or the CCS2 (CCNC) one.
 */
enum class CcspApiProfile { LEGACY, CCS2 }

/**
 * Every field the EU status parsers read, addressed symbolically. The same
 * keys resolve to different dot-paths depending on [CcspApiProfile] — see
 * [CcspKeyPathMap]. Shared between Hyundai EU and Kia EU, whose response
 * shapes are identical.
 */
enum class CcspResponseKey {
    VEHICLE_STATE,
    SYNC_DATE,
    ODO,
    SOC,
    BATTERY_12V,
    CHARGE_TIME,
    IS_CHARGING,
    PLUGGED_IN,
    ENGINE_ON,
    RANGE_TOTAL,
    RANGE_UNIT,
    TARGET_AC,
    TARGET_DC,
    TARGET_SOC_LIST,
    CHARGE_POWER_STD,
    CHARGE_POWER_FST,
    LOCK_1L,
    LOCK_1R,
    LOCK_2L,
    LOCK_2R,
    LOCK_STATUS,
    DOOR_FRONT_LEFT,
    DOOR_FRONT_RIGHT,
    DOOR_REAR_LEFT,
    DOOR_REAR_RIGHT,
    LOCATION_DATE,
    TRUNK,
    HOOD,
    PARK_DATE,
    LOCATION_LAT,
    LOCATION_LON,
    PARK_LAT,
    PARK_LON,
    AIRCON_SPEED,
    DEFROST_ON,
    STEERING_WHEEL_HEAT_ON,
    AIR_TEMP,
    TEMP_UNIT,
    AIR_CONTROL_ON,
    TPMS_FRONT_LEFT,
    TPMS_FRONT_RIGHT,
    TPMS_REAR_LEFT,
    TPMS_REAR_RIGHT,
    TPMS_STATUS,
}

/**
 * Table-driven dot-path lookup for one [CcspApiProfile]. Paths are walked with
 * [atPath], which supports array indices ("drvDistance.0.rangeByFuel").
 *
 * Returns null for keys the profile doesn't define (e.g. [CcspResponseKey.LOCK_STATUS]
 * is legacy-only; the per-door `LOCK_*` bits are CCS2-only). Note the CCS2 lock
 * bits are INVERTED: `Cabin.Door.*.Lock == 0` means locked — callers must read
 * them with `ccspBool(..., inverted = true)`.
 */
class CcspKeyPathMap(
    val profile: CcspApiProfile,
) {
    operator fun get(key: CcspResponseKey): String? =
        when (profile) {
            CcspApiProfile.CCS2 -> CCS2_PATHS[key]
            CcspApiProfile.LEGACY -> LEGACY_PATHS[key]
        }

    companion object {
        private val CCS2_PATHS: Map<CcspResponseKey, String> =
            mapOf(
                CcspResponseKey.VEHICLE_STATE to "state.Vehicle",
                CcspResponseKey.SYNC_DATE to "lastUpdateTime",
                CcspResponseKey.ODO to "Drivetrain.Odometer",
                CcspResponseKey.SOC to "Green.BatteryManagement.BatteryRemain.Ratio",
                CcspResponseKey.BATTERY_12V to "Electronics.Battery.Level",
                CcspResponseKey.ENGINE_ON to "DrivingReady",
                CcspResponseKey.CHARGE_TIME to "Green.ChargingInformation.Charging.RemainTime",
                CcspResponseKey.IS_CHARGING to "Green.ChargingInformation.Charging.RemainTime",
                CcspResponseKey.PLUGGED_IN to "Green.ChargingInformation.ConnectorFastening.State",
                CcspResponseKey.RANGE_TOTAL to "Drivetrain.FuelSystem.DTE.Total",
                CcspResponseKey.RANGE_UNIT to "Drivetrain.FuelSystem.DTE.Unit",
                CcspResponseKey.TARGET_AC to "Green.ChargingInformation.TargetSoC.Standard",
                CcspResponseKey.TARGET_DC to "Green.ChargingInformation.TargetSoC.Quick",
                CcspResponseKey.CHARGE_POWER_STD to "Green.Electric.SmartGrid.RealTimePower",
                CcspResponseKey.CHARGE_POWER_FST to "Green.Electric.SmartGrid.RealTimePower",
                // Inverted: 0 = locked, 1 = unlocked.
                CcspResponseKey.LOCK_1L to "Cabin.Door.Row1.Driver.Lock",
                CcspResponseKey.LOCK_1R to "Cabin.Door.Row1.Passenger.Lock",
                CcspResponseKey.LOCK_2L to "Cabin.Door.Row2.Left.Lock",
                CcspResponseKey.LOCK_2R to "Cabin.Door.Row2.Right.Lock",
                CcspResponseKey.DOOR_FRONT_LEFT to "Cabin.Door.Row1.Driver.Open",
                CcspResponseKey.DOOR_FRONT_RIGHT to "Cabin.Door.Row1.Passenger.Open",
                CcspResponseKey.DOOR_REAR_LEFT to "Cabin.Door.Row2.Left.Open",
                CcspResponseKey.DOOR_REAR_RIGHT to "Cabin.Door.Row2.Right.Open",
                CcspResponseKey.TRUNK to "Body.Trunk.Open",
                CcspResponseKey.HOOD to "Body.Hood.Open",
                CcspResponseKey.LOCATION_DATE to "Location.Date",
                CcspResponseKey.PARK_DATE to "time",
                CcspResponseKey.LOCATION_LAT to "Location.GeoCoord.Latitude",
                CcspResponseKey.LOCATION_LON to "Location.GeoCoord.Longitude",
                CcspResponseKey.PARK_LAT to "coord.lat",
                CcspResponseKey.PARK_LON to "coord.lon",
                CcspResponseKey.AIRCON_SPEED to "Cabin.HVAC.Row1.Driver.Blower.SpeedLevel",
                CcspResponseKey.DEFROST_ON to "Body.Windshield.Front.Defog.State",
                CcspResponseKey.STEERING_WHEEL_HEAT_ON to "Cabin.SteeringWheel.Heat.State",
                CcspResponseKey.AIR_TEMP to "Cabin.HVAC.Row1.Driver.Temperature.Value",
                CcspResponseKey.TEMP_UNIT to "Cabin.HVAC.Row1.Driver.Temperature.Unit",
                CcspResponseKey.TPMS_FRONT_LEFT to "Chassis.Axle.Row1.Left.Tire.PressureLow",
                CcspResponseKey.TPMS_FRONT_RIGHT to "Chassis.Axle.Row1.Right.Tire.PressureLow",
                CcspResponseKey.TPMS_REAR_LEFT to "Chassis.Axle.Row2.Left.Tire.PressureLow",
                CcspResponseKey.TPMS_REAR_RIGHT to "Chassis.Axle.Row2.Right.Tire.PressureLow",
                CcspResponseKey.TPMS_STATUS to "Chassis.Axle.Tire.PressureLow",
            )

        private val LEGACY_PATHS: Map<CcspResponseKey, String> =
            mapOf(
                CcspResponseKey.VEHICLE_STATE to "vehicleStatusInfo",
                CcspResponseKey.SYNC_DATE to "vehicleStatusInfo.vehicleStatus.time",
                CcspResponseKey.ODO to "odometer.value",
                CcspResponseKey.SOC to "vehicleStatus.evStatus.batteryStatus",
                CcspResponseKey.BATTERY_12V to "vehicleStatus.battery.batSoc",
                CcspResponseKey.ENGINE_ON to "vehicleStatus.engine",
                CcspResponseKey.CHARGE_TIME to "vehicleStatus.evStatus.remainTime2.atc.value",
                CcspResponseKey.IS_CHARGING to "vehicleStatus.evStatus.batteryCharge",
                CcspResponseKey.PLUGGED_IN to "vehicleStatus.evStatus.batteryPlugin",
                CcspResponseKey.RANGE_TOTAL to
                    "vehicleStatus.evStatus.drvDistance.0.rangeByFuel.evModeRange.value",
                CcspResponseKey.RANGE_UNIT to
                    "vehicleStatus.evStatus.drvDistance.0.rangeByFuel.evModeRange.unit",
                CcspResponseKey.TARGET_SOC_LIST to "vehicleStatus.evStatus.reservChargeInfos.targetSOClist",
                CcspResponseKey.CHARGE_POWER_STD to "vehicleStatus.evStatus.batteryPower.batteryStndChrgPower",
                CcspResponseKey.CHARGE_POWER_FST to "vehicleStatus.evStatus.batteryPower.batteryFstChrgPower",
                CcspResponseKey.LOCK_STATUS to "vehicleStatus.doorLock",
                CcspResponseKey.DOOR_FRONT_LEFT to "vehicleStatus.doorOpen.frontLeft",
                CcspResponseKey.DOOR_FRONT_RIGHT to "vehicleStatus.doorOpen.frontRight",
                CcspResponseKey.DOOR_REAR_LEFT to "vehicleStatus.doorOpen.backLeft",
                CcspResponseKey.DOOR_REAR_RIGHT to "vehicleStatus.doorOpen.backRight",
                CcspResponseKey.TRUNK to "vehicleStatus.trunkOpen",
                CcspResponseKey.HOOD to "vehicleStatus.hoodOpen",
                CcspResponseKey.LOCATION_DATE to "vehicleLocation.time",
                // Legacy /location/park nests everything under gpsDetail (observed
                // on a 2021 e-Niro), unlike CCS2 where coord/time sit at the root.
                // These were previously missing entirely, so the park branch of
                // parseLocation always produced (0, 0).
                CcspResponseKey.PARK_DATE to "gpsDetail.time",
                CcspResponseKey.PARK_LAT to "gpsDetail.coord.lat",
                CcspResponseKey.PARK_LON to "gpsDetail.coord.lon",
                CcspResponseKey.LOCATION_LAT to "vehicleLocation.coord.lat",
                CcspResponseKey.LOCATION_LON to "vehicleLocation.coord.lon",
                CcspResponseKey.AIR_CONTROL_ON to "vehicleStatus.airCtrlOn",
                CcspResponseKey.DEFROST_ON to "vehicleStatus.defrost",
                CcspResponseKey.STEERING_WHEEL_HEAT_ON to "vehicleStatus.steerWheelHeat",
                CcspResponseKey.AIR_TEMP to "vehicleStatus.airTemp.value",
                CcspResponseKey.TEMP_UNIT to "vehicleStatus.airTemp.unit",
                CcspResponseKey.TPMS_FRONT_LEFT to "vehicleStatus.tirePressureLamp.tirePressureLampFL",
                CcspResponseKey.TPMS_FRONT_RIGHT to "vehicleStatus.tirePressureLamp.tirePressureLampFR",
                CcspResponseKey.TPMS_REAR_LEFT to "vehicleStatus.tirePressureLamp.tirePressureLampRL",
                CcspResponseKey.TPMS_REAR_RIGHT to "vehicleStatus.tirePressureLamp.tirePressureLampRR",
                CcspResponseKey.TPMS_STATUS to "vehicleStatus.tirePressureLamp.tirePressureLampAll",
            )
    }
}

// JSON traversal helpers, matching the Swift EU clients' getXFromJson family.
// All are lenient: a null/empty path or missing element yields the default.

/** Element at a dot path; null for a null/empty path or any missing hop. */
internal fun ccspAny(data: JsonObject, path: String?): JsonElement? =
    if (path.isNullOrEmpty()) null else data.atPath(path)

/**
 * Boolean at a dot path, accepting bools, 0/1 numbers, and the string
 * spellings "true"/"1"/"yes" (case-insensitive). `inverted = true` flips the
 * reading — used for the CCS2 lock bits, where 0 means locked.
 */
internal fun ccspBool(data: JsonObject, path: String?, inverted: Boolean = false): Boolean {
    val primitive = ccspAny(data, path) as? JsonPrimitive ?: return false
    return when {
        primitive.isJsonBoolean -> {
            val value = primitive.content == "true"
            if (inverted) !value else value
        }

        primitive.isString -> {
            val lower = primitive.content.lowercase()
            if (inverted) {
                lower == "false" || lower == "0" || lower == "no"
            } else {
                lower == "true" || lower == "1" || lower == "yes"
            }
        }

        else -> {
            val value = ccspStrictInt(primitive) ?: return false
            if (inverted) value == 0 else value == 1
        }
    }
}

/** Double at a dot path — numbers or numeric strings; 0.0 otherwise. */
internal fun ccspDouble(data: JsonObject, path: String?): Double {
    val primitive = ccspAny(data, path) as? JsonPrimitive ?: return 0.0
    if (primitive.isJsonBoolean) return 0.0
    return primitive.content.toDoubleOrNull() ?: 0.0
}

/** Int from numbers or numeric strings (the Swift `extractNumber` analog). */
internal fun ccspNumber(data: JsonObject, path: String?): Int? = ccspAny(data, path).asIntOrNull()

/**
 * Strict integral-JSON-number check (rejects strings and booleans) — the
 * analog of Swift's `getAnyFromJson(...) is Int` guard.
 */
internal fun ccspStrictInt(data: JsonObject, path: String?): Int? =
    ccspStrictInt(ccspAny(data, path) as? JsonPrimitive)

private fun ccspStrictInt(primitive: JsonPrimitive?): Int? {
    if (primitive == null || primitive.isString || primitive.isJsonBoolean) return null
    return primitive.content
        .toDoubleOrNull()
        ?.takeIf { it % 1.0 == 0.0 }
        ?.toInt()
}

/**
 * Child object at a dot path. Mirrors the Swift helper's leniency: segments
 * that don't resolve to an object are skipped (not failed), and a null/empty
 * path returns the input itself.
 */
internal fun ccspChild(data: JsonObject, path: String?): JsonObject {
    if (path.isNullOrEmpty()) return data
    var current = data
    for (segment in path.split('.')) {
        current = (current[segment] as? JsonObject) ?: current
    }
    return current
}

/** JSON-string content only — the analog of Swift's `as? String` (numbers fail). */
internal fun ccspString(data: JsonObject, path: String?): String? =
    (ccspAny(data, path) as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * The Bluelink timestamp zoo, shared by Hyundai EU and Kia EU. Tries, in
 * order: `yyyyMMddHHmmss`, `yyyyMMddHHmmss.SSS` (both in the given zone,
 * defaulting to UTC like the Swift parser), then a bare 13-digit
 * (milliseconds) or 10-digit (seconds) epoch.
 */
object CcspDates {
    fun parse(value: String?, zone: ZoneId = ZoneOffset.UTC): Instant? {
        val raw = value?.trim() ?: return null
        if (raw.isEmpty()) return null
        BluelinkDates.parseBasic14(raw, zone)?.let { return it }
        BluelinkDates.parseBasic14Millis(raw, zone)?.let { return it }
        return BluelinkDates.parseEpoch(raw)
    }
}
