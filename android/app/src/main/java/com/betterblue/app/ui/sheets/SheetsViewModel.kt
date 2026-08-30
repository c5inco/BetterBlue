package com.betterblue.app.ui.sheets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betterblue.app.data.db.dao.ClimatePresetDao
import com.betterblue.app.data.db.entity.ClimatePresetEntity
import com.betterblue.app.data.db.entity.VehicleEntity
import com.betterblue.app.data.fake.DebugConfiguration
import com.betterblue.app.data.repo.AccountRepository
import com.betterblue.app.data.repo.VehicleRepository
import com.betterblue.app.data.repo.normalizedForCurrentFuelType
import com.betterblue.app.data.repo.toRaw
import com.betterblue.app.data.settings.AppSettings
import com.betterblue.app.ui.common.ActionError
import com.betterblue.kit.model.ClimateOptions
import com.betterblue.kit.model.FuelType
import com.betterblue.kit.model.VehicleCommand
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject

data class SheetUiState(
    val vehicle: VehicleEntity? = null,
    val presets: List<ClimatePresetEntity> = emptyList(),
    val isBusy: Boolean = false,
    val error: ActionError? = null,
    val dismissed: Boolean = false,
)

/**
 * Backs the per-vehicle detail sheets. One ViewModel because they all edit
 * the same vehicle row and share the same save/error plumbing; each sheet
 * uses the slice it needs.
 */
@HiltViewModel
class SheetsViewModel
    @Inject
    constructor(
        private val vehicles: VehicleRepository,
        private val accounts: AccountRepository,
        private val climatePresetDao: ClimatePresetDao,
        private val settings: AppSettings,
    ) : ViewModel() {
        private val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }

        private val _state = MutableStateFlow(SheetUiState())
        val state: StateFlow<SheetUiState> = _state.asStateFlow()

        fun load(vin: String) {
            viewModelScope.launch {
                _state.update {
                    it.copy(
                        vehicle = vehicles.getVehicle(vin),
                        presets = climatePresetDao.getForVehicle(vin),
                        dismissed = false,
                        error = null,
                    )
                }
            }
        }

        fun clearError() = _state.update { it.copy(error = null) }

        suspend fun account(accountId: String) = accounts.getAccount(accountId)

        // Vehicle info edits

        private fun edit(transform: (VehicleEntity) -> VehicleEntity) {
            val current = _state.value.vehicle ?: return
            val updated = transform(current)
            _state.update { it.copy(vehicle = updated) }
            viewModelScope.launch { vehicles.updateVehicle(updated) }
        }

        fun setCustomName(name: String) = edit { it.copy(customName = name.ifBlank { null }) }

        fun setHidden(hidden: Boolean) = edit { it.copy(isHidden = hidden) }

        fun setChargePortType(type: String) = edit { it.copy(chargePortType = type) }

        fun setSeatHeatControls(enabled: Boolean) = edit { it.copy(enableSeatHeatControls = enabled) }

        fun setSurroundViewOverride(value: Boolean?) = edit { it.copy(surroundViewOverride = value) }

        fun setClimateDurationOverride(value: Boolean?) = edit { it.copy(showClimateDurationOverride = value) }

        fun setAccentColor(slot: AccentSlot, name: String?) =
            edit { vehicle ->
                when (slot) {
                    AccentSlot.PRIMARY -> vehicle.copy(primaryColorName = name)
                    AccentSlot.CHARGING -> vehicle.copy(chargingColorName = name)
                    AccentSlot.GAS -> vehicle.copy(gasColorName = name)
                    AccentSlot.LOCK -> vehicle.copy(lockColorName = name)
                    AccentSlot.UNLOCK -> vehicle.copy(unlockColorName = name)
                    AccentSlot.START_CLIMATE -> vehicle.copy(startClimateColorName = name)
                    AccentSlot.STOP -> vehicle.copy(stopColorName = name)
                }
            }

        /**
         * Pins the powertrain. Clearing the override returns control to the
         * inferred value; either way the off-axis range is cleared immediately
         * so the UI doesn't keep showing a stale gas tank on an EV.
         */
        fun setFuelTypeOverride(type: FuelType?) =
            edit { vehicle ->
                vehicle.copy(fuelTypeOverrideRaw = type?.toRaw()).normalizedForCurrentFuelType()
            }

        // Climate presets

        fun addPreset(vin: String) {
            viewModelScope.launch {
                val preset =
                    ClimatePresetEntity(
                        id = UUID.randomUUID().toString(),
                        vehicleVin = vin,
                        name = "Preset ${_state.value.presets.size + 1}",
                        iconName = "fan",
                        climateOptions = ClimateOptions.forPreferredUnits(settings.currentTemperatureUnit()),
                        isSelected = _state.value.presets.isEmpty(),
                        sortOrder = _state.value.presets.size,
                    )
                climatePresetDao.upsert(preset)
                reloadPresets(vin)
            }
        }

        fun updatePreset(preset: ClimatePresetEntity) {
            viewModelScope.launch {
                climatePresetDao.update(preset)
                reloadPresets(preset.vehicleVin)
            }
        }

        fun deletePreset(preset: ClimatePresetEntity) {
            viewModelScope.launch {
                climatePresetDao.delete(preset)
                reloadPresets(preset.vehicleVin)
            }
        }

        fun selectPreset(preset: ClimatePresetEntity) {
            viewModelScope.launch {
                climatePresetDao.select(preset.vehicleVin, preset.id)
                reloadPresets(preset.vehicleVin)
            }
        }

        private suspend fun reloadPresets(vin: String) {
            _state.update { it.copy(presets = climatePresetDao.getForVehicle(vin)) }
        }

        // Charge limits

        fun setTargetSoc(vin: String, acLevel: Int, dcLevel: Int) {
            runAction("Set charge limits") {
                vehicles.sendCommand(vin, VehicleCommand.SetTargetSoc(acLevel = acLevel, dcLevel = dcLevel))
                _state.update { it.copy(dismissed = true) }
            }
        }

        // Fake vehicle debug configuration

        fun updateDebugConfiguration(config: DebugConfiguration) =
            edit { it.copy(debugConfigJson = json.encodeToString(config)) }

        fun debugConfiguration(): DebugConfiguration =
            _state.value.vehicle?.debugConfigJson?.let {
                runCatching { json.decodeFromString<DebugConfiguration>(it) }.getOrNull()
            } ?: DebugConfiguration()

        // Account actions

        fun resetSession(accountId: String) {
            runAction("Reset session") { accounts.resetSession(accountId) }
        }

        fun removeAccount(accountId: String) {
            runAction("Remove account") {
                accounts.removeAccount(accountId)
                _state.update { it.copy(dismissed = true) }
            }
        }

        fun updateAccount(accountId: String, password: String, pin: String, refreshToken: String?) {
            runAction("Update account") {
                accounts.updateAccount(accountId, password, pin, refreshToken)
            }
        }

        private fun runAction(name: String, block: suspend () -> Unit) {
            viewModelScope.launch {
                _state.update { it.copy(isBusy = true, error = null) }
                try {
                    block()
                } catch (e: Exception) {
                    _state.update { it.copy(error = ActionError(name, e)) }
                } finally {
                    _state.update { it.copy(isBusy = false) }
                }
            }
        }
    }

enum class AccentSlot(
    val label: String,
    val default: String,
) {
    PRIMARY("Refresh & map pin", "blue"),
    CHARGING("Charging", "green"),
    GAS("Fuel", "orange"),
    LOCK("Locked", "red"),
    UNLOCK("Unlocked", "green"),
    START_CLIMATE("Climate", "blue"),
    STOP("Stop actions", "red"),
}
