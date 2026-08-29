package com.betterblue.kit.model

sealed interface VehicleCommand {
    data object Lock : VehicleCommand
    data object Unlock : VehicleCommand
    data class StartClimate(val options: ClimateOptions) : VehicleCommand
    data object StopClimate : VehicleCommand
    data object StartCharge : VehicleCommand
    data object StopCharge : VehicleCommand
    data class SetTargetSoc(val acLevel: Int, val dcLevel: Int) : VehicleCommand
}
