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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
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
    customValueText: String? = null,
    height: androidx.compose.ui.unit.Dp = 38.dp,
    modifier: Modifier = Modifier
) {
    var isDragging by remember { mutableStateOf(false) }
    val currentValueState = rememberUpdatedState(value)
    val onValueChangeState = rememberUpdatedState(onValueChange)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
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

                            val factor = if (unit != "%" && span > 150f) {
                                // Non-linear proportional sensitivity: sensitive & precise at small sizes, accelerating at large sizes
                                (0.02f + 0.007f * accumulatedValue).coerceIn(0.02f, 7.0f)
                            } else {
                                // Percentage ranges (e.g. 1..100%, 0..200%, 25..400%): responsive sweep
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
        val textPadding = if (height < 36.dp) 8.dp else 12.dp
        val textSize = if (height < 36.dp) 11.sp else 13.sp

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = textPadding),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = textSize,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.75f),
                maxLines = 1,
                softWrap = false
            )

            val formatted = customValueText ?: if (displayDecimals == 0) {
                if (unit == "%") "${value.roundToInt()}%" else "${value.roundToInt()} $unit"
            } else {
                if (unit == "%") String.format(Locale.US, "%.1f%%", value) else String.format(Locale.US, "%.1f %s", value, unit)
            }

            Text(
                text = formatted,
                fontSize = textSize,
                fontWeight = FontWeight.Bold,
                color = if (isDragging) fillColor else Color.White,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/**
 * Dual range drag control with interactive Min and Max handles in a single progress bar.
 * Dragging near the left handle adjusts Min, near the right handle adjusts Max,
 * and dragging the middle region shifts the entire flow window.
 */
@Composable
fun RangeDragControl(
    label: String,
    minValue: Float,
    maxValue: Float,
    onRangeChange: (Float, Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    fillColor: Color = Color(0xFF81C784),
    height: androidx.compose.ui.unit.Dp = 38.dp,
    modifier: Modifier = Modifier
) {
    var isDragging by remember { mutableStateOf(false) }
    val currentMinState = rememberUpdatedState(minValue)
    val currentMaxState = rememberUpdatedState(maxValue)
    val onRangeChangeState = rememberUpdatedState(onRangeChange)

    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
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
                    val widthPx = size.width.toFloat().coerceAtLeast(1f)
                    val span = valueRange.endInclusive - valueRange.start

                    val minFrac = ((currentMinState.value - valueRange.start) / span).coerceIn(0f, 1f)
                    val maxFrac = ((currentMaxState.value - valueRange.start) / span).coerceIn(0f, 1f)
                    val touchFrac = (down.position.x / widthPx).coerceIn(0f, 1f)

                    // Target: 0 = Min, 1 = Max, 2 = Window shift
                    val distToMin = kotlin.math.abs(touchFrac - minFrac)
                    val distToMax = kotlin.math.abs(touchFrac - maxFrac)
                    val target = when {
                        distToMin < 0.14f && distToMin <= distToMax -> 0
                        distToMax < 0.14f -> 1
                        touchFrac in minFrac..maxFrac -> 2
                        touchFrac < minFrac -> 0
                        else -> 1
                    }

                    var lastX = down.position.x
                    down.consume()

                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull()
                        if (change != null && change.pressed) {
                            val curX = change.position.x
                            val dx = curX - lastX
                            lastX = curX

                            val curMin = currentMinState.value
                            val curMax = currentMaxState.value

                            when (target) {
                                0 -> {
                                    val frac = (curX / widthPx).coerceIn(0f, 1f)
                                    val newVal = (valueRange.start + frac * span).coerceIn(valueRange.start, curMax - 0.02f)
                                    onRangeChangeState.value(newVal, curMax)
                                }
                                1 -> {
                                    val frac = (curX / widthPx).coerceIn(0f, 1f)
                                    val newVal = (valueRange.start + frac * span).coerceIn(curMin + 0.02f, valueRange.endInclusive)
                                    onRangeChangeState.value(curMin, newVal)
                                }
                                2 -> {
                                    val dVal = (dx / widthPx) * span
                                    val windowSpan = curMax - curMin
                                    val newMin = (curMin + dVal).coerceIn(valueRange.start, valueRange.endInclusive - windowSpan)
                                    val newMax = (newMin + windowSpan).coerceIn(valueRange.start + windowSpan, valueRange.endInclusive)
                                    onRangeChangeState.value(newMin, newMax)
                                }
                            }
                            change.consume()
                        }
                    } while (event.changes.any { it.pressed })

                    isDragging = false
                }
            }
    ) {
        val span = valueRange.endInclusive - valueRange.start
        val minFrac = if (span > 0f) ((minValue - valueRange.start) / span).coerceIn(0f, 1f) else 0f
        val maxFrac = if (span > 0f) ((maxValue - valueRange.start) / span).coerceIn(0f, 1f) else 1f
        val widthDp = maxWidth

        val leftOffsetDp = widthDp * minFrac
        val fillWidthDp = (widthDp * (maxFrac - minFrac)).coerceAtLeast(4.dp)

        // Range fill bar
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .offset(x = leftOffsetDp)
                .width(fillWidthDp)
                .background(fillColor.copy(alpha = if (isDragging) 0.35f else 0.22f))
        )

        // Min handle line
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .offset(x = leftOffsetDp)
                .width(2.dp)
                .background(fillColor.copy(alpha = 0.90f))
        )

        // Max handle line
        val rightOffsetDp = (widthDp * maxFrac) - 2.dp
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .offset(x = rightOffsetDp.coerceAtLeast(0.dp))
                .width(2.dp)
                .background(fillColor.copy(alpha = 0.90f))
        )

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
                color = Color.White.copy(alpha = 0.75f),
                maxLines = 1
            )

            Text(
                text = "${(minValue * 100).roundToInt()}% – ${(maxValue * 100).roundToInt()}%",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (isDragging) fillColor else Color.White,
                maxLines = 1
            )
        }
    }
}

enum class AccordionPanel {
    NONE, SIZE_CURVE, OPACITY_CURVE, TILT_CURVE, PAPER_TEXTURE
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
    brushTilt: Float = 0.5f,
    tiltBezierConfig: BezierControlPoints = BezierControlPoints(),
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

                // Scaled stroke thickness for preview box using tiltBezierConfig
                val tiltResponse = if (brushType == BrushType.PENCIL) tiltBezierConfig.solveY(brushTilt.coerceIn(0f, 1f)) else 0f
                val tiltFactor = if (brushType == BrushType.PENCIL) 1.0f + tiltResponse * (brushTilt * 1.5f) else 1.0f
                val previewWidth = (1.5f + (brushSize / 1000f).coerceIn(0f, 1f) * 18f) * (0.30f + 0.70f * mappedSizeP) * tiltFactor

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
 * Compact curve toggle button next to Size, Opacity, and Tilt scrubbers.
 */
@Composable
fun CurveToggleButton(
    isActive: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isActive) activeColor.copy(alpha = 0.22f) else Color(0xFF22262E))
            .border(
                width = if (isActive) 1.5.dp else 1.dp,
                color = if (isActive) activeColor else Color(0x35FFFFFF),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(18.dp)) {
            val w = size.width
            val h = size.height
            val path = Path().apply {
                moveTo(2f, h - 2f)
                cubicTo(w * 0.35f, h * 0.85f, w * 0.65f, h * 0.15f, w - 2f, 2f)
            }
            drawPath(
                path = path,
                color = if (isActive) activeColor else Color.White.copy(alpha = 0.70f),
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )
            drawCircle(
                color = if (isActive) activeColor else Color.White.copy(alpha = 0.90f),
                radius = 2.2.dp.toPx(),
                center = Offset(w * 0.5f, h * 0.5f)
            )
        }
    }
}

val LEAD_GRADES = listOf(
    "9H", "8H", "7H", "6H", "5H", "4H", "3H", "2H", "H",
    "HB",
    "B", "2B", "3B", "4B", "5B", "6B", "7B", "8B", "9B"
)

fun gradeToHardness(index: Int): Float {
    val clamped = index.coerceIn(0, LEAD_GRADES.lastIndex)
    return if (clamped <= 9) {
        0.08f + (0.45f - 0.08f) * (clamped / 9f)
    } else {
        0.45f + (1.00f - 0.45f) * ((clamped - 9) / 9f)
    }
}

fun hardnessToGradeIndex(hardness: Float): Int {
    var bestIndex = 11 // default 2B
    var bestDiff = Float.MAX_VALUE
    for (i in LEAD_GRADES.indices) {
        val h = gradeToHardness(i)
        val diff = kotlin.math.abs(h - hardness)
        if (diff < bestDiff) {
            bestDiff = diff
            bestIndex = i
        }
    }
    return bestIndex
}

/**
 * Tactile discrete lead grade scrubber stepping smoothly from 9H to 9B with HB in the middle.
 * Displays only the discrete grade without any float/numbers.
 */
@Composable
fun DiscreteLeadGradeControl(
    hardness: Float,
    onHardnessChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    fillColor: Color = Color(0xFFFFCC80)
) {
    val currentIndex = remember(hardness) { hardnessToGradeIndex(hardness) }
    val currentOnHardnessChange = rememberUpdatedState(onHardnessChange)
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
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    isDragging = true
                    var accumulatedDx = 0f
                    var activeIndex = currentIndex
                    down.consume()

                    val pxPerStep = 18f

                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull()
                        if (change != null && change.pressed) {
                            val dx = change.position.x - change.previousPosition.x
                            accumulatedDx += dx

                            val stepsDelta = (accumulatedDx / pxPerStep).toInt()
                            if (stepsDelta != 0) {
                                val newIndex = (activeIndex + stepsDelta).coerceIn(0, LEAD_GRADES.lastIndex)
                                if (newIndex != activeIndex) {
                                    activeIndex = newIndex
                                    accumulatedDx -= stepsDelta * pxPerStep
                                    currentOnHardnessChange.value(gradeToHardness(newIndex))
                                }
                            }
                            change.consume()
                        }
                    } while (event.changes.any { it.pressed })

                    isDragging = false
                }
            }
    ) {
        val fraction = (currentIndex.toFloat() / LEAD_GRADES.lastIndex.toFloat()).coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .background(fillColor.copy(alpha = if (isDragging) 0.35f else 0.22f))
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Lead Grade",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.75f)
            )

            Text(
                text = LEAD_GRADES[currentIndex],
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = if (isDragging) fillColor else Color.White
            )
        }
    }
}

/**
 * Draggable Floating Tool Parameters Window.
 * Confined within app bounds, width 204dp to match other floating windows.
 * Switching the active tool updates this window in place.
 * Curve controls are toggled via dedicated buttons directly next to the Size & Opacity sliders.
 */
@Composable
fun ToolParametersFloatingWindow(
    brushType: BrushType,
    brushSize: Float,
    onBrushSizeChange: (Float) -> Unit,
    brushOpacity: Float,
    onBrushOpacityChange: (Float) -> Unit,
    brushHardness: Float = 0.70f,
    onBrushHardnessChange: (Float) -> Unit = {},
    brushTilt: Float = 0.50f,
    onBrushTiltChange: (Float) -> Unit = {},
    brushStabilizer: Float = 0.10f,
    onBrushStabilizerChange: (Float) -> Unit = {},
    brushColorRgb: Int,
    sizeBezierConfig: BezierControlPoints,
    onSizeBezierConfigChange: (BezierControlPoints) -> Unit,
    opacityBezierConfig: BezierControlPoints,
    onOpacityBezierConfigChange: (BezierControlPoints) -> Unit,
    tiltBezierConfig: BezierControlPoints = BezierControlPoints(),
    onTiltBezierConfigChange: (BezierControlPoints) -> Unit = {},
    grainType: Int = app.goodboy13.milton.core.constants.BrushDefaults.GRAIN_MEDIUM,
    onGrainTypeChange: (Int) -> Unit = {},
    grainScale: Float = 1.0f,
    onGrainScaleChange: (Float) -> Unit = {},
    grainDepth: Float = 0.60f,
    onGrainDepthChange: (Float) -> Unit = {},
    particleNoise: Float = app.goodboy13.milton.core.constants.BrushDefaults.DEFAULT_PARTICLE_NOISE,
    onParticleNoiseChange: (Float) -> Unit = {},
    minDensity: Float = app.goodboy13.milton.core.constants.BrushDefaults.DEFAULT_MIN_DENSITY,
    onMinDensityChange: (Float) -> Unit = {},
    maxDensity: Float = app.goodboy13.milton.core.constants.BrushDefaults.DEFAULT_MAX_DENSITY,
    onMaxDensityChange: (Float) -> Unit = {},
    activePreset: String = app.goodboy13.milton.core.constants.BrushDefaults.DEFAULT_GRAPHITE_PRESET,
    onSelectGraphitePreset: (app.goodboy13.milton.core.constants.GraphitePreset) -> Unit = {},
    customPresets: List<app.goodboy13.milton.core.constants.GraphitePreset> = emptyList(),
    onSaveCustomPreset: (String) -> Unit = {},
    onDeleteCustomPreset: (String) -> Unit = {},
    onClose: () -> Unit,
    containerWidth: Int,
    containerHeight: Int,
    state: FloatingWindowState,
    modifier: Modifier = Modifier
) {
    var activeAccordion by remember { mutableStateOf(AccordionPanel.NONE) }
    var isSavingPreset by remember { mutableStateOf(false) }
    var newPresetName by remember { mutableStateOf("") }
    var presetToDelete by remember { mutableStateOf<String?>(null) }

    if (presetToDelete != null) {
        val nameToDelete = presetToDelete!!
        AlertDialog(
            onDismissRequest = { presetToDelete = null },
            title = {
                Text(
                    text = "Delete Preset",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Delete \"$nameToDelete\"? This cannot be undone.",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFFE57373))
                        .clickable {
                            onDeleteCustomPreset(nameToDelete)
                            presetToDelete = null
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("Delete", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF2C313C))
                        .clickable { presetToDelete = null }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
                }
            },
            containerColor = Color(0xFF1E222A),
            shape = RoundedCornerShape(12.dp)
        )
    }

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
                BrushType.LIQUIFY -> LiquifyIcon(tint = Color(0xFF80DEEA), modifier = Modifier.size(16.dp))
            }
        }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (brushType == BrushType.LASSO) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF22262E))
                        .border(1.dp, Color(0x30FFFFFF), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = "Draw around elements with stylus to select & transform.\n\nOpen Layers menu to select multiple layers.",
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
            } else if (brushType == BrushType.LIQUIFY) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF22262E))
                        .border(1.dp, Color(0x30FFFFFF), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = "Draw around elements to begin Liquify deformation.\n\nModes: Push, Bloat, Pinch, Twirl CW/CCW, and Smooth.",
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
            } else {
                // Unified Graphite Presets for Drawing Tool: 2-Row Horizontal Scrolling Panel with Custom Preset Saving
                if (brushType == BrushType.PENCIL) {
                    val allPresets = remember(customPresets) {
                        app.goodboy13.milton.core.constants.BrushDefaults.GRAPHITE_PRESETS + customPresets
                    }
                    val row1 = remember(allPresets) {
                        allPresets.filterIndexed { index, _ -> index % 2 == 0 }
                    }
                    val row2 = remember(allPresets) {
                        allPresets.filterIndexed { index, _ -> index % 2 != 0 }
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // Row 1 of Presets
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                row1.forEach { p ->
                                    val isSelected = activePreset == p.name
                                    Box(
                                        modifier = Modifier
                                            .height(26.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (isSelected) Color(0xFFFFCC80).copy(alpha = 0.25f) else Color(0xFF22262E))
                                            .border(
                                                width = if (isSelected) 1.5.dp else 1.dp,
                                                color = if (isSelected) Color(0xFFFFCC80) else Color(0x35FFFFFF),
                                                shape = RoundedCornerShape(6.dp)
                                            )
                                            .clickable { onSelectGraphitePreset(p) }
                                            .padding(horizontal = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                text = p.name,
                                                fontSize = 10.5.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isSelected) Color(0xFFFFCC80) else Color.White.copy(alpha = 0.85f),
                                                maxLines = 1
                                            )
                                            if (p.isCustom) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(16.dp)
                                                        .clip(CircleShape)
                                                        .background(Color(0x35FFFFFF))
                                                        .clickable { presetToDelete = p.name },
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Close,
                                                        contentDescription = "Delete Preset",
                                                        tint = Color.White.copy(alpha = 0.90f),
                                                        modifier = Modifier.size(9.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                if (row1.size <= row2.size) {
                                    Box(
                                        modifier = Modifier
                                            .height(26.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (isSavingPreset) Color(0xFFFFCC80).copy(alpha = 0.20f) else Color(0xFF22262E))
                                            .border(
                                                width = 1.dp,
                                                color = if (isSavingPreset) Color(0xFFFFCC80) else Color(0x55FFFFFF),
                                                shape = RoundedCornerShape(6.dp)
                                            )
                                            .clickable { isSavingPreset = !isSavingPreset }
                                            .padding(horizontal = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Add,
                                                contentDescription = "Save Preset",
                                                tint = if (isSavingPreset) Color(0xFFFFCC80) else Color.White.copy(alpha = 0.85f),
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Text(
                                                text = "Save",
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (isSavingPreset) Color(0xFFFFCC80) else Color.White.copy(alpha = 0.85f),
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }

                            // Row 2 of Presets
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                row2.forEach { p ->
                                    val isSelected = activePreset == p.name
                                    Box(
                                        modifier = Modifier
                                            .height(26.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (isSelected) Color(0xFFFFCC80).copy(alpha = 0.25f) else Color(0xFF22262E))
                                            .border(
                                                width = if (isSelected) 1.5.dp else 1.dp,
                                                color = if (isSelected) Color(0xFFFFCC80) else Color(0x35FFFFFF),
                                                shape = RoundedCornerShape(6.dp)
                                            )
                                            .clickable { onSelectGraphitePreset(p) }
                                            .padding(horizontal = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                text = p.name,
                                                fontSize = 10.5.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isSelected) Color(0xFFFFCC80) else Color.White.copy(alpha = 0.85f),
                                                maxLines = 1
                                            )
                                            if (p.isCustom) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(16.dp)
                                                        .clip(CircleShape)
                                                        .background(Color(0x35FFFFFF))
                                                        .clickable { presetToDelete = p.name },
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Close,
                                                        contentDescription = "Delete Preset",
                                                        tint = Color.White.copy(alpha = 0.90f),
                                                        modifier = Modifier.size(9.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                if (row1.size > row2.size) {
                                    Box(
                                        modifier = Modifier
                                            .height(26.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (isSavingPreset) Color(0xFFFFCC80).copy(alpha = 0.20f) else Color(0xFF22262E))
                                            .border(
                                                width = 1.dp,
                                                color = if (isSavingPreset) Color(0xFFFFCC80) else Color(0x55FFFFFF),
                                                shape = RoundedCornerShape(6.dp)
                                            )
                                            .clickable { isSavingPreset = !isSavingPreset }
                                            .padding(horizontal = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Add,
                                                contentDescription = "Save Preset",
                                                tint = if (isSavingPreset) Color(0xFFFFCC80) else Color.White.copy(alpha = 0.85f),
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Text(
                                                text = "Save",
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (isSavingPreset) Color(0xFFFFCC80) else Color.White.copy(alpha = 0.85f),
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Inline Save Preset Name Card
                        AnimatedVisibility(visible = isSavingPreset) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF181B20))
                                    .border(1.dp, Color(0xFFFFCC80).copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                BasicTextField(
                                    value = newPresetName,
                                    onValueChange = { newPresetName = it },
                                    singleLine = true,
                                    textStyle = TextStyle(
                                        color = Color.White,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Medium
                                    ),
                                    modifier = Modifier.weight(1f),
                                    decorationBox = { innerTextField ->
                                        if (newPresetName.isEmpty()) {
                                            Text(
                                                text = "Preset name...",
                                                color = Color.White.copy(alpha = 0.45f),
                                                fontSize = 11.sp
                                            )
                                        }
                                        innerTextField()
                                    }
                                )
                                Box(
                                    modifier = Modifier
                                        .height(24.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xFFFFCC80))
                                        .clickable {
                                            val trimmed = newPresetName.trim()
                                            if (trimmed.isNotEmpty()) {
                                                onSaveCustomPreset(trimmed)
                                                newPresetName = ""
                                                isSavingPreset = false
                                            }
                                        }
                                        .padding(horizontal = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("Save", color = Color(0xFF121418), fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                                }
                                Box(
                                    modifier = Modifier
                                        .height(24.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xFF282C35))
                                        .clickable {
                                            newPresetName = ""
                                            isSavingPreset = false
                                        }
                                        .padding(horizontal = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("✕", color = Color.White.copy(alpha = 0.7f), fontSize = 10.5.sp)
                                }
                            }
                        }
                    }
                }

                // Live S-curve stroke preview
                StrokePreviewBox(
                    brushType = brushType,
                    brushSize = brushSize,
                    brushOpacity = brushOpacity,
                    brushColorRgb = brushColorRgb,
                    sizeBezierConfig = sizeBezierConfig,
                    opacityBezierConfig = opacityBezierConfig,
                    brushTilt = brushTilt,
                    tiltBezierConfig = tiltBezierConfig
                )

                // 1. Size Slider with Curve Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ValueDragControl(
                        label = "Size",
                        value = brushSize,
                        onValueChange = onBrushSizeChange,
                        valueRange = 1f..1000f,
                        unit = "px",
                        displayDecimals = 1,
                        fillColor = Color(0xFF64B5F6),
                        modifier = Modifier.weight(1f)
                    )
                    CurveToggleButton(
                        isActive = activeAccordion == AccordionPanel.SIZE_CURVE,
                        activeColor = Color(0xFF64B5F6),
                        onClick = {
                            activeAccordion = if (activeAccordion == AccordionPanel.SIZE_CURVE) {
                                AccordionPanel.NONE
                            } else {
                                AccordionPanel.SIZE_CURVE
                            }
                        }
                    )
                }

                AnimatedVisibility(visible = activeAccordion == AccordionPanel.SIZE_CURVE) {
                    BezierCurveGraph(
                        config = sizeBezierConfig,
                        onConfigChange = onSizeBezierConfigChange,
                        curveColor = Color(0xFF64B5F6)
                    )
                }

                // 2. Opacity Slider with Curve Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ValueDragControl(
                        label = "Opacity",
                        value = brushOpacity * 100f,
                        onValueChange = { onBrushOpacityChange(it / 100f) },
                        valueRange = 1f..100f,
                        unit = "%",
                        displayDecimals = 0,
                        fillColor = Color(0xFFFFB74D),
                        modifier = Modifier.weight(1f)
                    )
                    CurveToggleButton(
                        isActive = activeAccordion == AccordionPanel.OPACITY_CURVE,
                        activeColor = Color(0xFFFFB74D),
                        onClick = {
                            activeAccordion = if (activeAccordion == AccordionPanel.OPACITY_CURVE) {
                                AccordionPanel.NONE
                            } else {
                                AccordionPanel.OPACITY_CURVE
                            }
                        }
                    )
                }

                AnimatedVisibility(visible = activeAccordion == AccordionPanel.OPACITY_CURVE) {
                    BezierCurveGraph(
                        config = opacityBezierConfig,
                        onConfigChange = onOpacityBezierConfigChange,
                        curveColor = Color(0xFFFFB74D)
                    )
                }

                // Hardness / Edge Sharpness Slider (5% .. 98%)
                ValueDragControl(
                    label = "Hardness",
                    value = brushHardness * 100f,
                    onValueChange = { onBrushHardnessChange(it / 100f) },
                    valueRange = 5f..98f,
                    unit = "%",
                    displayDecimals = 0,
                    fillColor = Color(0xFFFFB74D)
                )

                // Pencil Specific Controls: 3. Noise, 4. Tilt, 5. Paper Texture, 6. Deposition Flow
                if (brushType == BrushType.PENCIL) {
                    // Particle Noise / Stipple Slider (0% .. 100%)
                    ValueDragControl(
                        label = "Noise",
                        value = particleNoise * 100f,
                        onValueChange = { onParticleNoiseChange(it / 100f) },
                        valueRange = 0f..100f,
                        unit = "%",
                        displayDecimals = 0,
                        fillColor = Color(0xFFFFCC80)
                    )

                    // 4. Tilt Shading Control with Curve Button (0% .. 200%)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ValueDragControl(
                            label = "Tilt",
                            value = brushTilt * 100f,
                            onValueChange = { onBrushTiltChange(it / 100f) },
                            valueRange = 0f..200f,
                            unit = "%",
                            displayDecimals = 0,
                            fillColor = Color(0xFF80DEEA),
                            modifier = Modifier.weight(1f)
                        )
                        CurveToggleButton(
                            isActive = activeAccordion == AccordionPanel.TILT_CURVE,
                            activeColor = Color(0xFF80DEEA),
                            onClick = {
                                activeAccordion = if (activeAccordion == AccordionPanel.TILT_CURVE) {
                                    AccordionPanel.NONE
                                } else {
                                    AccordionPanel.TILT_CURVE
                                }
                            }
                        )
                    }

                    AnimatedVisibility(visible = activeAccordion == AccordionPanel.TILT_CURVE) {
                        BezierCurveGraph(
                            config = tiltBezierConfig,
                            onConfigChange = onTiltBezierConfigChange,
                            curveColor = Color(0xFF80DEEA)
                        )
                    }

                    // 5. Paper Texture Collapsible Card (Mutually exclusive with curves)
                    val grainNames = app.goodboy13.milton.core.constants.BrushDefaults.GRAIN_NAMES
                    val currentGrainName = grainNames.getOrElse(grainType) { "Medium" }
                    val isTextureExpanded = activeAccordion == AccordionPanel.PAPER_TEXTURE

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF22262E))
                            .border(
                                1.dp,
                                if (isTextureExpanded) Color(0xFFFFCC80).copy(alpha = 0.85f) else Color(0x35FFFFFF),
                                RoundedCornerShape(10.dp)
                            )
                            .clickable {
                                activeAccordion = if (isTextureExpanded) AccordionPanel.NONE else AccordionPanel.PAPER_TEXTURE
                            }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Paper Texture",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.White.copy(alpha = 0.85f)
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = currentGrainName,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFFCC80)
                                )
                                Icon(
                                    imageVector = if (isTextureExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = if (isTextureExpanded) "Collapse" else "Expand",
                                    tint = Color(0xFFFFCC80),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    AnimatedVisibility(visible = isTextureExpanded) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF181B20))
                                .border(1.dp, Color(0x25FFFFFF), RoundedCornerShape(10.dp))
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Preset buttons Row 1: Smooth, Medium, Rough
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                listOf(
                                    0 to "Smooth",
                                    1 to "Medium",
                                    2 to "Rough"
                                ).forEach { (id, name) ->
                                    val isSelected = grainType == id
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(28.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (isSelected) Color(0xFFFFCC80).copy(alpha = 0.25f) else Color(0xFF22262E))
                                            .border(
                                                width = if (isSelected) 1.5.dp else 1.dp,
                                                color = if (isSelected) Color(0xFFFFCC80) else Color(0x35FFFFFF),
                                                shape = RoundedCornerShape(6.dp)
                                            )
                                            .clickable { onGrainTypeChange(id) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = name,
                                            fontSize = 11.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) Color(0xFFFFCC80) else Color.White.copy(alpha = 0.75f),
                                            maxLines = 1
                                        )
                                    }
                                }
                            }

                            // Preset buttons Row 2: Canvas, Laid
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                listOf(
                                    3 to "Canvas",
                                    4 to "Laid"
                                ).forEach { (id, name) ->
                                    val isSelected = grainType == id
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(28.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (isSelected) Color(0xFFFFCC80).copy(alpha = 0.25f) else Color(0xFF22262E))
                                            .border(
                                                width = if (isSelected) 1.5.dp else 1.dp,
                                                color = if (isSelected) Color(0xFFFFCC80) else Color(0x35FFFFFF),
                                                shape = RoundedCornerShape(6.dp)
                                            )
                                            .clickable { onGrainTypeChange(id) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = name,
                                            fontSize = 11.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) Color(0xFFFFCC80) else Color.White.copy(alpha = 0.75f),
                                            maxLines = 1
                                        )
                                    }
                                }
                            }

                            // Slider 1: Scale (25% .. 400%)
                            ValueDragControl(
                                label = "Scale",
                                value = grainScale * 100f,
                                onValueChange = { onGrainScaleChange(it / 100f) },
                                valueRange = 25f..400f,
                                unit = "%",
                                displayDecimals = 0,
                                fillColor = Color(0xFFFFCC80),
                                height = 34.dp
                            )

                            // Slider 2: Depth / Roughness (0% .. 100%)
                            ValueDragControl(
                                label = "Depth",
                                value = grainDepth * 100f,
                                onValueChange = { onGrainDepthChange(it / 100f) },
                                valueRange = 0f..100f,
                                unit = "%",
                                displayDecimals = 0,
                                fillColor = Color(0xFFFFCC80),
                                height = 34.dp
                            )
                        }
                    }

                    // 6. Deposition Flow Dual-Range Slider (Min & Max in a single non-overflowing track)
                    RangeDragControl(
                        label = "Flow",
                        minValue = minDensity,
                        maxValue = maxDensity,
                        onRangeChange = { newMin, newMax ->
                            onMinDensityChange(newMin)
                            onMaxDensityChange(newMax)
                        },
                        valueRange = 0f..1f,
                        fillColor = Color(0xFF81C784)
                    )
                }

                // Stabilizer Slider (0% .. 100%)
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
}
