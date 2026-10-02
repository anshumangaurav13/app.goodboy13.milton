package com.antigrav.milton.core.tile

import com.antigrav.milton.core.model.WorldRect
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages the sparse 2D collection of RasterTiles on the infinite plane.
 */
class TileMap {
    private val tiles = ConcurrentHashMap<TileCoord, RasterTile>()

    val count: Int get() = tiles.size

    fun getOrCreateTile(tx: Int, ty: Int): RasterTile {
        val coord = TileCoord(tx, ty)
        return tiles.computeIfAbsent(coord) {
            RasterTile(coord)
        }
    }

    fun getExistingTile(tx: Int, ty: Int): RasterTile? {
        return tiles[TileCoord(tx, ty)]
    }

    fun getVisibleTiles(bounds: WorldRect): List<RasterTile> {
        val range = TileCoord.getTileRangeForBounds(bounds)
        val result = mutableListOf<RasterTile>()
        range.forEach { tx, ty ->
            tiles[TileCoord(tx, ty)]?.let { tile ->
                result.add(tile)
            }
        }
        return result
    }

    fun getAllTiles(): List<RasterTile> {
        return tiles.values.toList()
    }

    fun clearAll() {
        for (tile in tiles.values) {
            tile.clear()
        }
    }

    fun releaseAll() {
        for (tile in tiles.values) {
            tile.releaseGl()
        }
        tiles.clear()
    }
}
