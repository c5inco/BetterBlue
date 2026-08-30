package com.betterblue.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val BetterBluePrimary = Color(0xFF2F6FED)

private val LightColors =
    lightColorScheme(
        primary = BetterBluePrimary,
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFF8AB4F8),
    )

@Composable
fun BetterBlueTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Deliberately no dynamic color: per-vehicle accent colors are the app's identity.
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
