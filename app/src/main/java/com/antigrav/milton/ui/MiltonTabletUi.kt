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
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import com.antigrav.milton.core.model.BezierControlPoints
import com.antigrav.milton.ui.components.EraserIcon
import com.antigrav.milton.ui.components.PaintbrushIcon
import com.antigrav.milton.ui.components.PenIcon
import com.antigrav.milton.ui.components.PencilIcon
import com.antigrav.milton.ui.components.ToolParametersPopup
import java.util.Locale
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
import androidx.compose.ui.window.Dialog
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
    brushColorRgb: Int,
    onBrushColorChange: (Int) -> Unit,
    bezierConfig: BezierControlPoints,
    onBezierConfigChange: (BezierControlPoints) -> Unit,
    canUndo: Boolean,
    onUndo: () -> Unit,
    canRedo: Boolean,
    onRedo: () -> Unit,
    zoomLevel: Float,
    onResetCanvas: () -> Unit,
    isZenMode: Boolean,
    onToggleZenMode: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var showColorWheelDialog by remember { mutableStateOf(false) }
    var showToolParametersMenu by remember { mutableStateOf(false) }

    LaunchedEffect(isZenMode) {
        if (isZenMode) {
            showToolParametersMenu = false
            showColorWheelDialog = false
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 1. Left Vertical Tool Rail
        AnimatedVisibility(
            visible = !isZenMode,
            enter = fadeIn() + slideInHorizontally { -it },
            exit = fadeOut() + slideOutHorizontally { -it },
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 16.dp, top = 24.dp, bottom = 24.dp)
        ) {
            TabletToolRail(
                activeBrush = brushType,
                onSelectBrush = onBrushTypeChange,
                onOpenToolParameters = { showToolParametersMenu = !showToolParametersMenu },
                size = brushSize,
                onSizeChange = onBrushSizeChange,
                opacity = brushOpacity,
                onOpacityChange = onBrushOpacityChange,
                colorRgb = brushColorRgb,
                onOpenColorPicker = { showColorWheelDialog = true }
            )
        }

        // 2. Tool Parameters Menu Popup (anchored next to rail)
        AnimatedVisibility(
            visible = !isZenMode && showToolParametersMenu,
            enter = fadeIn() + slideInHorizontally { -30 },
            exit = fadeOut() + slideOutHorizontally { -30 },
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 88.dp, top = 24.dp, bottom = 24.dp)
        ) {
            ToolParametersPopup(
                brushType = brushType,
                brushSize = brushSize,
                onBrushSizeChange = onBrushSizeChange,
                brushOpacity = brushOpacity,
                onBrushOpacityChange = onBrushOpacityChange,
                brushColorRgb = brushColorRgb,
                bezierConfig = bezierConfig,
                onBezierConfigChange = onBezierConfigChange,
                onDismiss = { showToolParametersMenu = false }
            )
        }

        // 3. Top Right Header Bar (Undo, Redo, Zoom %, Reset View, Single Fullscreen Toggle)
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp, end = 16.dp)
        ) {
            if (!isZenMode) {
                TabletTopBar(
                    canUndo = canUndo,
                    onUndo = onUndo,
                    canRedo = canRedo,
                    onRedo = onRedo,
                    zoomLevel = zoomLevel,
                    onResetCanvas = onResetCanvas,
                    onToggleZenMode = { onToggleZenMode(true) }
                )
            } else {
                // When in Zen / Fullscreen mode, the single button in the top right reverses fullscreen
                Surface(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .clickable { onToggleZenMode(false) },
                    color = Color(0xD0181A1F),
                    shadowElevation = 8.dp,
                    shape = CircleShape,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x44FFFFFF))
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.FullscreenExit,
                            contentDescription = "Exit Fullscreen",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }

        // 4. Color Wheel Dialog (Circular Hue ring + Saturation/Value square)
        if (showColorWheelDialog) {
            ColorWheelDialog(
                currentColorRgb = brushColorRgb,
                onColorSelected = { color ->
                    onBrushColorChange(color)
                    showColorWheelDialog = false
                },
                onDismiss = { showColorWheelDialog = false }
            )
        }
    }
}

/**
 * Left-docked vertical rail: 4 core brushes with distinct vector icons, quick drag scrubbers, and color swatch.
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
    Surface(
        modifier = Modifier
            .width(62.dp)
            .shadow(elevation = 14.dp, shape = RoundedCornerShape(22.dp))
            .clip(RoundedCornerShape(22.dp)),
        color = Color(0xF2181A1F),
        tonalElevation = 6.dp
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = 12.dp, horizontal = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
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
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
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
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )

            // Active Color Swatch Disc
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(colorRgb))
                    .border(2.5.dp, Color.White.copy(alpha = 0.85f), CircleShape)
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
            .size(46.dp)
            .clip(RoundedCornerShape(12.dp))
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
        shape = RoundedCornerShape(12.dp),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF90CAF9)) else null
    ) {
        Box(contentAlignment = Alignment.Center) {
            val tint = if (isSelected) Color.White else Color(0x88FFFFFF)
            when (brushType) {
                BrushType.PEN -> PenIcon(tint = tint, modifier = Modifier.size(24.dp))
                BrushType.PENCIL -> PencilIcon(tint = tint, modifier = Modifier.size(24.dp))
                BrushType.PAINTBRUSH -> PaintbrushIcon(tint = tint, modifier = Modifier.size(24.dp))
                BrushType.ERASER -> EraserIcon(tint = tint, modifier = Modifier.size(24.dp))
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
                    isDragging = true
                    down.consume()

                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull()
                        if (change != null && change.pressed) {
                            val curX = change.position.x
                            val curY = change.position.y
                            val dx = curX - lastX
                            val dy = -(curY - lastY)
                            lastX = curX
                            lastY = curY
                            val delta = if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) dx else dy
                            totalMoved += kotlin.math.abs(dx) + kotlin.math.abs(dy)

                            val factor = when {
                                size < 15f -> 0.20f
                                size < 50f -> 0.45f
                                size < 150f -> 0.90f
                                else -> 1.80f
                            }
                            val newSize = (size + delta * factor).coerceIn(1f, 500f)
                            onSizeChange(newSize)
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
                    isDragging = true
                    down.consume()

                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull()
                        if (change != null && change.pressed) {
                            val curX = change.position.x
                            val curY = change.position.y
                            val dx = curX - lastX
                            val dy = -(curY - lastY)
                            lastX = curX
                            lastY = curY
                            val delta = if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) dx else dy
                            totalMoved += kotlin.math.abs(dx) + kotlin.math.abs(dy)

                            val newOpacity = (opacity + delta * 0.003f).coerceIn(0.01f, 1.0f)
                            onOpacityChange(newOpacity)
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
 * Top minimal bar holding canvas navigation controls and the single fullscreen button.
 */
@Composable
private fun TabletTopBar(
    canUndo: Boolean,
    onUndo: () -> Unit,
    canRedo: Boolean,
    onRedo: () -> Unit,
    zoomLevel: Float,
    onResetCanvas: () -> Unit,
    onToggleZenMode: () -> Unit
) {
    Surface(
        modifier = Modifier
            .shadow(elevation = 10.dp, shape = RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp)),
        color = Color(0xF0181A1F),
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

            VerticalDivider(
                color = Color(0x25FFFFFF),
                thickness = 1.dp,
                modifier = Modifier
                    .height(20.dp)
                    .padding(horizontal = 2.dp)
            )

            // Zoom % Readout (Tapping resets view to 100%)
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onResetCanvas() }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                color = Color.Transparent
            ) {
                val zoomPct = (zoomLevel * 100).roundToInt()
                Text(
                    text = "$zoomPct%",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xDDFFFFFF)
                )
            }

            // Reset View Button
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

private enum class WheelTouchMode {
    NONE,
    HUE,
    SV
}

/**
 * HSV Color Wheel with circular rainbow Hue ring and inner Saturation-Value square.
 */
@Composable
private fun ColorWheelDialog(
    currentColorRgb: Int,
    onColorSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val initialHsv = remember(currentColorRgb) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(currentColorRgb, hsv)
        hsv
    }

    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initialHsv[1]) }
    var value by remember { mutableFloatStateOf(initialHsv[2]) }

    val activeColorInt = remember(hue, saturation, value) {
        android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value))
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .width(320.dp)
                .wrapContentHeight()
                .shadow(20.dp, RoundedCornerShape(22.dp))
                .clip(RoundedCornerShape(22.dp)),
            color = Color(0xF8181A20)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Color Wheel",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color(0x88FFFFFF),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // --- HSV Color Wheel Canvas ---
                var touchMode by remember { mutableStateOf(WheelTouchMode.NONE) }

                androidx.compose.foundation.Canvas(
                    modifier = Modifier
                        .size(220.dp)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val w = size.width.toFloat()
                                val h = size.height.toFloat()
                                val cx = w / 2f
                                val cy = h / 2f
                                val ringThickness = 22.dp.toPx()
                                val rOuter = minOf(cx, cy) - 2.dp.toPx()
                                val rInner = rOuter - ringThickness
                                val rSafe = rInner - 6.dp.toPx()
                                val sqSize = (rSafe * sqrt(2.0)).toFloat()
                                val sqLeft = cx - sqSize / 2f
                                val sqTop = cy - sqSize / 2f

                                val dx = down.position.x - cx
                                val dy = down.position.y - cy
                                val dist = hypot(dx, dy)

                                touchMode = if (dist >= rInner - 8.dp.toPx()) {
                                    WheelTouchMode.HUE
                                } else {
                                    WheelTouchMode.SV
                                }

                                if (touchMode == WheelTouchMode.HUE) {
                                    var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                                    if (angle < 0f) angle += 360f
                                    hue = angle
                                } else {
                                    saturation = ((down.position.x - sqLeft) / sqSize).coerceIn(0f, 1f)
                                    value = (1f - (down.position.y - sqTop) / sqSize).coerceIn(0f, 1f)
                                }
                                down.consume()

                                do {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull()
                                    if (change != null && change.pressed) {
                                        if (touchMode == WheelTouchMode.HUE) {
                                            val curDx = change.position.x - cx
                                            val curDy = change.position.y - cy
                                            var angle = Math.toDegrees(atan2(curDy.toDouble(), curDx.toDouble())).toFloat()
                                            if (angle < 0f) angle += 360f
                                            hue = angle
                                        } else {
                                            saturation = ((change.position.x - sqLeft) / sqSize).coerceIn(0f, 1f)
                                            value = (1f - (change.position.y - sqTop) / sqSize).coerceIn(0f, 1f)
                                        }
                                        change.consume()
                                    }
                                } while (event.changes.any { it.pressed })

                                touchMode = WheelTouchMode.NONE
                            }
                        }
                ) {
                    val w = size.width
                    val h = size.height
                    val cx = w / 2f
                    val cy = h / 2f
                    val ringThickness = 22.dp.toPx()
                    val rOuter = minOf(cx, cy) - 2.dp.toPx()
                    val rInner = rOuter - ringThickness
                    val rMid = (rOuter + rInner) / 2f

                    // 1. Outer Hue Ring
                    val hueColors = listOf(
                        Color.Red, Color.Yellow, Color.Green,
                        Color.Cyan, Color.Blue, Color.Magenta, Color.Red
                    )
                    drawCircle(
                        brush = Brush.sweepGradient(hueColors, center = Offset(cx, cy)),
                        radius = rMid,
                        center = Offset(cx, cy),
                        style = Stroke(width = ringThickness)
                    )

                    // Hue Thumb Indicator
                    val hueRad = Math.toRadians(hue.toDouble())
                    val thumbX = cx + rMid * cos(hueRad).toFloat()
                    val thumbY = cy + rMid * sin(hueRad).toFloat()

                    drawCircle(
                        color = Color(0x66000000),
                        radius = 8.dp.toPx(),
                        center = Offset(thumbX, thumbY),
                        style = Stroke(width = 2.5.dp.toPx())
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 7.dp.toPx(),
                        center = Offset(thumbX, thumbY),
                        style = Stroke(width = 2.dp.toPx())
                    )

                    // 2. Inner Saturation-Value Square
                    val rSafe = rInner - 6.dp.toPx()
                    val sqSize = (rSafe * sqrt(2.0)).toFloat()
                    val sqLeft = cx - sqSize / 2f
                    val sqTop = cy - sqSize / 2f
                    val sqRight = cx + sqSize / 2f
                    val sqBottom = cy + sqSize / 2f
                    val topLeft = Offset(sqLeft, sqTop)
                    val rectSize = Size(sqSize, sqSize)

                    // Pure Hue base fill
                    val pureHueColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
                    drawRect(
                        color = pureHueColor,
                        topLeft = topLeft,
                        size = rectSize
                    )

                    // Horizontal White (S=0) to Transparent (S=1)
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.White, Color.White.copy(alpha = 0f)),
                            startX = sqLeft,
                            endX = sqRight
                        ),
                        topLeft = topLeft,
                        size = rectSize
                    )

                    // Vertical Transparent (V=1) to Black (V=0)
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black),
                            startY = sqTop,
                            endY = sqBottom
                        ),
                        topLeft = topLeft,
                        size = rectSize
                    )

                    // Square border
                    drawRect(
                        color = Color(0x44000000),
                        topLeft = topLeft,
                        size = rectSize,
                        style = Stroke(width = 1.dp.toPx())
                    )

                    // SV Crosshair Indicator
                    val svX = sqLeft + saturation * sqSize
                    val svY = sqTop + (1f - value) * sqSize

                    drawCircle(
                        color = Color(0x66000000),
                        radius = 6.dp.toPx(),
                        center = Offset(svX, svY),
                        style = Stroke(width = 2.dp.toPx())
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 5.dp.toPx(),
                        center = Offset(svX, svY),
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Quick Palette Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    QUICK_PALETTE_COLORS.forEach { col ->
                        val isSelected = (col == activeColorInt)
                        Box(
                            modifier = Modifier
                                .size(28.dp)
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
                                }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = Color(0x25FFFFFF), thickness = 1.dp)
                Spacer(modifier = Modifier.height(14.dp))

                // Preview & Apply Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(activeColorInt))
                                .border(1.5.dp, Color.White, CircleShape)
                        )
                        Text(
                            text = String.format("#%06X", (0xFFFFFF and activeColorInt)),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xCCFFFFFF)
                        )
                    }

                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onColorSelected(activeColorInt) },
                        color = Color(0xFF3949AB),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = "Apply",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}
