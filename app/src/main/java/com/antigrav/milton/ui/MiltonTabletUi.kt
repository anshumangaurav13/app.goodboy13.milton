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
import androidx.compose.material.icons.filled.AutoFixNormal
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
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

    Box(modifier = modifier.fillMaxSize()) {
        // 1. Left Vertical Tool Rail (Pen, Pencil, Paintbrush, Eraser + Scrubbers + Color)
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
                size = brushSize,
                onSizeChange = onBrushSizeChange,
                opacity = brushOpacity,
                onOpacityChange = onBrushOpacityChange,
                colorRgb = brushColorRgb,
                onOpenColorPicker = { showColorWheelDialog = true }
            )
        }

        // 2. Top Right Header Bar (Undo, Redo, Zoom %, Reset View, Single Fullscreen Toggle)
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

        // 3. Color Wheel Dialog (Circular Hue ring + Saturation/Value square)
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
 * Left-docked vertical rail: 4 core brushes + reliable scrubbers + color swatch.
 */
@Composable
private fun TabletToolRail(
    activeBrush: BrushType,
    onSelectBrush: (BrushType) -> Unit,
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
            // 4 Core Tools: Pen, Pencil, Paintbrush, Eraser
            ToolButton(
                icon = Icons.Default.Edit,
                label = "Pen",
                isSelected = activeBrush == BrushType.PEN,
                onClick = { onSelectBrush(BrushType.PEN) }
            )
            ToolButton(
                icon = Icons.Default.Create,
                label = "Pencil",
                isSelected = activeBrush == BrushType.PENCIL,
                onClick = { onSelectBrush(BrushType.PENCIL) }
            )
            ToolButton(
                icon = Icons.Default.Brush,
                label = "Paintbrush",
                isSelected = activeBrush == BrushType.PAINTBRUSH,
                onClick = { onSelectBrush(BrushType.PAINTBRUSH) }
            )
            ToolButton(
                icon = Icons.Default.AutoFixNormal,
                label = "Eraser",
                isSelected = activeBrush == BrushType.ERASER,
                onClick = { onSelectBrush(BrushType.ERASER) }
            )

            HorizontalDivider(
                color = Color(0x28FFFFFF),
                thickness = 1.dp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )

            // Reliably responsive vertical Size scrubber
            VerticalThumbScrubber(
                fraction = ((size - 1f) / 119f).coerceIn(0f, 1f),
                onFractionChange = { frac ->
                    val newSize = (1f + frac * 119f).coerceIn(1f, 120f)
                    onSizeChange(newSize)
                },
                label = "S",
                badgeText = "${size.roundToInt()} px",
                fillColor = Color(0xFF64B5F6),
                modifier = Modifier.height(105.dp)
            )

            // Reliably responsive vertical Opacity scrubber
            VerticalThumbScrubber(
                fraction = ((opacity - 0.05f) / 0.95f).coerceIn(0f, 1f),
                onFractionChange = { frac ->
                    val newOpacity = (0.05f + frac * 0.95f).coerceIn(0.05f, 1.0f)
                    onOpacityChange(newOpacity)
                },
                label = "O",
                badgeText = "${(opacity * 100).roundToInt()}%",
                fillColor = Color(0xFFFFB74D),
                modifier = Modifier.height(85.dp)
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
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() },
        color = if (isSelected) Color(0xFF384353) else Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF90CAF9)) else null
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isSelected) Color.White else Color(0x88FFFFFF),
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/**
 * Vertical scrubber bar using raw awaitEachGesture for 100% instant, reliable touch tracking.
 */
@Composable
private fun VerticalThumbScrubber(
    fraction: Float,
    onFractionChange: (Float) -> Unit,
    label: String,
    badgeText: String,
    fillColor: Color,
    modifier: Modifier = Modifier
) {
    var isDragging by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .width(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF22262E))
            .border(1.dp, Color(0x35FFFFFF), RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    isDragging = true
                    val h = size.height.toFloat().coerceAtLeast(1f)
                    val newFrac = (1f - (down.position.y / h)).coerceIn(0f, 1f)
                    onFractionChange(newFrac)
                    down.consume()

                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull()
                        if (change != null && change.pressed) {
                            val dragFrac = (1f - (change.position.y / h)).coerceIn(0f, 1f)
                            onFractionChange(dragFrac)
                            change.consume()
                        }
                    } while (event.changes.any { it.pressed })

                    isDragging = false
                }
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        // Visual fill level (from bottom upwards)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(fraction.coerceIn(0.04f, 1f))
                .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                .background(fillColor.copy(alpha = 0.60f))
        )

        // Center indicator letter
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.90f),
            modifier = Modifier.align(Alignment.Center)
        )

        // Live badge tooltip popping to the right of the rail
        if (isDragging) {
            Surface(
                modifier = Modifier
                    .offset { IntOffset(x = 110, y = 0) }
                    .shadow(10.dp, RoundedCornerShape(8.dp)),
                color = Color(0xF5181A1F),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x33FFFFFF))
            ) {
                Text(
                    text = badgeText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
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
