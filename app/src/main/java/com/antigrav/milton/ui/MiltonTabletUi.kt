package com.antigrav.milton.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.antigrav.milton.core.brush.BrushType
import kotlin.math.roundToInt

val ARTIST_PALETTE_COLORS = listOf(
    0xFF111111.toInt(), // Ink Black
    0xFF424242.toInt(), // Charcoal
    0xFF78909C.toInt(), // Slate Gray
    0xFFD32F2F.toInt(), // Crimson Red
    0xFFE91E63.toInt(), // Magenta Pink
    0xFF7B1FA2.toInt(), // Royal Violet
    0xFF1976D2.toInt(), // Cobalt Blue
    0xFF0097A7.toInt(), // Cyan Teal
    0xFF388E3C.toInt(), // Forest Green
    0xFF8BC34A.toInt(), // Leaf Green
    0xFFF57C00.toInt(), // Amber Orange
    0xFFFFB300.toInt(), // Sunflower Yellow
    0xFF795548.toInt(), // Earth Brown
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
    var showColorDialog by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        // 1. Left Vertical Tool Rail (Non-dominant thumb ergonomics)
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
                onOpenColorPicker = { showColorDialog = true },
                onToggleZenMode = { onToggleZenMode(true) }
            )
        }

        // 2. Top Minimal Bar (Undo, Redo, Zoom Readout, Reset View, Zen Toggle)
        AnimatedVisibility(
            visible = !isZenMode,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp, end = 16.dp)
        ) {
            TabletTopBar(
                canUndo = canUndo,
                onUndo = onUndo,
                canRedo = canRedo,
                onRedo = onRedo,
                zoomLevel = zoomLevel,
                onResetCanvas = onResetCanvas,
                onToggleZenMode = { onToggleZenMode(true) }
            )
        }

        // 3. Zen Mode Minimized Pill (Restore UI)
        AnimatedVisibility(
            visible = isZenMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
        ) {
            Surface(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .clickable { onToggleZenMode(false) },
                color = Color(0x33000000),
                tonalElevation = 2.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.FullscreenExit,
                        contentDescription = "Exit Zen Mode",
                        tint = Color(0xAAFFFFFF),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        // 4. Color Palette & Picker Dialog
        if (showColorDialog) {
            ColorPickerDialog(
                currentColorRgb = brushColorRgb,
                onColorSelected = { color ->
                    onBrushColorChange(color)
                    showColorDialog = false
                },
                onDismiss = { showColorDialog = false }
            )
        }
    }
}

/**
 * Left-docked vertical rail optimized for tablet two-handed workflow.
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
    onOpenColorPicker: () -> Unit,
    onToggleZenMode: () -> Unit
) {
    Surface(
        modifier = Modifier
            .width(58.dp)
            .shadow(elevation = 12.dp, shape = RoundedCornerShape(22.dp))
            .clip(RoundedCornerShape(22.dp)),
        color = Color(0xF0181A1F),
        tonalElevation = 6.dp
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = 10.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Tools section
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
                label = "Round Brush",
                isSelected = activeBrush == BrushType.ROUND_BRUSH,
                onClick = { onSelectBrush(BrushType.ROUND_BRUSH) }
            )
            ToolButton(
                icon = Icons.Default.Highlight,
                label = "Marker",
                isSelected = activeBrush == BrushType.MARKER,
                onClick = { onSelectBrush(BrushType.MARKER) }
            )
            ToolButton(
                icon = Icons.Default.AutoFixNormal,
                label = "Eraser",
                isSelected = activeBrush == BrushType.ERASER,
                onClick = { onSelectBrush(BrushType.ERASER) }
            )

            HorizontalDivider(
                color = Color(0x25FFFFFF),
                thickness = 1.dp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )

            // Vertical Size Scrubber
            VerticalThumbScrubber(
                fraction = ((size - 1f) / 119f).coerceIn(0f, 1f),
                onFractionChange = { frac ->
                    val newSize = (1f + frac * 119f).coerceIn(1f, 120f)
                    onSizeChange(newSize)
                },
                label = "S",
                badgeText = "${size.roundToInt()} px",
                fillColor = Color(0xFF64B5F6),
                modifier = Modifier.height(100.dp)
            )

            // Vertical Opacity Scrubber
            VerticalThumbScrubber(
                fraction = ((opacity - 0.05f) / 0.95f).coerceIn(0f, 1f),
                onFractionChange = { frac ->
                    val newOpacity = (0.05f + frac * 0.95f).coerceIn(0.05f, 1.0f)
                    onOpacityChange(newOpacity)
                },
                label = "O",
                badgeText = "${(opacity * 100).roundToInt()}%",
                fillColor = Color(0xFFFFB74D),
                modifier = Modifier.height(80.dp)
            )

            HorizontalDivider(
                color = Color(0x25FFFFFF),
                thickness = 1.dp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )

            // Color Swatch Button
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color(colorRgb))
                    .border(2.dp, Color.White.copy(alpha = 0.8f), CircleShape)
                    .clickable { onOpenColorPicker() }
            )

            Spacer(modifier = Modifier.height(2.dp))

            // Zen Mode Quick Toggle Button
            IconButton(
                onClick = onToggleZenMode,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Fullscreen,
                    contentDescription = "Zen Fullscreen Mode",
                    tint = Color(0xBBFFFFFF),
                    modifier = Modifier.size(20.dp)
                )
            }
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
            .size(42.dp)
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
 * Vertical scrubber bar enabling instant thumb-dragging for Size and Opacity.
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
    var trackHeightPx by remember { mutableFloatStateOf(1f) }

    Box(
        modifier = modifier
            .width(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF23272F))
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(10.dp))
            .onSizeChanged { trackHeightPx = it.height.toFloat().coerceAtLeast(1f) }
            .pointerInput(trackHeightPx) {
                detectTapGestures { offset ->
                    val newFrac = (1f - (offset.y / trackHeightPx)).coerceIn(0f, 1f)
                    onFractionChange(newFrac)
                }
            }
            .pointerInput(trackHeightPx) {
                detectDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false }
                ) { change, dragAmount ->
                    change.consume()
                    // Dragging up (negative dy) increases value
                    val deltaFrac = -dragAmount.y / trackHeightPx
                    val newFrac = (fraction + deltaFrac).coerceIn(0f, 1f)
                    onFractionChange(newFrac)
                }
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        // Visual fill level (from bottom upwards)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(fraction.coerceIn(0.04f, 1f))
                .clip(RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp))
                .background(fillColor.copy(alpha = 0.55f))
        )

        // Indicator letter
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.85f),
            modifier = Modifier
                .align(Alignment.Center)
        )

        // Live readout badge popping out to the right when touched / dragged
        if (isDragging) {
            Surface(
                modifier = Modifier
                    .offset { IntOffset(x = 120, y = -((trackHeightPx * fraction) - trackHeightPx / 2).roundToInt()) }
                    .shadow(8.dp, RoundedCornerShape(8.dp)),
                color = Color(0xF0181A1F),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = badgeText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

/**
 * Top minimal bar holding canvas navigation controls.
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

            // Zen Mode Fullscreen Button
            IconButton(
                onClick = onToggleZenMode,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Fullscreen,
                    contentDescription = "Enter Zen Mode",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * Modern popup dialog for color selection: preset swatches + HSV Hue slider.
 */
@Composable
private fun ColorPickerDialog(
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
    var sat by remember { mutableFloatStateOf(if (initialHsv[1] < 0.05f) 0.85f else initialHsv[1]) }
    var value by remember { mutableFloatStateOf(if (initialHsv[2] < 0.05f) 0.90f else initialHsv[2]) }

    val activeColor = remember(hue, sat, value) {
        android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .width(320.dp)
                .wrapContentHeight()
                .shadow(16.dp, RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp)),
            color = Color(0xF81C1E24)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Color Palette",
                        fontSize = 15.sp,
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

                // Curated Palette Grid
                LazyVerticalGrid(
                    columns = GridCells.Fixed(7),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(ARTIST_PALETTE_COLORS) { col ->
                        val isPicked = (col == currentColorRgb)
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(col))
                                .border(
                                    width = if (isPicked) 2.5.dp else 1.dp,
                                    color = if (isPicked) Color.White else Color(0x33FFFFFF),
                                    shape = CircleShape
                                )
                                .clickable {
                                    onColorSelected(col)
                                }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = Color(0x25FFFFFF), thickness = 1.dp)
                Spacer(modifier = Modifier.height(14.dp))

                // Hue Spectrum Gradient Bar
                Text(
                    text = "Spectrum Hue",
                    fontSize = 12.sp,
                    color = Color(0xAAFFFFFF),
                    modifier = Modifier.align(Alignment.Start)
                )

                Slider(
                    value = hue,
                    onValueChange = { hue = it },
                    valueRange = 0f..360f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(activeColor),
                        activeTrackColor = Color.Transparent,
                        inactiveTrackColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color.Red, Color.Yellow, Color.Green,
                                    Color.Cyan, Color.Blue, Color.Magenta, Color.Red
                                )
                            )
                        )
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Preview & Accept Row
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
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(activeColor))
                                .border(1.5.dp, Color.White, CircleShape)
                        )
                        Text(
                            text = String.format("#%06X", (0xFFFFFF and activeColor)),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xCCFFFFFF)
                        )
                    }

                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onColorSelected(activeColor) },
                        color = Color(0xFF3949AB),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = "Apply",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}
