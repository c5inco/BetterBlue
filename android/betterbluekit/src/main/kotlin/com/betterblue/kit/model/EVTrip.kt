package com.betterblue.kit.model

import com.betterblue.kit.json.InstantEpochMillisSerializer
import kotlinx.serialization.Serializable
import java.time.Instant
import kotlin.time.Duration

/** Details of an EV trip including energy consumption breakdown. */
@Serializable
data class EVTripSummary(
    /** Trip distance, in whatever units the API reported. */
    val distance: Distance,
    /** Odometer reading at trip end, in whatever units the API reported. */
    val odometer: Distance,
    /** Energy used by accessories (Wh). */
    val accessoriesEnergy: Int,
    /** Total energy used (Wh). */
    val totalEnergyUsed: Int,
    /** Energy regenerated (Wh). */
    val regenEnergy: Int,
    /** Energy used by climate system (Wh). */
    val climateEnergy: Int,
    /** Energy used by drivetrain (Wh). */
    val drivetrainEnergy: Int,
    /** Energy used for battery care/conditioning (Wh). */
    val batteryCareEnergy: Int,
    @Serializable(with = InstantEpochMillisSerializer::class)
    val startDate: Instant,
    val duration: Duration,
    /** Average speed (mph). */
    val avgSpeed: Double,
    /** Maximum speed (mph). */
    val maxSpeed: Double,
) {
    val id: String get() = "${startDate.epochSecond}-${odometer.length}"

    /** Calculated efficiency in distance-per-kWh, expressed in [units]. */
    fun efficiency(units: Distance.Units): Double {
        if (totalEnergyUsed <= 0) return 0.0
        return distance.units.convert(distance.length, units) / (totalEnergyUsed / 1000.0)
    }
}

/** Summary of a specific trip from the tripinfo endpoint. */
@Serializable
data class EVTripInfo(
    @Serializable(with = InstantEpochMillisSerializer::class)
    val date: Instant,
    val driveTime: Duration,
    val idleTime: Duration,
    val distance: Distance,
    /**
     * Average speed in the API's native unit — matches `distance.units` per
     * hour (km/h for Europe, the only backend serving this today).
     */
    val avgSpeed: Double,
    /** Maximum speed, same unit convention as [avgSpeed]. */
    val maxSpeed: Double,
)
