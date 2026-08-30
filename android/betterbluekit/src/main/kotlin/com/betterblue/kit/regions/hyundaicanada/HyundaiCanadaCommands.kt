package com.betterblue.kit.regions.hyundaicanada

// Hyundai Canada command helpers. Mirrors HyundaiCanada+Commands.swift.

import com.betterblue.kit.model.ClimateOptions
import com.betterblue.kit.model.HvacTemperatureTable
import com.betterblue.kit.model.Temperature
import com.betterblue.kit.model.VehicleCommand
import com.betterblue.kit.model.convertSeatSetting
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun HyundaiCanadaClient.commandPath(command: VehicleCommand): String = when (command) {
    VehicleCommand.Lock -> "drlck"
    VehicleCommand.Unlock -> "drulck"
    is VehicleCommand.StartClimate -> "evc/rfon"
    VehicleCommand.StopClimate -> "evc/rfoff"
    VehicleCommand.StartCharge -> "evc/rcstrt"
    VehicleCommand.StopCharge -> "evc/rcstp"
    is VehicleCommand.SetTargetSoc -> "evc/setsoc"
}

internal fun HyundaiCanadaClient.makeCommandBody(
    command: VehicleCommand,
    useRemoteControl: Boolean,
): JsonObject = when (command) {
    is VehicleCommand.StartClimate -> {
        val options = command.options
        val hvacInfo = buildJsonObject {
            put("airCtrl", if (options.climate) 1 else 0)
            put("defrost", options.defrost)
            put(
                "airTemp",
                buildJsonObject {
                    put("value", climateTemperatureValue(options))
                    put("unit", 0)
                    put("hvacTempType", 1)
                },
            )
            put("igniOnDuration", options.duration)
            put("heating1", options.heatValue)

            val seatConfig = makeSeatClimateConfig(options)
            if (seatConfig.isNotEmpty()) {
                put(
                    "seatHeaterVentCMD",
                    buildJsonObject {
                        for ((key, value) in seatConfig) put(key, value)
                    },
                )
            }
        }

        buildJsonObject {
            put("pin", pin)
            // Some vehicles only accept the older `remoteControl` wrapper —
            // the caller retries with it when `hvacInfo` fails.
            put(if (useRemoteControl) "remoteControl" else "hvacInfo", hvacInfo)
        }
    }

    VehicleCommand.StopClimate,
    VehicleCommand.StartCharge,
    VehicleCommand.StopCharge,
    VehicleCommand.Lock,
    VehicleCommand.Unlock,
    -> buildJsonObject { put("pin", pin) }

    is VehicleCommand.SetTargetSoc -> buildJsonObject {
        put("pin", pin)
        put(
            "tsoc",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("plugType", 0)
                        put("level", command.dcLevel)
                    },
                )
                add(
                    buildJsonObject {
                        put("plugType", 1)
                        put("level", command.acLevel)
                    },
                )
            },
        )
    }
}

private fun makeSeatClimateConfig(options: ClimateOptions): Map<String, Int> = mapOf(
    "drvSeatOptCmd" to convertSeatSetting(options.frontLeftSeat, options.frontLeftVentilation),
    "astSeatOptCmd" to convertSeatSetting(options.frontRightSeat, options.frontRightVentilation),
    "rlSeatOptCmd" to convertSeatSetting(options.rearLeftSeat, options.rearLeftVentilation),
    "rrSeatOptCmd" to convertSeatSetting(options.rearRightSeat, options.rearRightVentilation),
).filterValues { it != 0 }

private fun climateTemperatureValue(options: ClimateOptions): String {
    // Hyundai Canada uses the legacy HEX scheme (e.g. "10H"). Snap to the
    // standard lookup table's 0.5°C grid first, then encode.
    val tempCelsius = Temperature.hvacConvert(
        value = options.temperature.value,
        sourceUnits = options.temperature.units,
        targetUnits = Temperature.Units.CELSIUS,
        table = HvacTemperatureTable.STANDARD,
    )
    return Temperature.encodeAirTempToHex(tempCelsius)
}
