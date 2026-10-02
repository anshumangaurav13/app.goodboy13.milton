package app.goodboy13.milton.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.goodboy13.milton.core.model.BezierControlPoints
import app.goodboy13.milton.core.model.PressureCurveDefaults
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.roundToInt

val DEFAULT_PRESSURE_PRESETS = listOf(
    "Standard" to PressureCurveDefaults.STANDARD,
    "Soft" to PressureCurveDefaults.SOFT,
    "Firm" to PressureCurveDefaults.FIRM,
    "Linear" to PressureCurveDefaults.LINEAR
)

@Composable
fun BezierCurveGraph(
    config: BezierControlPoints,
    onConfigChange: (BezierControlPoints) -> Unit,
    modifier: Modifier = Modifier,
    presets: List<Pair<String, BezierControlPoints>> = DEFAULT_PRESSURE_PRESETS,
    curveColor: Color = Color(0xFF64B5F6),
    handle1Color: Color = Color(0xFF42A5F5), // Blue CP1
    handle2Color: Color = Color(0xFFFFB74D)  // Orange CP2
) {
    var activeHandle by remember { mutableIntStateOf(0) } // 0 = none, 1 = cp1, 2 = cp2
    val currentConfig by rememberUpdatedState(config)
    val currentOnConfigChange by rememberUpdatedState(onConfigChange)

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Coordinate readout header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(handle1Color, CircleShape)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = String.format(Locale.US, "P1:(%.2f,%.2f)", config.cp1x, config.cp1y),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.75f)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(handle2Color, CircleShape)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = String.format(Locale.US, "P2:(%.2f,%.2f)", config.cp2x, config.cp2y),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.75f)
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        // Interactive Graph Canvas Area
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.35f)
                .background(
                    Color(0xFF14171D),
                    RoundedCornerShape(10.dp)
                )
                .border(
                    1.dp,
                    Color(0x35FFFFFF),
                    RoundedCornerShape(10.dp)
                )
                .padding(6.dp),
            contentAlignment = Alignment.Center
        ) {
            val canvasW = constraints.maxWidth.toFloat()
            val canvasH = constraints.maxHeight.toFloat()
            val pad = 6f
            val graphLeft = pad
            val graphRight = canvasW - pad
            val graphTop = pad
            val graphBottom = canvasH - pad
            val graphW = (graphRight - graphLeft).coerceAtLeast(1f)
            val graphH = (graphBottom - graphTop).coerceAtLeast(1f)

            val minP = config.minPercent.coerceIn(0f, 1f)
            val maxP = 1.0f

            fun toScreen(nx: Float, ny: Float): Offset {
                val sx = graphLeft + nx.coerceIn(0f, 1f) * graphW
                val sy = graphBottom - ny.coerceIn(0f, 1f) * graphH
                return Offset(sx, sy)
            }

            fun toNormalized(sx: Float, sy: Float): Pair<Float, Float> {
                val nx = ((sx - graphLeft) / graphW).coerceIn(0f, 1f)
                val ny = ((graphBottom - sy) / graphH).coerceIn(0f, 1f)
                return Pair(round(nx * 100f) / 100f, round(ny * 100f) / 100f)
            }

            val gridColor = Color.White.copy(alpha = 0.12f)
            val diagonalColor = Color.White.copy(alpha = 0.20f)
            val anchorColor = Color.White.copy(alpha = 0.50f)

            val startPos = toScreen(0f, minP)
            val endPos = toScreen(1f, 1.0f)
            val cp1ScreenY = minP + (1.0f - minP) * config.cp1y
            val cp2ScreenY = minP + (1.0f - minP) * config.cp2y
            val cp1Pos = toScreen(config.cp1x, cp1ScreenY)
            val cp2Pos = toScreen(config.cp2x, cp2ScreenY)

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                val d1 = hypot((offset.x - cp1Pos.x).toDouble(), (offset.y - cp1Pos.y).toDouble()).toFloat()
                                val d2 = hypot((offset.x - cp2Pos.x).toDouble(), (offset.y - cp2Pos.y).toDouble()).toFloat()
                                activeHandle = if (d1 <= d2) 1 else 2
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val (nx, ny) = toNormalized(change.position.x, change.position.y)
                                val cfg = currentConfig
                                val curMinP = cfg.minPercent.coerceIn(0f, 1f)
                                val yRange = (1.0f - curMinP).coerceAtLeast(0.01f)
                                val mappedY = ((ny - curMinP) / yRange).coerceIn(0f, 1f)

                                if (activeHandle == 1) {
                                    currentOnConfigChange(cfg.copy(cp1x = nx, cp1y = mappedY, maxPercent = 1.0f))
                                } else if (activeHandle == 2) {
                                    currentOnConfigChange(cfg.copy(cp2x = nx, cp2y = mappedY, maxPercent = 1.0f))
                                }
                            },
                            onDragEnd = { activeHandle = 0 },
                            onDragCancel = { activeHandle = 0 }
                        )
                    }
            ) {
                // 1. Draw 4x4 coordinate grid (5 horizontal lines, 5 vertical lines)
                val dashEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
                for (i in 0..4) {
                    val t = i / 4.0f
                    val y = graphBottom - t * graphH
                    val x = graphLeft + t * graphW

                    // Horizontal grid line
                    drawLine(
                        color = gridColor,
                        start = Offset(graphLeft, y),
                        end = Offset(graphRight, y),
                        strokeWidth = if (i == 0 || i == 4) 1.2f else 0.8f,
                        pathEffect = if (i == 0 || i == 4) null else dashEffect
                    )

                    // Vertical grid line
                    drawLine(
                        color = gridColor,
                        start = Offset(x, graphTop),
                        end = Offset(x, graphBottom),
                        strokeWidth = if (i == 0 || i == 4) 1.2f else 0.8f,
                        pathEffect = if (i == 0 || i == 4) null else dashEffect
                    )
                }

                // 2. Diagonal reference line (0,0) -> (1,1)
                drawLine(
                    color = diagonalColor,
                    start = Offset(graphLeft, graphBottom),
                    end = Offset(graphRight, graphTop),
                    strokeWidth = 1.0f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f), 0f)
                )

                // 3. Min % Guideline threshold if non-trivial
                if (minP > 0.01f) {
                    val y = toScreen(0f, minP).y
                    drawLine(
                        color = Color(0x6626A69A),
                        start = Offset(graphLeft, y),
                        end = Offset(graphRight, y),
                        strokeWidth = 1.0f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
                    )
                }

                // 4. Tangent handle lines
                drawLine(
                    color = handle1Color.copy(alpha = 0.65f),
                    start = startPos,
                    end = cp1Pos,
                    strokeWidth = 1.6f
                )
                drawLine(
                    color = handle2Color.copy(alpha = 0.65f),
                    start = endPos,
                    end = cp2Pos,
                    strokeWidth = 1.6f
                )

                // 5. Continuous Cubic Bezier Curve (scaled to minPercent..maxPercent)
                val curvePath = Path().apply {
                    moveTo(startPos.x, startPos.y)
                    cubicTo(
                        cp1Pos.x, cp1Pos.y,
                        cp2Pos.x, cp2Pos.y,
                        endPos.x, endPos.y
                    )
                }
                drawPath(
                    path = curvePath,
                    color = curveColor,
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                )

                // 6. Anchors at (0, minPercent) and (1, maxPercent)
                drawCircle(
                    color = anchorColor,
                    radius = 3.5.dp.toPx(),
                    center = startPos
                )
                drawCircle(
                    color = anchorColor,
                    radius = 3.5.dp.toPx(),
                    center = endPos
                )

                // 7. Control point draggable handles (knobs)
                // Handle 1 (CP1)
                drawCircle(
                    color = handle1Color,
                    radius = if (activeHandle == 1) 8.dp.toPx() else 6.5.dp.toPx(),
                    center = cp1Pos
                )
                drawCircle(
                    color = Color.White,
                    radius = 2.5.dp.toPx(),
                    center = cp1Pos
                )

                // Handle 2 (CP2)
                drawCircle(
                    color = handle2Color,
                    radius = if (activeHandle == 2) 8.dp.toPx() else 6.5.dp.toPx(),
                    center = cp2Pos
                )
                drawCircle(
                    color = Color.White,
                    radius = 2.5.dp.toPx(),
                    center = cp2Pos
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Min % percentage control (Max is always 100% since brush slider sets maximum)
        ValueDragControl(
            label = "Min",
            value = (config.minPercent * 100f).roundToInt().toFloat(),
            onValueChange = { newMin ->
                val minVal = (newMin / 100f).coerceIn(0f, 1.0f)
                onConfigChange(config.copy(minPercent = minVal, maxPercent = 1.0f))
            },
            valueRange = 0f..100f,
            unit = "%",
            displayDecimals = 0,
            fillColor = Color(0xFF26A69A),
            modifier = Modifier.fillMaxWidth()
        )

        // Preset Chips: single horizontal row of mini curve icons
        if (presets.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for ((label, presetConfig) in presets) {
                    val isSelected = kotlin.math.abs(config.cp1x - presetConfig.cp1x) < 0.04f &&
                            kotlin.math.abs(config.cp1y - presetConfig.cp1y) < 0.04f &&
                            kotlin.math.abs(config.cp2x - presetConfig.cp2x) < 0.04f &&
                            kotlin.math.abs(config.cp2y - presetConfig.cp2y) < 0.04f

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isSelected) Color(0xFF3949AB) else Color(0xFF232730),
                        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF90CAF9)) else null,
                        onClick = {
                            onConfigChange(
                                presetConfig.copy(
                                    minPercent = config.minPercent,
                                    maxPercent = 1.0f
                                )
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(26.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Canvas(modifier = Modifier.size(width = 20.dp, height = 14.dp)) {
                                val w = size.width
                                val h = size.height
                                val path = Path()
                                path.moveTo(0f, h)
                                path.cubicTo(
                                    w * presetConfig.cp1x, h * (1f - presetConfig.cp1y),
                                    w * presetConfig.cp2x, h * (1f - presetConfig.cp2y),
                                    w, 0f
                                )
                                drawPath(
                                    path = path,
                                    color = if (isSelected) Color.White else Color(0xAAFFFFFF),
                                    style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
