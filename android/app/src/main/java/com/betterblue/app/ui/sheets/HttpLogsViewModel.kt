package com.betterblue.app.ui.sheets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betterblue.app.data.db.entity.HttpLogEntity
import com.betterblue.app.data.log.HttpLogSinkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HttpLogsViewModel
    @Inject
    constructor(
        private val logSinkManager: HttpLogSinkManager,
    ) : ViewModel() {
        val logs: StateFlow<List<HttpLogEntity>> =
            logSinkManager
                .observeLogs()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        private val _expandedId = MutableStateFlow<Long?>(null)
        val expandedId: StateFlow<Long?> = _expandedId.asStateFlow()

        fun toggle(id: Long) {
            _expandedId.value = if (_expandedId.value == id) null else id
        }

        fun clear() {
            viewModelScope.launch { logSinkManager.clearLogs() }
        }
    }
