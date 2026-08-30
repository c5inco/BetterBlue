package com.betterblue.app.ui.sheets

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.betterblue.app.ui.common.ErrorDetailsCard

/**
 * The 360° camera stills. Loading existing captures is cheap; asking for a
 * new one is slow — the vehicle wakes its cameras, shoots, and uploads,
 * which the ViewModel polls for up to six minutes.
 */
@Composable
fun SurroundViewSheet(vin: String, viewModel: SurroundViewViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(vin) { viewModel.load(vin) }

    // Backing out abandons the poll; the vehicle still finishes its upload.
    DisposableEffect(vin) {
        onDispose { viewModel.cancelCapture() }
    }

    SheetScaffold(title = "Surround View") {
        when {
            state.isLoading -> {
                CenteredProgress("Loading captures…")
            }

            state.capturePhase != null -> {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    CircularProgressIndicator()
                    Text(
                        state.capturePhase!!.message,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    TextButton(onClick = viewModel::cancelCapture) { Text("Stop waiting") }
                }
            }

            state.tiles.isEmpty() -> {
                Text(
                    "No captures yet. Ask the vehicle to take a fresh set of photos.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    state.tiles.forEachIndexed { index, tile ->
                        FilterChip(
                            selected = index == state.selectedTile,
                            onClick = { viewModel.selectTile(index) },
                            label = { Text(tile.position.displayName) },
                        )
                    }
                }
                state.tiles.getOrNull(state.selectedTile)?.let { tile ->
                    tile.bitmap?.let { bitmap ->
                        Image(
                            bitmap = bitmap,
                            contentDescription = "${tile.position.displayName} camera",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f),
                        )
                    }
                }
                state.capturedAtLabel?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        state.error?.let { ErrorDetailsCard(it) }

        Button(
            onClick = { viewModel.requestCapture(vin) },
            enabled = state.capturePhase == null && !state.isLoading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Take new photos")
        }
    }
}

@Composable
private fun CenteredProgress(message: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator()
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
