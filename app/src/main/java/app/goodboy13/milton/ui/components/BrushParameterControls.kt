package app.goodboy13.milton.ui.components

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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.draw.scale
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.goodboy13.milton.core.brush.BrushType
import app.goodboy13.milton.core.model.BezierControlPoints
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Value drag control:
 * Dragging right / up increases value relative to current value.
 * Dragging left / down decreases value.
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
    val currentValueState = rememberUpdatedState(value)
    val onValueChangeState = rememberUpdatedState(onValueChange)

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
                    var accumulatedValue = currentValueState.value
                    down.consume()

                    val span = valueRange.endInclusive - valueRange.start

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

                            // Dragging right (dx > 0) or up (dy > 0) increases value; left / down decreases
                            val delta = dx + dy

                            val factor = if (span > 150f) {
                                // Continuous proportional sensitivity: slow & precise in low range, swift in large range
                                (0.05f + 0.0055f * accumulatedValue).coerceIn(0.05f, 3.0f)
                            } else {
                                // Percentage ranges (e.g. 1..100%): responsive sweep
                                (span / 300f).coerceAtLeast(0.25f)
                            }

                            accumulatedValue = (accumulatedValue + delta * factor)
                                .coerceIn(valueRange.start, valueRange.endInclusive)
                            onValueChangeState.value(accumulatedValue)
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
 * Dynamic S-curve stroke preview box matching active brush parameters and pressure curves.
 */
@Composable
fun StrokePreviewBox(
    brushType: BrushType,
    brushSize: Float,
    brushOpacity: Float,
    brushColorRgb: Int,
    sizeBezierConfig: BezierControlPoints,
    opacityBezierConfig: BezierControlPoints,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF101216))
            .border(1.dp, Color(0x25FFFFFF), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
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

            val steps = 60
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
                val mappedSizeP = sizeBezierConfig.solveY(rawP)
                val maxAlpha = (brushOpacity * opacityBezierConfig.maxPercent.coerceIn(0f, 1f)).coerceIn(0.001f, 1.0f)
                val minAlpha = opacityBezierConfig.minPercent.coerceIn(0f, 1f).coerceAtMost(maxAlpha)
                val normOpacityY = opacityBezierConfig.solveNormalizedY(rawP)
                val strokeAlpha = (minAlpha + (maxAlpha - minAlpha) * normOpacityY).coerceIn(0.08f, 1.0f)

                // Scaled stroke thickness for preview box
                val previewWidth = (1.5f + (brushSize / 500f).coerceIn(0f, 1f) * 18f) * (0.30f + 0.70f * mappedSizeP)

                val strokeColor = if (brushType == BrushType.ERASER) {
                    Color(0xFFB0BEC5).copy(alpha = strokeAlpha)
                } else {
                    Color(brushColorRgb).copy(alpha = strokeAlpha)
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
 * Draggable Floating Tool Parameters Window.
 * Confined within app bounds, width 204dp to match other floating windows.
 * Switching the active tool updates this window in place.
 * Bezier pressure curves are neatly collapsed under Size and Opacity.
 */
@Composable
fun ToolParametersFloatingWindow(
    brushType: BrushType,
    brushSize: Float,
    onBrushSizeChange: (Float) -> Unit,
    brushOpacity: Float,
    onBrushOpacityChange: (Float) -> Unit,
    brushStabilizer: Float = 0.10f,
    onBrushStabilizerChange: (Float) -> Unit = {},
    brushColorRgb: Int,
    sizeBezierConfig: BezierControlPoints,
    onSizeBezierConfigChange: (BezierControlPoints) -> Unit,
    opacityBezierConfig: BezierControlPoints,
    onOpacityBezierConfigChange: (BezierControlPoints) -> Unit,
    onClose: () -> Unit,
    containerWidth: Int,
    containerHeight: Int,
    state: FloatingWindowState,
    modifier: Modifier = Modifier
) {
    var showSizeDynamics by remember { mutableStateOf(false) }
    var showOpacityDynamics by remember { mutableStateOf(false) }

    DraggableFloatingWindow(
        title = brushType.displayName,
        onClose = onClose,
        containerWidth = containerWidth,
        containerHeight = containerHeight,
        state = state,
        modifier = modifier.width(204.dp),
        headerActions = {
            when (brushType) {
                BrushType.PEN -> PenIcon(tint = Color(0xFF90CAF9), modifier = Modifier.size(16.dp))
                BrushType.PENCIL -> PencilIcon(tint = Color(0xFFFFCC80), modifier = Modifier.size(16.dp))
                BrushType.PAINTBRUSH -> PaintbrushIcon(tint = Color(0xFFA5D6A7), modifier = Modifier.size(16.dp))
                BrushType.ERASER -> EraserIcon(tint = Color(0xFFEF9A9A), modifier = Modifier.size(16.dp))
                BrushType.LASSO -> LassoIcon(tint = Color(0xFFCE93D8), modifier = Modifier.size(16.dp))
            }
        }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Live S-curve stroke preview
            StrokePreviewBox(
                brushType = brushType,
                brushSize = brushSize,
                brushOpacity = brushOpacity,
                brushColorRgb = brushColorRgb,
                sizeBezierConfig = sizeBezierConfig,
                opacityBezierConfig = opacityBezierConfig
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

            // Collapsible Size Dynamics Accordion
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { showSizeDynamics = !showSizeDynamics }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Size Pressure Curve",
                    fontSize = 11.sp,
                    color = if (showSizeDynamics) Color(0xFF64B5F6) else Color.White.copy(alpha = 0.65f),
                    fontWeight = FontWeight.Medium
                )
                Icon(
                    imageVector = if (showSizeDynamics) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = if (showSizeDynamics) Color(0xFF64B5F6) else Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(16.dp)
                )
            }
            AnimatedVisibility(visible = showSizeDynamics) {
                BezierCurveGraph(
                    config = sizeBezierConfig,
                    onConfigChange = onSizeBezierConfigChange,
                    curveColor = Color(0xFF64B5F6)
                )
            }

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

            // Collapsible Opacity Dynamics Accordion
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { showOpacityDynamics = !showOpacityDynamics }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Opacity Pressure Curve",
                    fontSize = 11.sp,
                    color = if (showOpacityDynamics) Color(0xFFFFB74D) else Color.White.copy(alpha = 0.65f),
                    fontWeight = FontWeight.Medium
                )
                Icon(
                    imageVector = if (showOpacityDynamics) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = if (showOpacityDynamics) Color(0xFFFFB74D) else Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(16.dp)
                )
            }
            AnimatedVisibility(visible = showOpacityDynamics) {
                BezierCurveGraph(
                    config = opacityBezierConfig,
                    onConfigChange = onOpacityBezierConfigChange,
                    curveColor = Color(0xFFFFB74D)
                )
            }

            // Stabilizer Scrubber (0% .. 100%)
            ValueDragControl(
                label = "Stabilizer",
                value = brushStabilizer * 100f,
                onValueChange = { onBrushStabilizerChange(it / 100f) },
                valueRange = 0f..100f,
                unit = "%",
                displayDecimals = 0,
                fillColor = Color(0xFF81C784)
            )
        }
    }
}
