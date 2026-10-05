package app.goodboy13.milton.ui.reticle

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.goodboy13.milton.ui.EyedropperReticleState

/**
 * Precision circular eyedropper reticle matching digital painting standards:
 * - Colored outer ring reflecting the sampled hue
 * - Transparent central aperture
 * - Thin crosshair plus (+) at the exact sampling center
 * - Clean presentation without text labels
 */
@Composable
fun EyedropperReticleOverlay(
    state: EyedropperReticleState
) {
    if (!state.isVisible) return

    val density = LocalDensity.current
    val reticleSize = 56.dp
    val halfSizePx = with(density) { (reticleSize / 2f).toPx() }
    val xDp = with(density) { (state.screenX - halfSizePx).toDp() }
    val yDp = with(density) { (state.screenY - halfSizePx).toDp() }

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .offset(x = xDp, y = yDp)
                .size(reticleSize)
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f

            val ringWidth = 5.5.dp.toPx()
            val ringRadius = 20.dp.toPx() // center line of stroke

            val outerRadius = ringRadius + ringWidth / 2f
            val innerRadius = ringRadius - ringWidth / 2f

            // 1. Subtle drop shadow behind the ring for depth against any canvas background
            drawCircle(
                color = Color(0x3D000000),
                radius = outerRadius + 0.5.dp.toPx(),
                center = Offset(cx, cy + 1.dp.toPx()),
                style = Stroke(width = ringWidth + 2.dp.toPx())
            )

            // 2. Outer dark border line
            drawCircle(
                color = Color(0x55000000),
                radius = outerRadius,
                center = Offset(cx, cy),
                style = Stroke(width = 1.dp.toPx())
            )

            // 3. Colored ring band
            drawCircle(
                color = Color(state.color),
                radius = ringRadius,
                center = Offset(cx, cy),
                style = Stroke(width = ringWidth)
            )

            // 4. Inner dark border line
            drawCircle(
                color = Color(0x55000000),
                radius = innerRadius,
                center = Offset(cx, cy),
                style = Stroke(width = 1.dp.toPx())
            )

            // Subtle inner & outer white contrast rims (0.5dp) for high contrast on black canvas
            drawCircle(
                color = Color(0x33FFFFFF),
                radius = outerRadius + 0.5.dp.toPx(),
                center = Offset(cx, cy),
                style = Stroke(width = 0.6.dp.toPx())
            )
            drawCircle(
                color = Color(0x33FFFFFF),
                radius = innerRadius - 0.5.dp.toPx(),
                center = Offset(cx, cy),
                style = Stroke(width = 0.6.dp.toPx())
            )

            // 5. Thin center plus (+) marking the exact sampling origin
            val plusArm = 6.dp.toPx()
            val plusWidth = 1.1.dp.toPx()

            // Subtle dark backing for the plus so it remains distinct on pure white pixels
            drawLine(
                color = Color(0x77000000),
                start = Offset(cx - plusArm - 0.5.dp.toPx(), cy),
                end = Offset(cx + plusArm + 0.5.dp.toPx(), cy),
                strokeWidth = plusWidth + 1.2.dp.toPx()
            )
            drawLine(
                color = Color(0x77000000),
                start = Offset(cx, cy - plusArm - 0.5.dp.toPx()),
                end = Offset(cx, cy + plusArm + 0.5.dp.toPx()),
                strokeWidth = plusWidth + 1.2.dp.toPx()
            )

            // Crisp white core
            drawLine(
                color = Color.White,
                start = Offset(cx - plusArm, cy),
                end = Offset(cx + plusArm, cy),
                strokeWidth = plusWidth
            )
            drawLine(
                color = Color.White,
                start = Offset(cx, cy - plusArm),
                end = Offset(cx, cy + plusArm),
                strokeWidth = plusWidth
            )
        }
    }
}
