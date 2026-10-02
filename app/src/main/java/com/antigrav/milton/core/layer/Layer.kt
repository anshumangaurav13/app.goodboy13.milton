package com.antigrav.milton.core.layer

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.antigrav.milton.core.tile.TileMap

/**
 * Represents a single raster drawing layer on the infinite canvas.
 * Properties are backed by Compose MutableState for reactive UI updates.
 * Order in LayerManager is bottom-to-top (index 0 is bottom, index N-1 is top).
 */
class Layer(
    val id: Long,
    initialName: String,
    initialOpacity: Float = 1.0f,
    initialIsVisible: Boolean = true,
    val tileMap: TileMap,
    initialThumbnailBitmap: Bitmap? = null
) {
    var name: String by mutableStateOf(initialName)
    var opacity: Float by mutableFloatStateOf(initialOpacity)
    var isVisible: Boolean by mutableStateOf(initialIsVisible)
    var thumbnailBitmap: Bitmap? by mutableStateOf(initialThumbnailBitmap)
}
