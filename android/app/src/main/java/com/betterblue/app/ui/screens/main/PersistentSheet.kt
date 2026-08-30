package com.betterblue.app.ui.screens.main

import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** The two resting positions of the vehicle sheet. */
enum class SheetDetent {
    COLLAPSED,
    EXPANDED,
    ;

    val storageKey: String get() = name.lowercase()

    companion object {
        fun fromStorageKey(key: String?): SheetDetent =
            entries.firstOrNull { it.storageKey == key } ?: COLLAPSED
    }
}

/**
 * A hand-rolled two-detent bottom sheet.
 *
 * Deliberately not `BottomSheetScaffold`: its two states can't express
 * per-VIN detent memory or a programmatic detent restore when the pager
 * changes page, and its nested-scroll handling fights a `HorizontalPager`
 * living inside the sheet. `AnchoredDraggable` gives the drag behavior
 * without either problem.
 */
@Composable
fun PersistentSheet(
    detent: SheetDetent,
    onDetentChanged: (SheetDetent) -> Unit,
    collapsedHeight: androidx.compose.ui.unit.Dp = 220.dp,
    expandedHeightFraction: Float = 0.85f,
    modifier: Modifier = Modifier,
    content: @Composable (SheetDetent) -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val maxHeightPx = with(density) { maxHeight.toPx() }
        val collapsedPx = with(density) { collapsedHeight.toPx() }
        val expandedPx = maxHeightPx * expandedHeightFraction

        val anchors =
            remember(maxHeightPx, collapsedPx, expandedPx) {
                DraggableAnchors {
                    SheetDetent.COLLAPSED at maxHeightPx - collapsedPx
                    SheetDetent.EXPANDED at maxHeightPx - expandedPx
                }
            }

        val dragState =
            remember {
                AnchoredDraggableState(initialValue = detent)
            }

        LaunchedEffect(anchors) { dragState.updateAnchors(anchors) }

        // Restore the remembered detent when the caller changes it (e.g. the
        // pager moved to a vehicle with a different stored preference).
        LaunchedEffect(detent) {
            if (dragState.currentValue != detent) {
                dragState.animateTo(detent)
            }
        }

        // Report settled positions back so they can be persisted per VIN.
        LaunchedEffect(dragState) {
            snapshotFlow { dragState.settledValue }.collect(onDetentChanged)
        }

        Surface(
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(maxHeight)
                    .offset { IntOffset(0, dragState.requireOffset().roundToInt()) },
        ) {
            Box(Modifier.fillMaxSize()) {
                // Drag handle: the sheet is only draggable by this strip, so
                // scrollable card content underneath keeps its own gestures.
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(28.dp)
                            .anchoredDraggable(dragState, Orientation.Vertical),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(width = 36.dp, height = 4.dp)
                            .background(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                RoundedCornerShape(2.dp),
                            ),
                    )
                }

                Box(Modifier.fillMaxSize().padding(top = 28.dp)) {
                    content(dragState.settledValue)
                }
            }
        }
    }
}
