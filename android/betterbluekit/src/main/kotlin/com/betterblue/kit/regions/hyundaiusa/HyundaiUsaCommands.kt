package com.betterblue.kit.regions.hyundaiusa

import com.betterblue.kit.model.Temperature
import com.betterblue.kit.model.Vehicle
import com.betterblue.kit.model.VehicleCommand
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

internal fun HyundaiUsaClient.commandUrl(command: VehicleCommand, vehicle: Vehicle): String {
    val path = when (command) {
        VehicleCommand.Unlock -> "ac/v2/rcs/rdo/on"
        VehicleCommand.Lock -> "ac/v2/rcs/rdo/off"
        is VehicleCommand.StartClimate ->
            if (vehicle.fuelType.hasElectricCapability) "ac/v2/evc/fatc/start" else "ac/v2/rcs/rsc/start"

        VehicleCommand.StopClimate ->
            if (vehicle.fuelType.hasElectricCapability) "ac/v2/evc/fatc/stop" else "ac/v2/rcs/rsc/stop"

        VehicleCommand.StartCharge -> "ac/v2/evc/charge/start"
        VehicleCommand.StopCharge -> "ac/v2/evc/charge/stop"
        is VehicleCommand.SetTargetSoc -> "ac/v2/evc/charge/targetsoc/set"
    }
    return "$baseUrl/$path"
}

internal fun HyundaiUsaClient.commandBody(command: VehicleCommand, vehicle: Vehicle): JsonObject =
    when (command) {
        is VehicleCommand.StartClimate -> {
            val options = command.options
            // Hyundai US always expects Fahrenheit (unit 1) — matches
            // HyundaiBlueLinkApiUSA.start_climate. Sending the preset's own
            // unit produced unit 0 + a Celsius value.
            val fahrenheit = options.temperature.units
                .convert(options.temperature.value, Temperature.Units.FAHRENHEIT)
                .roundToInt()

            if (vehicle.fuelType.hasElectricCapability) {
                buildJsonObject {
                    put("airCtrl", if (options.climate) 1 else 0)
                    put(
                        "airTemp",
                        buildJsonObject {
                            put("value", fahrenheit.toString())
                            put("unit", 1)
                        },
                    )
                    put("defrost", options.defrost)
                    put("heating1", options.heatValue)
                    if (vehicle.generation >= 3) {
                        put("igniOnDuration", options.duration)
                        put(
                            "seatHeaterVentInfo",
                            buildJsonObject {
                                for ((key, value) in options.getSeatHeaterVentInfo()) put(key, value)
                            },
                        )
                    }
                }
            } else {
                buildJsonObject {
                    put("Ims", 0)
                    put("airCtrl", if (options.climate) 1 else 0)
                    put(
                        "airTemp",
                        buildJsonObject {
                            put("unit", 1)
                            put("value", fahrenheit)
                        },
                    )
                    put("defrost", options.defrost)
                    put("heating1", options.heatValue)
                    put("igniOnDuration", options.duration)
                    put(
                        "seatHeaterVentInfo",
                        buildJsonObject {
                            for ((key, value) in options.getSeatHeaterVentInfo()) put(key, value)
                        },
                    )
                    put("username", username)
                    put("vin", vehicle.vin)
                }
            }
        }

        // Hyundai US start charge takes no body (just headers). `chargeRatio`
        // is the Kia US shape — wrong here.
        VehicleCommand.StartCharge -> buildJsonObject {}

        is VehicleCommand.SetTargetSoc -> buildJsonObject {
            put(
                "targetSOClist",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("targetSOClevel", command.acLevel)
                            put("plugType", 1)
                        },
                    )
                    add(
                        buildJsonObject {
                            put("targetSOClevel", command.dcLevel)
                            put("plugType", 0)
                        },
                    )
                },
            )
        }

        else -> buildJsonObject {}
    }
