package com.betterblue.app.ui.sheets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.betterblue.app.ui.common.ErrorDetailsCard
import com.betterblue.kit.model.Distance
import com.betterblue.kit.model.EVTripSummary
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** EV driving history with the per-trip energy breakdown. */
@Composable
fun TripDetailsSheet(
    vin: String,
    distanceUnit: Distance.Units,
    viewModel: TripDetailsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(vin) { viewModel.load(vin) }

    SheetScaffold(title = "Trips") {
        when {
            state.isLoading -> {
                Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            state.error != null -> {
                ErrorDetailsCard(state.error!!)
            }

            state.trips.isEmpty() -> {
                Text(
                    "No trip history reported for this vehicle.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                EnergyChart(state.trips, distanceUnit)
                for (trip in state.trips) {
                    TripRow(
                        trip = trip,
                        distanceUnit = distanceUnit,
                        expanded = state.expandedTripId == trip.id,
                        onClick = { viewModel.toggleTrip(trip.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/**
 * Total energy per trip as a simple bar chart. Hand-drawn on Canvas: the
 * data is a handful of bars, and a chart dependency would earn its weight
 * only with axes and interaction we don't need here.
 */
@Composable
private fun EnergyChart(trips: List<EVTripSummary>, distanceUnit: Distance.Units) {
    val maxEnergy = trips.maxOfOrNull { it.totalEnergyUsed } ?: return
    if (maxEnergy <= 0) return

    val barColor = MaterialTheme.colorScheme.primary
    val regenColor = MaterialTheme.colorScheme.tertiary

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Energy used per trip", style = MaterialTheme.typography.labelLarge)
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val count = trips.size
            val slot = size.width / count
            val barWidth = slot * 0.6f
            trips.forEachIndexed { index, trip ->
                val usedFraction = trip.totalEnergyUsed.toFloat() / maxEnergy
                val regenFraction = trip.regenEnergy.toFloat() / maxEnergy
                val x = index * slot + (slot - barWidth) / 2f

                drawRect(
                    color = barColor,
                    topLeft = Offset(x, size.height * (1f - usedFraction)),
                    size = Size(barWidth, size.height * usedFraction),
                )
                // Regen sits at the base of the same bar.
                drawRect(
                    color = regenColor,
                    topLeft = Offset(x, size.height * (1f - regenFraction)),
                    size = Size(barWidth, size.height * regenFraction),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LegendSwatch("Used", barColor)
            LegendSwatch("Regenerated", regenColor)
        }
    }
}

@Composable
private fun LegendSwatch(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).background(color, RoundedCornerShape(2.dp)))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun TripRow(
    trip: EVTripSummary,
    distanceUnit: Distance.Units,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                DATE_FORMAT.format(trip.startDate.atZone(ZoneId.systemDefault())),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                trip.distance.units.format(trip.distance.length, distanceUnit),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Text(
            "%.1f %s/kWh · %d Wh used".format(
                trip.efficiency(distanceUnit),
                distanceUnit.abbreviation,
                trip.totalEnergyUsed,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (expanded) {
            InfoRow("Drivetrain", "${trip.drivetrainEnergy} Wh")
            InfoRow("Climate", "${trip.climateEnergy} Wh")
            InfoRow("Accessories", "${trip.accessoriesEnergy} Wh")
            InfoRow("Battery care", "${trip.batteryCareEnergy} Wh")
            InfoRow("Regenerated", "${trip.regenEnergy} Wh")
            InfoRow("Average speed", "%.0f".format(trip.avgSpeed))
            InfoRow("Max speed", "%.0f".format(trip.maxSpeed))
            InfoRow("Odometer", trip.odometer.units.format(trip.odometer.length, distanceUnit))
        }
    }
}

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, h:mm a")
