package com.betterblue.app.ui.screens.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betterblue.app.data.db.entity.VehicleEntity
import com.betterblue.app.data.repo.CommandOutcome
import com.betterblue.app.data.repo.VehicleRepository
import com.betterblue.app.data.repo.effectiveFuelType
import com.betterblue.app.data.settings.AppSettings
import com.betterblue.app.ui.common.ActionError
import com.betterblue.app.ui.sheets.SheetRoute
import com.betterblue.kit.model.Distance
import com.betterblue.kit.model.Temperature
import com.betterblue.kit.model.VehicleStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Per-vehicle transient command state driving the button UI. */
data class VehicleActionState(
    val inProgress: Boolean = false,
    val statusMessage: String? = null,
    /** Soft "awaiting confirmation" — the command was accepted, not confirmed. */
    val awaitingConfirmation: Boolean = false,
    val error: ActionError? = null,
)

data class MainUiState(
    val selectedVin: String? = null,
    val isRefreshing: Boolean = false,
    val actions: Map<String, VehicleActionState> = emptyMap(),
    val sheet: SheetRoute? = null,
    /** Per-VIN persistent-sheet detent memory. */
    val detents: Map<String, String> = emptyMap(),
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val vehicles: VehicleRepository,
    private val settings: AppSettings,
) : ViewModel() {

    val visibleVehicles: StateFlow<List<VehicleEntity>> = vehicles.observeVisibleVehicles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val distanceUnit: StateFlow<Distance.Units> = settings.preferredDistanceUnit
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Distance.Units.MILES)

    val temperatureUnit: StateFlow<Temperature.Units> = settings.preferredTemperatureUnit
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Temperature.Units.FAHRENHEIT)

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settings.sheetDetentsByVin.collect { stored ->
                _state.update { it.copy(detents = stored) }
            }
        }
    }

    fun selectVehicle(vin: String) = _state.update { it.copy(selectedVin = vin) }

    fun showSheet(route: SheetRoute) = _state.update { it.copy(sheet = route) }

    fun dismissSheet() = _state.update { it.copy(sheet = null) }

    fun setDetent(vin: String, detent: String) {
        viewModelScope.launch { settings.setSheetDetent(vin, detent) }
    }

    /**
     * The 60-second foreground poll. Overlapping triggers are harmless — the
     * kit's CachedApiClient collapses them via its TTL and in-flight dedup.
     */
    suspend fun runPollLoop() {
        while (true) {
            val vin = _state.value.selectedVin
            if (vin != null) {
                runCatching { vehicles.fetchAndUpdateVehicleStatus(vin, cached = true) }
            }
            delay(POLL_INTERVAL_MILLIS)
        }
    }

    fun refresh(vin: String) {
        viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true) }
            try {
                vehicles.fetchAndUpdateVehicleStatus(vin, cached = false, forceVehicleListRefresh = true)
                updateAction(vin) { VehicleActionState() }
            } catch (e: Exception) {
                updateAction(vin) { it.copy(error = ActionError("Refresh vehicle", e)) }
            } finally {
                _state.update { it.copy(isRefreshing = false) }
            }
        }
    }

    // Commands, each verified by the post-command status wait

    fun toggleLock(vehicle: VehicleEntity) {
        val locked = vehicle.lockStatus == "locked"
        runCommand(
            vin = vehicle.vin,
            action = { if (locked) vehicles.unlock(vehicle.vin) else vehicles.lock(vehicle.vin) },
            condition = { status ->
                status.lockStatus == if (locked) {
                    VehicleStatus.LockStatus.UNLOCKED
                } else {
                    VehicleStatus.LockStatus.LOCKED
                }
            },
            actionName = if (locked) "Unlock vehicle" else "Lock vehicle",
        )
    }

    fun toggleClimate(vehicle: VehicleEntity) {
        val running = vehicle.climateStatus?.airControlOn == true
        runCommand(
            vin = vehicle.vin,
            action = {
                if (running) vehicles.stopClimate(vehicle.vin) else vehicles.startClimate(vehicle.vin)
            },
            condition = { status -> status.climateStatus.airControlOn != running },
            actionName = if (running) "Stop climate" else "Start climate",
        )
    }

    fun toggleCharge(vehicle: VehicleEntity) {
        val charging = vehicle.evStatus?.charging == true
        runCommand(
            vin = vehicle.vin,
            action = { if (charging) vehicles.stopCharge(vehicle.vin) else vehicles.startCharge(vehicle.vin) },
            condition = { status -> status.evStatus?.charging != charging },
            actionName = if (charging) "Stop charging" else "Start charging",
        )
    }

    private fun runCommand(
        vin: String,
        action: suspend () -> Unit,
        condition: (VehicleStatus) -> Boolean,
        actionName: String,
    ) {
        viewModelScope.launch {
            updateAction(vin) { VehicleActionState(inProgress = true) }
            val outcome = vehicles.executeAndVerify(
                vin = vin,
                action = action,
                condition = condition,
                statusMessageUpdater = { message ->
                    updateAction(vin) { it.copy(statusMessage = message) }
                },
            )
            when (outcome) {
                CommandOutcome.Confirmed ->
                    updateAction(vin) { VehicleActionState() }

                // Deliberately NOT an error: the command was accepted upstream
                // and the vehicle just hasn't confirmed yet.
                CommandOutcome.AwaitingConfirmation ->
                    updateAction(vin) { VehicleActionState(awaitingConfirmation = true) }

                is CommandOutcome.Failed ->
                    updateAction(vin) {
                        VehicleActionState(error = ActionError(actionName, outcome.error))
                    }
            }
        }
    }

    fun dismissActionState(vin: String) = updateAction(vin) { VehicleActionState() }

    private fun updateAction(vin: String, transform: (VehicleActionState) -> VehicleActionState) {
        _state.update { state ->
            val current = state.actions[vin] ?: VehicleActionState()
            state.copy(actions = state.actions + (vin to transform(current)))
        }
    }

    /** Dispatches a deep-link action once the vehicle list is available. */
    fun handleDeepLink(vin: String, startClimate: Boolean = false, startCharge: Boolean = false) {
        selectVehicle(vin)
        viewModelScope.launch {
            val vehicle = vehicles.getVehicle(vin) ?: return@launch
            when {
                startClimate -> toggleClimateForDeepLink(vehicle)
                startCharge -> runCommand(
                    vin = vin,
                    action = { vehicles.startCharge(vin) },
                    condition = { it.evStatus?.charging == true },
                    actionName = "Start charging",
                )
            }
        }
    }

    private fun toggleClimateForDeepLink(vehicle: VehicleEntity) = runCommand(
        vin = vehicle.vin,
        action = { vehicles.startClimate(vehicle.vin) },
        condition = { it.climateStatus.airControlOn },
        actionName = "Start climate",
    )

    /** Whether the vehicle can show charging controls at all. */
    fun supportsCharging(vehicle: VehicleEntity): Boolean = vehicle.effectiveFuelType.hasElectricCapability

    private companion object {
        const val POLL_INTERVAL_MILLIS = 60_000L
    }
}
