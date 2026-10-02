package app.goodboy13.milton.ui.reticle

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.goodboy13.milton.ui.EyedropperReticleState

/**
 * High-precision loupe reticle for eyedropper sampling.
 * Follows stylus / finger while dragging, showing target crosshair and live sampled color loupe.
 */
@Composable
fun EyedropperReticleOverlay(
    state: EyedropperReticleState
) {
    if (!state.isVisible) return

    val density = LocalDensity.current
    val xDp = with(density) { state.screenX.toDp() }
    val yDp = with(density) { state.screenY.toDp() }

    // Offset loupe above the contact point so finger/stylus tip doesn't block the sampled preview
    val loupeOffsetY = if (state.screenY < 140f) 52.dp else (-52).dp

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Center target crosshairs at exact sampled point
        Box(
            modifier = Modifier
                .offset(x = xDp - 10.dp, y = yDp - 10.dp)
                .size(20.dp),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                // Center dot
                drawCircle(color = Color.White, radius = 2.dp.toPx())
                drawCircle(color = Color.Black, radius = 2.dp.toPx(), style = Stroke(width = 0.8.dp.toPx()))
                // 4 Crosshairs
                drawLine(Color.White, Offset(cx - 8.dp.toPx(), cy), Offset(cx - 4.dp.toPx(), cy), strokeWidth = 1.5.dp.toPx())
                drawLine(Color.White, Offset(cx + 4.dp.toPx(), cy), Offset(cx + 8.dp.toPx(), cy), strokeWidth = 1.5.dp.toPx())
                drawLine(Color.White, Offset(cx, cy - 8.dp.toPx()), Offset(cx, cy - 4.dp.toPx()), strokeWidth = 1.5.dp.toPx())
                drawLine(Color.White, Offset(cx, cy + 4.dp.toPx()), Offset(cx, cy + 8.dp.toPx()), strokeWidth = 1.5.dp.toPx())
            }
        }

        // 2. Magnified circular color loupe
        Column(
            modifier = Modifier
                .offset(x = xDp - 24.dp, y = yDp + loupeOffsetY - 24.dp)
                .width(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .shadow(elevation = 12.dp, shape = CircleShape)
                    .clip(CircleShape)
                    .background(Color(state.color))
                    .border(2.5.dp, Color.White, CircleShape)
                    .border(4.dp, Color(0x55000000), CircleShape)
            )
            Surface(
                color = Color(0xD0181A1F),
                shape = RoundedCornerShape(4.dp),
                border = androidx.compose.foundation.BorderStroke(0.5.dp, Color(0x44FFFFFF))
            ) {
                Text(
                    text = String.format("#%06X", 0xFFFFFF and state.color),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
    }
}
