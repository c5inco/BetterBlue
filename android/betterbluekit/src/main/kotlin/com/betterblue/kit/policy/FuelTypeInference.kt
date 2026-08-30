package com.betterblue.kit.policy

import com.betterblue.kit.model.FuelType
import com.betterblue.kit.model.VehicleStatus

/**
 * Infers the powertrain from a status payload's SHAPE.
 *
 * Borrowed from `hyundai_kia_connect_api`: when the vehicle-list parser can't
 * authoritatively classify the powertrain (Kia USA only confirms
 * `fuelType == 4` as EV; everything else falls into gas as a conservative
 * default), the status response's structure is the source of truth.
 *
 *   - evStatus + gasRange ⇒ PHEV
 *   - evStatus only       ⇒ pure EV
 *   - gasRange only       ⇒ pure ICE
 *   - neither             ⇒ no signal
 */
fun inferFuelType(status: VehicleStatus): FuelType? =
    when {
        status.evStatus != null && status.gasRange != null -> FuelType.PHEV
        status.evStatus != null -> FuelType.ELECTRIC
        status.gasRange != null -> FuelType.GAS
        else -> null
    }

/**
 * True when [to] is strictly more specific than [from] along the
 * gas → electric → phev axis.
 *
 * Self-healing only ever UPGRADES specificity, so a one-off missing field in
 * a status response can't demote a vehicle we already know is a PHEV back to
 * electric or gas.
 */
fun isFuelTypeUpgrade(from: FuelType, to: FuelType): Boolean =
    when (from to to) {
        FuelType.GAS to FuelType.ELECTRIC,
        FuelType.GAS to FuelType.PHEV,
        FuelType.ELECTRIC to FuelType.PHEV,
        -> true

        else -> false
    }

/**
 * The fuel type to store after applying a status update: the inferred type
 * when it's an upgrade, otherwise the current one unchanged.
 */
fun healedFuelType(current: FuelType, status: VehicleStatus): FuelType {
    val inferred = inferFuelType(status) ?: return current
    return if (isFuelTypeUpgrade(current, inferred)) inferred else current
}
