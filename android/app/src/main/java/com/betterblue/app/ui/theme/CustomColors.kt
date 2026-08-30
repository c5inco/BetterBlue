package com.betterblue.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Catalog of named color choices users can pick to customize per-vehicle UI
 * accents (refresh button, map pin, lock state, climate action, charging
 * bolt). Colors persist by NAME; the resolver maps a stored name back to a
 * color, and unknown names fall through to the default so a renamed option
 * can't crash.
 */
object CustomColors {
    data class Option(
        val name: String,
        val displayName: String,
        val color: Color,
    )

    val palette: List<Option> =
        listOf(
            Option("blue", "Blue", Color(0xFF007AFF)),
            Option("green", "Green", Color(0xFF34C759)),
            Option("red", "Red", Color(0xFFFF3B30)),
            Option("orange", "Orange", Color(0xFFFF9500)),
            Option("yellow", "Yellow", Color(0xFFFFCC00)),
            Option("pink", "Pink", Color(0xFFFF2D55)),
            Option("purple", "Purple", Color(0xFFAF52DE)),
            Option("indigo", "Indigo", Color(0xFF5856D6)),
            Option("teal", "Teal", Color(0xFF30B0C7)),
            Option("cyan", "Cyan", Color(0xFF32ADE6)),
            Option("mint", "Mint", Color(0xFF00C7BE)),
            Option("brown", "Brown", Color(0xFFA2845E)),
            Option("gray", "Gray", Color(0xFF8E8E93)),
        )

    fun color(name: String?, default: String): Color =
        palette.firstOrNull { it.name == name }?.color
            ?: palette.firstOrNull { it.name == default }?.color
            ?: palette[0].color

    fun option(name: String?, default: String): Option =
        palette.firstOrNull { it.name == name }
            ?: palette.firstOrNull { it.name == default }
            ?: palette[0]
}

/** Resolved accent colors for one vehicle (defaults match iOS). */
data class VehicleAccentColors(
    val primary: Color,
    val charging: Color,
    val gas: Color,
    val lock: Color,
    val unlock: Color,
    val startClimate: Color,
    val stop: Color,
) {
    companion object {
        fun resolve(
            primaryName: String?,
            chargingName: String?,
            gasName: String?,
            lockName: String?,
            unlockName: String?,
            startClimateName: String?,
            stopName: String?,
        ) = VehicleAccentColors(
            primary = CustomColors.color(primaryName, "blue"),
            charging = CustomColors.color(chargingName, "green"),
            gas = CustomColors.color(gasName, "orange"),
            lock = CustomColors.color(lockName, "red"),
            unlock = CustomColors.color(unlockName, "green"),
            startClimate = CustomColors.color(startClimateName, "blue"),
            stop = CustomColors.color(stopName, "red"),
        )
    }
}
