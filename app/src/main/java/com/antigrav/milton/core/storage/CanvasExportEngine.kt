package com.antigrav.milton.core.storage

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import com.antigrav.milton.core.layer.LayerManager
import com.antigrav.milton.core.viewport.Viewport
import com.antigrav.milton.core.memory.DirectBufferPool
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * High-performance export engine that renders the visible canvas area at 1:1 canvas resolution.
 */
object CanvasExportEngine {
    private const val TAG = "CanvasExportEngine"

    /**
     * Composites visible tiles across all visible layers into a Bitmap at 1:1 canvas resolution,
     * faithfully reproducing canvas rotation, zoom, horizontal flip, and screen framing with zero tile seams.
     */
    fun renderVisibleAreaToBitmap(
        viewport: Viewport,
        layerManager: LayerManager,
        backgroundColorRgb: Int,
        storageManager: DocumentStorageManager? = null,
        maxDimension: Int = 8192
    ): Bitmap {
        val screenW = viewport.screenWidth.toFloat()
        val screenH = viewport.screenHeight.toFloat()

        val rawWidth = (screenW / viewport.zoom).roundToInt().coerceAtLeast(64)
        val rawHeight = (screenH / viewport.zoom).roundToInt().coerceAtLeast(64)

        // Scale proportionally if exceeds maximum memory dimensions
        val scale = if (rawWidth > maxDimension || rawHeight > maxDimension) {
            val maxRaw = maxOf(rawWidth, rawHeight).toFloat()
            maxDimension / maxRaw
        } else {
            1.0f
        }

        val width = (rawWidth * scale).roundToInt().coerceIn(64, maxDimension)
        val height = (rawHeight * scale).roundToInt().coerceIn(64, maxDimension)

        Log.i(TAG, "Exporting visible canvas area: screen=($screenW x $screenH), world=($rawWidth x $rawHeight) -> export=($width x $height), scale=$scale, rot=${viewport.rotationDegrees}")

        val outputBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap)

        // 1. Fill background with canvas paper color
        canvas.drawColor(backgroundColorRgb)

        // Compute 4 screen corners in world space
        val p0 = viewport.screenToWorld(0f, 0f)
        val p1 = viewport.screenToWorld(screenW, 0f)
        val p2 = viewport.screenToWorld(screenW, screenH)
        val p3 = viewport.screenToWorld(0f, screenH)

        val minX = minOf(p0.x, p1.x, p2.x, p3.x)
        val maxX = maxOf(p0.x, p1.x, p2.x, p3.x)
        val minY = minOf(p0.y, p1.y, p2.y, p3.y)
        val maxY = maxOf(p0.y, p1.y, p2.y, p3.y)
        val bounds = com.antigrav.milton.core.model.WorldRect(minX, minY, maxX, maxY)

        // Transformation matrix: world coordinates -> export bitmap coordinates
        val worldToExportMatrix = android.graphics.Matrix()
        val srcPts = floatArrayOf(
            p0.x, p0.y,
            p1.x, p1.y,
            p3.x, p3.y
        )
        val dstPts = floatArrayOf(
            0f, 0f,
            width.toFloat(), 0f,
            0f, height.toFloat()
        )
        worldToExportMatrix.setPolyToPoly(srcPts, 0, dstPts, 0, 3)

        val tempTileBitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        // Disable anti-alias and filter bitmap to prevent transparent border bleeding & tile seams
        val paint = Paint().apply {
            isAntiAlias = false
            isFilterBitmap = false
        }
        val tileMatrix = android.graphics.Matrix()

        DirectBufferPool.useBuffer { tileBuffer ->
            // 2. Composite visible layers bottom-to-top
            val allLayers = layerManager.layers
            for (layer in allLayers) {
                if (!layer.isVisible || layer.opacity <= 0.001f) continue

                val visibleTiles = layer.tileMap.getVisibleTiles(bounds)
                if (visibleTiles.isEmpty()) continue

                paint.alpha = (layer.opacity.coerceIn(0f, 1f) * 255).roundToInt()

                for (tile in visibleTiles) {
                    if (!tile.hasContent) continue

                    val rawPixels: ByteArray? = try {
                        storageManager?.getTileBytes(layer.id, tile.coord.tx, tile.coord.ty, layer.tileMap.cacheDir)
                            ?: if (tile.isOnDisk) {
                                val swap = File(layer.tileMap.cacheDir, "tile_${tile.coord.tx}_${tile.coord.ty}.bin")
                                if (swap.exists()) {
                                    com.antigrav.milton.core.history.UndoManager.decompress(swap.readBytes())
                                } else null
                            } else if (tile.isInitialized) {
                                tile.readPixels()
                            } else null
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to read pixels for tile ${tile.coord}: ${e.message}")
                        null
                    }

                    if (rawPixels == null || rawPixels.isEmpty()) continue

                    // Vertically flip OpenGL rows so row 0 aligns with Bitmap top
                    val flipped = flipPixelsVertically(rawPixels, 512, 512)

                    tileBuffer.position(0)
                    tileBuffer.put(flipped)
                    tileBuffer.position(0)
                    tempTileBitmap.copyPixelsFromBuffer(tileBuffer)

                    tileMatrix.reset()
                    tileMatrix.postTranslate(tile.coord.worldLeft, tile.coord.worldTop)
                    tileMatrix.postConcat(worldToExportMatrix)

                    canvas.drawBitmap(tempTileBitmap, tileMatrix, paint)
                }
            }
        }

        tempTileBitmap.recycle()
        return outputBitmap
    }

    private fun flipPixelsVertically(src: ByteArray, width: Int, height: Int): ByteArray {
        val stride = width * 4
        val dst = ByteArray(src.size)
        for (row in 0 until height) {
            val srcOffset = (height - 1 - row) * stride
            val dstOffset = row * stride
            System.arraycopy(src, srcOffset, dst, dstOffset, stride)
        }
        return dst
    }

    /**
     * Saves exported image into local application cache folder for immediate sharing.
     */
    fun saveToExportCache(
        context: Context,
        bitmap: Bitmap,
        filename: String,
        format: Bitmap.CompressFormat,
        quality: Int = 95
    ): File {
        val exportDir = File(context.cacheDir, "exports")
        exportDir.mkdirs()
        val file = File(exportDir, filename)
        FileOutputStream(file).use { out ->
            bitmap.compress(format, quality, out)
        }
        return file
    }

    /**
     * Saves exported image directly into Android device Public Pictures/Milton directory
     * so it immediately appears in the user's Gallery / Photos app.
     */
    fun saveToPublicPictures(
        context: Context,
        bitmap: Bitmap,
        filename: String,
        isPng: Boolean
    ): Uri? {
        val mimeType = if (isPng) "image/png" else "image/jpeg"
        val format = if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val quality = if (isPng) 100 else 94

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Milton")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null

        try {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(format, quality, out)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            return uri
        } catch (e: Exception) {
            Log.e(TAG, "Error saving to MediaStore", e)
            resolver.delete(uri, null, null)
            return null
        }
    }

    /**
     * Saves exported .milton archive to device Public Downloads/Milton folder.
     */
    fun saveToPublicDownloads(
        context: Context,
        file: File,
        filename: String
    ): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Milton")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        }

        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        } else {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val miltonDir = File(downloadsDir, "Milton").apply { mkdirs() }
            val target = File(miltonDir, filename)
            file.copyTo(target, overwrite = true)
            Uri.fromFile(target)
        } ?: return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                Log.e(TAG, "Error saving to Downloads", e)
                resolver.delete(uri, null, null)
                return null
            }
        }
        return uri
    }

    /**
     * Creates an Android Share Sheet Intent for an exported file using FileProvider.
     */
    fun createShareIntent(context: Context, file: File, mimeType: String, title: String): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
