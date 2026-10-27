package app.goodboy13.milton.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Coordinate mapping for the inner color square:
 * - Top-left (0, 0): Pure White (S=0, V=1)
 * - Bottom-left (0, 1): Pure Black (S=0, V=0)
 * - Entire right edge (1, y): Pure Hue (S=1, V=1)
 * - Top edge (x, 0): Tint ramp from White to Pure Hue (V=1, S=x)
 * - Bottom edge (x, 1): Shade ramp from Black to Pure Hue (V=x, S=1)
 */
object ColorSquareMapping {
    fun touchToSv(nx: Float, ny: Float): Pair<Float, Float> {
        val x = nx.coerceIn(0f, 1f)
        val y = ny.coerceIn(0f, 1f)
        val v = (1f - y * (1f - x)).coerceIn(0f, 1f)
        val s = if (v > 0.001f) (x / v).coerceIn(0f, 1f) else 0f
        return Pair(s, v)
    }

    fun svToCoordinates(saturation: Float, value: Float, fallbackY: Float = 0.5f): Pair<Float, Float> {
        val s = saturation.coerceIn(0f, 1f)
        val v = value.coerceIn(0f, 1f)
        val x = (s * v).coerceIn(0f, 1f)
        val y = if (x < 0.999f) {
            ((1f - v) / (1f - x)).coerceIn(0f, 1f)
        } else {
            fallbackY.coerceIn(0f, 1f)
        }
        return Pair(x, y)
    }
}

enum class WheelTouchMode {
    NONE,
    HUE,
    SV
}

/**
 * Reusable HSV color wheel component:
 * Outer sweep ring for continuous Hue selection [0..360].
 * Inner 2D box for Saturation [0..1] and Value [0..1].
 */
@Composable
fun HsvColorWheel(
    hue: Float,
    onHueChange: (Float) -> Unit,
    saturation: Float,
    onSaturationChange: (Float) -> Unit,
    value: Float,
    onValueChange: (Float) -> Unit,
    onColorChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    wheelSize: Dp = 150.dp
) {
    var touchMode by remember { mutableStateOf(WheelTouchMode.NONE) }
    var currentTouchX by remember { mutableFloatStateOf(0.5f) }
    var currentTouchY by remember { mutableFloatStateOf(0.5f) }

    val currentHue by rememberUpdatedState(hue)
    val currentSaturation by rememberUpdatedState(saturation)
    val currentValue by rememberUpdatedState(value)
    val onHueChangeState by rememberUpdatedState(onHueChange)
    val onSaturationChangeState by rememberUpdatedState(onSaturationChange)
    val onValueChangeState by rememberUpdatedState(onValueChange)
    val onColorChangedState by rememberUpdatedState(onColorChanged)

    Canvas(
        modifier = modifier
            .size(wheelSize)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val cx = w / 2f
                    val cy = h / 2f
                    val ringThickness = 15.dp.toPx()
                    val rOuter = minOf(cx, cy) - 2.dp.toPx()
                    val rInner = rOuter - ringThickness
                    val rSafe = rInner - 5.dp.toPx()
                    val sqSize = (rSafe * sqrt(2.0)).toFloat()
                    val sqLeft = cx - sqSize / 2f
                    val sqTop = cy - sqSize / 2f

                    val dx = down.position.x - cx
                    val dy = down.position.y - cy
                    val dist = hypot(dx, dy)

                    // Robust boundary separation between Outer Hue Ring and Inner SV Square
                    val splitRadius = (rInner + rSafe) * 0.5f
                    touchMode = if (dist >= splitRadius) {
                        WheelTouchMode.HUE
                    } else {
                        WheelTouchMode.SV
                    }

                    var curHue = currentHue
                    var curSat = currentSaturation
                    var curVal = currentValue

                    if (touchMode == WheelTouchMode.HUE) {
                        var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                        if (angle < 0f) angle += 360f
                        curHue = angle
                        onHueChangeState(angle)
                        // If current color is neutral (grayscale/graphite/white), touching a hue should give that vibrant hue!
                        if (curSat < 0.05f) {
                            curSat = 1.0f
                            currentTouchX = 1.0f
                            currentTouchY = 0.5f
                            onSaturationChangeState(1.0f)
                            if (curVal < 0.20f) {
                                curVal = 1.0f
                                onValueChangeState(1.0f)
                            }
                        }
                    } else {
                        val nx = ((down.position.x - sqLeft) / sqSize).coerceIn(0f, 1f)
                        val ny = ((down.position.y - sqTop) / sqSize).coerceIn(0f, 1f)
                        currentTouchX = nx
                        currentTouchY = ny
                        val (mappedS, mappedV) = ColorSquareMapping.touchToSv(nx, ny)
                        curSat = mappedS
                        curVal = mappedV
                        onSaturationChangeState(curSat)
                        onValueChangeState(curVal)
                    }
                    val newCol = android.graphics.Color.HSVToColor(floatArrayOf(curHue, curSat, curVal))
                    onColorChangedState(newCol)
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
                                curHue = angle
                                onHueChangeState(angle)
                                if (curSat < 0.05f) {
                                    curSat = 1.0f
                                    currentTouchX = 1.0f
                                    currentTouchY = 0.5f
                                    onSaturationChangeState(1.0f)
                                }
                            } else {
                                val nx = ((change.position.x - sqLeft) / sqSize).coerceIn(0f, 1f)
                                val ny = ((change.position.y - sqTop) / sqSize).coerceIn(0f, 1f)
                                currentTouchX = nx
                                currentTouchY = ny
                                val (mappedS, mappedV) = ColorSquareMapping.touchToSv(nx, ny)
                                curSat = mappedS
                                curVal = mappedV
                                onSaturationChangeState(curSat)
                                onValueChangeState(curVal)
                            }
                            val moveCol = android.graphics.Color.HSVToColor(floatArrayOf(curHue, curSat, curVal))
                            onColorChangedState(moveCol)
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
        val ringThickness = 15.dp.toPx()
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
            radius = 7.dp.toPx(),
            center = Offset(thumbX, thumbY),
            style = Stroke(width = 2.dp.toPx())
        )
        drawCircle(
            color = Color.White,
            radius = 6.dp.toPx(),
            center = Offset(thumbX, thumbY),
            style = Stroke(width = 1.5.dp.toPx())
        )

        // 2. Inner Saturation-Value Square
        val rSafe = rInner - 5.dp.toPx()
        val sqSize = (rSafe * sqrt(2.0)).toFloat()
        val sqLeft = cx - sqSize / 2f
        val sqTop = cy - sqSize / 2f
        val sqRight = cx + sqSize / 2f
        val sqBottom = cy + sqSize / 2f
        val topLeft = Offset(sqLeft, sqTop)
        val rectSize = Size(sqSize, sqSize)

        // 1. Vertical White-to-Black gradient (Left edge: White at top, Black at bottom)
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.White, Color.Black),
                startY = sqTop,
                endY = sqBottom
            ),
            topLeft = topLeft,
            size = rectSize
        )

        // 2. Horizontal Transparent-to-PureHue gradient (Right edge: Pure Hue)
        val pureHueColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(pureHueColor.copy(alpha = 0f), pureHueColor),
                startX = sqLeft,
                endX = sqRight
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
        val (coordX, coordY) = if (touchMode == WheelTouchMode.SV) {
            Pair(currentTouchX, currentTouchY)
        } else {
            ColorSquareMapping.svToCoordinates(saturation, value, fallbackY = currentTouchY)
        }
        val svX = sqLeft + coordX * sqSize
        val svY = sqTop + coordY * sqSize

        drawCircle(
            color = Color(0x66000000),
            radius = 5.dp.toPx(),
            center = Offset(svX, svY),
            style = Stroke(width = 1.5.dp.toPx())
        )
        drawCircle(
            color = Color.White,
            radius = 4.dp.toPx(),
            center = Offset(svX, svY),
            style = Stroke(width = 1.5.dp.toPx())
        )
    }
}
