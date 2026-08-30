package com.betterblue.app.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.betterblue.app.data.db.entity.VehicleEntity
import com.betterblue.app.data.repo.effectiveFuelType
import com.betterblue.app.data.repo.toRaw
import com.betterblue.app.ui.theme.CustomColors
import com.betterblue.kit.model.FuelType

/** Per-vehicle appearance and behavior settings. */
@Composable
fun VehicleInfoSheet(vehicle: VehicleEntity, viewModel: SheetsViewModel) {
    SheetScaffold(title = "Vehicle") {
        SheetSection("Name") {
            var name by remember(vehicle.vin) { mutableStateOf(vehicle.customName ?: "") }
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    viewModel.setCustomName(it)
                },
                label = { Text("Custom name") },
                placeholder = { Text(vehicle.model) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SheetSection("Visibility") {
            SwitchRow(
                label = "Hide this vehicle",
                detail = "Hidden vehicles stay in the account but are not shown on the map.",
                checked = vehicle.isHidden,
                onCheckedChange = viewModel::setHidden,
            )
        }

        SheetSection("Colors") {
            for (slot in AccentSlot.entries) {
                AccentColorRow(
                    slot = slot,
                    selected = vehicle.accentName(slot),
                    onSelect = { viewModel.setAccentColor(slot, it) },
                )
            }
        }

        if (vehicle.effectiveFuelType.hasElectricCapability) {
            SheetSection("Charge port") {
                val ports = listOf("CCS1", "CCS2", "NACS")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ports.forEachIndexed { index, port ->
                        SegmentedButton(
                            selected = vehicle.chargePortType == port,
                            onClick = { viewModel.setChargePortType(port) },
                            shape = SegmentedButtonDefaults.itemShape(index, ports.size),
                        ) { Text(port) }
                    }
                }
                Text(
                    "Only affects which plug icon is shown.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SheetSection("Powertrain") {
            // The vehicle-list endpoints don't always report a reliable
            // powertrain, and the self-heal can be fooled by a mis-shaped
            // status payload — this pins it.
            val options =
                listOf<Pair<String, FuelType?>>(
                    "Automatic" to null,
                    "Gas" to FuelType.GAS,
                    "Electric" to FuelType.ELECTRIC,
                    "Plug-in hybrid" to FuelType.PHEV,
                )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, (label, type) ->
                    SegmentedButton(
                        selected = vehicle.fuelTypeOverrideRaw == type?.toRaw(),
                        onClick = { viewModel.setFuelTypeOverride(type) },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    ) { Text(label) }
                }
            }
            Text(
                "Automatic infers the powertrain from what the vehicle reports.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SheetSection("Controls") {
            SwitchRow(
                label = "Seat heat controls",
                detail = "Always shown on newer vehicles; enable here for older ones.",
                checked = vehicle.enableSeatHeatControls,
                onCheckedChange = viewModel::setSeatHeatControls,
            )
            TriStateRow(
                label = "Climate duration picker",
                value = vehicle.showClimateDurationOverride,
                onChange = viewModel::setClimateDurationOverride,
            )
            TriStateRow(
                label = "Surround view",
                value = vehicle.surroundViewOverride,
                onChange = viewModel::setSurroundViewOverride,
            )
        }

        SheetSection("Details") {
            InfoRow("VIN", vehicle.vin)
            InfoRow("Model", vehicle.model)
            InfoRow("Generation", vehicle.generation.toString())
        }
    }
}

private fun VehicleEntity.accentName(slot: AccentSlot): String? =
    when (slot) {
        AccentSlot.PRIMARY -> primaryColorName
        AccentSlot.CHARGING -> chargingColorName
        AccentSlot.GAS -> gasColorName
        AccentSlot.LOCK -> lockColorName
        AccentSlot.UNLOCK -> unlockColorName
        AccentSlot.START_CLIMATE -> startClimateColorName
        AccentSlot.STOP -> stopColorName
    }

@Composable
private fun AccentColorRow(slot: AccentSlot, selected: String?, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(slot.label, style = MaterialTheme.typography.bodyMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(CustomColors.palette, key = { it.name }) { option ->
                val isSelected = (selected ?: slot.default) == option.name
                Box(
                    color = option.color,
                    selected = isSelected,
                    onClick = { onSelect(option.name) },
                )
            }
        }
    }
}

@Composable
private fun Box(color: Color, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        modifier =
            Modifier
                .size(28.dp)
                .background(color, CircleShape)
                .border(
                    width = if (selected) 3.dp else 0.dp,
                    color = MaterialTheme.colorScheme.onSurface,
                    shape = CircleShape,
                ).clickable(onClick = onClick),
    )
}

@Composable
internal fun SwitchRow(
    label: String,
    detail: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Automatic / On / Off, mapping to null / true / false. */
@Composable
private fun TriStateRow(label: String, value: Boolean?, onChange: (Boolean?) -> Unit) {
    val options = listOf<Pair<String, Boolean?>>("Auto" to null, "On" to true, "Off" to false)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (text, option) ->
                SegmentedButton(
                    selected = value == option,
                    onClick = { onChange(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) { Text(text) }
            }
        }
    }
}

@Composable
internal fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
