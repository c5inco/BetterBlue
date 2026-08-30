package com.betterblue.app.ui.screens.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.betterblue.app.data.db.entity.VehicleEntity
import com.betterblue.app.data.repo.displayName
import com.betterblue.app.data.repo.effectiveFuelType
import com.betterblue.app.ui.common.ErrorDetailsCard
import com.betterblue.app.ui.sheets.SheetRoute
import com.betterblue.app.ui.theme.VehicleAccentColors
import com.betterblue.kit.model.Distance
import com.betterblue.kit.model.Temperature
import java.time.Duration
import java.time.Instant

/**
 * The vehicle card that lives inside the persistent sheet. Collapsed shows
 * identity, range and the primary actions; expanded adds the detail rows.
 */
@Composable
fun VehicleCard(
    vehicle: VehicleEntity,
    detent: SheetDetent,
    action: VehicleActionState,
    distanceUnit: Distance.Units,
    temperatureUnit: Temperature.Units,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onToggleLock: () -> Unit,
    onToggleClimate: () -> Unit,
    onToggleCharge: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSheet: (SheetRoute) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors =
        VehicleAccentColors.resolve(
            vehicle.primaryColorName,
            vehicle.chargingColorName,
            vehicle.gasColorName,
            vehicle.lockColorName,
            vehicle.unlockColorName,
            vehicle.startClimateColorName,
            vehicle.stopColorName,
        )

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    vehicle.displayName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    lastUpdatedLabel(vehicle.lastUpdated),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRefresh, enabled = !isRefreshing) {
                if (isRefreshing) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = colors.primary)
                }
            }
            VehicleMenu(vehicle = vehicle, onOpenSheet = onOpenSheet, onOpenSettings = onOpenSettings)
        }

        // Range
        vehicle.evStatus?.let { ev ->
            RangeRow(
                label = if (ev.charging) "Charging" else "Battery",
                percentage = ev.evRange.percentage,
                rangeText =
                    ev.evRange.range.units
                        .format(ev.evRange.range.length, distanceUnit),
                color = colors.charging,
            )
            if (ev.charging && ev.chargeTimeSeconds > 0) {
                Text(
                    "${ev.chargeTimeSeconds / 60} min to ${ev.currentTargetSoc?.toInt() ?: 100}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        vehicle.gasRange?.let { gas ->
            RangeRow(
                label = "Fuel",
                percentage = gas.percentage,
                rangeText = gas.range.units.format(gas.range.length, distanceUnit),
                color = colors.gas,
            )
        }

        // Command state: an "awaiting confirmation" outcome is a soft chip,
        // never an error card — the command WAS accepted upstream.
        when {
            action.inProgress -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(action.statusMessage ?: "Working…", style = MaterialTheme.typography.labelLarge)
                }
            }

            action.awaitingConfirmation -> {
                AssistChip(
                    onClick = onDismissError,
                    label = { Text("Awaiting confirmation") },
                )
            }

            action.error != null -> {
                ErrorDetailsCard(action.error)
            }
        }

        // Primary actions
        val locked = vehicle.lockStatus == "locked"
        val climateOn = vehicle.climateStatus?.airControlOn == true
        val charging = vehicle.evStatus?.charging == true

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ActionButton(
                label = if (locked) "Unlock" else "Lock",
                icon = if (locked) Icons.Filled.LockOpen else Icons.Filled.Lock,
                color = if (locked) colors.unlock else colors.lock,
                enabled = !action.inProgress,
                onClick = onToggleLock,
                modifier = Modifier.weight(1f),
            )
            ActionButton(
                label = if (climateOn) "Stop" else "Climate",
                icon = Icons.Filled.AcUnit,
                color = if (climateOn) colors.stop else colors.startClimate,
                enabled = !action.inProgress,
                onClick = onToggleClimate,
                modifier = Modifier.weight(1f),
            )
            if (vehicle.effectiveFuelType.hasElectricCapability) {
                ActionButton(
                    label = if (charging) "Stop" else "Charge",
                    icon = Icons.Filled.Bolt,
                    color = if (charging) colors.stop else colors.charging,
                    enabled = !action.inProgress,
                    onClick = onToggleCharge,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Below the fold
        if (detent == SheetDetent.EXPANDED) {
            DetailRows(vehicle, distanceUnit, temperatureUnit)
        }
    }
}

@Composable
private fun RangeRow(label: String, percentage: Double, rangeText: String, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text("${percentage.toInt()}% · $rangeText", style = MaterialTheme.typography.labelLarge)
        }
        LinearProgressIndicator(
            progress = { (percentage / 100.0).toFloat().coerceIn(0f, 1f) },
            color = color,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = color),
        modifier = modifier,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}

@Composable
private fun DetailRows(
    vehicle: VehicleEntity,
    distanceUnit: Distance.Units,
    temperatureUnit: Temperature.Units,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DetailRow("Odometer", vehicle.odometer.units.format(vehicle.odometer.length, distanceUnit))
        vehicle.climateStatus?.let {
            DetailRow(
                "Cabin temperature",
                it.temperature.units.format(it.temperature.value, temperatureUnit),
            )
        }
        vehicle.battery12V?.let { DetailRow("12V battery", "$it%") }
        vehicle.doorOpen?.let { DetailRow("Doors", it.openDoorsDescription) }
        vehicle.trunkOpen?.let { DetailRow("Trunk", if (it) "Open" else "Closed") }
        vehicle.hoodOpen?.let { DetailRow("Hood", if (it) "Open" else "Closed") }
        vehicle.tirePressureWarning?.let { DetailRow("Tire pressure", it.warningDescription) }
        DetailRow("VIN", vehicle.vin)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun lastUpdatedLabel(lastUpdatedMillis: Long?): String {
    val millis = lastUpdatedMillis ?: return "Never updated"
    val minutes = Duration.between(Instant.ofEpochMilli(millis), Instant.now()).toMinutes()
    return when {
        minutes < 1 -> "Updated just now"
        minutes < 60 -> "Updated $minutes min ago"
        minutes < 60 * 24 -> "Updated ${minutes / 60} hr ago"
        else -> "Updated ${minutes / (60 * 24)} d ago"
    }
}

/**
 * The card's overflow menu — the Android analog of the iOS long-press
 * context menu on the sheet header. Entries are gated the same way: charge
 * limits and trips only for vehicles with a battery, surround view behind
 * the per-vehicle override, fake config only for fake vehicles.
 */
@Composable
private fun VehicleMenu(
    vehicle: VehicleEntity,
    onOpenSheet: (SheetRoute) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            fun item(label: String, route: SheetRoute) {
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        onOpenSheet(route)
                    },
                )
            }

            item("Vehicle settings", SheetRoute.VehicleInfo(vehicle.vin))
            item("Climate presets", SheetRoute.ClimateSettings(vehicle.vin))

            if (vehicle.effectiveFuelType.hasElectricCapability) {
                item("Charge limits", SheetRoute.ChargeLimits(vehicle.vin))
                item("Trips", SheetRoute.TripDetails(vehicle.vin))
            }

            // Surround view is a newer-generation feature; an unknown
            // generation is assumed capable rather than hidden on a guess.
            val showsSurroundView =
                vehicle.surroundViewOverride ?: (vehicle.generation == 0 || vehicle.generation >= 3)
            if (showsSurroundView) {
                item("Surround view", SheetRoute.SurroundView(vehicle.vin))
            }

            item("Account", SheetRoute.AccountInfo(vehicle.vin, vehicle.accountId))
            item("HTTP logs", SheetRoute.HttpLogs(vehicle.vin))

            if (vehicle.debugConfigJson != null) {
                item("Debug configuration", SheetRoute.FakeVehicleConfig(vehicle.vin))
            }

            DropdownMenuItem(
                text = { Text("App settings") },
                onClick = {
                    expanded = false
                    onOpenSettings()
                },
            )
        }
    }
}
