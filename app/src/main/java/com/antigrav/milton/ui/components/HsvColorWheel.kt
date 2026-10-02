package com.antigrav.milton.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

    Canvas(
        modifier = modifier
            .size(wheelSize)
            .pointerInput(wheelSize) {
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

                    touchMode = if (dist >= rInner - 6.dp.toPx()) {
                        WheelTouchMode.HUE
                    } else {
                        WheelTouchMode.SV
                    }

                    var curHue = hue
                    var curSat = saturation
                    var curVal = value

                    if (touchMode == WheelTouchMode.HUE) {
                        var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                        if (angle < 0f) angle += 360f
                        curHue = angle
                        onHueChange(angle)
                    } else {
                        curSat = ((down.position.x - sqLeft) / sqSize).coerceIn(0f, 1f)
                        curVal = (1f - (down.position.y - sqTop) / sqSize).coerceIn(0f, 1f)
                        onSaturationChange(curSat)
                        onValueChange(curVal)
                    }
                    val newCol = android.graphics.Color.HSVToColor(floatArrayOf(curHue, curSat, curVal))
                    onColorChanged(newCol)
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
                                onHueChange(angle)
                            } else {
                                curSat = ((change.position.x - sqLeft) / sqSize).coerceIn(0f, 1f)
                                curVal = (1f - (change.position.y - sqTop) / sqSize).coerceIn(0f, 1f)
                                onSaturationChange(curSat)
                                onValueChange(curVal)
                            }
                            val moveCol = android.graphics.Color.HSVToColor(floatArrayOf(curHue, curSat, curVal))
                            onColorChanged(moveCol)
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
