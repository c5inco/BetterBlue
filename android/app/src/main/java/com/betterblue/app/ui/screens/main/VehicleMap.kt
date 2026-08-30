package com.betterblue.app.ui.screens.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.betterblue.app.BuildConfig
import com.betterblue.app.data.db.entity.VehicleEntity
import com.betterblue.app.data.repo.displayName
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState

/**
 * The map behind the vehicle sheet. Shows the VEHICLE's reported position —
 * the app never asks for the user's location, matching iOS.
 *
 * This composable is the single seam over the maps provider: swapping Google
 * Maps for MapLibre touches only this file. Without a MAPS_API_KEY the map
 * SDK renders an empty grey tile, so fall back to a legible placeholder
 * instead (CI and fresh checkouts build keyless by design).
 */
@Composable
fun VehicleMap(vehicle: VehicleEntity?, modifier: Modifier = Modifier) {
    val location = vehicle?.location?.takeIf { it.hasCoordinates }

    if (!hasMapsKey()) {
        NoKeyMapFallback(vehicle, modifier)
        return
    }

    if (location == null) {
        NoLocationFallback(modifier)
        return
    }

    val position = LatLng(location.latitude, location.longitude)
    val cameraPositionState = rememberCameraPositionState {
        this.position = CameraPosition.fromLatLngZoom(position, DEFAULT_ZOOM)
    }

    // Re-center when the selected vehicle (or its position) changes.
    LaunchedEffect(position) {
        cameraPositionState.position = CameraPosition.fromLatLngZoom(position, DEFAULT_ZOOM)
    }

    GoogleMap(
        modifier = modifier,
        cameraPositionState = cameraPositionState,
        // No location permission is requested anywhere in the app.
        properties = MapProperties(isMyLocationEnabled = false),
        uiSettings = MapUiSettings(myLocationButtonEnabled = false, zoomControlsEnabled = false),
    ) {
        Marker(
            state = MarkerState(position = position),
            title = vehicle?.displayName.orEmpty(),
        )
    }
}

@Composable
private fun NoLocationFallback(modifier: Modifier = Modifier) {
    PlaceholderSurface(
        modifier = modifier,
        icon = { Icon(Icons.Filled.LocationOff, contentDescription = null) },
        title = "No location received",
        detail = "The vehicle hasn't reported a position yet.",
    )
}

@Composable
private fun NoKeyMapFallback(vehicle: VehicleEntity?, modifier: Modifier = Modifier) {
    val location = vehicle?.location?.takeIf { it.hasCoordinates }
    PlaceholderSurface(
        modifier = modifier,
        icon = { Icon(Icons.Filled.Map, contentDescription = null) },
        title = location?.debug ?: "Map unavailable",
        detail = if (BuildConfig.DEBUG) {
            "Add MAPS_API_KEY to android/local.properties to enable the map."
        } else {
            "Map is unavailable in this build."
        },
    )
}

@Composable
private fun PlaceholderSurface(
    modifier: Modifier,
    icon: @Composable () -> Unit,
    title: String,
    detail: String,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            icon()
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun hasMapsKey(): Boolean = BuildConfig.MAPS_API_KEY.isNotBlank()

private const val DEFAULT_ZOOM = 15f
