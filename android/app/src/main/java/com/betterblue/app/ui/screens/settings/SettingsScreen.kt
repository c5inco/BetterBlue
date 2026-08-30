package com.betterblue.app.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.betterblue.app.BuildConfig
import com.betterblue.app.data.repo.AccountRepository
import com.betterblue.app.data.repo.displayName
import com.betterblue.kit.model.Distance
import com.betterblue.kit.model.Temperature

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onAddAccount: () -> Unit,
    onOpenAccount: (String) -> Unit,
    onOpenVehicle: (String) -> Unit,
    onOpenHttpLogs: () -> Unit,
    onOpenTroubleshooting: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val accounts by viewModel.accounts.collectAsState()
    val vehicles by viewModel.vehicles.collectAsState()
    val distanceUnit by viewModel.distanceUnit.collectAsState()
    val temperatureUnit by viewModel.temperatureUnit.collectAsState()
    val debugMode by viewModel.debugMode.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item { SectionLabel("Vehicles") }
            items(vehicles, key = { it.vin }) { vehicle ->
                ListItem(
                    headlineContent = { Text(vehicle.displayName) },
                    supportingContent = { Text(vehicle.vin) },
                    leadingContent = { Icon(Icons.Filled.DirectionsCar, contentDescription = null) },
                    trailingContent = {
                        if (vehicle.isHidden) {
                            Text("Hidden", style = MaterialTheme.typography.labelMedium)
                        }
                    },
                    modifier = Modifier.clickable { onOpenVehicle(vehicle.vin) },
                )
            }

            item { SectionLabel("Accounts") }
            items(accounts, key = { it.id }) { account ->
                ListItem(
                    headlineContent = { Text(account.username) },
                    supportingContent = {
                        Text(
                            "${AccountRepository.brandFromRaw(account.brand).displayName} · " +
                                AccountRepository.regionFromRaw(account.region).displayName,
                        )
                    },
                    leadingContent = { Icon(Icons.Filled.Person, contentDescription = null) },
                    modifier = Modifier.clickable { onOpenAccount(account.id) },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Add Account") },
                    leadingContent = { Icon(Icons.Filled.Add, contentDescription = null) },
                    modifier = Modifier.clickable(onClick = onAddAccount),
                )
            }

            item {
                SectionLabel("Units")
                UnitPickerRow(
                    label = "Distance",
                    options = listOf("Miles", "Kilometers"),
                    selectedIndex = if (distanceUnit == Distance.Units.MILES) 0 else 1,
                    onSelect = {
                        viewModel.setDistanceUnit(
                            if (it == 0) Distance.Units.MILES else Distance.Units.KILOMETERS,
                        )
                    },
                )
                UnitPickerRow(
                    label = "Temperature",
                    options = listOf("°F", "°C"),
                    selectedIndex = if (temperatureUnit == Temperature.Units.FAHRENHEIT) 0 else 1,
                    onSelect = {
                        viewModel.setTemperatureUnit(
                            if (it == 0) Temperature.Units.FAHRENHEIT else Temperature.Units.CELSIUS,
                        )
                    },
                )
            }

            item {
                SectionLabel("Debug")
                ListItem(
                    headlineContent = { Text("Debug Mode") },
                    supportingContent = { Text("Records HTTP logs for troubleshooting") },
                    trailingContent = {
                        Switch(checked = debugMode, onCheckedChange = viewModel::setDebugMode)
                    },
                )
                if (debugMode) {
                    ListItem(
                        headlineContent = { Text("HTTP Logs") },
                        leadingContent = { Icon(Icons.Filled.ReceiptLong, contentDescription = null) },
                        modifier = Modifier.clickable(onClick = onOpenHttpLogs),
                    )
                }
            }

            item {
                SectionLabel("About")
                ListItem(
                    headlineContent = { Text("Troubleshooting") },
                    leadingContent = {
                        Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null)
                    },
                    modifier = Modifier.clickable(onClick = onOpenTroubleshooting),
                )
                ListItem(
                    headlineContent = { Text("Version") },
                    trailingContent = { Text(BuildConfig.VERSION_NAME) },
                )
                HorizontalDivider()
                Text(
                    "BetterBlue is fully open source: github.com/schmidtwmark/BetterBlue",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun UnitPickerRow(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column { Text(label, style = MaterialTheme.typography.bodyLarge) }
        SingleChoiceSegmentedButtonRow {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                ) {
                    Text(option)
                }
            }
        }
    }
}
