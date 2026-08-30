package com.betterblue.app.ui.sheets

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.betterblue.app.data.db.entity.VehicleEntity
import com.betterblue.app.data.fake.DebugConfiguration

/**
 * Failure injection for a fake vehicle — how every error path gets
 * exercised without a real car refusing anything.
 */
@Composable
fun FakeVehicleConfigSheet(vehicle: VehicleEntity, viewModel: SheetsViewModel) {
    val config = remember(vehicle.debugConfigJson) { viewModel.debugConfiguration() }

    fun update(transform: (DebugConfiguration) -> DebugConfiguration) {
        viewModel.updateDebugConfiguration(transform(config))
    }

    SheetScaffold(title = "Debug") {
        Text(
            "These switches make the fake client fail on demand. They have no effect on real vehicles.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SheetSection("Authentication") {
            SwitchRow(
                label = "Fail credential validation",
                checked = config.shouldFailCredentialValidation,
                onCheckedChange = { v -> update { it.copy(shouldFailCredentialValidation = v) } },
            )
            SwitchRow(
                label = "Fail login",
                checked = config.shouldFailLogin,
                onCheckedChange = { v -> update { it.copy(shouldFailLogin = v) } },
            )
            SwitchRow(
                label = "Fail PIN validation",
                checked = config.shouldFailPinValidation,
                onCheckedChange = { v -> update { it.copy(shouldFailPinValidation = v) } },
            )
        }

        SheetSection("Fetching") {
            SwitchRow(
                label = "Fail vehicle fetch",
                checked = config.shouldFailVehicleFetch,
                onCheckedChange = { v -> update { it.copy(shouldFailVehicleFetch = v) } },
            )
            SwitchRow(
                label = "Fail status fetch",
                checked = config.shouldFailStatusFetch,
                onCheckedChange = { v -> update { it.copy(shouldFailStatusFetch = v) } },
            )
        }

        SheetSection("Commands") {
            SwitchRow(
                label = "Fail lock",
                checked = config.shouldFailLock,
                onCheckedChange = { v -> update { it.copy(shouldFailLock = v) } },
            )
            SwitchRow(
                label = "Fail unlock",
                checked = config.shouldFailUnlock,
                onCheckedChange = { v -> update { it.copy(shouldFailUnlock = v) } },
            )
            SwitchRow(
                label = "Fail start climate",
                checked = config.shouldFailStartClimate,
                onCheckedChange = { v -> update { it.copy(shouldFailStartClimate = v) } },
            )
            SwitchRow(
                label = "Fail stop climate",
                checked = config.shouldFailStopClimate,
                onCheckedChange = { v -> update { it.copy(shouldFailStopClimate = v) } },
            )
            SwitchRow(
                label = "Fail start charge",
                checked = config.shouldFailStartCharge,
                onCheckedChange = { v -> update { it.copy(shouldFailStartCharge = v) } },
            )
            SwitchRow(
                label = "Fail stop charge",
                checked = config.shouldFailStopCharge,
                onCheckedChange = { v -> update { it.copy(shouldFailStopCharge = v) } },
            )
        }

        SheetSection("Surround view") {
            SwitchRow(
                label = "Refuse capture request",
                checked = config.shouldFailSurroundView,
                onCheckedChange = { v -> update { it.copy(shouldFailSurroundView = v) } },
            )
            SwitchRow(
                label = "Accept but never upload",
                detail = "Reaches the six-minute give-up path without waiting out a real failure.",
                checked = config.shouldFailSurroundViewUpload,
                onCheckedChange = { v -> update { it.copy(shouldFailSurroundViewUpload = v) } },
            )
        }

        SheetSection("Error messages") {
            var credentialMessage by remember(vehicle.vin) {
                mutableStateOf(config.customCredentialErrorMessage)
            }
            var pinMessage by remember(vehicle.vin) { mutableStateOf(config.customPinErrorMessage) }

            OutlinedTextField(
                value = credentialMessage,
                onValueChange = {
                    credentialMessage = it
                    update { config -> config.copy(customCredentialErrorMessage = it) }
                },
                label = { Text("Credential error") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = pinMessage,
                onValueChange = {
                    pinMessage = it
                    update { config -> config.copy(customPinErrorMessage = it) }
                },
                label = { Text("PIN error") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
