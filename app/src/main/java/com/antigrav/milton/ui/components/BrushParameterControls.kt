package com.antigrav.milton.ui.components

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigrav.milton.core.brush.BrushType
import com.antigrav.milton.core.model.BezierControlPoints
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Value drag control:
 * Dragging right / up increases value (up to 500px for size or 100% for opacity).
 * Dragging left / down decreases value.
 * Uses dynamic scaling for high precision at small values and fast sweeping for large values.
 */
@Composable
fun ValueDragControl(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    unit: String = "px",
    displayDecimals: Int = 1,
    fillColor: Color = Color(0xFF64B5F6),
    modifier: Modifier = Modifier
) {
    var isDragging by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF22262E))
            .border(
                width = if (isDragging) 1.5.dp else 1.dp,
                color = if (isDragging) fillColor.copy(alpha = 0.85f) else Color(0x35FFFFFF),
                shape = RoundedCornerShape(10.dp)
            )
            .pointerInput(valueRange) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    isDragging = true
                    var lastX = down.position.x
                    var lastY = down.position.y
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

                            val delta = if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) dx else dy

                            // Dynamic sensitivity
                            val factor = if (valueRange.endInclusive > 100f) {
                                // Size range 1..500px
                                when {
                                    value < 15f -> 0.15f
                                    value < 50f -> 0.35f
                                    value < 150f -> 0.75f
                                    else -> 1.50f
                                }
                            } else {
                                // Opacity range 0.01..1.0
                                0.003f
                            }

                            val nextVal = (value + delta * factor).coerceIn(valueRange.start, valueRange.endInclusive)
                            onValueChange(nextVal)
                            change.consume()
                        }
                    } while (event.changes.any { it.pressed })

                    isDragging = false
                }
            }
    ) {
        // Proportion fill bar
        val fraction = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .background(fillColor.copy(alpha = if (isDragging) 0.35f else 0.22f))
        )

        // Text Content
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.75f)
            )

            val formatted = if (displayDecimals == 0) {
                "${value.roundToInt()} $unit"
            } else {
                String.format(Locale.US, "%.1f %s", value, unit)
            }

            Text(
                text = formatted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (isDragging) fillColor else Color.White
            )
        }
    }
}

/**
 * Dynamic S-curve stroke preview box matching active brush parameters and pressure curve.
 */
@Composable
fun StrokePreviewBox(
    brushType: BrushType,
    brushSize: Float,
    brushOpacity: Float,
    brushColorRgb: Int,
    bezierConfig: BezierControlPoints,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF101216))
            .border(1.dp, Color(0x25FFFFFF), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            val startX = 6f
            val startY = h * 0.72f
            val endX = w - 6f
            val endY = h * 0.28f

            val cp1X = w * 0.35f
            val cp1Y = h * 0.12f
            val cp2X = w * 0.65f
            val cp2Y = h * 0.88f

            val steps = 70
            var prevX = startX
            var prevY = startY

            for (i in 1..steps) {
                val t = i / steps.toFloat()
                val omt = 1f - t

                val curX = omt * omt * omt * startX +
                        3f * omt * omt * t * cp1X +
                        3f * omt * t * t * cp2X +
                        t * t * t * endX
                val curY = omt * omt * omt * startY +
                        3f * omt * omt * t * cp1Y +
                        3f * omt * t * t * cp2Y +
                        t * t * t * endY

                // Tapered pressure profile across the S-curve
                val rawP = kotlin.math.sin(t * Math.PI.toFloat()).coerceIn(0.05f, 1.0f)
                val mappedP = bezierConfig.solveY(rawP)

                // Scaled stroke thickness for preview box
                val previewWidth = (1.5f + (brushSize / 500f).coerceIn(0f, 1f) * 22f) * (0.30f + 0.70f * mappedP)

                val strokeColor = if (brushType == BrushType.ERASER) {
                    Color(0xFFB0BEC5)
                } else {
                    Color(brushColorRgb).copy(alpha = brushOpacity.coerceIn(0.15f, 1.0f))
                }

                drawLine(
                    color = strokeColor,
                    start = Offset(prevX, prevY),
                    end = Offset(curX, curY),
                    strokeWidth = previewWidth * 2f,
                    cap = StrokeCap.Round
                )

                prevX = curX
                prevY = curY
            }
        }
    }
}

/**
 * Floating Tool Parameters Menu appearing upon holding (or tapping active) tool button.
 */
@Composable
fun ToolParametersPopup(
    brushType: BrushType,
    brushSize: Float,
    onBrushSizeChange: (Float) -> Unit,
    brushOpacity: Float,
    onBrushOpacityChange: (Float) -> Unit,
    brushColorRgb: Int,
    bezierConfig: BezierControlPoints,
    onBezierConfigChange: (BezierControlPoints) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .width(284.dp)
            .shadow(elevation = 18.dp, shape = RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp)),
        color = Color(0xF2181A1F),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x35FFFFFF)),
        tonalElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header Row: Tool Icon + Name + Close Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (brushType) {
                        BrushType.PEN -> PenIcon(tint = Color(0xFF90CAF9), modifier = Modifier.size(20.dp))
                        BrushType.PENCIL -> PencilIcon(tint = Color(0xFFFFCC80), modifier = Modifier.size(20.dp))
                        BrushType.PAINTBRUSH -> PaintbrushIcon(tint = Color(0xFFA5D6A7), modifier = Modifier.size(20.dp))
                        BrushType.ERASER -> EraserIcon(tint = Color(0xFFEF9A9A), modifier = Modifier.size(20.dp))
                    }
                    Text(
                        text = brushType.displayName,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White.copy(alpha = 0.70f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Live S-curve stroke preview
            StrokePreviewBox(
                brushType = brushType,
                brushSize = brushSize,
                brushOpacity = brushOpacity,
                brushColorRgb = brushColorRgb,
                bezierConfig = bezierConfig
            )

            // Parameters Section
            Text(
                text = "Parameters",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.65f)
            )

            // Size Scrubber (1px .. 500px)
            ValueDragControl(
                label = "Size",
                value = brushSize,
                onValueChange = onBrushSizeChange,
                valueRange = 1f..500f,
                unit = "px",
                displayDecimals = 1,
                fillColor = Color(0xFF64B5F6)
            )

            // Opacity Scrubber (1% .. 100%)
            ValueDragControl(
                label = "Opacity",
                value = brushOpacity * 100f,
                onValueChange = { onBrushOpacityChange(it / 100f) },
                valueRange = 1f..100f,
                unit = "%",
                displayDecimals = 0,
                fillColor = Color(0xFFFFB74D)
            )

            HorizontalDivider(
                color = Color(0x25FFFFFF),
                thickness = 1.dp,
                modifier = Modifier.padding(vertical = 2.dp)
            )

            // Pressure Response Section with Bezier Curve Editor
            Text(
                text = "Pressure Response",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.65f)
            )

            BezierCurveGraph(
                config = bezierConfig,
                onConfigChange = onBezierConfigChange
            )
        }
    }
}
