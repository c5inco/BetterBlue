package com.betterblue.app.ui.sheets

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betterblue.app.data.repo.VehicleRepository
import com.betterblue.app.ui.common.ActionError
import com.betterblue.kit.ApiException
import com.betterblue.kit.model.SurroundViewCameraPosition
import com.betterblue.kit.model.SurroundViewCapture
import com.betterblue.kit.surroundview.CapturePhase
import com.betterblue.kit.surroundview.CapturePoller
import com.betterblue.kit.surroundview.CaptureResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** One rendered camera view. */
data class RenderedTile(
    val position: SurroundViewCameraPosition,
    val bitmap: ImageBitmap?,
)

data class SurroundViewUiState(
    val isLoading: Boolean = true,
    val tiles: List<RenderedTile> = emptyList(),
    val selectedTile: Int = 0,
    val capturePhase: CapturePhase? = null,
    val capturedAtLabel: String? = null,
    val error: ActionError? = null,
)

@HiltViewModel
class SurroundViewViewModel
    @Inject
    constructor(
        private val vehicles: VehicleRepository,
    ) : ViewModel() {
        private val _state = MutableStateFlow(SurroundViewUiState())
        val state: StateFlow<SurroundViewUiState> = _state.asStateFlow()

        private val poller = CapturePoller()
        private var captureJob: Job? = null
        private var newest: SurroundViewCapture? = null

        fun load(vin: String) {
            viewModelScope.launch {
                _state.update { it.copy(isLoading = true, error = null) }
                try {
                    apply(vehicles.fetchSurroundViewCaptures(vin))
                } catch (e: Exception) {
                    _state.update { it.copy(error = ActionError("Load surround view", e)) }
                } finally {
                    _state.update { it.copy(isLoading = false) }
                }
            }
        }

        fun selectTile(index: Int) = _state.update { it.copy(selectedTile = index) }

        fun requestCapture(vin: String) {
            captureJob?.cancel()
            captureJob =
                viewModelScope.launch {
                    _state.update { it.copy(error = null) }
                    val result =
                        poller.run(
                            requestCapture = { vehicles.requestSurroundViewCapture(vin) },
                            fetchCaptures = { vehicles.fetchSurroundViewCaptures(vin) },
                            baseline = newest,
                            onPhase = { phase -> _state.update { it.copy(capturePhase = phase) } },
                        )
                    _state.update { it.copy(capturePhase = null) }

                    when (result) {
                        is CaptureResult.NewCapture -> {
                            apply(result.captures)
                        }

                        CaptureResult.TimedOut -> {
                            _state.update {
                                it.copy(
                                    error =
                                        ActionError(
                                            "New surround view capture",
                                            ApiException(
                                                "The vehicle didn't upload new images in time. It may be asleep " +
                                                    "or out of cellular coverage — try again in a few minutes.",
                                            ),
                                        ),
                                )
                            }
                        }

                        is CaptureResult.RequestFailed -> {
                            _state.update {
                                it.copy(error = ActionError("New surround view capture", result.error))
                            }
                        }
                    }
                }
        }

        fun cancelCapture() {
            captureJob?.cancel()
            captureJob = null
            _state.update { it.copy(capturePhase = null) }
        }

        private suspend fun apply(captures: List<SurroundViewCapture>) {
            val capture = captures.firstOrNull() ?: return
            newest = capture
            val rendered = withContext(Dispatchers.Default) { render(capture) }
            _state.update {
                it.copy(
                    tiles = rendered,
                    selectedTile = 0,
                    capturedAtLabel =
                        capture.capturedAt?.let { at ->
                            "Captured ${TIMESTAMP.format(at.atZone(ZoneId.systemDefault()))}"
                        },
                )
            }
        }

        /**
         * Decodes each frame once and crops the per-camera tiles out of it —
         * the payload is usually a single wide composite strip, so decoding per
         * tile would re-decode megabytes for every camera.
         */
        private fun render(capture: SurroundViewCapture): List<RenderedTile> {
            val decoded =
                capture.frames.map { frame ->
                    runCatching { BitmapFactory.decodeByteArray(frame, 0, frame.size) }.getOrNull()
                }

            return capture.tiles.map { tile ->
                val source = decoded.getOrNull(tile.frameIndex)
                val bitmap =
                    when {
                        source == null -> {
                            null
                        }

                        tile.crop == null -> {
                            source
                        }

                        else -> {
                            runCatching {
                                val rect =
                                    Rect(
                                        tile.crop!!.originX,
                                        tile.crop!!.originY,
                                        tile.crop!!.originX + tile.crop!!.width,
                                        tile.crop!!.originY + tile.crop!!.height,
                                    ).apply {
                                        // Clamp: a mis-declared imageSize must not crash the sheet.
                                        right = right.coerceAtMost(source.width)
                                        bottom = bottom.coerceAtMost(source.height)
                                    }
                                if (rect.width() <= 0 || rect.height() <= 0) {
                                    null
                                } else {
                                    Bitmap.createBitmap(source, rect.left, rect.top, rect.width(), rect.height())
                                }
                            }.getOrNull()
                        }
                    }
                RenderedTile(tile.position, bitmap?.asImageBitmap())
            }
        }

        private companion object {
            val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, h:mm a")
        }
    }
