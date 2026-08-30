package com.betterblue.kit.regions.hyundaieurope

import com.betterblue.kit.model.ClimateOptions
import com.betterblue.kit.model.HvacTemperatureTable
import com.betterblue.kit.model.Temperature
import com.betterblue.kit.model.VehicleCommand
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Command path + body builders for Hyundai Europe, mirroring the Swift
// `+Commands` extension file.

internal fun HyundaiEuropeClient.commandPathAndBody(
    command: VehicleCommand,
    ccs2: Boolean = true,
    drvSeatLoc: String = "L",
): Pair<String, JsonObject> {
    val deviceId = config.deviceId ?: ""
    return when (command) {
        VehicleCommand.Lock -> {
            if (ccs2) {
                "ccs2/control/door" to buildJsonObject { put("command", "close") }
            } else {
                "control/door" to
                    buildJsonObject {
                        put("action", "close")
                        put("deviceId", deviceId)
                    }
            }
        }

        VehicleCommand.Unlock -> {
            if (ccs2) {
                "ccs2/control/door" to buildJsonObject { put("command", "open") }
            } else {
                "control/door" to
                    buildJsonObject {
                        put("action", "open")
                        put("deviceId", deviceId)
                    }
            }
        }

        is VehicleCommand.StartClimate -> {
            // EU vehicles share ApiImplType1.start_climate across both
            // brands — CCS2 uses a flat body (same as Kia EU); legacy uses an
            // action/hvacType body with a HEX temp code. HVAC temps must snap
            // via the EU lookup table (Temperature.hvacConvert) — a linear
            // F→C conversion like 22.22°C silently no-ops on the car.
            val options = command.options
            val tempCelsius =
                Temperature.hvacConvert(
                    options.temperature.value,
                    sourceUnits = options.temperature.units,
                    targetUnits = Temperature.Units.CELSIUS,
                    table = HvacTemperatureTable.EUROPEAN,
                )
            if (ccs2) {
                "ccs2/control/temperature" to
                    euStartClimateCcs2Body(options, tempCelsius, drvSeatLoc)
            } else {
                "control/temperature" to
                    buildJsonObject {
                        put("action", "start")
                        put("hvacType", 0)
                        put(
                            "options",
                            buildJsonObject {
                                put("defrost", options.defrost)
                                put("heating1", options.heatValue)
                                put("igniOnDuration", options.duration)
                            },
                        )
                        put("tempCode", Temperature.encodeAirTempToHex(tempCelsius))
                        put("unit", "C")
                    }
            }
        }

        VehicleCommand.StopClimate -> {
            if (ccs2) {
                "ccs2/control/temperature" to buildJsonObject { put("command", "stop") }
            } else {
                "control/temperature" to
                    buildJsonObject {
                        put("action", "stop")
                        put("hvacType", 0)
                        put(
                            "options",
                            buildJsonObject {
                                put("defrost", true)
                                put("heating1", 1)
                            },
                        )
                        put("tempCode", "10H")
                        put("unit", "C")
                    }
            }
        }

        VehicleCommand.StartCharge -> {
            if (ccs2) {
                "ccs2/control/charge" to buildJsonObject { put("command", "start") }
            } else {
                "control/charge" to
                    buildJsonObject {
                        put("action", "start")
                        put("deviceId", deviceId)
                    }
            }
        }

        VehicleCommand.StopCharge -> {
            if (ccs2) {
                "ccs2/control/charge" to buildJsonObject { put("command", "stop") }
            } else {
                "control/charge" to
                    buildJsonObject {
                        put("action", "stop")
                        put("deviceId", deviceId)
                    }
            }
        }

        is VehicleCommand.SetTargetSoc -> {
            // plugType 0 = DC fast charge, 1 = AC — per ApiImplType1
            // set_charge_limits. The mapping was once inverted, so users set
            // the AC and DC limits onto the opposite plug type.
            "charge/target" to
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
    }
}

/**
 * CCS2 climate-start body, identical in shape for Hyundai EU and Kia EU
 * (both brands share ApiImplType1.start_climate, CCS2 branch). `tempCelsius`
 * is already snapped to the 0.5°C EU grid.
 */
internal fun euStartClimateCcs2Body(
    options: ClimateOptions,
    tempCelsius: Double,
    drvSeatLoc: String,
): JsonObject {
    // On a right-hand-drive car the driver sits on the right, so the
    // front-left/right seat controls map to passenger/driver — they're
    // SWAPPED relative to LHD. Matches hyundai_kia_connect_api's
    // `start_climate` seat handling.
    val (drvSeat, psgSeat) =
        if (drvSeatLoc == "R") {
            options.frontRightSeat to options.frontLeftSeat
        } else {
            options.frontLeftSeat to options.frontRightSeat
        }
    return buildJsonObject {
        put("command", "start")
        put("ignitionDuration", options.duration)
        put("strgWhlHeating", options.steeringWheel)
        put("hvacTempType", 1)
        put("hvacTemp", tempCelsius)
        // Rear-window + side-mirror heaters ride along with the heating
        // levels that engage them (1/2/4); off for 0 and steering-only (3).
        put("sideRearMirrorHeating", if (options.heatValue in listOf(1, 2, 4)) 1 else 0)
        put("drvSeatLoc", drvSeatLoc)
        put(
            "seatClimateInfo",
            buildJsonObject {
                put("drvSeatClimateState", drvSeat)
                put("psgSeatClimateState", psgSeat)
                put("rrSeatClimateState", options.rearRightSeat)
                put("rlSeatClimateState", options.rearLeftSeat)
            },
        )
        put("tempUnit", "C")
        put("windshieldFrontDefogState", options.defrost)
    }
}
