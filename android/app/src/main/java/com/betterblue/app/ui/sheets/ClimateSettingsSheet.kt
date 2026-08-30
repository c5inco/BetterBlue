package com.betterblue.app.ui.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.betterblue.app.data.db.entity.ClimatePresetEntity
import com.betterblue.app.data.db.entity.VehicleEntity
import com.betterblue.app.data.repo.effectiveFuelType
import com.betterblue.kit.model.ClimateOptions
import com.betterblue.kit.model.HvacTemperatureTable
import com.betterblue.kit.model.Temperature
import kotlin.math.roundToInt

/**
 * Climate presets. Editing writes straight through so the selected preset is
 * always what a Start Climate command will use.
 */
@Composable
fun ClimateSettingsSheet(
    vehicle: VehicleEntity,
    presets: List<ClimatePresetEntity>,
    temperatureUnit: Temperature.Units,
    viewModel: SheetsViewModel,
) {
    var selectedIndex by remember(vehicle.vin, presets.size) {
        mutableIntStateOf(presets.indexOfFirst { it.isSelected }.coerceAtLeast(0))
    }

    SheetScaffold(title = "Climate") {
        if (presets.isEmpty()) {
            Text(
                "No presets yet. Add one to control temperature, defrost and seat heat.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                presets.forEachIndexed { index, preset ->
                    FilterChip(
                        selected = index == selectedIndex,
                        onClick = { selectedIndex = index },
                        label = { Text(preset.name) },
                        leadingIcon =
                            if (preset.isSelected) {
                                { Icon(Icons.Filled.Check, contentDescription = "Active preset") }
                            } else {
                                null
                            },
                    )
                }
            }

            presets.getOrNull(selectedIndex)?.let { preset ->
                PresetEditor(
                    preset = preset,
                    vehicle = vehicle,
                    temperatureUnit = temperatureUnit,
                    onChange = viewModel::updatePreset,
                    onSelect = { viewModel.selectPreset(preset) },
                    onDelete = { viewModel.deletePreset(preset) },
                )
            }
        }

        TextButton(onClick = { viewModel.addPreset(vehicle.vin) }) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text("Add preset", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun PresetEditor(
    preset: ClimatePresetEntity,
    vehicle: VehicleEntity,
    temperatureUnit: Temperature.Units,
    onChange: (ClimatePresetEntity) -> Unit,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    val options = preset.climateOptions

    fun update(transform: (ClimateOptions) -> ClimateOptions) {
        onChange(preset.copy(climateOptions = transform(options)))
    }

    SheetSection("Preset") {
        OutlinedTextField(
            value = preset.name,
            onValueChange = { onChange(preset.copy(name = it)) },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    SheetSection("Temperature") {
        TemperatureControl(options.temperature, temperatureUnit) { newTemp ->
            update { it.copy(temperature = newTemp) }
        }
    }

    SheetSection("Options") {
        SwitchRow(
            label = "Climate control",
            checked = options.climate,
            onCheckedChange = { value -> update { it.copy(climate = value) } },
        )
        SwitchRow(
            label = "Front defrost",
            checked = options.defrost,
            onCheckedChange = { value -> update { it.copy(defrost = value) } },
        )
        SwitchRow(
            label = "Rear defrost",
            checked = options.rearDefrost,
            onCheckedChange = { value -> update { it.copy(rearDefrost = value) } },
        )
        LevelRow("Steering wheel heat", options.steeringWheel) { level ->
            update { it.copy(steeringWheel = level) }
        }

        if (vehicle.generation >= 3 || vehicle.enableSeatHeatControls) {
            LevelRow("Driver seat", options.frontLeftSeat) { l -> update { it.copy(frontLeftSeat = l) } }
            LevelRow("Passenger seat", options.frontRightSeat) { l -> update { it.copy(frontRightSeat = l) } }
            LevelRow("Rear left seat", options.rearLeftSeat) { l -> update { it.copy(rearLeftSeat = l) } }
            LevelRow("Rear right seat", options.rearRightSeat) { l -> update { it.copy(rearRightSeat = l) } }
        }

        // The duration field is ignored by the USA APIs on older vehicles,
        // so it follows the same override the vehicle sheet exposes.
        val showDuration = vehicle.showClimateDurationOverride ?: (vehicle.generation >= 3)
        if (showDuration) {
            Column {
                Text("Run for ${options.duration} min", style = MaterialTheme.typography.bodyLarge)
                Slider(
                    value = options.duration.toFloat(),
                    onValueChange = { value -> update { it.copy(duration = value.roundToInt()) } },
                    valueRange = 5f..30f,
                    steps = 4,
                )
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!preset.isSelected) {
            TextButton(onClick = onSelect) { Text("Use this preset") }
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete preset")
        }
    }
}

/**
 * Temperature stepper. Values are snapped through the car's HVAC lookup
 * table rather than converted linearly — an off-grid value is silently
 * rejected by the vehicle.
 */
@Composable
private fun TemperatureControl(
    temperature: Temperature,
    displayUnit: Temperature.Units,
    onChange: (Temperature) -> Unit,
) {
    val shown =
        Temperature.hvacConvert(
            temperature.value,
            temperature.units,
            displayUnit,
            HvacTemperatureTable.STANDARD,
        )
    val range = displayUnit.hvacRange
    val step = if (displayUnit == Temperature.Units.CELSIUS) 0.5f else 1f

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            displayUnit.format(shown, displayUnit),
            style = MaterialTheme.typography.headlineMedium,
        )
        Slider(
            value = shown.toFloat(),
            onValueChange = { raw ->
                val snapped = (raw / step).roundToInt() * step
                onChange(Temperature(displayUnit, snapped.toDouble()))
            },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
        )
    }
}

/** Off / 1 / 2 / 3 heat level. */
@Composable
private fun LevelRow(label: String, level: Int, onChange: (Int) -> Unit) {
    val labels = listOf("Off", "1", "2", "3")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { index, text ->
                SegmentedButton(
                    selected = level == index,
                    onClick = { onChange(index) },
                    shape = SegmentedButtonDefaults.itemShape(index, labels.size),
                ) { Text(text) }
            }
        }
    }
}
