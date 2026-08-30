package com.betterblue.kit.regions.kiausa

import com.betterblue.kit.HttpMethod
import com.betterblue.kit.model.Temperature
import com.betterblue.kit.model.VehicleCommand
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

internal fun KiaUsaClient.commandMethod(command: VehicleCommand): HttpMethod =
    when (command) {
        // rems/stop and evc/cancel are GETs with no body.
        VehicleCommand.StopClimate, VehicleCommand.StopCharge -> HttpMethod.GET

        else -> HttpMethod.POST
    }

internal fun KiaUsaClient.commandUrl(command: VehicleCommand): String {
    val path =
        when (command) {
            VehicleCommand.Lock -> "rems/door/lock"

            VehicleCommand.Unlock -> "rems/door/unlock"

            is VehicleCommand.StartClimate -> "rems/start"

            VehicleCommand.StopClimate -> "rems/stop"

            VehicleCommand.StartCharge -> "evc/charge"

            VehicleCommand.StopCharge -> "evc/cancel"

            // Kia US uses "evc/sts" for charge limits — NOT the Hyundai
            // "evc/charge/targetsoc/set" path we had copied. Matches
            // KiaUvoApiUSA.set_charge_limits.
            is VehicleCommand.SetTargetSoc -> "evc/sts"
        }
    return "$apiUrl$path"
}

internal fun KiaUsaClient.commandBody(command: VehicleCommand): JsonObject =
    when (command) {
        is VehicleCommand.StartClimate -> {
            val options = command.options

            buildJsonObject {
                put(
                    "remoteClimate",
                    buildJsonObject {
                        put("airCtrl", options.climate)
                        put("defrost", options.defrost)
                        // Kia US always expects Fahrenheit (unit 1). The
                        // value is the F temperature as a string, clamped to
                        // "LOW"/"HIGH" outside the 62–82°F range. Matches
                        // KiaUvoApiUSA.start_climate. Previously we sent the
                        // raw preset value + its own unit, so a Celsius
                        // preset produced {"value":"22","unit":0} — which the
                        // car reads as 22°F (freezing) or rejects outright.
                        put("airTemp", kiaUsAirTemp(options.temperature))
                        put(
                            "ignitionOnDuration",
                            buildJsonObject {
                                put("unit", 4)
                                put("value", options.duration)
                            },
                        )
                        put(
                            "heatingAccessory",
                            buildJsonObject {
                                put("steeringWheel", if (options.steeringWheel > 0) 1 else 0)
                                put("steeringWheelStep", options.steeringWheel)
                                put("rearWindow", if (options.rearDefrost) 1 else 0)
                                put("sideMirror", if (options.rearDefrost) 1 else 0)
                            },
                        )

                        // Only include heatVentSeat when the user actually set a
                        // seat — Kia now validates seat-climate capability at the
                        // car level and rejects the whole command if the body
                        // includes heatVentSeat on a vehicle that can't do it.
                        // (See the explicit note in KiaUvoApiUSA.start_climate.)
                        //
                        // Each seat is a nested {heatVentType, heatVentLevel,
                        // heatVentStep} object — NOT a flat int. Matches
                        // KiaUvoApiUSA._seat_settings.
                        val seats =
                            mapOf(
                                "driverSeat" to seatClimateSetting(options.frontLeftSeat, options.frontLeftVentilation),
                                "passengerSeat" to
                                    seatClimateSetting(options.frontRightSeat, options.frontRightVentilation),
                                "rearLeftSeat" to seatClimateSetting(options.rearLeftSeat, options.rearLeftVentilation),
                                "rearRightSeat" to
                                    seatClimateSetting(options.rearRightSeat, options.rearRightVentilation),
                            )
                        if (seats.values.any { (it["heatVentType"] ?: 0) != 0 }) {
                            put(
                                "heatVentSeat",
                                buildJsonObject {
                                    for ((seatName, setting) in seats) {
                                        put(
                                            seatName,
                                            buildJsonObject {
                                                for ((key, value) in setting) put(key, value)
                                            },
                                        )
                                    }
                                },
                            )
                        }
                    },
                )
            }
        }

        VehicleCommand.StartCharge -> {
            buildJsonObject { put("chargeRatio", 100) }
        }

        is VehicleCommand.SetTargetSoc -> {
            // Kia US encodes plugType 0 as DC fast charge and plugType 1
            // as AC (matches hyundai_kia_connect_api). The previous
            // mapping was inverted, so users saw — and set — AC/DC
            // limits swapped (issue #41).
            buildJsonObject {
                put(
                    "targetSOClist",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("targetSOClevel", command.dcLevel)
                                put("plugType", 0)
                            },
                        )
                        add(
                            buildJsonObject {
                                put("targetSOClevel", command.acLevel)
                                put("plugType", 1)
                            },
                        )
                    },
                )
            }
        }

        else -> {
            buildJsonObject {}
        }
    }

/**
 * Builds the `airTemp` payload for Kia US: always Fahrenheit
 * (unit 1), value clamped to the 62–82°F controllable range
 * with "LOW"/"HIGH" sentinels outside it. Converts from
 * whatever unit the preset was stored in.
 */
private fun kiaUsAirTemp(temperature: Temperature): JsonObject {
    val fahrenheit = temperature.units.convert(temperature.value, Temperature.Units.FAHRENHEIT)
    val value =
        when {
            fahrenheit < 62 -> "LOW"
            fahrenheit > 82 -> "HIGH"
            else -> fahrenheit.roundToInt().toString()
        }
    return buildJsonObject {
        put("value", value)
        put("unit", 1)
    }
}

// Seat setting conversion

/**
 * Maps our model (heat level 0–3 + ventilation flag) to Kia's
 * nested seat object. Mirrors KiaUvoApiUSA._seat_settings:
 *   heatVentType: 0 = off, 1 = heat, 2 = cool (ventilation)
 *   heatVentLevel: off=1, low=2, medium=3, high=4
 *   heatVentStep:  off=0, low=3, medium=2, high=1
 */
private fun seatClimateSetting(heatLevel: Int, ventilationEnabled: Boolean): Map<String, Int> {
    val level = heatLevel.coerceIn(0, 3)
    if (level <= 0) {
        return mapOf("heatVentType" to 0, "heatVentLevel" to 1, "heatVentStep" to 0)
    }
    return mapOf(
        "heatVentType" to if (ventilationEnabled) 2 else 1,
        "heatVentLevel" to level + 1, // 1→2, 2→3, 3→4
        "heatVentStep" to 4 - level, // 1→3, 2→2, 3→1
    )
}
