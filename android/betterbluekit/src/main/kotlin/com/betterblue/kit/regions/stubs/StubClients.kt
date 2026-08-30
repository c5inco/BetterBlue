package com.betterblue.kit.regions.stubs

import com.betterblue.kit.ApiClient
import com.betterblue.kit.ApiClientBase
import com.betterblue.kit.ApiClientConfig
import com.betterblue.kit.ApiException
import com.betterblue.kit.model.AuthToken
import com.betterblue.kit.model.Vehicle
import com.betterblue.kit.model.VehicleCommand
import com.betterblue.kit.model.VehicleStatus

/**
 * Placeholder clients for regions BetterBlueKit does not implement yet. They
 * exist so the brand/region matrix is total and the UI can surface a clear
 * "not supported" message instead of a crash.
 */
internal abstract class UnsupportedRegionClient(config: ApiClientConfig) : ApiClientBase(config), ApiClient {
    private fun unsupported(): Nothing = throw ApiException.regionNotSupported(
        "$apiName is not yet supported",
        apiName = apiName,
    )

    override suspend fun login(): AuthToken = unsupported()
    override suspend fun fetchVehicles(authToken: AuthToken): List<Vehicle> = unsupported()
    override suspend fun fetchVehicleStatus(
        vehicle: Vehicle,
        authToken: AuthToken,
        cached: Boolean,
    ): VehicleStatus = unsupported()

    override suspend fun sendCommand(vehicle: Vehicle, command: VehicleCommand, authToken: AuthToken) = unsupported()
    override suspend fun registerDevice(): String? = unsupported()
}

internal class HyundaiAustraliaClient(config: ApiClientConfig) : UnsupportedRegionClient(config) {
    override val apiName: String get() = "HyundaiAustralia"
}

internal class KiaAustraliaClient(config: ApiClientConfig) : UnsupportedRegionClient(config) {
    override val apiName: String get() = "KiaAustralia"
}

internal class KiaCanadaClient(config: ApiClientConfig) : UnsupportedRegionClient(config) {
    override val apiName: String get() = "KiaCanada"
}
