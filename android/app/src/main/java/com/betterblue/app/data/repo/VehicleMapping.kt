package com.betterblue.app.data.repo

import com.betterblue.app.data.db.entity.VehicleEntity
import com.betterblue.kit.log.BBLogCategory
import com.betterblue.kit.log.BBLogger
import com.betterblue.kit.model.FuelType
import com.betterblue.kit.model.Vehicle
import com.betterblue.kit.model.VehicleMarketOptions
import com.betterblue.kit.model.VehicleStatus

/** The kit-facing projection of a stored vehicle. */
fun VehicleEntity.toVehicle(): Vehicle = Vehicle(
    vin = vin,
    regId = regId,
    model = model,
    accountId = accountId,
    fuelType = effectiveFuelType,
    generation = generation,
    odometer = odometer,
    vehicleKey = vehicleKey,
    marketOptions = marketOptions ?: VehicleMarketOptions.Generic,
)

/** Serial-name helpers. */
private fun fuelTypeFromRaw(raw: String?): FuelType? = when (raw) {
    "gas" -> FuelType.GAS
    "electric" -> FuelType.ELECTRIC
    "phev" -> FuelType.PHEV
    else -> null
}

fun FuelType.toRaw(): String = when (this) {
    FuelType.GAS -> "gas"
    FuelType.ELECTRIC -> "electric"
    FuelType.PHEV -> "phev"
}

/** Inferred (self-healed) powertrain, before any user override. */
val VehicleEntity.inferredFuelType: FuelType
    get() = fuelTypeFromRaw(fuelTypeRaw) ?: FuelType.GAS

/**
 * Powertrain the rest of the app should use: a user override wins, otherwise
 * the self-healed inferred value.
 */
val VehicleEntity.effectiveFuelType: FuelType
    get() = fuelTypeFromRaw(fuelTypeOverrideRaw) ?: inferredFuelType

val VehicleEntity.displayName: String
    get() = customName?.trim().takeUnless { it.isNullOrEmpty() } ?: model

val VehicleEntity.lockStatusEnum: VehicleStatus.LockStatus?
    get() = when (lockStatus) {
        "locked" -> VehicleStatus.LockStatus.LOCKED
        "unlocked" -> VehicleStatus.LockStatus.UNLOCKED
        "unknown" -> VehicleStatus.LockStatus.UNKNOWN
        else -> null
    }

/**
 * Returns true when [to] is more specific than [from] along the
 * gas → electric → phev axis. Used to one-way upgrade a misclassified
 * fuelType once a status payload reveals the real powertrain — without ever
 * demoting (a PHEV momentarily returning evStatus only must not flip back to
 * electric).
 */
internal fun isFuelTypeUpgrade(from: FuelType, to: FuelType): Boolean = when (from to to) {
    FuelType.GAS to FuelType.ELECTRIC,
    FuelType.GAS to FuelType.PHEV,
    FuelType.ELECTRIC to FuelType.PHEV,
    -> true

    else -> false
}

/**
 * Merges a fetched [VehicleStatus] into the stored row, preserving UI state.
 * Port of iOS `BBVehicle.updateStatus(with:)` including the fuel-type
 * self-heal:
 *
 *   - evStatus + gasRange present ⇒ PHEV
 *   - evStatus only ⇒ pure EV
 *   - gasRange only ⇒ pure ICE
 *
 * Only ever UPGRADES specificity, so a one-off missing field can't demote a
 * vehicle we already know is a PHEV.
 */
fun VehicleEntity.updatedWithStatus(status: VehicleStatus): VehicleEntity {
    val inferred: FuelType? = when {
        status.evStatus != null && status.gasRange != null -> FuelType.PHEV
        status.evStatus != null -> FuelType.ELECTRIC
        status.gasRange != null -> FuelType.GAS
        else -> null
    }

    var newFuelTypeRaw = fuelTypeRaw
    if (inferred != null && isFuelTypeUpgrade(inferredFuelType, inferred)) {
        BBLogger.info(
            BBLogCategory.API,
            "Vehicle: self-heal fuelType $fuelTypeRaw → ${inferred.toRaw()} for VIN $vin " +
                "based on status payload shape",
        )
        newFuelTypeRaw = inferred.toRaw()
    }

    // Effective type gates which ranges are written; keep existing values
    // when the new status omits them (PHEVs legitimately carry both).
    val effective = fuelTypeFromRaw(fuelTypeOverrideRaw)
        ?: fuelTypeFromRaw(newFuelTypeRaw)
        ?: FuelType.GAS

    var newGasRange = gasRange
    var newEvStatus = evStatus
    if (effective == FuelType.GAS || effective == FuelType.PHEV) {
        status.gasRange?.let { newGasRange = it }
    }
    if (effective == FuelType.ELECTRIC || effective == FuelType.PHEV) {
        status.evStatus?.let { newEvStatus = it }
    }
    // Only clear the off-axis range for pure EV/ICE
    if (effective == FuelType.ELECTRIC && status.evStatus != null && status.gasRange == null) {
        newGasRange = null
    }
    if (effective == FuelType.GAS && status.evStatus == null && status.gasRange != null) {
        newEvStatus = null
    }

    return copy(
        fuelTypeRaw = newFuelTypeRaw,
        lastUpdated = status.lastUpdated.toEpochMilli(),
        syncDate = status.syncDate?.toEpochMilli(),
        gasRange = newGasRange,
        evStatus = newEvStatus,
        location = status.location,
        lockStatus = when (status.lockStatus) {
            VehicleStatus.LockStatus.LOCKED -> "locked"
            VehicleStatus.LockStatus.UNLOCKED -> "unlocked"
            VehicleStatus.LockStatus.UNKNOWN -> "unknown"
        },
        climateStatus = status.climateStatus,
        odometer = status.odometer ?: odometer,
        battery12V = status.battery12V,
        doorOpen = status.doorOpen,
        trunkOpen = status.trunkOpen,
        hoodOpen = status.hoodOpen,
        accessoryOn = status.accessoryOn,
        tirePressureWarning = status.tirePressureWarning,
    )
}

/**
 * Off-axis-range cleanup after a fuel-type override change: stale ranges set
 * BEFORE the override won't clear on their own.
 */
fun VehicleEntity.normalizedForCurrentFuelType(): VehicleEntity = when (effectiveFuelType) {
    FuelType.GAS -> copy(evStatus = null)
    FuelType.ELECTRIC -> copy(gasRange = null)
    FuelType.PHEV -> this
}
