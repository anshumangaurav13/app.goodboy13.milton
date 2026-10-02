package app.goodboy13.milton.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ColorLens
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.zIndex
import app.goodboy13.milton.core.layer.Layer
import app.goodboy13.milton.core.layer.LayerManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/**
 * Compact draggable floating window for multi-layer management:
 * - Up to 16 layers maximum.
 * - Layer thumbnails showing visible portions.
 * - Active layer selection.
 * - Visibility toggle and opacity scrubber.
 * - Physical drag-and-drop reordering with glowing insertion line and auto-scrolling.
 * - Attached context menu for background color selection with HSV wheel and presets.
 */
@Composable
fun LayersFloatingWindow(
    layers: List<Layer>,
    activeLayerId: Long,
    onSelectLayer: (Long) -> Unit,
    onAddLayer: () -> Unit,
    onDeleteLayer: (Long) -> Unit,
    onClearLayer: (Long) -> Unit = {},
    onToggleVisibility: (Long, Boolean) -> Unit,
    onOpacityChange: (Long, Float) -> Unit,
    onMoveLayerUp: (Long) -> Unit,
    onMoveLayerDown: (Long) -> Unit,
    onReorderLayer: (fromStorageIndex: Int, toStorageIndex: Int) -> Unit = { _, _ -> },
    onReorderLayers: (List<Long>) -> Unit = {},
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

    val listState = rememberLazyListState()
    var listHeightPx by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val edgeThreshold = with(density) { 45.dp.toPx() }
    val cardHeightPx = with(density) { 38.dp.toPx() }

    // Physical drag-and-drop states
    var draggingLayerId by remember { mutableStateOf<Long?>(null) }
    var dragTouchYInList by remember { mutableFloatStateOf(0f) }
    var dragGrabOffsetInCard by remember { mutableFloatStateOf(0f) }
    var dropSlotIndex by remember { mutableIntStateOf(-1) }
    var autoScrollSpeed by remember { mutableFloatStateOf(0f) }

    // Proximity auto-scrolling effect
    LaunchedEffect(draggingLayerId, autoScrollSpeed) {
        if (draggingLayerId != null && autoScrollSpeed != 0f) {
            while (isActive) {
                listState.scrollBy(autoScrollSpeed)
                delay(16L) // ~60 FPS
            }
        }
    }

    fun computeClosestSlot(touchY: Float, itemCount: Int): Int {
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isEmpty()) return -1
        var bestSlot = 0
        var bestDist = Float.MAX_VALUE
        for (item in visible) {
            val topBoundary = item.offset.toFloat()
            val bottomBoundary = (item.offset + item.size).toFloat()
            val dTop = kotlin.math.abs(touchY - topBoundary)
            if (dTop < bestDist) {
                bestDist = dTop
                bestSlot = item.index
            }
            val dBottom = kotlin.math.abs(touchY - bottomBoundary)
            if (dBottom < bestDist) {
                bestDist = dBottom
                bestSlot = item.index + 1
            }
        }
        return bestSlot.coerceIn(0, itemCount)
    }

    DraggableFloatingWindow(
        title = "Layers (${layers.size}/${LayerManager.MAX_LAYERS})",
        onClose = onClose,
        containerWidth = containerWidth,
        containerHeight = containerHeight,
        state = state,
        modifier = Modifier.width(220.dp),
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
            Box(modifier = Modifier.fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp)
                        .onGloballyPositioned { coords ->
                            listHeightPx = coords.size.height.toFloat()
                        },
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(
                        items = reversedLayers,
                        key = { _, layer -> layer.id }
                    ) { indexInReversed, layer ->
                        val isActive = (layer.id == activeLayerId)
                        val isTopmost = (indexInReversed == 0)
                        val isBottommost = (indexInReversed == reversedLayers.size - 1)
                        val isBeingDragged = (layer.id == draggingLayerId)

                        LayerCard(
                            layer = layer,
                            isActive = isActive,
                            isDimmed = isBeingDragged,
                            canDelete = canDelete,
                            canMoveUp = !isTopmost,
                            canMoveDown = !isBottommost,
                            onSelect = { onSelectLayer(layer.id) },
                            onToggleVisibility = { onToggleVisibility(layer.id, !layer.isVisible) },
                            onOpacityChange = { onOpacityChange(layer.id, it) },
                            onClear = { onClearLayer(layer.id) },
                            onDelete = { onDeleteLayer(layer.id) },
                            onMoveUp = { onMoveLayerUp(layer.id) },
                            onMoveDown = { onMoveLayerDown(layer.id) },
                            onDragStart = { startOffset ->
                                draggingLayerId = layer.id
                                val itemInfo = listState.layoutInfo.visibleItemsInfo.find { it.key == layer.id }
                                val initialY = (itemInfo?.offset?.toFloat() ?: 0f) + startOffset.y
                                dragTouchYInList = initialY
                                dragGrabOffsetInCard = startOffset.y
                                dropSlotIndex = computeClosestSlot(initialY, reversedLayers.size)
                            },
                            onDrag = { dragDelta ->
                                dragTouchYInList += dragDelta.y

                                if (dragTouchYInList < edgeThreshold) {
                                    val proximity = ((edgeThreshold - dragTouchYInList) / edgeThreshold).coerceIn(0.2f, 1.0f)
                                    autoScrollSpeed = -proximity * 14f
                                } else if (listHeightPx > 0f && dragTouchYInList > listHeightPx - edgeThreshold) {
                                    val proximity = ((dragTouchYInList - (listHeightPx - edgeThreshold)) / edgeThreshold).coerceIn(0.2f, 1.0f)
                                    autoScrollSpeed = proximity * 14f
                                } else {
                                    autoScrollSpeed = 0f
                                }

                                dropSlotIndex = computeClosestSlot(dragTouchYInList, reversedLayers.size)
                            },
                            onDragEnd = {
                                autoScrollSpeed = 0f
                                val fromReversedIdx = reversedLayers.indexOfFirst { it.id == draggingLayerId }
                                val targetSlot = dropSlotIndex
                                if (fromReversedIdx >= 0 && targetSlot >= 0) {
                                    val visualList = reversedLayers.map { it.id }.toMutableList()
                                    val item = visualList.removeAt(fromReversedIdx)
                                    val insertIdx = if (targetSlot > fromReversedIdx) targetSlot - 1 else targetSlot
                                    visualList.add(insertIdx.coerceIn(0, visualList.size), item)
                                    val newStorageOrder = visualList.reversed()
                                    onReorderLayers(newStorageOrder)
                                }
                                draggingLayerId = null
                                dropSlotIndex = -1
                            },
                            onDragCancel = {
                                autoScrollSpeed = 0f
                                draggingLayerId = null
                                dropSlotIndex = -1
                            }
                        )
                    }
                }

                // Glowing insertion indicator as an overlay on top of the list (no layout shifts)
                if (draggingLayerId != null && dropSlotIndex in 0..reversedLayers.size) {
                    val visible = listState.layoutInfo.visibleItemsInfo
                    val lineY = if (dropSlotIndex < reversedLayers.size) {
                        val item = visible.firstOrNull { it.index == dropSlotIndex }
                        item?.offset?.toFloat() ?: if (visible.isNotEmpty() && dropSlotIndex < visible.first().index) 0f else listHeightPx
                    } else {
                        val lastItem = visible.lastOrNull()
                        if (lastItem != null) (lastItem.offset + lastItem.size).toFloat() else listHeightPx
                    }
                    GlowingInsertionLine(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                translationY = lineY - with(density) { 4.dp.toPx() }
                            }
                            .zIndex(20f)
                    )
                }

                // Smooth floating card preview following touch position
                if (draggingLayerId != null) {
                    val draggingLayer = reversedLayers.find { it.id == draggingLayerId }
                    if (draggingLayer != null) {
                        val cardY = (dragTouchYInList - dragGrabOffsetInCard).coerceIn(0f, (listHeightPx - cardHeightPx).coerceAtLeast(0f))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    translationY = cardY
                                    scaleX = 1.03f
                                    scaleY = 1.03f
                                    shadowElevation = 16.dp.toPx()
                                }
                                .zIndex(25f)
                        ) {
                            FloatingLayerCardPreview(layer = draggingLayer)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = Color(0x25FFFFFF), thickness = 1.dp)
            Spacer(modifier = Modifier.height(6.dp))

            // Canvas Background Color Button and Attached Popover
            CanvasBackgroundButton(
                currentColorRgb = backgroundColorRgb,
                onColorChange = onChangeBackgroundColor,
                windowState = state
            )
        }
    }
}

/**
 * Neon glowing insertion indicator line showing target drop location.
 */
@Composable
private fun GlowingInsertionLine(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(1.5.dp))
                .background(Color(0xFF64B5F6))
                .shadow(
                    elevation = 8.dp,
                    shape = RoundedCornerShape(1.5.dp),
                    ambientColor = Color(0xFF64B5F6),
                    spotColor = Color(0xFF64B5F6)
                )
        )
    }
}

/**
 * Attached background color selector with a dedicated button and context popover
 * reusing the full HSV color wheel and convenient preset swatches.
 */
@Composable
private fun CanvasBackgroundButton(
    currentColorRgb: Int,
    onColorChange: (Int) -> Unit,
    windowState: FloatingWindowState
) {
    var showMenu by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { showMenu = !showMenu }
                .background(Color(0xFF22262E))
                .border(
                    width = 1.dp,
                    color = if (showMenu) Color(0xFF64B5F6) else Color(0x35FFFFFF),
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
            color = Color.Transparent
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(Color(currentColorRgb))
                            .border(1.dp, Color(0x66FFFFFF), CircleShape)
                    )
                    Text(
                        text = "Background",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = String.format("#%06X", 0xFFFFFF and currentColorRgb),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0x88FFFFFF)
                    )
                    Icon(
                        imageVector = if (showMenu) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = Color(0x88FFFFFF),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // Attached Context Popover Menu
        if (showMenu) {
            val initialHsv = remember(currentColorRgb) {
                val hsv = FloatArray(3)
                android.graphics.Color.colorToHSV(currentColorRgb, hsv)
                hsv
            }
            var hue by remember { mutableFloatStateOf(if (initialHsv[1] > 0.05f) initialHsv[0] else 40f) }
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

            val bgPresets = listOf(
                0xFFFFFFFF.toInt(), // Pure White
                0xFFF6F4ED.toInt(), // Warm Paper
                0xFFDDD9CE.toInt(), // Kraft Cream
                0xFF32353B.toInt(), // Charcoal Slate
                0xFF181A1F.toInt()  // Deep Black
            )

            // Attach next to the layers floating window
            val attachX = if (windowState.offsetX > 214f) -210 else 210
            val attachY = (-60).coerceAtLeast(-windowState.offsetY.toInt())

            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(x = attachX, y = attachY),
                onDismissRequest = { showMenu = false }
            ) {
                Surface(
                    modifier = Modifier
                        .width(204.dp)
                        .shadow(16.dp, RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0x40FFFFFF), RoundedCornerShape(12.dp)),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xF2181A1F)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Background Color",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            IconButton(
                                onClick = { showMenu = false },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Full HSV Color Wheel
                        HsvColorWheel(
                            hue = hue,
                            onHueChange = { hue = it },
                            saturation = saturation,
                            onSaturationChange = { saturation = it },
                            value = value,
                            onValueChange = { value = it },
                            onColorChanged = { newCol ->
                                onColorChange(newCol)
                            },
                            wheelSize = 145.dp
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(color = Color(0x25FFFFFF), thickness = 1.dp)
                        Spacer(modifier = Modifier.height(6.dp))

                        // Presets Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            bgPresets.forEach { colorInt ->
                                val isSelected = (colorInt == currentColorRgb)
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(Color(colorInt))
                                        .border(
                                            width = if (isSelected) 2.dp else 1.dp,
                                            color = if (isSelected) Color(0xFF64B5F6) else Color(0x35FFFFFF),
                                            shape = CircleShape
                                        )
                                        .clickable {
                                            val hsv = FloatArray(3)
                                            android.graphics.Color.colorToHSV(colorInt, hsv)
                                            hue = hsv[0]
                                            saturation = hsv[1]
                                            value = hsv[2]
                                            onColorChange(colorInt)
                                        }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LayerCard(
    layer: Layer,
    isActive: Boolean,
    isDimmed: Boolean = false,
    canDelete: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onSelect: () -> Unit,
    onToggleVisibility: () -> Unit,
    onOpacityChange: (Float) -> Unit,
    onClear: () -> Unit = {},
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDragStart: (androidx.compose.ui.geometry.Offset) -> Unit = {},
    onDrag: (androidx.compose.ui.geometry.Offset) -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDragCancel: () -> Unit = {}
) {
    val cardBg = if (isActive) Color(0x283949AB) else Color(0xFF20232B)
    val borderColor = if (isActive) Color(0xFF64B5F6) else Color(0x22FFFFFF)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = if (isDimmed) 0.35f else 1.0f
            }
            .clip(RoundedCornerShape(8.dp))
            .border(
                width = 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable { onSelect() },
        color = cardBg,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 5.dp)
        ) {
            // Main Row: Visibility, Thumbnail, Name, Drag Handle, Reorder Arrows, Delete
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
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

                // Layer Name: concise number (e.g. "13" instead of "Layer 13")
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

                // Drag handle with smooth, unstealable drag gestures
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .pointerInput(layer.id) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                var currentPos = down.position
                                onDragStart(currentPos)
                                do {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull()
                                    if (change != null && change.pressed) {
                                        val delta = change.position - currentPos
                                        currentPos = change.position
                                        change.consume()
                                        onDrag(delta)
                                    }
                                } while (event.changes.any { it.pressed })
                                onDragEnd()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.DragHandle,
                        contentDescription = "Drag to reorder",
                        tint = if (isDimmed) Color(0xFF64B5F6) else Color(0x75FFFFFF),
                        modifier = Modifier.size(16.dp)
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

                // Clear Layer Button
                IconButton(
                    onClick = onClear,
                    modifier = Modifier.size(20.dp)
                ) {
                    ClearLayerIcon(
                        tint = Color(0xFFFFCA28),
                        modifier = Modifier.size(14.dp)
                    )
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

/**
 * Lightweight floating card preview rendered in overlay directly tracking stylus/finger.
 */
@Composable
private fun FloatingLayerCardPreview(layer: Layer) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.5.dp, Color(0xFF64B5F6), RoundedCornerShape(8.dp)),
        color = Color(0xFF262C38),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
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
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            }

            val displayName = if (layer.name.startsWith("Layer ", ignoreCase = true)) {
                layer.name.substring(6).trim()
            } else {
                layer.name
            }
            Text(
                text = displayName,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF90CAF9),
                modifier = Modifier.weight(1f)
            )

            Icon(
                imageVector = Icons.Default.DragHandle,
                contentDescription = null,
                tint = Color(0xFF64B5F6),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
