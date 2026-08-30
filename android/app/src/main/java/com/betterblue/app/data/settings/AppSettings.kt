package com.betterblue.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.betterblue.kit.model.Distance
import com.betterblue.kit.model.Temperature
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * User preferences over Preferences DataStore. Flow reads are inherently
 * live and cross-process-safe, which dissolves the iOS `live*()` accessor
 * workaround for stale singleton caches.
 */
@Singleton
class AppSettings @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val distanceUnitKey = stringPreferencesKey("preferredDistanceUnit")
    private val temperatureUnitKey = stringPreferencesKey("preferredTemperatureUnit")
    private val debugModeKey = booleanPreferencesKey("debugModeEnabled")
    private val sheetDetentsKey = stringPreferencesKey("sheetDetentsByVin")

    val preferredDistanceUnit: Flow<Distance.Units> = dataStore.data.map { prefs ->
        when (prefs[distanceUnitKey]) {
            "kilometers" -> Distance.Units.KILOMETERS
            else -> Distance.Units.MILES
        }
    }

    val preferredTemperatureUnit: Flow<Temperature.Units> = dataStore.data.map { prefs ->
        when (prefs[temperatureUnitKey]) {
            "celsius" -> Temperature.Units.CELSIUS
            else -> Temperature.Units.FAHRENHEIT
        }
    }

    val debugModeEnabled: Flow<Boolean> = dataStore.data.map { it[debugModeKey] ?: false }

    /** Per-VIN persistent-sheet detent memory ("collapsed"/"expanded"). */
    val sheetDetentsByVin: Flow<Map<String, String>> = dataStore.data.map { prefs ->
        prefs[sheetDetentsKey]?.let {
            try {
                Json.decodeFromString<Map<String, String>>(it)
            } catch (_: Exception) {
                emptyMap()
            }
        } ?: emptyMap()
    }

    suspend fun currentDistanceUnit(): Distance.Units = preferredDistanceUnit.first()
    suspend fun currentTemperatureUnit(): Temperature.Units = preferredTemperatureUnit.first()
    suspend fun isDebugModeEnabled(): Boolean = debugModeEnabled.first()

    suspend fun setPreferredDistanceUnit(units: Distance.Units) {
        dataStore.edit { it[distanceUnitKey] = if (units == Distance.Units.KILOMETERS) "kilometers" else "miles" }
    }

    suspend fun setPreferredTemperatureUnit(units: Temperature.Units) {
        dataStore.edit {
            it[temperatureUnitKey] = if (units == Temperature.Units.CELSIUS) "celsius" else "fahrenheit"
        }
    }

    suspend fun setDebugModeEnabled(enabled: Boolean) {
        dataStore.edit { it[debugModeKey] = enabled }
    }

    suspend fun setSheetDetent(vin: String, detent: String) {
        dataStore.edit { prefs ->
            val current = prefs[sheetDetentsKey]?.let {
                try {
                    Json.decodeFromString<Map<String, String>>(it)
                } catch (_: Exception) {
                    emptyMap()
                }
            } ?: emptyMap()
            prefs[sheetDetentsKey] = Json.encodeToString(current + (vin to detent))
        }
    }
}
