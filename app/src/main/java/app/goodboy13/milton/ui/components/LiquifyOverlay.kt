package app.goodboy13.milton.ui.components

import android.graphics.Paint
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.goodboy13.milton.core.liquify.LiquifyMode
import app.goodboy13.milton.core.liquify.LiquifySession
import app.goodboy13.milton.core.viewport.Viewport
import kotlin.math.roundToInt

/**
 * Fullscreen Liquify Overlay:
 * - High-speed Skia drawBitmapMesh deformation rendering.
 * - Wireframe mesh strain grid overlay.
 * - Dashed lasso boundary delimiter.
 * - Floating contextual control dock: mode selection, brush size/strength scrubbers,
 *   grid toggle, reset, cancel, and commit.
 */
@Composable
fun LiquifyOverlay(
    session: LiquifySession,
    viewport: Viewport,
    onModeChange: (LiquifyMode) -> Unit,
    onSizeChange: (Float) -> Unit,
    onStrengthChange: (Float) -> Unit,
    onToggleMeshGrid: (Boolean) -> Unit,
    onReset: () -> Unit,
    onCancel: () -> Unit,
    onCommit: () -> Unit,
    isCanvasFlipped: Boolean = false,
    modifier: Modifier = Modifier
) {
    val screenVerts = remember(session.mesh.vertexCount) { FloatArray(session.mesh.vertexCount * 2) }

    Box(modifier = modifier.fillMaxSize()) {
        // 1. Deformation Mesh Canvas
        Canvas(modifier = Modifier.fillMaxSize()) {
            val nativeCanvas = drawContext.canvas.nativeCanvas
            val paint = Paint().apply { isFilterBitmap = true }

            session.mesh.computeScreenVerts(viewport, screenVerts)

            // Render deformed layer patches
            for ((_, patch) in session.layerPatches) {
                nativeCanvas.drawBitmapMesh(
                    patch.previewBitmap,
                    session.mesh.gridCols,
                    session.mesh.gridRows,
                    screenVerts,
                    0,
                    null,
                    0,
                    paint
                )
            }

            // 2. Wireframe Strain Grid
            if (session.showMeshGrid) {
                val gridPaint = Paint().apply {
                    color = 0x5500E5FF.toInt()
                    strokeWidth = 1f
                    style = Paint.Style.STROKE
                    isAntiAlias = false
                }
                val cols = session.mesh.gridCols
                val rows = session.mesh.gridRows

                for (r in 0..rows) {
                    val rOffset = r * (cols + 1)
                    for (c in 0 until cols) {
                        val i1 = (rOffset + c) * 2
                        val i2 = (rOffset + c + 1) * 2
                        nativeCanvas.drawLine(
                            screenVerts[i1], screenVerts[i1 + 1],
                            screenVerts[i2], screenVerts[i2 + 1],
                            gridPaint
                        )
                    }
                }

                for (c in 0..cols) {
                    for (r in 0 until rows) {
                        val i1 = (r * (cols + 1) + c) * 2
                        val i2 = ((r + 1) * (cols + 1) + c) * 2
                        nativeCanvas.drawLine(
                            screenVerts[i1], screenVerts[i1 + 1],
                            screenVerts[i2], screenVerts[i2 + 1],
                            gridPaint
                        )
                    }
                }
            }

            // 3. Dashed Lasso Boundary
            if (session.polygon.isNotEmpty()) {
                val lassoPath = Path()
                val p0 = viewport.worldToScreen(session.polygon[0].x, session.polygon[0].y)
                lassoPath.moveTo(p0.x, p0.y)
                for (i in 1 until session.polygon.size) {
                    val pt = viewport.worldToScreen(session.polygon[i].x, session.polygon[i].y)
                    lassoPath.lineTo(pt.x, pt.y)
                }
                lassoPath.close()

                drawPath(
                    path = lassoPath,
                    color = Color(0xFF64B5F6).copy(alpha = 0.85f),
                    style = Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f)
                    )
                )
            }
        }

        // 4. Floating Contextual Control Dock
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
                .shadow(16.dp, RoundedCornerShape(24.dp))
                .border(1.dp, Color(0x35FFFFFF), RoundedCornerShape(24.dp)),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xF2181A1F),
            tonalElevation = 8.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mode Buttons
                LiquifyModeButton(
                    mode = LiquifyMode.PUSH,
                    isSelected = session.activeMode == LiquifyMode.PUSH,
                    onClick = { onModeChange(LiquifyMode.PUSH) }
                )
                LiquifyModeButton(
                    mode = LiquifyMode.BLOAT,
                    isSelected = session.activeMode == LiquifyMode.BLOAT,
                    onClick = { onModeChange(LiquifyMode.BLOAT) }
                )
                LiquifyModeButton(
                    mode = LiquifyMode.PINCH,
                    isSelected = session.activeMode == LiquifyMode.PINCH,
                    onClick = { onModeChange(LiquifyMode.PINCH) }
                )
                LiquifyModeButton(
                    mode = LiquifyMode.TWIRL_CW,
                    isSelected = session.activeMode == LiquifyMode.TWIRL_CW,
                    onClick = { onModeChange(LiquifyMode.TWIRL_CW) }
                )
                LiquifyModeButton(
                    mode = LiquifyMode.TWIRL_CCW,
                    isSelected = session.activeMode == LiquifyMode.TWIRL_CCW,
                    onClick = { onModeChange(LiquifyMode.TWIRL_CCW) }
                )
                LiquifyModeButton(
                    mode = LiquifyMode.SMOOTH,
                    isSelected = session.activeMode == LiquifyMode.SMOOTH,
                    onClick = { onModeChange(LiquifyMode.SMOOTH) }
                )

                // Divider
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(26.dp)
                        .background(Color(0x35FFFFFF))
                )

                // Size Scrubber
                LiquifyValueScrubber(
                    label = "Size",
                    valueText = "${session.brushSize.roundToInt()}px",
                    currentValue = session.brushSize,
                    minVal = 10f,
                    maxVal = 500f,
                    factor = 0.5f,
                    onValueChange = onSizeChange
                )

                // Strength Scrubber
                LiquifyValueScrubber(
                    label = "Str",
                    valueText = "${(session.brushStrength * 100f).roundToInt()}%",
                    currentValue = session.brushStrength * 100f,
                    minVal = 5f,
                    maxVal = 100f,
                    factor = 0.25f,
                    onValueChange = { onStrengthChange(it / 100f) }
                )

                // Wireframe Grid Toggle
                IconButton(
                    onClick = { onToggleMeshGrid(!session.showMeshGrid) },
                    modifier = Modifier.size(36.dp)
                ) {
                    MeshGridIcon(
                        tint = if (session.showMeshGrid) Color(0xFF00E5FF) else Color(0x77FFFFFF),
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Reset Button
                IconButton(
                    onClick = onReset,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Reset Mesh",
                        tint = Color(0xAAFFFFFF),
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Cancel (✕)
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(0x33EF5350))
                        .border(1.dp, Color(0x66EF5350), CircleShape)
                        .clickable { onCancel() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel Liquify",
                        tint = Color(0xFFFF8A80),
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Commit (✓)
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2E7D32))
                        .border(1.dp, Color(0xFF81C784), CircleShape)
                        .clickable { onCommit() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Commit Liquify",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LiquifyModeButton(
    mode: LiquifyMode,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() },
        color = if (isSelected) Color(0xFF3949AB) else Color.Transparent,
        shape = RoundedCornerShape(8.dp),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.2.dp, Color(0xFF90CAF9)) else null
    ) {
        Box(contentAlignment = Alignment.Center) {
            val tint = if (isSelected) Color.White else Color(0x88FFFFFF)
            when (mode) {
                LiquifyMode.PUSH -> PushIcon(tint = tint, modifier = Modifier.size(20.dp))
                LiquifyMode.BLOAT -> BloatIcon(tint = tint, modifier = Modifier.size(20.dp))
                LiquifyMode.PINCH -> PinchIcon(tint = tint, modifier = Modifier.size(20.dp))
                LiquifyMode.TWIRL_CW -> TwirlCwIcon(tint = tint, modifier = Modifier.size(20.dp))
                LiquifyMode.TWIRL_CCW -> TwirlCcwIcon(tint = tint, modifier = Modifier.size(20.dp))
                LiquifyMode.SMOOTH -> SmoothIcon(tint = tint, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun LiquifyValueScrubber(
    label: String,
    valueText: String,
    currentValue: Float,
    minVal: Float,
    maxVal: Float,
    factor: Float,
    onValueChange: (Float) -> Unit
) {
    var isDragging by remember { mutableStateOf(false) }
    val curValState = rememberUpdatedState(currentValue)
    val onValChangeState = rememberUpdatedState(onValueChange)

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF22262E))
            .border(
                1.dp,
                if (isDragging) Color(0xFF64B5F6) else Color(0x35FFFFFF),
                RoundedCornerShape(8.dp)
            )
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var lastX = down.position.x
                    var lastY = down.position.y
                    var acc = curValState.value
                    isDragging = true
                    down.consume()

                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull()
                        if (change != null && change.pressed) {
                            val curX = change.position.x
                            val curY = change.position.y
                            val dx = curX - lastX
                            val dy = -(curY - lastY)
                            lastX = curX
                            lastY = curY
                            val delta = dx + dy
                            acc = (acc + delta * factor).coerceIn(minVal, maxVal)
                            onValChangeState.value(acc)
                            change.consume()
                        }
                    } while (event.changes.any { it.pressed })
                    isDragging = false
                }
            }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0x88FFFFFF)
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = valueText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (isDragging) Color(0xFF64B5F6) else Color.White
            )
        }
    }
}
