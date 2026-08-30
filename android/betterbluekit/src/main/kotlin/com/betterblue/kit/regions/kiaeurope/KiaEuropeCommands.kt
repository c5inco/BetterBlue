package com.betterblue.kit.regions.kiaeurope

import com.betterblue.kit.model.HvacTemperatureTable
import com.betterblue.kit.model.Temperature
import com.betterblue.kit.model.VehicleCommand
import com.betterblue.kit.regions.hyundaieurope.euStartClimateCcs2Body
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Command helpers for the Kia Europe client. Endpoint shapes are identical
// to Hyundai EU — see HyundaiEuropeCommands.kt.

internal fun KiaEuropeClient.commandPathAndBody(
    command: VehicleCommand,
    ccs2: Boolean = true,
    drvSeatLoc: String = "L",
): Pair<String, JsonObject> {
    val deviceId = config.deviceId ?: ""
    return when (command) {
        VehicleCommand.Lock ->
            if (ccs2) {
                "ccs2/control/door" to buildJsonObject { put("command", "close") }
            } else {
                "control/door" to buildJsonObject {
                    put("action", "close")
                    put("deviceId", deviceId)
                }
            }

        VehicleCommand.Unlock ->
            if (ccs2) {
                "ccs2/control/door" to buildJsonObject { put("command", "open") }
            } else {
                "control/door" to buildJsonObject {
                    put("action", "open")
                    put("deviceId", deviceId)
                }
            }

        is VehicleCommand.StartClimate -> {
            // Mirrors the Swift client: Kia EU always uses the CCS2 climate
            // endpoint/body for startClimate, regardless of the ccs2 flag.
            // Kia EU only accepts temperatures on the 0.5°C grid (15.0–30.0)
            // — sending 22.22 (linear F→C of 72°F) silently no-ops on the
            // car, so hvacConvert snaps via the EU lookup table.
            val options = command.options
            val tempCelsius = Temperature.hvacConvert(
                options.temperature.value,
                sourceUnits = options.temperature.units,
                targetUnits = Temperature.Units.CELSIUS,
                table = HvacTemperatureTable.EUROPEAN,
            )
            "ccs2/control/temperature" to euStartClimateCcs2Body(options, tempCelsius, drvSeatLoc)
        }

        VehicleCommand.StopClimate ->
            if (ccs2) {
                "ccs2/control/temperature" to buildJsonObject { put("command", "stop") }
            } else {
                "control/temperature" to buildJsonObject {
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

        VehicleCommand.StartCharge ->
            if (ccs2) {
                "ccs2/control/charge" to buildJsonObject { put("command", "start") }
            } else {
                "control/charge" to buildJsonObject {
                    put("action", "start")
                    put("deviceId", deviceId)
                }
            }

        VehicleCommand.StopCharge ->
            if (ccs2) {
                "ccs2/control/charge" to buildJsonObject { put("command", "stop") }
            } else {
                "control/charge" to buildJsonObject {
                    put("action", "stop")
                    put("deviceId", deviceId)
                }
            }

        is VehicleCommand.SetTargetSoc ->
            // plugType 0 = DC fast charge, 1 = AC — per ApiImplType1
            // set_charge_limits. The mapping was once inverted, so users set
            // the AC and DC limits onto the opposite plug type.
            "charge/target" to buildJsonObject {
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
