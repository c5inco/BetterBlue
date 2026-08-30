package com.betterblue.app.ui.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.betterblue.app.data.db.entity.VehicleEntity
import kotlin.math.roundToInt

/**
 * AC/DC target state of charge. The vehicles accept 50–100% in 10% steps,
 * which is what the slider's step count encodes.
 */
@Composable
fun ChargeLimitsSheet(vehicle: VehicleEntity, viewModel: SheetsViewModel, isBusy: Boolean) {
    val ev = vehicle.evStatus

    var ac by remember(vehicle.vin) { mutableFloatStateOf((ev?.targetSocAC ?: 80.0).toFloat()) }
    var dc by remember(vehicle.vin) { mutableFloatStateOf((ev?.targetSocDC ?: 80.0).toFloat()) }

    SheetScaffold(title = "Charge Limits") {
        LimitSlider("AC charging", ac) { ac = it }
        LimitSlider("DC fast charging", dc) { dc = it }

        Text(
            "Limits apply the next time the vehicle starts charging.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(
            onClick = { viewModel.setTargetSoc(vehicle.vin, ac.roundToInt(), dc.roundToInt()) },
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (isBusy) "Saving…" else "Save")
        }
    }
}

@Composable
private fun LimitSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text("${value.roundToInt()}%", style = MaterialTheme.typography.bodyLarge)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = 50f..100f,
            // 50→100 in 10% steps is five intervals, so four interior stops.
            steps = 4,
        )
    }
}
