package com.betterblue.app.ui.sheets

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.betterblue.kit.model.Distance
import com.betterblue.kit.model.Temperature

/**
 * The single host for every per-vehicle sheet. Routing through one sealed
 * `when` keeps the set exhaustive at compile time — adding a
 * [SheetRoute] case without a screen is a build error, not a blank sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSheetHost(
    route: SheetRoute?,
    distanceUnit: Distance.Units,
    temperatureUnit: Temperature.Units,
    onDismiss: () -> Unit,
    viewModel: SheetsViewModel = hiltViewModel(),
) {
    if (route == null) return

    val state by viewModel.state.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(route) { viewModel.load(route.vin) }

    // An action that finishes the sheet's job (deleting an account, saving
    // charge limits) closes it rather than leaving a stale form open.
    LaunchedEffect(state.dismissed) { if (state.dismissed) onDismiss() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        val vehicle = state.vehicle

        when (route) {
            is SheetRoute.VehicleInfo -> {
                vehicle?.let { VehicleInfoSheet(it, viewModel) }
            }

            is SheetRoute.AccountInfo -> {
                val account by produceAccount(viewModel, route.accountId)
                account?.let { AccountInfoSheet(it, state, viewModel) }
            }

            is SheetRoute.ClimateSettings -> {
                vehicle?.let {
                    ClimateSettingsSheet(it, state.presets, temperatureUnit, viewModel)
                }
            }

            is SheetRoute.ChargeLimits -> {
                vehicle?.let { ChargeLimitsSheet(it, viewModel, state.isBusy) }
            }

            is SheetRoute.TripDetails -> {
                TripDetailsSheet(route.vin, distanceUnit)
            }

            is SheetRoute.SurroundView -> {
                SurroundViewSheet(route.vin)
            }

            is SheetRoute.HttpLogs -> {
                HttpLogsSheet(route.vin)
            }

            is SheetRoute.FakeVehicleConfig -> {
                vehicle?.let { FakeVehicleConfigSheet(it, viewModel) }
            }
        }
    }
}
