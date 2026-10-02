package com.antigrav.milton.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import com.antigrav.milton.core.layer.Layer
import com.antigrav.milton.core.model.BezierControlPoints
import com.antigrav.milton.ui.components.DraggableFloatingWindow
import com.antigrav.milton.ui.components.EraserIcon
import com.antigrav.milton.ui.components.FlipCanvasIcon
import com.antigrav.milton.ui.components.FloatingWindowState
import com.antigrav.milton.ui.components.HsvColorWheel
import com.antigrav.milton.ui.components.LayersFloatingWindow
import com.antigrav.milton.ui.components.PaintbrushIcon
import com.antigrav.milton.ui.components.PenIcon
import com.antigrav.milton.ui.components.PencilIcon
import com.antigrav.milton.ui.components.ToolParametersFloatingWindow
import com.antigrav.milton.ui.components.rememberFloatingWindowState
import java.util.Locale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigrav.milton.core.brush.BrushType
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

val QUICK_PALETTE_COLORS = listOf(
    0xFF111111.toInt(), // Ink Black
    0xFF4A4A4A.toInt(), // Graphite
    0xFF78909C.toInt(), // Slate Gray
    0xFFD32F2F.toInt(), // Crimson Red
    0xFF1976D2.toInt(), // Cobalt Blue
    0xFF388E3C.toInt(), // Forest Green
    0xFFF57C00.toInt(), // Amber Orange
    0xFFFFFFFF.toInt()  // Paper White
)

@Composable
fun MiltonTabletUi(
    brushType: BrushType,
    onBrushTypeChange: (BrushType) -> Unit,
    brushSize: Float,
    onBrushSizeChange: (Float) -> Unit,
    brushOpacity: Float,
    onBrushOpacityChange: (Float) -> Unit,
    brushStabilizer: Float = 0.10f,
    onBrushStabilizerChange: (Float) -> Unit = {},
    brushColorRgb: Int,
    onBrushColorChange: (Int) -> Unit,
    sizeBezierConfig: BezierControlPoints = BezierControlPoints(),
    onSizeBezierConfigChange: (BezierControlPoints) -> Unit = {},
    opacityBezierConfig: BezierControlPoints = BezierControlPoints(),
    onOpacityBezierConfigChange: (BezierControlPoints) -> Unit = {},
    bezierConfig: BezierControlPoints = sizeBezierConfig,
    onBezierConfigChange: (BezierControlPoints) -> Unit = onSizeBezierConfigChange,
    canUndo: Boolean,
    onUndo: () -> Unit,
    canRedo: Boolean,
    onRedo: () -> Unit,
    zoomLevel: Float,
    isZoomLocked: Boolean,
    onToggleZoomLock: () -> Unit,
    rotationDegrees: Float,
    isRotationLocked: Boolean,
    onToggleRotationLock: () -> Unit,
    onResetCanvas: () -> Unit,
    isZenMode: Boolean,
    onToggleZenMode: (Boolean) -> Unit,
    layers: List<Layer>,
    activeLayerId: Long,
    onSelectLayer: (Long) -> Unit,
    onAddLayer: () -> Unit,
    onDeleteLayer: (Long) -> Unit,
    onToggleLayerVisibility: (Long, Boolean) -> Unit,
    onLayerOpacityChange: (Long, Float) -> Unit,
    onMoveLayerUp: (Long) -> Unit,
    onMoveLayerDown: (Long) -> Unit,
    recentColors: List<Int>,
    canvasBackgroundColor: Int = 0xFFFFFFFF.toInt(),
    onCanvasBackgroundColorChange: (Int) -> Unit = {},
    isCanvasFlipped: Boolean = false,
    onToggleFlipCanvas: () -> Unit = {},
    onReorderLayer: (Int, Int) -> Unit = { _, _ -> },
    onReorderLayers: (List<Long>) -> Unit = {},
    isEyedropperActive: Boolean = false,
    onToggleEyedropper: () -> Unit = {},
    eyedropperReticleState: EyedropperReticleState = EyedropperReticleState(),
    modifier: Modifier = Modifier
) {
    var showColorPaletteWindow by remember { mutableStateOf(false) }
    var showLayersWindow by remember { mutableStateOf(false) }
    var showToolParametersWindow by remember { mutableStateOf(false) }

    // Preserve window open/closed states across fullscreen (Zen mode) toggles
    var preZenToolParams by remember { mutableStateOf(false) }
    var preZenColorPalette by remember { mutableStateOf(false) }
    var preZenLayers by remember { mutableStateOf(false) }

    val toolParamsWindowState = rememberFloatingWindowState(initialX = 64f, initialY = 100f)
    val colorWindowState = rememberFloatingWindowState(initialX = 64f, initialY = 120f)
    val layersWindowState = rememberFloatingWindowState(initialX = 1400f, initialY = 40f)

    LaunchedEffect(isZenMode) {
        if (isZenMode) {
            preZenToolParams = showToolParametersWindow
            preZenColorPalette = showColorPaletteWindow
            preZenLayers = showLayersWindow
            showToolParametersWindow = false
            showColorPaletteWindow = false
            showLayersWindow = false
        } else {
            showToolParametersWindow = preZenToolParams
            showColorPaletteWindow = preZenColorPalette
            showLayersWindow = preZenLayers
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val containerW = constraints.maxWidth
        val containerH = constraints.maxHeight

        // 1. Left Vertical Tool Rail (docked to left screen edge)
        AnimatedVisibility(
            visible = !isZenMode,
            enter = fadeIn() + slideInHorizontally { -it },
            exit = fadeOut() + slideOutHorizontally { -it },
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 0.dp)
        ) {
            TabletToolRail(
                activeBrush = brushType,
                onSelectBrush = onBrushTypeChange,
                onOpenToolParameters = { showToolParametersWindow = !showToolParametersWindow },
                size = brushSize,
                onSizeChange = onBrushSizeChange,
                opacity = brushOpacity,
                onOpacityChange = onBrushOpacityChange,
                colorRgb = brushColorRgb,
                onOpenColorPicker = { showColorPaletteWindow = !showColorPaletteWindow }
            )
        }

        // 2. Floating Draggable Tool Parameters Window (confined within app bounds)
        if (!isZenMode && showToolParametersWindow) {
            ToolParametersFloatingWindow(
                brushType = brushType,
                brushSize = brushSize,
                onBrushSizeChange = onBrushSizeChange,
                brushOpacity = brushOpacity,
                onBrushOpacityChange = onBrushOpacityChange,
                brushStabilizer = brushStabilizer,
                onBrushStabilizerChange = onBrushStabilizerChange,
                brushColorRgb = brushColorRgb,
                sizeBezierConfig = sizeBezierConfig,
                onSizeBezierConfigChange = onSizeBezierConfigChange,
                opacityBezierConfig = opacityBezierConfig,
                onOpacityBezierConfigChange = onOpacityBezierConfigChange,
                onClose = { showToolParametersWindow = false },
                containerWidth = containerW,
                containerHeight = containerH,
                state = toolParamsWindowState
            )
        }

        // 3. Top Right Header Bar (docked to top edge, flat on top, rounded on bottom)
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 0.dp, end = 20.dp)
        ) {
            if (!isZenMode) {
                TabletTopBar(
                    canUndo = canUndo,
                    onUndo = onUndo,
                    canRedo = canRedo,
                    onRedo = onRedo,
                    isCanvasFlipped = isCanvasFlipped,
                    onToggleFlipCanvas = onToggleFlipCanvas,
                    zoomLevel = zoomLevel,
                    isZoomLocked = isZoomLocked,
                    onToggleZoomLock = onToggleZoomLock,
                    rotationDegrees = rotationDegrees,
                    isRotationLocked = isRotationLocked,
                    onToggleRotationLock = onToggleRotationLock,
                    isLayersOpen = showLayersWindow,
                    onToggleLayers = { showLayersWindow = !showLayersWindow },
                    onResetCanvas = onResetCanvas,
                    onToggleZenMode = { onToggleZenMode(true) }
                )
            } else {
                val zenExitShape = RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp, bottomStart = 14.dp, bottomEnd = 14.dp)
                Surface(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(zenExitShape)
                        .clickable { onToggleZenMode(false) },
                    color = Color(0xD0181A1F),
                    shadowElevation = 8.dp,
                    shape = zenExitShape,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x44FFFFFF))
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.FullscreenExit,
                            contentDescription = "Exit Fullscreen",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // 4. Floating Draggable Color Palette Window (confined within app bounds)
        if (!isZenMode && showColorPaletteWindow) {
            ColorPaletteFloatingWindow(
                currentColorRgb = brushColorRgb,
                onColorSelected = onBrushColorChange,
                recentColors = recentColors,
                isEyedropperActive = isEyedropperActive,
                onToggleEyedropper = onToggleEyedropper,
                onClose = { showColorPaletteWindow = false },
                containerWidth = containerW,
                containerHeight = containerH,
                state = colorWindowState
            )
        }

        // 5. Floating Draggable Layers Window (confined within app bounds)
        if (!isZenMode && showLayersWindow) {
            LayersFloatingWindow(
                layers = layers,
                activeLayerId = activeLayerId,
                onSelectLayer = onSelectLayer,
                onAddLayer = onAddLayer,
                onDeleteLayer = onDeleteLayer,
                onToggleVisibility = onToggleLayerVisibility,
                onOpacityChange = onLayerOpacityChange,
                onMoveLayerUp = onMoveLayerUp,
                onMoveLayerDown = onMoveLayerDown,
                onReorderLayer = onReorderLayer,
                onReorderLayers = onReorderLayers,
                backgroundColorRgb = canvasBackgroundColor,
                onChangeBackgroundColor = onCanvasBackgroundColorChange,
                onClose = { showLayersWindow = false },
                containerWidth = containerW,
                containerHeight = containerH,
                state = layersWindowState
            )
        }

        // 6. Live Eyedropper Reticle Overlay
        if (eyedropperReticleState.isVisible) {
            EyedropperReticleOverlay(
                state = eyedropperReticleState
            )
        }
    }
}

/**
 * Left-docked vertical rail: Attached directly to left edge, flat on left and rounded on right.
 */
@Composable
private fun TabletToolRail(
    activeBrush: BrushType,
    onSelectBrush: (BrushType) -> Unit,
    onOpenToolParameters: () -> Unit,
    size: Float,
    onSizeChange: (Float) -> Unit,
    opacity: Float,
    onOpacityChange: (Float) -> Unit,
    colorRgb: Int,
    onOpenColorPicker: () -> Unit
) {
    val railShape = RoundedCornerShape(topStart = 0.dp, bottomStart = 0.dp, topEnd = 16.dp, bottomEnd = 16.dp)
    Surface(
        modifier = Modifier
            .width(56.dp)
            .shadow(elevation = 12.dp, shape = railShape)
            .clip(railShape),
        color = Color(0xF2181A1F),
        shape = railShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x35FFFFFF)),
        tonalElevation = 6.dp
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = 10.dp, horizontal = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            // 4 Core Tools with Custom Distinct Vector Logos
            ToolButton(
                brushType = BrushType.PEN,
                isSelected = activeBrush == BrushType.PEN,
                onSelect = { onSelectBrush(BrushType.PEN) },
                onOpenMenu = onOpenToolParameters
            )
            ToolButton(
                brushType = BrushType.PENCIL,
                isSelected = activeBrush == BrushType.PENCIL,
                onSelect = { onSelectBrush(BrushType.PENCIL) },
                onOpenMenu = onOpenToolParameters
            )
            ToolButton(
                brushType = BrushType.PAINTBRUSH,
                isSelected = activeBrush == BrushType.PAINTBRUSH,
                onSelect = { onSelectBrush(BrushType.PAINTBRUSH) },
                onOpenMenu = onOpenToolParameters
            )
            ToolButton(
                brushType = BrushType.ERASER,
                isSelected = activeBrush == BrushType.ERASER,
                onSelect = { onSelectBrush(BrushType.ERASER) },
                onOpenMenu = onOpenToolParameters
            )

            HorizontalDivider(
                color = Color(0x28FFFFFF),
                thickness = 1.dp,
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp)
            )

            // Quick Rail Size Drag Scrubber (1px..500px)
            QuickRailSizeScrubber(
                size = size,
                onSizeChange = onSizeChange,
                onOpenMenu = onOpenToolParameters
            )

            // Quick Rail Opacity Drag Scrubber (1%..100%)
            QuickRailOpacityScrubber(
                opacity = opacity,
                onOpacityChange = onOpacityChange,
                onOpenMenu = onOpenToolParameters
            )

            HorizontalDivider(
                color = Color(0x28FFFFFF),
                thickness = 1.dp,
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp)
            )

            // Active Color Swatch Disc
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color(colorRgb))
                    .border(2.dp, Color.White.copy(alpha = 0.85f), CircleShape)
                    .clickable { onOpenColorPicker() }
            )
        }
    }
}

@Composable
private fun ToolButton(
    brushType: BrushType,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onOpenMenu: () -> Unit
) {
    Surface(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(10.dp))
            .pointerInput(brushType, isSelected) {
                detectTapGestures(
                    onTap = {
                        if (isSelected) {
                            onOpenMenu()
                        } else {
                            onSelect()
                        }
                    },
                    onLongPress = {
                        onSelect()
                        onOpenMenu()
                    }
                )
            },
        color = if (isSelected) Color(0xFF3949AB) else Color.Transparent,
        shape = RoundedCornerShape(10.dp),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF90CAF9)) else null
    ) {
        Box(contentAlignment = Alignment.Center) {
            val tint = if (isSelected) Color.White else Color(0x88FFFFFF)
            when (brushType) {
                BrushType.PEN -> PenIcon(tint = tint, modifier = Modifier.size(22.dp))
                BrushType.PENCIL -> PencilIcon(tint = tint, modifier = Modifier.size(22.dp))
                BrushType.PAINTBRUSH -> PaintbrushIcon(tint = tint, modifier = Modifier.size(22.dp))
                BrushType.ERASER -> EraserIcon(tint = tint, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/**
 * Compact Size Scrubber on the Rail:
 * Dragging up / right increases size up to 500px, dragging down / left decreases.
 * Tapping opens the tool parameters menu.
 */
@Composable
private fun QuickRailSizeScrubber(
    size: Float,
    onSizeChange: (Float) -> Unit,
    onOpenMenu: () -> Unit
) {
    var isDragging by remember { mutableStateOf(false) }
    val currentSizeState = rememberUpdatedState(size)
    val onSizeChangeState = rememberUpdatedState(onSizeChange)

    Box(
        modifier = Modifier
            .width(46.dp)
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF22262E))
            .border(
                1.dp,
                if (isDragging) Color(0xFF64B5F6) else Color(0x35FFFFFF),
                RoundedCornerShape(12.dp)
            )
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var lastX = down.position.x
                    var lastY = down.position.y
                    var totalMoved = 0f
                    var accumulatedSize = currentSizeState.value
                    isDragging = true
                    down.consume()

                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull()
                        if (change != null && change.pressed) {
                            val curX = change.position.x
                            val curY = change.position.y
                            val dx = curX - lastX
                            val dy = -(curY - lastY) // up is positive
                            lastX = curX
                            lastY = curY
                            val delta = dx + dy
                            totalMoved += kotlin.math.abs(dx) + kotlin.math.abs(dy)

                            // Continuous proportional sensitivity: slow & precise in low range, swift in large range
                            val factor = (0.05f + 0.0055f * accumulatedSize).coerceIn(0.05f, 3.0f)
                            accumulatedSize = (accumulatedSize + delta * factor).coerceIn(1f, 500f)
                            onSizeChangeState.value(accumulatedSize)
                            change.consume()
                        }
                    } while (event.changes.any { it.pressed })

                    if (totalMoved < 10f) {
                        onOpenMenu()
                    }
                    isDragging = false
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val indicatorRadius = (2.5f + (size / 500f).coerceIn(0f, 1f) * 9f).dp
            Box(
                modifier = Modifier
                    .size(indicatorRadius * 2f)
                    .clip(CircleShape)
                    .background(Color(0xFF64B5F6))
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${size.roundToInt()}",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        if (isDragging) {
            Surface(
                modifier = Modifier
                    .offset { IntOffset(x = 110, y = 0) }
                    .shadow(12.dp, RoundedCornerShape(8.dp)),
                color = Color(0xF5181A1F),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF64B5F6))
            ) {
                Text(
                    text = String.format(Locale.US, "Size: %.1f px", size),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

/**
 * Compact Opacity Scrubber on the Rail:
 * Dragging up / right increases opacity up to 100%, dragging down / left decreases.
 * Tapping opens the tool parameters menu.
 */
@Composable
private fun QuickRailOpacityScrubber(
    opacity: Float,
    onOpacityChange: (Float) -> Unit,
    onOpenMenu: () -> Unit
) {
    var isDragging by remember { mutableStateOf(false) }
    val currentOpacityState = rememberUpdatedState(opacity)
    val onOpacityChangeState = rememberUpdatedState(onOpacityChange)

    Box(
        modifier = Modifier
            .width(46.dp)
            .height(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF22262E))
            .border(
                1.dp,
                if (isDragging) Color(0xFFFFB74D) else Color(0x35FFFFFF),
                RoundedCornerShape(12.dp)
            )
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var lastX = down.position.x
                    var lastY = down.position.y
                    var totalMoved = 0f
                    var accumulatedOpacity = currentOpacityState.value
                    isDragging = true
                    down.consume()

                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull()
                        if (change != null && change.pressed) {
                            val curX = change.position.x
                            val curY = change.position.y
                            val dx = curX - lastX
                            val dy = -(curY - lastY) // up is positive
                            lastX = curX
                            lastY = curY
                            val delta = dx + dy
                            totalMoved += kotlin.math.abs(dx) + kotlin.math.abs(dy)

                            // ~300px drag sweeps 0% to 100%
                            val factor = 0.0035f
                            accumulatedOpacity = (accumulatedOpacity + delta * factor).coerceIn(0.01f, 1.0f)
                            onOpacityChangeState.value(accumulatedOpacity)
                            change.consume()
                        }
                    } while (event.changes.any { it.pressed })

                    if (totalMoved < 10f) {
                        onOpenMenu()
                    }
                    isDragging = false
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val pct = (opacity * 100).roundToInt()
        Text(
            text = "$pct%",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFFFB74D)
        )

        if (isDragging) {
            Surface(
                modifier = Modifier
                    .offset { IntOffset(x = 110, y = 0) }
                    .shadow(12.dp, RoundedCornerShape(8.dp)),
                color = Color(0xF5181A1F),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFB74D))
            ) {
                Text(
                    text = "Opacity: $pct%",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

/**
 * Top minimal bar holding canvas navigation controls, zoom/rotation lock toggles, and single fullscreen button.
 */
@Composable
private fun TabletTopBar(
    canUndo: Boolean,
    onUndo: () -> Unit,
    canRedo: Boolean,
    onRedo: () -> Unit,
    isCanvasFlipped: Boolean = false,
    onToggleFlipCanvas: () -> Unit = {},
    zoomLevel: Float,
    isZoomLocked: Boolean,
    onToggleZoomLock: () -> Unit,
    rotationDegrees: Float,
    isRotationLocked: Boolean,
    onToggleRotationLock: () -> Unit,
    isLayersOpen: Boolean,
    onToggleLayers: () -> Unit,
    onResetCanvas: () -> Unit,
    onToggleZenMode: () -> Unit
) {
    val topBarShape = RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp, bottomStart = 14.dp, bottomEnd = 14.dp)
    Surface(
        modifier = Modifier
            .shadow(elevation = 10.dp, shape = topBarShape)
            .clip(topBarShape),
        shape = topBarShape,
        color = Color(0xF0181A1F),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x35FFFFFF)),
        tonalElevation = 6.dp
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Undo
            IconButton(
                onClick = onUndo,
                enabled = canUndo,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Undo,
                    contentDescription = "Undo",
                    tint = if (canUndo) Color.White else Color(0x38FFFFFF),
                    modifier = Modifier.size(18.dp)
                )
            }

            // Redo
            IconButton(
                onClick = onRedo,
                enabled = canRedo,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Redo,
                    contentDescription = "Redo",
                    tint = if (canRedo) Color.White else Color(0x38FFFFFF),
                    modifier = Modifier.size(18.dp)
                )
            }

            // Horizontal Flip Canvas Toggle Button (directly next to Redo)
            IconButton(
                onClick = onToggleFlipCanvas,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (isCanvasFlipped) Color(0x3564B5F6) else Color.Transparent)
            ) {
                FlipCanvasIcon(
                    tint = if (isCanvasFlipped) Color(0xFF64B5F6) else Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(18.dp)
                )
            }

            VerticalDivider(
                color = Color(0x25FFFFFF),
                thickness = 1.dp,
                modifier = Modifier
                    .height(20.dp)
                    .padding(horizontal = 2.dp)
            )

            // Zoom Lock Toggle Button (Clicking toggles zoom lock)
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onToggleZoomLock() }
                    .background(if (isZoomLocked) Color(0x2FFF9800) else Color.Transparent)
                    .border(
                        width = 1.dp,
                        color = if (isZoomLocked) Color(0xFFFFB74D) else Color(0x20FFFFFF),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 7.dp, vertical = 5.dp),
                color = Color.Transparent
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (isZoomLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = if (isZoomLocked) "Zoom Locked" else "Zoom Unlocked",
                        tint = if (isZoomLocked) Color(0xFFFFB74D) else Color(0x88FFFFFF),
                        modifier = Modifier.size(13.dp)
                    )
                    val zoomPct = (zoomLevel * 100).roundToInt()
                    Text(
                        text = "$zoomPct%",
                        fontSize = 12.sp,
                        fontWeight = if (isZoomLocked) FontWeight.Bold else FontWeight.Medium,
                        color = if (isZoomLocked) Color(0xFFFFB74D) else Color(0xDDFFFFFF)
                    )
                }
            }

            // Rotation Lock Toggle Button (Clicking toggles rotation lock)
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onToggleRotationLock() }
                    .background(if (isRotationLocked) Color(0x2F42A5F5) else Color.Transparent)
                    .border(
                        width = 1.dp,
                        color = if (isRotationLocked) Color(0xFF64B5F6) else Color(0x20FFFFFF),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 7.dp, vertical = 5.dp),
                color = Color.Transparent
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (isRotationLocked) Icons.Default.Lock else Icons.Default.ScreenRotation,
                        contentDescription = if (isRotationLocked) "Rotation Locked" else "Rotation Unlocked",
                        tint = if (isRotationLocked) Color(0xFF64B5F6) else Color(0x88FFFFFF),
                        modifier = Modifier.size(13.dp)
                    )
                    val rotDeg = (rotationDegrees.roundToInt() % 360).let { if (it < 0) it + 360 else it }
                    Text(
                        text = "$rotDeg°",
                        fontSize = 12.sp,
                        fontWeight = if (isRotationLocked) FontWeight.Bold else FontWeight.Medium,
                        color = if (isRotationLocked) Color(0xFF64B5F6) else Color(0xDDFFFFFF)
                    )
                }
            }

            // Layers Window Toggle Button
            IconButton(
                onClick = onToggleLayers,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (isLayersOpen) Color(0x3564B5F6) else Color.Transparent)
            ) {
                Icon(
                    imageVector = Icons.Default.Layers,
                    contentDescription = "Layers",
                    tint = if (isLayersOpen) Color(0xFF64B5F6) else Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(20.dp)
                )
            }

            // Dedicated Reset View Button
            IconButton(
                onClick = onResetCanvas,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CenterFocusStrong,
                    contentDescription = "Reset Canvas View",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(18.dp)
                )
            }

            // Single Fullscreen Toggle Button on the top right
            IconButton(
                onClick = onToggleZenMode,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Fullscreen,
                    contentDescription = "Enter Fullscreen",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * Draggable Floating Color Palette Window.
 * Confined strictly to container bounds.
 * Changes to hue / saturation / value update brush color live.
 * Displays dynamic Recent Colors row under the wheel.
 */
@Composable
private fun ColorPaletteFloatingWindow(
    currentColorRgb: Int,
    onColorSelected: (Int) -> Unit,
    recentColors: List<Int>,
    isEyedropperActive: Boolean,
    onToggleEyedropper: () -> Unit,
    onClose: () -> Unit,
    containerWidth: Int,
    containerHeight: Int,
    state: FloatingWindowState
) {
    val initialHsv = remember(currentColorRgb) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(currentColorRgb, hsv)
        hsv
    }

    var hue by remember { mutableFloatStateOf(if (initialHsv[1] > 0.05f) initialHsv[0] else 210f) }
    var saturation by remember { mutableFloatStateOf(if (initialHsv[1] > 0.05f) initialHsv[1] else 0.85f) }
    var value by remember { mutableFloatStateOf(initialHsv[2]) }

    LaunchedEffect(currentColorRgb) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(currentColorRgb, hsv)
        if (hsv[1] > 0.04f && hsv[2] > 0.04f) {
            hue = hsv[0]
        }
        if (hsv[2] > 0.04f && hsv[1] > 0.02f) {
            saturation = hsv[1]
        }
        value = hsv[2]
    }

    val activeColorInt = remember(hue, saturation, value) {
        android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value))
    }

    DraggableFloatingWindow(
        title = "Color Palette",
        onClose = onClose,
        containerWidth = containerWidth,
        containerHeight = containerHeight,
        state = state,
        modifier = Modifier.width(204.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Full HSV Color Wheel
            HsvColorWheel(
                hue = hue,
                onHueChange = { hue = it },
                saturation = saturation,
                onSaturationChange = { saturation = it },
                value = value,
                onValueChange = { value = it },
                onColorChanged = onColorSelected,
                wheelSize = 160.dp
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Dynamic Recent Colors Row (Under Wheel)
            if (recentColors.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)
                ) {
                    recentColors.take(6).forEach { col ->
                        val isSelected = (col == activeColorInt)
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(Color(col))
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) Color.White else Color(0x33FFFFFF),
                                    shape = CircleShape
                                )
                                .clickable {
                                    val hsv = FloatArray(3)
                                    android.graphics.Color.colorToHSV(col, hsv)
                                    hue = hsv[0]
                                    saturation = hsv[1]
                                    value = hsv[2]
                                    onColorSelected(col)
                                }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = Color(0x25FFFFFF), thickness = 1.dp)
            Spacer(modifier = Modifier.height(8.dp))

            // Preview & Eyedropper Row - Clean, No "Done" button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color(activeColorInt))
                            .border(1.5.dp, Color.White, CircleShape)
                    )
                    Text(
                        text = String.format("#%06X", (0xFFFFFF and activeColorInt)),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xEEFFFFFF)
                    )
                }

                IconButton(
                    onClick = onToggleEyedropper,
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(if (isEyedropperActive) Color(0xFF64B5F6).copy(alpha = 0.35f) else Color.Transparent)
                        .border(
                            width = 1.dp,
                            color = if (isEyedropperActive) Color(0xFF64B5F6) else Color(0x33FFFFFF),
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.Colorize,
                        contentDescription = "Eyedropper",
                        tint = if (isEyedropperActive) Color(0xFF64B5F6) else Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

/**
 * Floating Eyedropper Reticle:
 * Follows stylus / finger while dragging, showing target crosshair and live sampled color loupe.
 */
@Composable
private fun EyedropperReticleOverlay(
    state: EyedropperReticleState
) {
    if (!state.isVisible) return

    val density = androidx.compose.ui.platform.LocalDensity.current
    val xDp = with(density) { state.screenX.toDp() }
    val yDp = with(density) { state.screenY.toDp() }

    // Offset loupe above the contact point so finger/stylus tip doesn't block the sampled preview
    val loupeOffsetY = if (state.screenY < 140f) 52.dp else (-52).dp

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Center target crosshairs at exact sampled point
        Box(
            modifier = Modifier
                .offset(x = xDp - 10.dp, y = yDp - 10.dp)
                .size(20.dp),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                // Center dot
                drawCircle(color = Color.White, radius = 2.dp.toPx())
                drawCircle(color = Color.Black, radius = 2.dp.toPx(), style = Stroke(width = 0.8.dp.toPx()))
                // 4 Crosshairs
                drawLine(Color.White, Offset(cx - 8.dp.toPx(), cy), Offset(cx - 4.dp.toPx(), cy), strokeWidth = 1.5.dp.toPx())
                drawLine(Color.White, Offset(cx + 4.dp.toPx(), cy), Offset(cx + 8.dp.toPx(), cy), strokeWidth = 1.5.dp.toPx())
                drawLine(Color.White, Offset(cx, cy - 8.dp.toPx()), Offset(cx, cy - 4.dp.toPx()), strokeWidth = 1.5.dp.toPx())
                drawLine(Color.White, Offset(cx, cy + 4.dp.toPx()), Offset(cx, cy + 8.dp.toPx()), strokeWidth = 1.5.dp.toPx())
            }
        }

        // 2. Magnified circular color loupe
        Column(
            modifier = Modifier
                .offset(x = xDp - 24.dp, y = yDp + loupeOffsetY - 24.dp)
                .width(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .shadow(elevation = 12.dp, shape = CircleShape)
                    .clip(CircleShape)
                    .background(Color(state.color))
                    .border(2.5.dp, Color.White, CircleShape)
                    .border(4.dp, Color(0x55000000), CircleShape)
            )
            Surface(
                color = Color(0xD0181A1F),
                shape = RoundedCornerShape(4.dp),
                border = androidx.compose.foundation.BorderStroke(0.5.dp, Color(0x44FFFFFF))
            ) {
                Text(
                    text = String.format("#%06X", 0xFFFFFF and state.color),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
    }
}
