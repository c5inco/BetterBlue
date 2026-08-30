package com.betterblue.kit.model

import kotlinx.serialization.Serializable

@Serializable
data class ClimateOptions(
    val climate: Boolean = true,
    val temperature: Temperature = Temperature(Temperature.Units.FAHRENHEIT, 72.0),
    val defrost: Boolean = false,
    val duration: Int = 10,
    val frontLeftSeat: Int = 0,
    val frontRightSeat: Int = 0,
    val rearLeftSeat: Int = 0,
    val rearRightSeat: Int = 0,
    val steeringWheel: Int = 0,
    val frontLeftVentilation: Boolean = false,
    val frontRightVentilation: Boolean = false,
    val rearLeftVentilation: Boolean = false,
    val rearRightVentilation: Boolean = false,
    val rearDefrost: Boolean = false,
) {
    /**
     * Legacy "heating1" code combining rear defrost and steering wheel heat:
     * 0 = neither, 2 = rear defrost only, 3 = steering wheel only, 4 = both.
     */
    val heatValue: Int
        get() =
            when {
                !rearDefrost && steeringWheel == 0 -> 0
                rearDefrost && steeringWheel != 0 -> 4
                rearDefrost -> 2
                steeringWheel != 0 -> 3
                else -> 0
            }

    fun getSeatHeaterVentInfo(): Map<String, Int> =
        mapOf(
            "drvSeatHeatState" to convertSeatSetting(frontLeftSeat, frontLeftVentilation),
            "astSeatHeatState" to convertSeatSetting(frontRightSeat, frontRightVentilation),
            "rlSeatHeatState" to convertSeatSetting(rearLeftSeat, rearLeftVentilation),
            "rrSeatHeatState" to convertSeatSetting(rearRightSeat, rearRightVentilation),
        )

    companion object {
        /**
         * Fresh options with the temperature defaulted to the comfortable
         * midpoint of the HVAC range in the caller's preferred unit — 72°F or
         * 22°C — so a new preset doesn't start on an off-grid converted value.
         */
        fun forPreferredUnits(preferredUnits: Temperature.Units): ClimateOptions =
            when (preferredUnits) {
                Temperature.Units.FAHRENHEIT -> {
                    ClimateOptions(temperature = Temperature(Temperature.Units.FAHRENHEIT, 72.0))
                }

                Temperature.Units.CELSIUS -> {
                    ClimateOptions(temperature = Temperature(Temperature.Units.CELSIUS, 22.0))
                }
            }
    }
}

/**
 * Maps the internal seat level to the values Hyundai/Kia expect
 * (from egmp-bluelink-scriptable): 0 stays 0; cooling 1→3, 2→4, 3→5;
 * heat 1→6, 2→7, 3→8.
 */
internal fun convertSeatSetting(value: Int, cooling: Boolean): Int =
    when {
        value == 0 -> 0
        cooling -> value + 2
        else -> value + 5
    }
