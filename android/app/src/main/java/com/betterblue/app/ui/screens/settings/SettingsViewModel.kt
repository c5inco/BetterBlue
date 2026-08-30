package com.betterblue.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betterblue.app.data.db.entity.AccountEntity
import com.betterblue.app.data.db.entity.VehicleEntity
import com.betterblue.app.data.repo.AccountRepository
import com.betterblue.app.data.repo.VehicleRepository
import com.betterblue.app.data.settings.AppSettings
import com.betterblue.kit.model.Distance
import com.betterblue.kit.model.Temperature
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val accountRepository: AccountRepository,
        private val vehicleRepository: VehicleRepository,
        private val settings: AppSettings,
    ) : ViewModel() {
        val accounts: StateFlow<List<AccountEntity>> =
            accountRepository
                .observeAccounts()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        val vehicles: StateFlow<List<VehicleEntity>> =
            vehicleRepository
                .observeVehicles()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        val distanceUnit: StateFlow<Distance.Units> =
            settings.preferredDistanceUnit
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Distance.Units.MILES)

        val temperatureUnit: StateFlow<Temperature.Units> =
            settings.preferredTemperatureUnit
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Temperature.Units.FAHRENHEIT)

        val debugMode: StateFlow<Boolean> =
            settings.debugModeEnabled
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

        fun setDistanceUnit(units: Distance.Units) {
            viewModelScope.launch { settings.setPreferredDistanceUnit(units) }
        }

        fun setTemperatureUnit(units: Temperature.Units) {
            viewModelScope.launch { settings.setPreferredTemperatureUnit(units) }
        }

        fun setDebugMode(enabled: Boolean) {
            viewModelScope.launch { settings.setDebugModeEnabled(enabled) }
        }

        fun removeAccount(id: String) {
            viewModelScope.launch { accountRepository.removeAccount(id) }
        }

        fun setVehicleHidden(vin: String, hidden: Boolean) {
            viewModelScope.launch {
                vehicleRepository.getVehicle(vin)?.let { vehicle ->
                    // Persist through the DAO via repository helpers
                    vehicleRepository.updateVehicle(vehicle.copy(isHidden = hidden))
                }
            }
        }
    }
