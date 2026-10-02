package com.antigrav.milton.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigrav.milton.core.layer.Layer
import com.antigrav.milton.core.layer.LayerManager
import kotlin.math.roundToInt

/**
 * Compact draggable floating window for multi-layer management:
 * - Up to 16 layers maximum.
 * - Layer thumbnails showing visible portions.
 * - Active layer selection.
 * - Visibility toggle and opacity scrubber.
 * - Drag-and-drop & button reordering.
 * - Add layer (+) and delete layer (trash) controls.
 */
@Composable
fun LayersFloatingWindow(
    layers: List<Layer>,
    activeLayerId: Long,
    onSelectLayer: (Long) -> Unit,
    onAddLayer: () -> Unit,
    onDeleteLayer: (Long) -> Unit,
    onToggleVisibility: (Long, Boolean) -> Unit,
    onOpacityChange: (Long, Float) -> Unit,
    onMoveLayerUp: (Long) -> Unit,
    onMoveLayerDown: (Long) -> Unit,
    backgroundColorRgb: Int = 0xFFFFFFFF.toInt(),
    onChangeBackgroundColor: (Int) -> Unit = {},
    onClose: () -> Unit,
    containerWidth: Int,
    containerHeight: Int,
    state: FloatingWindowState
) {
    val canAdd = layers.size < LayerManager.MAX_LAYERS
    val canDelete = layers.size > LayerManager.MIN_LAYERS

    // Visual stacking order: Top layer shown first at the top of the UI list
    val reversedLayers = layers.reversed()

    DraggableFloatingWindow(
        title = "Layers (${layers.size}/${LayerManager.MAX_LAYERS})",
        onClose = onClose,
        containerWidth = containerWidth,
        containerHeight = containerHeight,
        state = state,
        modifier = Modifier.width(204.dp),
        headerActions = {
            IconButton(
                onClick = onAddLayer,
                enabled = canAdd,
                modifier = Modifier.size(22.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add Layer",
                    tint = if (canAdd) Color(0xFF64B5F6) else Color(0x35FFFFFF),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 340.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(
                    items = reversedLayers,
                    key = { _, layer -> layer.id }
                ) { indexInReversed, layer ->
                    val isActive = (layer.id == activeLayerId)
                    // Visual position: index 0 is top of stack, last index is bottom of stack
                    val isTopmost = (indexInReversed == 0)
                    val isBottommost = (indexInReversed == reversedLayers.size - 1)

                    LayerCard(
                        layer = layer,
                        isActive = isActive,
                        canDelete = canDelete,
                        canMoveUp = !isTopmost,
                        canMoveDown = !isBottommost,
                        onSelect = { onSelectLayer(layer.id) },
                        onToggleVisibility = { onToggleVisibility(layer.id, !layer.isVisible) },
                        onOpacityChange = { onOpacityChange(layer.id, it) },
                        onDelete = { onDeleteLayer(layer.id) },
                        onMoveUp = { onMoveLayerUp(layer.id) },
                        onMoveDown = { onMoveLayerDown(layer.id) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = Color(0x25FFFFFF), thickness = 1.dp)
            Spacer(modifier = Modifier.height(6.dp))

            // Canvas Background Color Row
            CanvasBackgroundRow(
                currentColorRgb = backgroundColorRgb,
                onColorChange = onChangeBackgroundColor
            )
        }
    }
}

@Composable
private fun CanvasBackgroundRow(
    currentColorRgb: Int,
    onColorChange: (Int) -> Unit
) {
    val bgPresets = listOf(
        0xFFFFFFFF.toInt(), // Pure White
        0xFFF6F4ED.toInt(), // Warm Paper
        0xFFDDD9CE.toInt(), // Kraft Cream
        0xFF32353B.toInt(), // Charcoal Slate
        0xFF181A1F.toInt()  // Deep Black
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Background",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xBBFFFFFF)
            )
            Text(
                text = String.format("#%06X", 0xFFFFFF and currentColorRgb),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0x88FFFFFF)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            bgPresets.forEach { colorInt ->
                val isSelected = (colorInt == currentColorRgb)
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color(colorInt))
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) Color(0xFF64B5F6) else Color(0x35FFFFFF),
                            shape = CircleShape
                        )
                        .clickable { onColorChange(colorInt) }
                )
            }
        }
    }
}

@Composable
private fun LayerCard(
    layer: Layer,
    isActive: Boolean,
    canDelete: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onSelect: () -> Unit,
    onToggleVisibility: () -> Unit,
    onOpacityChange: (Float) -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    val cardBg = if (isActive) Color(0x283949AB) else Color(0xFF20232B)
    val borderColor = if (isActive) Color(0xFF64B5F6) else Color(0x22FFFFFF)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable { onSelect() },
        color = cardBg,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 5.dp)
        ) {
            // Main Row: Visibility, Thumbnail, Name, Reorder, Delete
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                // Visibility Eye Toggle
                IconButton(
                    onClick = onToggleVisibility,
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        imageVector = if (layer.isVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = if (layer.isVisible) "Hide Layer" else "Show Layer",
                        tint = if (layer.isVisible) Color.White else Color(0x45FFFFFF),
                        modifier = Modifier.size(14.dp)
                    )
                }

                // Square Thumbnail (28x28 dp)
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF14161C))
                        .border(1.dp, Color(0x30FFFFFF), RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    val thumb = layer.thumbnailBitmap
                    if (thumb != null && !thumb.isRecycled) {
                        Image(
                            bitmap = thumb.asImageBitmap(),
                            contentDescription = "Thumbnail for ${layer.name}",
                            modifier = Modifier.size(28.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                }

                // Layer Name: displays concise number (e.g. "13" instead of "Layer 13")
                val displayName = if (layer.name.startsWith("Layer ", ignoreCase = true)) {
                    layer.name.substring(6).trim()
                } else {
                    layer.name
                }
                Text(
                    text = displayName,
                    fontSize = 12.sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                    color = if (isActive) Color(0xFF90CAF9) else Color(0xDDFFFFFF),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // Drag handle with drag gesture detection
                var dragAccumulator by remember { mutableFloatStateOf(0f) }
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .pointerInput(layer.id) {
                            detectDragGestures(
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragAccumulator += dragAmount.y
                                    val threshold = 32.dp.toPx()
                                    if (dragAccumulator < -threshold) {
                                        // Dragged UP
                                        if (canMoveUp) onMoveUp()
                                        dragAccumulator = 0f
                                    } else if (dragAccumulator > threshold) {
                                        // Dragged DOWN
                                        if (canMoveDown) onMoveDown()
                                        dragAccumulator = 0f
                                    }
                                },
                                onDragEnd = { dragAccumulator = 0f },
                                onDragCancel = { dragAccumulator = 0f }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.DragHandle,
                        contentDescription = "Drag to reorder",
                        tint = Color(0x75FFFFFF),
                        modifier = Modifier.size(15.dp)
                    )
                }

                // Up / Down Buttons for instant precision reordering
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy((-6).dp)
                ) {
                    IconButton(
                        onClick = onMoveUp,
                        enabled = canMoveUp,
                        modifier = Modifier.size(18.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowDropUp,
                            contentDescription = "Move Up",
                            tint = if (canMoveUp) Color(0xBBFFFFFF) else Color(0x22FFFFFF),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(
                        onClick = onMoveDown,
                        enabled = canMoveDown,
                        modifier = Modifier.size(18.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "Move Down",
                            tint = if (canMoveDown) Color(0xBBFFFFFF) else Color(0x22FFFFFF),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // Delete Trash Button
                IconButton(
                    onClick = onDelete,
                    enabled = canDelete,
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Delete Layer",
                        tint = if (canDelete) Color(0xFFEF5350) else Color(0x22FFFFFF),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Sub-row: Opacity Scrubber
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val pct = (layer.opacity * 100).roundToInt()
                Text(
                    text = "$pct%",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0x88FFFFFF),
                    modifier = Modifier.width(28.dp)
                )
                Slider(
                    value = layer.opacity,
                    onValueChange = onOpacityChange,
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF64B5F6),
                        activeTrackColor = Color(0xFF64B5F6),
                        inactiveTrackColor = Color(0x25FFFFFF)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(18.dp)
                )
            }
        }
    }
}
