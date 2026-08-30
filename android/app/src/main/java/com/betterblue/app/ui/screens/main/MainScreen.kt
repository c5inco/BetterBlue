package com.betterblue.app.ui.screens.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.betterblue.app.ui.navigation.DeepLinkAction
import com.betterblue.app.ui.sheets.AppSheetHost

@Composable
fun MainScreen(
    deepLinkAction: DeepLinkAction?,
    onOpenSettings: () -> Unit,
    onAddAccount: () -> Unit,
    viewModel: MainViewModel = hiltViewModel(),
) {
    val vehicles by viewModel.visibleVehicles.collectAsState()
    val state by viewModel.state.collectAsState()
    val distanceUnit by viewModel.distanceUnit.collectAsState()
    val temperatureUnit by viewModel.temperatureUnit.collectAsState()

    LaunchedEffect(deepLinkAction, vehicles.isNotEmpty()) {
        val action = deepLinkAction ?: return@LaunchedEffect
        if (vehicles.none { it.vin == action.vin }) return@LaunchedEffect
        viewModel.handleDeepLink(
            vin = action.vin,
            startClimate = action is DeepLinkAction.StartClimate,
            startCharge = action is DeepLinkAction.StartCharge,
        )
    }

    // The 60-second foreground poll runs only while the screen is started;
    // repeatOnLifecycle cancels and restarts it across background trips.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.runPollLoop()
        }
    }

    if (vehicles.isEmpty()) {
        EmptyState(onAddAccount = onAddAccount, onOpenSettings = onOpenSettings)
        return
    }

    val pagerState = rememberPagerState(pageCount = { vehicles.size })

    // Selecting follows the pager so the map and the poll target stay in sync.
    LaunchedEffect(pagerState.currentPage, vehicles) {
        vehicles.getOrNull(pagerState.currentPage)?.let { viewModel.selectVehicle(it.vin) }
    }

    Box(Modifier.fillMaxSize()) {
        VehicleMap(
            vehicle = vehicles.getOrNull(pagerState.currentPage),
            modifier = Modifier.fillMaxSize(),
        )

        FilledTonalIconButton(
            onClick = onOpenSettings,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
        ) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings")
        }

        PersistentSheet(
            detent =
                SheetDetent.fromStorageKey(
                    state.detents[vehicles.getOrNull(pagerState.currentPage)?.vin],
                ),
            onDetentChanged = { detent ->
                vehicles.getOrNull(pagerState.currentPage)?.let {
                    viewModel.setDetent(it.vin, detent.storageKey)
                }
            },
        ) { detent ->
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                val vehicle = vehicles[page]
                VehicleCard(
                    vehicle = vehicle,
                    detent = detent,
                    action = state.actions[vehicle.vin] ?: VehicleActionState(),
                    distanceUnit = distanceUnit,
                    temperatureUnit = temperatureUnit,
                    isRefreshing = state.isRefreshing && state.selectedVin == vehicle.vin,
                    onRefresh = { viewModel.refresh(vehicle.vin) },
                    onToggleLock = { viewModel.toggleLock(vehicle) },
                    onToggleClimate = { viewModel.toggleClimate(vehicle) },
                    onToggleCharge = { viewModel.toggleCharge(vehicle) },
                    onOpenSettings = onOpenSettings,
                    onOpenSheet = viewModel::showSheet,
                    onDismissError = { viewModel.dismissActionState(vehicle.vin) },
                )
            }
        }

        AppSheetHost(
            route = state.sheet,
            distanceUnit = distanceUnit,
            temperatureUnit = temperatureUnit,
            onDismiss = viewModel::dismissSheet,
        )
    }
}

@Composable
private fun EmptyState(onAddAccount: () -> Unit, onOpenSettings: () -> Unit) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth().padding(32.dp),
        ) {
            Icon(
                Icons.Filled.DirectionsCar,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text("No vehicles yet", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Add your Hyundai BlueLink or Kia Connect account to get started.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onAddAccount) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("Add Account", modifier = Modifier.padding(start = 8.dp))
            }
            Text(
                "Settings",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp).clickable(onClick = onOpenSettings),
            )
        }
    }
}
