package com.antigrav.milton.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Base state controller for draggable floating windows in Milton.
 * Encapsulates position tracking, dragging math, and strict boundary clamping
 * so windows can never be dragged or positioned outside the visible app viewport.
 */
open class FloatingWindowState(
    initialX: Float = 60f,
    initialY: Float = 80f
) {
    var offsetX by mutableFloatStateOf(initialX)
        protected set
    var offsetY by mutableFloatStateOf(initialY)
        protected set

    var windowSize by mutableStateOf(IntSize.Zero)
        internal set

    /**
     * Updates window coordinates during a drag gesture, strictly clamping to container bounds.
     */
    open fun onDrag(dragAmount: Offset, containerWidth: Int, containerHeight: Int) {
        val w = windowSize.width
        val h = windowSize.height

        val maxX = (containerWidth - w).coerceAtLeast(0).toFloat()
        val maxY = (containerHeight - h).coerceAtLeast(0).toFloat()

        offsetX = (offsetX + dragAmount.x).coerceIn(0f, maxX)
        offsetY = (offsetY + dragAmount.y).coerceIn(0f, maxY)
    }

    /**
     * Clamps the window inside container bounds if the container or window resized.
     */
    open fun clampToBounds(containerWidth: Int, containerHeight: Int) {
        val w = windowSize.width
        val h = windowSize.height
        if (w > 0 && containerWidth > 0) {
            val maxX = (containerWidth - w).coerceAtLeast(0).toFloat()
            offsetX = offsetX.coerceIn(0f, maxX)
        }
        if (h > 0 && containerHeight > 0) {
            val maxY = (containerHeight - h).coerceAtLeast(0).toFloat()
            offsetY = offsetY.coerceIn(0f, maxY)
        }
    }

    open fun setPosition(x: Float, y: Float) {
        offsetX = x
        offsetY = y
    }
}

@Composable
fun rememberFloatingWindowState(initialX: Float = 60f, initialY: Float = 80f): FloatingWindowState {
    return remember { FloatingWindowState(initialX, initialY) }
}

/**
 * Reusable Floating Draggable Window Composable.
 * Used for Color Palette, Layers Panel, and future floating tool panels.
 * Confined strictly to visible container bounds.
 */
@Composable
fun DraggableFloatingWindow(
    title: String,
    onClose: () -> Unit,
    containerWidth: Int,
    containerHeight: Int,
    state: FloatingWindowState = rememberFloatingWindowState(),
    modifier: Modifier = Modifier,
    headerActions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit
) {
    LaunchedEffect(containerWidth, containerHeight, state.windowSize) {
        state.clampToBounds(containerWidth, containerHeight)
    }

    Surface(
        modifier = modifier
            .offset { IntOffset(state.offsetX.roundToInt(), state.offsetY.roundToInt()) }
            .onSizeChanged { state.windowSize = it }
            .shadow(12.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp)),
        color = Color(0xF6181A20),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x35FFFFFF))
    ) {
        Column {
            // Draggable Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF20232B))
                    .pointerInput(containerWidth, containerHeight) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            state.onDrag(dragAmount, containerWidth, containerHeight)
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    headerActions()
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White.copy(alpha = 0.70f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            // Window Body
            Box(modifier = Modifier.padding(10.dp)) {
                content()
            }
        }
    }
}
