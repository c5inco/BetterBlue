package com.betterblue.kit.fake

import com.betterblue.kit.model.SurroundViewCapture
import com.betterblue.kit.model.Vehicle
import com.betterblue.kit.model.VehicleCommand
import com.betterblue.kit.model.VehicleStatus

/**
 * Supplies vehicles, state, and failure injection for [FakeApiClient]. Mirrors
 * the Swift `FakeVehicleProvider` protocol: the host app backs this with its
 * persistence layer so fake vehicle state survives across launches; tests back
 * it with in-memory fixtures.
 */
interface FakeVehicleProvider {
    suspend fun getFakeVehicles(username: String, accountId: String): List<Vehicle>

    suspend fun getVehicleStatus(vin: String, accountId: String): VehicleStatus

    suspend fun executeCommand(command: VehicleCommand, vin: String, accountId: String)

    // Debug configuration checks

    suspend fun shouldFailCredentialValidation(accountId: String): Boolean

    suspend fun shouldFailLogin(accountId: String): Boolean

    suspend fun shouldFailVehicleFetch(accountId: String): Boolean

    suspend fun shouldFailStatusFetch(vin: String, accountId: String): Boolean

    suspend fun shouldFailPinValidation(vin: String, accountId: String): Boolean

    suspend fun shouldFailCommand(command: VehicleCommand, vin: String, accountId: String): Boolean

    suspend fun getCustomCredentialErrorMessage(accountId: String): String

    suspend fun getCustomPinErrorMessage(vin: String, accountId: String): String

    // Surround view — defaulted so existing providers keep conforming without
    // changes (mirrors the Swift protocol extension).

    suspend fun requestSurroundViewCapture(vin: String, accountId: String) {}

    suspend fun getSurroundViewCaptures(vin: String, accountId: String): List<SurroundViewCapture> = emptyList()
}
