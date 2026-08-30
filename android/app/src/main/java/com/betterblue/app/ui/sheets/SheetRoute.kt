package com.betterblue.app.ui.sheets

/**
 * The modal sheets reachable from a vehicle card. A single sealed route +
 * one host composable is the Compose analog of the iOS
 * `VehicleSheetPresentation` enum router: one source of truth, and the
 * `when` stays exhaustive at compile time.
 */
sealed interface SheetRoute {
    val vin: String

    data class VehicleInfo(override val vin: String) : SheetRoute
    data class AccountInfo(override val vin: String, val accountId: String) : SheetRoute
    data class ClimateSettings(override val vin: String) : SheetRoute
    data class ChargeLimits(override val vin: String) : SheetRoute
    data class TripDetails(override val vin: String) : SheetRoute
    data class SurroundView(override val vin: String) : SheetRoute
    data class HttpLogs(override val vin: String) : SheetRoute
    data class FakeVehicleConfig(override val vin: String) : SheetRoute
}
