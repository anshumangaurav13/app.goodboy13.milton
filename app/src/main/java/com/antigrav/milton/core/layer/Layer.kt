package com.antigrav.milton.core.layer

import android.graphics.Bitmap
import com.antigrav.milton.core.tile.TileMap

/**
 * Represents a single raster drawing layer on the infinite canvas.
 * Order in LayerManager is bottom-to-top (index 0 is bottom, index N-1 is top).
 */
data class Layer(
    val id: Long,
    var name: String,
    var opacity: Float = 1.0f,
    var isVisible: Boolean = true,
    val tileMap: TileMap,
    var thumbnailBitmap: Bitmap? = null
)
