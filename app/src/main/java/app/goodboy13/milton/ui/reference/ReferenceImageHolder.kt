package app.goodboy13.milton.ui.reference

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Floating reference image item model with screen position and viewport transformation states.
 */
class ReferenceImageItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "REF",
    val imageBitmap: ImageBitmap,
    initialX: Float = 140f,
    initialY: Float = 140f,
    initialWidth: Float = 340f,
    initialHeight: Float = 260f
) {
    // Window position and dimensions on screen (always axis-aligned)
    var offsetX by mutableFloatStateOf(initialX)
    var offsetY by mutableFloatStateOf(initialY)
    var widthPx by mutableFloatStateOf(initialWidth)
    var heightPx by mutableFloatStateOf(initialHeight)
    var isMinimized by mutableStateOf(false)

    // Inner photo transform (viewport crop, pan, zoom, rotation, flip)
    var isFlipped by mutableStateOf(false)
    var imgPanX by mutableFloatStateOf(0f)
    var imgPanY by mutableFloatStateOf(0f)
    var imgZoom by mutableFloatStateOf(1.0f)
    var imgRotation by mutableFloatStateOf(0f)

    val isTransformed: Boolean
        get() = imgPanX != 0f || imgPanY != 0f || imgZoom != 1.0f || imgRotation != 0f || isFlipped

    fun resetTransform() {
        imgPanX = 0f
        imgPanY = 0f
        imgZoom = 1.0f
        imgRotation = 0f
        isFlipped = false
    }
}

/**
 * Memory-safe bitmap decoder for user reference images.
 * Downscales images exceeding 2048px to prevent OOM errors on high-resolution camera photos.
 */
object ReferenceImageLoader {
    fun loadFromUri(context: Context, uri: Uri): ReferenceImageItem? {
        return try {
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            }
            val origW = boundsOptions.outWidth
            val origH = boundsOptions.outHeight
            if (origW <= 0 || origH <= 0) return null

            val maxDim = 2048
            var sampleSize = 1
            while ((origW / sampleSize) > maxDim * 2 || (origH / sampleSize) > maxDim * 2) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            context.contentResolver.openInputStream(uri)?.use { stream ->
                val original = BitmapFactory.decodeStream(stream, null, decodeOptions) ?: return null
                val w = original.width
                val h = original.height
                val finalBitmap = if (w > maxDim || h > maxDim) {
                    val scale = minOf(maxDim.toFloat() / w, maxDim.toFloat() / h)
                    val tw = (w * scale).toInt().coerceAtLeast(1)
                    val th = (h * scale).toInt().coerceAtLeast(1)
                    val scaled = Bitmap.createScaledBitmap(original, tw, th, true)
                    if (scaled != original) original.recycle()
                    scaled
                } else {
                    original
                }

                val imageBitmap = finalBitmap.asImageBitmap()
                val aspect = finalBitmap.width.toFloat() / finalBitmap.height.toFloat().coerceAtLeast(1f)
                val initW = 340f
                val initH = (initW / aspect).coerceIn(160f, 520f)

                ReferenceImageItem(
                    title = "REF",
                    imageBitmap = imageBitmap,
                    initialWidth = initW,
                    initialHeight = initH
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Floating reference image overlay container.
 * Supports multiple instances with independent positioning, depth z-ordering, and removal.
 */
@Composable
fun ReferenceImageOverlay(
    referenceImages: List<ReferenceImageItem>,
    containerWidth: Int = 0,
    containerHeight: Int = 0,
    onBringToFront: (ReferenceImageItem) -> Unit,
    onRemove: (ReferenceImageItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        for (item in referenceImages) {
            key(item.id) {
                FloatingReferenceImageBox(
                    item = item,
                    containerWidth = containerWidth,
                    containerHeight = containerHeight,
                    onBringToFront = { onBringToFront(item) },
                    onClose = { onRemove(item) }
                )
            }
        }
    }
}

/**
 * Interactive floating reference image box.
 * Supports:
 * - Floating minimized pill mode (drag anywhere, tap/expand to restore, close)
 * - Expanded viewport mode (drag window, corner resize grip, 2-finger zoom/rotate/pan, flip, reset fit, minimize, close)
 */
@Composable
fun FloatingReferenceImageBox(
    item: ReferenceImageItem,
    containerWidth: Int = 0,
    containerHeight: Int = 0,
    onBringToFront: () -> Unit,
    onClose: () -> Unit
) {
    val density = LocalDensity.current

    if (item.isMinimized) {
        // --- MINIMIZED FLOATING PILL ---
        val pillShape = RoundedCornerShape(20.dp)
        Row(
            modifier = Modifier
                .offset { IntOffset(item.offsetX.roundToInt(), item.offsetY.roundToInt()) }
                .shadow(8.dp, pillShape)
                .clip(pillShape)
                .background(Color(0xF0181A1F))
                .border(1.dp, Color(0x35FFFFFF), pillShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    item.isMinimized = false
                    onBringToFront()
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        onBringToFront()
                    }
                }
                .pointerInput(containerWidth, containerHeight) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        if (containerWidth > 0 && containerHeight > 0) {
                            val maxX = (containerWidth - 80f).coerceAtLeast(0f)
                            val maxY = (containerHeight - 40f).coerceAtLeast(0f)
                            item.offsetX = (item.offsetX + dragAmount.x).coerceIn(0f, maxX)
                            item.offsetY = (item.offsetY + dragAmount.y).coerceIn(0f, maxY)
                        } else {
                            item.offsetX += dragAmount.x
                            item.offsetY += dragAmount.y
                        }
                    }
                }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail
            Image(
                bitmap = item.imageBitmap,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, Color(0x35FFFFFF), RoundedCornerShape(6.dp))
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = item.title,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.SansSerif
            )
            Spacer(Modifier.width(6.dp))
            // Expand button
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF22262E))
                    .clickable {
                        item.isMinimized = false
                        onBringToFront()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.OpenInFull,
                    contentDescription = "Expand",
                    tint = Color(0xFF64B5F6),
                    modifier = Modifier.size(12.dp)
                )
            }
            Spacer(Modifier.width(4.dp))
            // Close button
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onClose() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color(0xAAFFFFFF),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    } else {
        // --- EXPANDED FLOATING WINDOW (Axis-Aligned Window with Inner Viewport Crop) ---
        val widthDp = with(density) { item.widthPx.toDp() }
        val heightDp = with(density) { item.heightPx.toDp() }
        val windowShape = RoundedCornerShape(14.dp)

        Column(
            modifier = Modifier
                .offset { IntOffset(item.offsetX.roundToInt(), item.offsetY.roundToInt()) }
                .width(widthDp)
                .height(heightDp + 38.dp)
                .shadow(12.dp, windowShape)
                .clip(windowShape)
                .background(Color(0xF0181A1F))
                .border(1.dp, Color(0x35FFFFFF), windowShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    onBringToFront()
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        onBringToFront()
                    }
                }
        ) {
            // Window Header Bar (Drag Handle + Title + Flip + Fit/Reset + Minimize + Close)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .background(Color(0xFF20232B))
                    .pointerInput(containerWidth, containerHeight) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            if (containerWidth > 0 && containerHeight > 0) {
                                val maxX = (containerWidth - 80f).coerceAtLeast(0f)
                                val maxY = (containerHeight - 40f).coerceAtLeast(0f)
                                item.offsetX = (item.offsetX + dragAmount.x).coerceIn(0f, maxX)
                                item.offsetY = (item.offsetY + dragAmount.y).coerceIn(0f, maxY)
                            } else {
                                item.offsetX += dragAmount.x
                                item.offsetY += dragAmount.y
                            }
                        }
                    }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.DragIndicator,
                    contentDescription = "Move Window",
                    tint = Color(0xAAFFFFFF),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = item.title,
                    color = Color.White,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif,
                    modifier = Modifier.weight(1f)
                )

                // Flip Photo Horizontal button
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (item.isFlipped) Color(0x3564B5F6) else Color.Transparent)
                        .clickable { item.isFlipped = !item.isFlipped },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Flip,
                        contentDescription = "Flip Reference",
                        tint = if (item.isFlipped) Color(0xFF64B5F6) else Color(0xAAFFFFFF),
                        modifier = Modifier.size(15.dp)
                    )
                }

                Spacer(Modifier.width(3.dp))

                // Reset / Fit button if photo was cropped, zoomed, rotated, or flipped
                if (item.isTransformed) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF2B3038))
                            .border(1.dp, Color(0x35FFFFFF), RoundedCornerShape(6.dp))
                            .clickable { item.resetTransform() }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Fit Crop",
                                tint = Color(0xFF64B5F6),
                                modifier = Modifier.size(11.dp)
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                text = "Fit",
                                color = Color(0xFF64B5F6),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.SansSerif
                            )
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                }

                // Minimize Button
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { item.isMinimized = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Remove,
                        contentDescription = "Minimize Reference",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Spacer(Modifier.width(2.dp))

                // Close Button
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onClose() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close Reference",
                        tint = Color(0xAAFFFFFF),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Image Viewport Container Box (Acts as Crop Frame with Zoom, Pan, Rotate, and Corner Resize)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(heightDp)
                    .clip(RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
                    .clipToBounds()
                    .background(Color(0xFF14161A))
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, pan, zoom, rotation ->
                            // 1. Zoom with clamp
                            val oldZoom = item.imgZoom
                            val newZoom = (oldZoom * zoom).coerceIn(0.2f, 15f)
                            val effectiveZoomMultiplier = newZoom / oldZoom

                            // 2. Rotation delta
                            val rotDelta = rotation
                            item.imgRotation = (item.imgRotation + rotDelta) % 360f

                            // 3. Transform around centroid (fingers midpoint)
                            val boxCenterX = item.widthPx * 0.5f
                            val boxCenterY = item.heightPx * 0.5f

                            // Current image center relative to top-left of box
                            val curImageCenterX = boxCenterX + item.imgPanX
                            val curImageCenterY = boxCenterY + item.imgPanY

                            // Vector from current image center to centroid
                            val vx = centroid.x - curImageCenterX
                            val vy = centroid.y - curImageCenterY

                            // Rotate and scale vector around centroid
                            val rad = Math.toRadians(rotDelta.toDouble())
                            val cosR = Math.cos(rad).toFloat()
                            val sinR = Math.sin(rad).toFloat()

                            val vxTrans = (vx * cosR - vy * sinR) * effectiveZoomMultiplier
                            val vyTrans = (vx * sinR + vy * cosR) * effectiveZoomMultiplier

                            // New image center pinned to centroid + pan
                            val newImageCenterX = centroid.x - vxTrans + pan.x
                            val newImageCenterY = centroid.y - vyTrans + pan.y

                            item.imgPanX = newImageCenterX - boxCenterX
                            item.imgPanY = newImageCenterY - boxCenterY
                            item.imgZoom = newZoom
                        }
                    }
            ) {
                Image(
                    bitmap = item.imageBitmap,
                    contentDescription = item.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = item.imgPanX
                            translationY = item.imgPanY
                            scaleX = if (item.isFlipped) -item.imgZoom else item.imgZoom
                            scaleY = item.imgZoom
                            rotationZ = item.imgRotation
                        }
                )

                // Corner Resize Grip Handle (Drag bottom-right corner to resize window frame)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(32.dp)
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                item.widthPx = (item.widthPx + dragAmount.x).coerceIn(160f, 1600f)
                                item.heightPx = (item.heightPx + dragAmount.y).coerceIn(120f, 1200f)
                            }
                        }
                        .padding(4.dp),
                    contentAlignment = Alignment.BottomEnd
                ) {
                    Icon(
                        Icons.Default.SouthEast,
                        contentDescription = "Resize Window",
                        tint = Color(0x88FFFFFF),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
