package com.antigrav.milton.core.tile

import com.antigrav.milton.core.model.WorldRect
import kotlin.math.floor

data class TileCoord(val tx: Int, val ty: Int) {
    val worldLeft: Float get() = tx * TILE_SIZE.toFloat()
    val worldTop: Float get() = ty * TILE_SIZE.toFloat()
    val worldRight: Float get() = (tx + 1) * TILE_SIZE.toFloat()
    val worldBottom: Float get() = (ty + 1) * TILE_SIZE.toFloat()

    companion object {
        const val TILE_SIZE = 512

        fun fromWorld(wx: Float, wy: Float): TileCoord {
            val tx = floor(wx / TILE_SIZE.toFloat()).toInt()
            val ty = floor(wy / TILE_SIZE.toFloat()).toInt()
            return TileCoord(tx, ty)
        }

        fun getTileRangeForBounds(bounds: WorldRect): TileRange {
            val minTx = floor(bounds.left / TILE_SIZE.toFloat()).toInt()
            val maxTx = floor(bounds.right / TILE_SIZE.toFloat()).toInt()
            val minTy = floor(bounds.top / TILE_SIZE.toFloat()).toInt()
            val maxTy = floor(bounds.bottom / TILE_SIZE.toFloat()).toInt()
            return TileRange(minTx, maxTx, minTy, maxTy)
        }
    }
}

data class TileRange(
    val minTx: Int,
    val maxTx: Int,
    val minTy: Int,
    val maxTy: Int
) {
    inline fun forEach(action: (tx: Int, ty: Int) -> Unit) {
        for (y in minTy..maxTy) {
            for (x in minTx..maxTx) {
                action(x, y)
            }
        }
    }
}
