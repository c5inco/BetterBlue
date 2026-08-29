package com.betterblue.kit.model

import kotlinx.serialization.Serializable

@Serializable
data class Vehicle(
    val vin: String,
    val regId: String,
    val model: String,
    /** Owning account id (UUID string). */
    val accountId: String,
    val fuelType: FuelType,
    val generation: Int,
    val odometer: Distance,
    val vehicleKey: String? = null,
    val marketOptions: VehicleMarketOptions = VehicleMarketOptions.Generic,
) {
    val id: String get() = vin
}
