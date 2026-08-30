package com.betterblue.kit.fake

import com.betterblue.kit.ApiClient
import com.betterblue.kit.ApiClientConfig
import com.betterblue.kit.ApiException
import com.betterblue.kit.OptionalApiFeature
import com.betterblue.kit.log.BBLogCategory
import com.betterblue.kit.log.BBLogger
import com.betterblue.kit.model.AuthToken
import com.betterblue.kit.model.SurroundViewCapture
import com.betterblue.kit.model.Vehicle
import com.betterblue.kit.model.VehicleCommand
import com.betterblue.kit.model.VehicleStatus
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Fake API client for testing and development. Implements [ApiClient]
 * directly — no HTTP, no [com.betterblue.kit.ApiClientBase] — delegating all
 * data and failure injection to a [FakeVehicleProvider]. Mirrors the Swift
 * `FakeAPIClient`.
 */
class FakeApiClient(
    configuration: ApiClientConfig,
    private val vehicleProvider: FakeVehicleProvider,
) : ApiClient {
    private val username: String = configuration.username
    private val accountId: String = configuration.accountId

    init {
        BBLogger.info(
            BBLogCategory.FAKE_API,
            "Initialized for user '${configuration.username}' with custom vehicle provider",
        )
    }

    // ApiClient implementation

    override suspend fun login(): AuthToken {
        // Check for debug credential validation failure across all fake
        // vehicles for this account
        if (vehicleProvider.shouldFailCredentialValidation(accountId)) {
            BBLogger.warning(BBLogCategory.FAKE_API, "Debug: Simulating credential validation failure")
            val message = vehicleProvider.getCustomCredentialErrorMessage(accountId)
            throw ApiException.invalidCredentials(message, apiName = "FakeAPI")
        }

        // Check for debug login failure
        if (vehicleProvider.shouldFailLogin(accountId)) {
            BBLogger.warning(BBLogCategory.FAKE_API, "Debug: Simulating login failure")
            throw ApiException.logError("Debug: Simulated login failure", code = 500, apiName = "FakeAPI")
        }

        BBLogger.info(BBLogCategory.FAKE_API, "Login successful for user '$username'")
        return AuthToken(
            accessToken = "fake_access_token_${UUID.randomUUID()}",
            refreshToken = "fake_refresh_token_${UUID.randomUUID()}",
            expiresAt = Instant.now().plus(Duration.ofSeconds(3600)),
        )
    }

    override suspend fun fetchVehicles(authToken: AuthToken): List<Vehicle> {
        // Check for debug vehicle fetch failure
        if (vehicleProvider.shouldFailVehicleFetch(accountId)) {
            BBLogger.warning(BBLogCategory.FAKE_API, "Debug: Simulating vehicle fetch failure")
            throw ApiException.logError("Debug: Simulated vehicle fetch failure", code = 500, apiName = "FakeAPI")
        }

        BBLogger.info(BBLogCategory.FAKE_API, "Fetching vehicles for user '$username'...")
        val vehicles = vehicleProvider.getFakeVehicles(username, accountId)
        val vinsList = vehicles.joinToString(", ") { it.vin }
        BBLogger.info(
            BBLogCategory.FAKE_API,
            "Fetched ${vehicles.size} fake vehicles for user '$username': [$vinsList]",
        )
        return vehicles
    }

    override suspend fun fetchVehicleStatus(vehicle: Vehicle, authToken: AuthToken, cached: Boolean): VehicleStatus {
        // Check for debug status fetch failure
        if (vehicleProvider.shouldFailStatusFetch(vehicle.vin, accountId)) {
            BBLogger.warning(BBLogCategory.FAKE_API, "Debug: Simulating status fetch failure")
            throw ApiException.logError("Debug: Simulated status fetch failure", code = 500, apiName = "FakeAPI")
        }

        val status = vehicleProvider.getVehicleStatus(vehicle.vin, accountId)
        BBLogger.info(
            BBLogCategory.FAKE_API,
            "Fetched vehicle status for fake vehicle '${vehicle.vin}' (cached: $cached)",
        )
        return status
    }

    override suspend fun sendCommand(vehicle: Vehicle, command: VehicleCommand, authToken: AuthToken) {
        // Check for debug PIN validation failure
        if (vehicleProvider.shouldFailPinValidation(vehicle.vin, accountId)) {
            BBLogger.warning(BBLogCategory.FAKE_API, "Debug: Simulating PIN validation failure")
            val errorMessage = vehicleProvider.getCustomPinErrorMessage(vehicle.vin, accountId)
            throw ApiException.invalidPin(errorMessage, apiName = "FakeAPI")
        }

        // Check for debug command-specific failures
        if (vehicleProvider.shouldFailCommand(command, vehicle.vin, accountId)) {
            val commandName = commandName(command)
            BBLogger.warning(BBLogCategory.FAKE_API, "Debug: Simulating $commandName failure")
            throw ApiException.logError("Debug: Simulated $commandName failure", code = 500, apiName = "FakeAPI")
        }

        vehicleProvider.executeCommand(command, vehicle.vin, accountId)
        BBLogger.info(BBLogCategory.FAKE_API, "Command completed successfully for fake vehicle '${vehicle.vin}'")
    }

    // Fake vehicles always claim surround view so the feature can be
    // exercised in development builds; the provider decides what imagery (if
    // any) comes back.
    override fun optionalFeaturesSupported(): Set<OptionalApiFeature> = setOf(OptionalApiFeature.SURROUND_VIEW)

    override suspend fun requestSurroundViewCapture(vehicle: Vehicle, authToken: AuthToken) {
        vehicleProvider.requestSurroundViewCapture(vehicle.vin, accountId)
        BBLogger.info(BBLogCategory.FAKE_API, "Requested surround view capture for fake vehicle '${vehicle.vin}'")
    }

    override suspend fun fetchSurroundViewCaptures(
        vehicle: Vehicle,
        authToken: AuthToken,
    ): List<SurroundViewCapture> {
        val captures = vehicleProvider.getSurroundViewCaptures(vehicle.vin, accountId)
        BBLogger.info(
            BBLogCategory.FAKE_API,
            "Fetched ${captures.size} surround view capture(s) for '${vehicle.vin}'",
        )
        return captures
    }

    companion object {
        /**
         * Lowercase-first command name for error messages, matching Swift's
         * `String(describing: command)` (e.g. "startClimate").
         */
        private fun commandName(command: VehicleCommand): String =
            command::class.simpleName?.replaceFirstChar { it.lowercase() } ?: "command"
    }
}
