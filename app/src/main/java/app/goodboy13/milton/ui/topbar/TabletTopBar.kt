package app.goodboy13.milton.ui.topbar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.goodboy13.milton.ui.components.FlipCanvasIcon
import kotlin.math.roundToInt

/**
 * Top minimal bar holding canvas navigation controls, zoom/rotation lock toggles, and single fullscreen button.
 */
@Composable
fun TabletTopBar(
    canUndo: Boolean,
    onUndo: () -> Unit,
    canRedo: Boolean,
    onRedo: () -> Unit,
    isCanvasFlipped: Boolean = false,
    onToggleFlipCanvas: () -> Unit = {},
    zoomLevel: Float,
    isZoomLocked: Boolean,
    onToggleZoomLock: () -> Unit,
    rotationDegrees: Float,
    isRotationLocked: Boolean,
    onToggleRotationLock: () -> Unit,
    isLayersOpen: Boolean,
    onToggleLayers: () -> Unit,
    onResetCanvas: () -> Unit,
    onToggleZenMode: () -> Unit
) {
    val topBarShape = RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp, bottomStart = 14.dp, bottomEnd = 0.dp)
    Surface(
        modifier = Modifier
            .shadow(elevation = 10.dp, shape = topBarShape)
            .clip(topBarShape)
            .drawBehind {
                val sw = 1.dp.toPx()
                val r = 14.dp.toPx()
                val w = size.width
                val h = size.height
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(sw / 2f, 0f)
                    lineTo(sw / 2f, h - r)
                    arcTo(
                        rect = androidx.compose.ui.geometry.Rect(
                            sw / 2f,
                            h - 2f * r + sw / 2f,
                            2f * r - sw / 2f,
                            h - sw / 2f
                        ),
                        startAngleDegrees = 180f,
                        sweepAngleDegrees = -90f,
                        forceMoveTo = false
                    )
                    lineTo(w, h - sw / 2f)
                }
                drawPath(
                    path = path,
                    color = Color(0x35FFFFFF),
                    style = Stroke(width = sw)
                )
            },
        shape = topBarShape,
        color = Color(0xF0181A1F),
        tonalElevation = 6.dp
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Undo
            IconButton(
                onClick = onUndo,
                enabled = canUndo,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Undo,
                    contentDescription = "Undo",
                    tint = if (canUndo) Color.White else Color(0x38FFFFFF),
                    modifier = Modifier.size(18.dp)
                )
            }

            // Redo
            IconButton(
                onClick = onRedo,
                enabled = canRedo,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Redo,
                    contentDescription = "Redo",
                    tint = if (canRedo) Color.White else Color(0x38FFFFFF),
                    modifier = Modifier.size(18.dp)
                )
            }

            // Horizontal Flip Canvas Toggle Button (directly next to Redo)
            IconButton(
                onClick = onToggleFlipCanvas,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (isCanvasFlipped) Color(0x3564B5F6) else Color.Transparent)
            ) {
                FlipCanvasIcon(
                    tint = if (isCanvasFlipped) Color(0xFF64B5F6) else Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(18.dp)
                )
            }

            VerticalDivider(
                color = Color(0x25FFFFFF),
                thickness = 1.dp,
                modifier = Modifier
                    .height(20.dp)
                    .padding(horizontal = 2.dp)
            )

            // Zoom Lock Toggle Button (Clicking toggles zoom lock)
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onToggleZoomLock() }
                    .background(if (isZoomLocked) Color(0x2FFF9800) else Color.Transparent)
                    .border(
                        width = 1.dp,
                        color = if (isZoomLocked) Color(0xFFFFB74D) else Color(0x20FFFFFF),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 7.dp, vertical = 5.dp),
                color = Color.Transparent
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (isZoomLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = if (isZoomLocked) "Zoom Locked" else "Zoom Unlocked",
                        tint = if (isZoomLocked) Color(0xFFFFB74D) else Color(0x88FFFFFF),
                        modifier = Modifier.size(13.dp)
                    )
                    val zoomPct = (zoomLevel * 100).roundToInt()
                    Text(
                        text = "$zoomPct%",
                        fontSize = 12.sp,
                        fontWeight = if (isZoomLocked) FontWeight.Bold else FontWeight.Medium,
                        color = if (isZoomLocked) Color(0xFFFFB74D) else Color(0xDDFFFFFF)
                    )
                }
            }

            // Rotation Lock Toggle Button (Clicking toggles rotation lock)
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onToggleRotationLock() }
                    .background(if (isRotationLocked) Color(0x2F42A5F5) else Color.Transparent)
                    .border(
                        width = 1.dp,
                        color = if (isRotationLocked) Color(0xFF64B5F6) else Color(0x20FFFFFF),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 7.dp, vertical = 5.dp),
                color = Color.Transparent
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (isRotationLocked) Icons.Default.Lock else Icons.Default.ScreenRotation,
                        contentDescription = if (isRotationLocked) "Rotation Locked" else "Rotation Unlocked",
                        tint = if (isRotationLocked) Color(0xFF64B5F6) else Color(0x88FFFFFF),
                        modifier = Modifier.size(13.dp)
                    )
                    val rotDeg = (rotationDegrees.roundToInt() % 360).let { if (it < 0) it + 360 else it }
                    Text(
                        text = "$rotDeg°",
                        fontSize = 12.sp,
                        fontWeight = if (isRotationLocked) FontWeight.Bold else FontWeight.Medium,
                        color = if (isRotationLocked) Color(0xFF64B5F6) else Color(0xDDFFFFFF)
                    )
                }
            }

            // Layers Window Toggle Button
            IconButton(
                onClick = onToggleLayers,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (isLayersOpen) Color(0x3564B5F6) else Color.Transparent)
            ) {
                Icon(
                    imageVector = Icons.Default.Layers,
                    contentDescription = "Layers",
                    tint = if (isLayersOpen) Color(0xFF64B5F6) else Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(20.dp)
                )
            }

            // Dedicated Reset View Button
            IconButton(
                onClick = onResetCanvas,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CenterFocusStrong,
                    contentDescription = "Reset Canvas View",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(18.dp)
                )
            }

            // Single Fullscreen Toggle Button on the top right
            IconButton(
                onClick = onToggleZenMode,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Fullscreen,
                    contentDescription = "Enter Fullscreen",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
