package app.goodboy13.milton.ui.rail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.goodboy13.milton.core.brush.BrushType
import app.goodboy13.milton.ui.components.EraserIcon
import app.goodboy13.milton.ui.components.PaintbrushIcon
import app.goodboy13.milton.ui.components.PenIcon
import app.goodboy13.milton.ui.components.PencilIcon
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Left-docked vertical rail: Attached directly to left edge, flat on left and rounded on right.
 */
@Composable
fun TabletToolRail(
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
            .clip(railShape)
            .drawBehind {
                val sw = 1.dp.toPx()
                val r = 16.dp.toPx()
                val w = this.size.width
                val h = this.size.height
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(0f, sw / 2f)
                    lineTo(w - r, sw / 2f)
                    arcTo(
                        rect = androidx.compose.ui.geometry.Rect(
                            w - 2f * r + sw / 2f,
                            sw / 2f,
                            w - sw / 2f,
                            2f * r - sw / 2f
                        ),
                        startAngleDegrees = 270f,
                        sweepAngleDegrees = 90f,
                        forceMoveTo = false
                    )
                    lineTo(w - sw / 2f, h - r)
                    arcTo(
                        rect = androidx.compose.ui.geometry.Rect(
                            w - 2f * r + sw / 2f,
                            h - 2f * r + sw / 2f,
                            w - sw / 2f,
                            h - sw / 2f
                        ),
                        startAngleDegrees = 0f,
                        sweepAngleDegrees = 90f,
                        forceMoveTo = false
                    )
                    lineTo(0f, h - sw / 2f)
                }
                drawPath(
                    path = path,
                    color = Color(0x35FFFFFF),
                    style = Stroke(width = sw)
                )
            },
        color = Color(0xF2181A1F),
        shape = railShape,
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
