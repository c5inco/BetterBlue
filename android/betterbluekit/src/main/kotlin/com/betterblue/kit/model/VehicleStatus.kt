package com.betterblue.kit.model

import com.betterblue.kit.json.InstantEpochMillisSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Serializable
data class VehicleStatus(
    val vin: String,
    @Serializable(with = InstantEpochMillisSerializer::class)
    val lastUpdated: Instant = Instant.now(),
    @Serializable(with = InstantEpochMillisSerializer::class)
    val syncDate: Instant? = null,
    val gasRange: FuelRange? = null,
    val evStatus: EvStatus? = null,
    val location: Location,
    val lockStatus: LockStatus,
    val climateStatus: ClimateStatus,
    val odometer: Distance? = null,
    val battery12V: Int? = null,
    val doorOpen: DoorStatus? = null,
    val trunkOpen: Boolean? = null,
    val hoodOpen: Boolean? = null,
    val tirePressureWarning: TirePressureWarning? = null,
    // Additional flags reported by some regions (e.g. Canada)
    val engineOn: Boolean? = null,
    val accessoryOn: Boolean? = null,
    val remoteIgnition: Boolean? = null,
    val transmissionCondition: Boolean? = null,
    val sleepMode: Boolean? = null,
    val washerFluidLow: Boolean? = null,
) {
    @Serializable
    data class FuelRange(
        val range: Distance,
        val percentage: Double,
    )

    @Serializable
    enum class PlugType(
        val code: Int,
    ) {
        @SerialName("unplugged")
        UNPLUGGED(0),

        @SerialName("dcCharger")
        DC_CHARGER(1),

        @SerialName("acCharger")
        AC_CHARGER(2),
        ;

        companion object {
            /**
             * Maps Hyundai/Kia's `batteryPlugin` int code to a typed plug.
             * Documented values (per hyundai_kia_connect_api):
             *   0 = unplugged, 1 = DC fast charger, 2 = AC portable charger,
             *   3 = AC station charger. Some vehicles also report 4 — observed
             *   on IONIQ 5s plugged into J1772 AC chargers, so it's AC.
             * The DC code is specifically 1; ANY other non-zero value is AC.
             */
            fun fromBatteryPlugin(value: Int): PlugType =
                when (value) {
                    0 -> UNPLUGGED
                    1 -> DC_CHARGER
                    else -> AC_CHARGER
                }
        }
    }

    @Serializable
    data class EvStatus(
        val charging: Boolean,
        val chargeSpeed: Double,
        val evRange: FuelRange,
        val plugType: PlugType = PlugType.UNPLUGGED,
        val chargeTimeSeconds: Long = 0,
        val targetSocAC: Double? = null,
        val targetSocDC: Double? = null,
    ) {
        val pluggedIn: Boolean get() = plugType != PlugType.UNPLUGGED
        val chargeTime: Duration get() = chargeTimeSeconds.seconds

        val currentTargetSoc: Double?
            get() =
                when (plugType) {
                    PlugType.AC_CHARGER -> targetSocAC
                    PlugType.DC_CHARGER -> targetSocDC
                    PlugType.UNPLUGGED -> null
                }
    }

    @Serializable
    data class Location(
        val latitude: Double,
        val longitude: Double,
    ) {
        val debug: String get() = "$latitude°, $longitude°"

        /**
         * True when the location carries real coordinates. The APIs use (0, 0)
         * — null island — as their "no fix" sentinel, so treat that as absent.
         */
        val hasCoordinates: Boolean get() = latitude != 0.0 || longitude != 0.0
    }

    @Serializable
    enum class LockStatus {
        @SerialName("locked")
        LOCKED,

        @SerialName("unlocked")
        UNLOCKED,

        @SerialName("unknown")
        UNKNOWN,
        ;

        fun toggled(): LockStatus =
            when (this) {
                LOCKED -> UNLOCKED
                UNLOCKED -> LOCKED
                UNKNOWN -> UNKNOWN
            }

        companion object {
            fun fromLocked(locked: Boolean?): LockStatus =
                when (locked) {
                    null -> UNKNOWN
                    true -> LOCKED
                    false -> UNLOCKED
                }
        }
    }

    @Serializable
    data class ClimateStatus(
        val defrostOn: Boolean,
        val airControlOn: Boolean,
        val steeringWheelHeatingOn: Boolean,
        val temperature: Temperature,
    )

    @Serializable
    data class DoorStatus(
        val frontLeft: Boolean,
        val frontRight: Boolean,
        val backLeft: Boolean,
        val backRight: Boolean,
    ) {
        val anyOpen: Boolean get() = frontLeft || frontRight || backLeft || backRight

        val openDoorsDescription: String
            get() {
                val doors =
                    buildList {
                        if (frontLeft) add("FL")
                        if (frontRight) add("FR")
                        if (backLeft) add("BL")
                        if (backRight) add("BR")
                    }
                return if (doors.isEmpty()) "None" else doors.joinToString(", ")
            }
    }

    @Serializable
    data class TirePressureWarning(
        val frontLeft: Boolean,
        val frontRight: Boolean,
        val rearLeft: Boolean,
        val rearRight: Boolean,
        val all: Boolean,
    ) {
        val hasWarning: Boolean get() = all || frontLeft || frontRight || rearLeft || rearRight

        val warningDescription: String
            get() {
                if (all) return "All tires"
                val tires =
                    buildList {
                        if (frontLeft) add("FL")
                        if (frontRight) add("FR")
                        if (rearLeft) add("RL")
                        if (rearRight) add("RR")
                    }
                return if (tires.isEmpty()) "OK" else tires.joinToString(", ")
            }
    }
}
