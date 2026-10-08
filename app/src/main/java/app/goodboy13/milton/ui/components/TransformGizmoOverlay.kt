package app.goodboy13.milton.ui.components

import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.goodboy13.milton.core.model.Vec2
import app.goodboy13.milton.core.selection.SelectionState
import app.goodboy13.milton.core.selection.TransformSession
import app.goodboy13.milton.core.viewport.Viewport
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private enum class DragMode {
    NONE,
    TRANSLATE,
    ROTATE,
    SCALE_TL,
    SCALE_TR,
    SCALE_BR,
    SCALE_BL
}

@Composable
fun TransformGizmoOverlay(
    selectionState: SelectionState,
    viewport: Viewport,
    onUpdateTransform: (
        dx: Float?,
        dy: Float?,
        sx: Float?,
        sy: Float?,
        rot: Float?,
        flipH: Boolean?,
        flipV: Boolean?
    ) -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (selectionState) {
        is SelectionState.DrawingLasso -> {
            LassoDrawingCanvas(
                points = selectionState.points,
                viewport = viewport,
                modifier = modifier
            )
        }
        is SelectionState.ActiveTransform -> {
            TransformSessionOverlay(
                session = selectionState.session,
                viewport = viewport,
                onUpdateTransform = onUpdateTransform,
                onCommit = onCommit,
                onCancel = onCancel,
                onReset = onReset,
                modifier = modifier
            )
        }
        SelectionState.Idle -> {}
    }
}

@Composable
private fun LassoDrawingCanvas(
    points: List<Vec2>,
    viewport: Viewport,
    modifier: Modifier = Modifier
) {
    if (points.isEmpty()) return

    Canvas(modifier = modifier.fillMaxSize()) {
        val path = Path()
        val p0 = viewport.worldToScreen(points[0].x, points[0].y)
        path.moveTo(p0.x, p0.y)
        for (i in 1 until points.size) {
            val pt = viewport.worldToScreen(points[i].x, points[i].y)
            path.lineTo(pt.x, pt.y)
        }

        // Draw animated marching-ants style dashed lasso
        drawPath(
            path = path,
            color = Color(0xFF64B5F6),
            style = Stroke(
                width = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
            )
        )
    }
}

@Composable
private fun TransformSessionOverlay(
    session: TransformSession,
    viewport: Viewport,
    onUpdateTransform: (
        dx: Float?,
        dy: Float?,
        sx: Float?,
        sy: Float?,
        rot: Float?,
        flipH: Boolean?,
        flipV: Boolean?
    ) -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    var dragMode by remember { mutableStateOf(DragMode.NONE) }
    var initialTouchScreen by remember { mutableStateOf(Vec2(0f, 0f)) }
    var initialSessionTrans by remember { mutableStateOf(Vec2(0f, 0f)) }
    var initialScale by remember { mutableStateOf(Vec2(1f, 1f)) }
    var initialRotation by remember { mutableStateOf(0f) }
    var initialDistToPivot by remember { mutableStateOf(1f) }
    var initialAngleToPivot by remember { mutableStateOf(0f) }

    fun computeTransformedWorldCorners(): List<Vec2> {
        val src = session.srcBounds
        val effSx = if (session.flipH) -session.scaleX else session.scaleX
        val effSy = if (session.flipV) -session.scaleY else session.scaleY
        val cosT = cos(session.rotationRad)
        val sinT = sin(session.rotationRad)
        val corners = listOf(
            Vec2(src.left, src.top),
            Vec2(src.right, src.top),
            Vec2(src.right, src.bottom),
            Vec2(src.left, src.bottom)
        )
        return corners.map { c ->
            val cx = (c.x - session.pivotX) * effSx
            val cy = (c.y - session.pivotY) * effSy
            val rx = cx * cosT - cy * sinT + session.pivotX + session.translationX
            val ry = cx * sinT + cy * cosT + session.pivotY + session.translationY
            Vec2(rx, ry)
        }
    }

    val worldCorners = computeTransformedWorldCorners()
    val screenCorners = worldCorners.map { viewport.worldToScreen(it.x, it.y) }
    val p0 = screenCorners[0]
    val p1 = screenCorners[1]
    val p2 = screenCorners[2]
    val p3 = screenCorners[3]

    // Pivot in screen space
    val pivotWorld = Vec2(session.pivotX + session.translationX, session.pivotY + session.translationY)
    val pivotScreen = viewport.worldToScreen(pivotWorld.x, pivotWorld.y)

    // Top center for rotation stem
    val topCenter = Vec2((p0.x + p1.x) * 0.5f, (p0.y + p1.y) * 0.5f)
    val stemDx = p1.x - p0.x
    val stemDy = p1.y - p0.y
    val stemLen = hypot(stemDx, stemDy).coerceAtLeast(0.001f)
    // Perpendicular normal pointing out
    val nx = -stemDy / stemLen
    val ny = stemDx / stemLen
    val rotHandleScreen = Vec2(topCenter.x + nx * 32f, topCenter.y + ny * 32f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(session) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val touch = Vec2(offset.x, offset.y)
                        initialTouchScreen = touch
                        initialSessionTrans = Vec2(session.translationX, session.translationY)
                        initialScale = Vec2(session.scaleX, session.scaleY)
                        initialRotation = session.rotationRad

                        val handleHitRadius = 28f
                        if (hypot(touch.x - rotHandleScreen.x, touch.y - rotHandleScreen.y) <= handleHitRadius) {
                            dragMode = DragMode.ROTATE
                            initialAngleToPivot = atan2(touch.y - pivotScreen.y, touch.x - pivotScreen.x)
                        } else if (hypot(touch.x - p0.x, touch.y - p0.y) <= handleHitRadius) {
                            dragMode = DragMode.SCALE_TL
                            initialDistToPivot = hypot(touch.x - pivotScreen.x, touch.y - pivotScreen.y).coerceAtLeast(1f)
                        } else if (hypot(touch.x - p1.x, touch.y - p1.y) <= handleHitRadius) {
                            dragMode = DragMode.SCALE_TR
                            initialDistToPivot = hypot(touch.x - pivotScreen.x, touch.y - pivotScreen.y).coerceAtLeast(1f)
                        } else if (hypot(touch.x - p2.x, touch.y - p2.y) <= handleHitRadius) {
                            dragMode = DragMode.SCALE_BR
                            initialDistToPivot = hypot(touch.x - pivotScreen.x, touch.y - pivotScreen.y).coerceAtLeast(1f)
                        } else if (hypot(touch.x - p3.x, touch.y - p3.y) <= handleHitRadius) {
                            dragMode = DragMode.SCALE_BL
                            initialDistToPivot = hypot(touch.x - pivotScreen.x, touch.y - pivotScreen.y).coerceAtLeast(1f)
                        } else {
                            dragMode = DragMode.TRANSLATE
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val currentTouch = Vec2(change.position.x, change.position.y)

                        when (dragMode) {
                            DragMode.TRANSLATE -> {
                                val startWorld = viewport.screenToWorld(initialTouchScreen.x, initialTouchScreen.y)
                                val curWorld = viewport.screenToWorld(currentTouch.x, currentTouch.y)
                                val dwx = curWorld.x - startWorld.x
                                val dwy = curWorld.y - startWorld.y
                                onUpdateTransform(
                                    initialSessionTrans.x + dwx,
                                    initialSessionTrans.y + dwy,
                                    null, null, null, null, null
                                )
                            }
                            DragMode.ROTATE -> {
                                val currentAngle = atan2(currentTouch.y - pivotScreen.y, currentTouch.x - pivotScreen.x)
                                val deltaAngle = currentAngle - initialAngleToPivot
                                onUpdateTransform(
                                    null, null, null, null,
                                    initialRotation + deltaAngle,
                                    null, null
                                )
                            }
                            DragMode.SCALE_TL, DragMode.SCALE_TR, DragMode.SCALE_BR, DragMode.SCALE_BL -> {
                                val currentDist = hypot(currentTouch.x - pivotScreen.x, currentTouch.y - pivotScreen.y)
                                val ratio = (currentDist / initialDistToPivot).coerceIn(0.05f, 20f)
                                onUpdateTransform(
                                    null, null,
                                    (initialScale.x * ratio).coerceIn(0.05f, 20f),
                                    (initialScale.y * ratio).coerceIn(0.05f, 20f),
                                    null, null, null
                                )
                            }
                            DragMode.NONE -> {}
                        }
                    },
                    onDragEnd = { dragMode = DragMode.NONE },
                    onDragCancel = { dragMode = DragMode.NONE }
                )
            }
    ) {
        // 1. Preview Bitmaps
        Canvas(modifier = Modifier.fillMaxSize()) {
            val nativeCanvas = drawContext.canvas.nativeCanvas
            val paint = Paint().apply { isFilterBitmap = true }

            val srcPoly = floatArrayOf(
                0f, 0f,
                session.srcBounds.width, 0f,
                session.srcBounds.width, session.srcBounds.height,
                0f, session.srcBounds.height
            )

            val dstPoly = floatArrayOf(
                p0.x, p0.y,
                p1.x, p1.y,
                p2.x, p2.y,
                p3.x, p3.y
            )

            val matrix = Matrix()
            val mapped = matrix.setPolyToPoly(srcPoly, 0, dstPoly, 0, 4)

            if (mapped) {
                for ((_, patch) in session.layerPatches) {
                    nativeCanvas.drawBitmap(patch.previewBitmap, matrix, paint)
                }
            }

            // 2. Gizmo Bounding Box Outline
            val boxPath = Path().apply {
                moveTo(p0.x, p0.y)
                lineTo(p1.x, p1.y)
                lineTo(p2.x, p2.y)
                lineTo(p3.x, p3.y)
                close()
            }

            drawPath(
                path = boxPath,
                color = Color(0xFF64B5F6),
                style = Stroke(width = 1.5.dp.toPx())
            )

            // Rotation Stem
            drawLine(
                color = Color(0xFF81D4FA),
                start = Offset(topCenter.x, topCenter.y),
                end = Offset(rotHandleScreen.x, rotHandleScreen.y),
                strokeWidth = 1.5.dp.toPx()
            )

            // Rotation Handle
            drawCircle(
                color = Color(0xFF81D4FA),
                radius = 8.dp.toPx(),
                center = Offset(rotHandleScreen.x, rotHandleScreen.y)
            )
            drawCircle(
                color = Color.White,
                radius = 3.dp.toPx(),
                center = Offset(rotHandleScreen.x, rotHandleScreen.y)
            )

            // 4 Corner Scale Handles
            val handleRadius = 6.dp.toPx()
            val handleBorderWidth = 1.5.dp.toPx()
            for (corner in listOf(p0, p1, p2, p3)) {
                drawCircle(
                    color = Color.White,
                    radius = handleRadius,
                    center = Offset(corner.x, corner.y)
                )
                drawCircle(
                    color = Color(0xFF1976D2),
                    radius = handleRadius,
                    center = Offset(corner.x, corner.y),
                    style = Stroke(width = handleBorderWidth)
                )
            }
        }

        // 3. Transform Action Toolbar Pill (Floating dock)
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Flip Horizontal
                IconButton(
                    onClick = { onUpdateTransform(null, null, null, null, null, !session.flipH, null) },
                    modifier = Modifier.size(36.dp)
                ) {
                    FlipCanvasIcon(tint = if (session.flipH) Color(0xFF64B5F6) else Color.White, modifier = Modifier.size(18.dp))
                }

                // Flip Vertical
                IconButton(
                    onClick = { onUpdateTransform(null, null, null, null, null, null, !session.flipV) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Flip Vertical",
                        tint = if (session.flipV) Color(0xFF64B5F6) else Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Reset
                IconButton(
                    onClick = onReset,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Reset Transform",
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
                        contentDescription = "Cancel Transform",
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
                        contentDescription = "Commit Transform",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
