package com.betterblue.app.data.fake

import com.betterblue.kit.model.VehicleCommand
import kotlinx.serialization.Serializable

/**
 * Failure-injection switches for a fake vehicle, stored as JSON on the
 * vehicle row. All fields default so older stored blobs decode additively
 * (`ignoreUnknownKeys` + defaults — the analog of the iOS hand-written
 * `decodeIfPresent` initializer).
 */
@Serializable
data class DebugConfiguration(
    val shouldFailCredentialValidation: Boolean = false,
    val shouldFailLogin: Boolean = false,
    val shouldFailVehicleFetch: Boolean = false,
    val shouldFailStatusFetch: Boolean = false,
    val shouldFailPinValidation: Boolean = false,
    /** The vehicle refuses the capture request outright. */
    val shouldFailSurroundView: Boolean = false,
    /**
     * The request is accepted but the images never arrive — the only way to
     * reach the surround-view screen's give-up path without waiting out a
     * real failure.
     */
    val shouldFailSurroundViewUpload: Boolean = false,
    // Command-specific failures
    val shouldFailLock: Boolean = false,
    val shouldFailUnlock: Boolean = false,
    val shouldFailStartClimate: Boolean = false,
    val shouldFailStopClimate: Boolean = false,
    val shouldFailStartCharge: Boolean = false,
    val shouldFailStopCharge: Boolean = false,
    // Custom error messages
    val customCredentialErrorMessage: String = "Invalid credentials",
    val customPinErrorMessage: String = "Invalid PIN",
) {
    fun shouldFailCommand(command: VehicleCommand): Boolean =
        when (command) {
            VehicleCommand.Lock -> shouldFailLock
            VehicleCommand.Unlock -> shouldFailUnlock
            is VehicleCommand.StartClimate -> shouldFailStartClimate
            VehicleCommand.StopClimate -> shouldFailStopClimate
            VehicleCommand.StartCharge -> shouldFailStartCharge
            VehicleCommand.StopCharge -> shouldFailStopCharge
            is VehicleCommand.SetTargetSoc -> false
        }
}
