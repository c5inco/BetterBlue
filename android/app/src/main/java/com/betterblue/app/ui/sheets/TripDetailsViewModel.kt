package com.betterblue.app.ui.sheets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betterblue.app.data.repo.VehicleRepository
import com.betterblue.app.ui.common.ActionError
import com.betterblue.kit.model.EVTripSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TripDetailsUiState(
    val isLoading: Boolean = true,
    val trips: List<EVTripSummary> = emptyList(),
    val expandedTripId: String? = null,
    val error: ActionError? = null,
)

@HiltViewModel
class TripDetailsViewModel
    @Inject
    constructor(
        private val vehicles: VehicleRepository,
    ) : ViewModel() {
        private val _state = MutableStateFlow(TripDetailsUiState())
        val state: StateFlow<TripDetailsUiState> = _state.asStateFlow()

        fun load(vin: String) {
            viewModelScope.launch {
                _state.update { it.copy(isLoading = true, error = null) }
                try {
                    // A null result means the brand doesn't expose trip history —
                    // an empty list renders the "no history" copy either way.
                    _state.update { it.copy(trips = vehicles.fetchEvTripSummary(vin).orEmpty()) }
                } catch (e: Exception) {
                    _state.update { it.copy(error = ActionError("Load trips", e)) }
                } finally {
                    _state.update { it.copy(isLoading = false) }
                }
            }
        }

        fun toggleTrip(id: String) =
            _state.update {
                it.copy(expandedTripId = if (it.expandedTripId == id) null else id)
            }
    }
