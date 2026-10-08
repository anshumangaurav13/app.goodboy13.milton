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
import app.goodboy13.milton.ui.LiquifyReticleState

/**
 * High-precision circular cursor overlay for the Liquify tool:
 * - High-contrast cyan boundary indicating the active deformation radius
 * - Subtle dark drop shadow visible against light and dark canvas backgrounds
 * - Center anchor pip for precise stylus positioning
 */
@Composable
fun LiquifyReticleOverlay(
    state: LiquifyReticleState
) {
    if (!state.isVisible || state.radiusScreen <= 0.5f) return

    val density = LocalDensity.current
    val diameterPx = state.radiusScreen * 2f
    val diameterDp = with(density) { diameterPx.toDp() }
    val xDp = with(density) { (state.screenX - state.radiusScreen).toDp() }
    val yDp = with(density) { (state.screenY - state.radiusScreen).toDp() }

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .offset(x = xDp, y = yDp)
                .size(diameterDp)
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = state.radiusScreen

            // 1. Drop shadow / dark outline
            drawCircle(
                color = Color(0x60000000),
                radius = r,
                center = Offset(cx, cy),
                style = Stroke(width = 3.dp.toPx())
            )

            // 2. Crisp bright cyan inner circle
            drawCircle(
                color = Color(0xFF4DD0E1),
                radius = r,
                center = Offset(cx, cy),
                style = Stroke(width = 1.5.dp.toPx())
            )

            // 3. Center anchor dot
            drawCircle(
                color = Color(0x90000000),
                radius = 3.dp.toPx(),
                center = Offset(cx, cy)
            )
            drawCircle(
                color = Color(0xFF4DD0E1),
                radius = 2.dp.toPx(),
                center = Offset(cx, cy)
            )
        }
    }
}
