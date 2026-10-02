package com.antigrav.milton.core.tile

import com.antigrav.milton.core.model.WorldRect
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages the sparse 2D collection of RasterTiles on the infinite plane.
 * Enforces an LRU VRAM memory budget (default 96 tiles = ~96MB) by paging
 * cold, non-visible tiles to compressed disk swap storage.
 */
class TileMap(
    val cacheDir: File? = null,
    val maxResidentTiles: Int = 96
) {
    private val tiles = ConcurrentHashMap<TileCoord, RasterTile>()

    val count: Int get() = tiles.size

    val residentCount: Int get() = tiles.values.count { it.isInitialized }

    val onDiskCount: Int get() = tiles.values.count { it.isOnDisk }

    init {
        cacheDir?.mkdirs()
        // Clean up any stale swap files from past sessions on startup
        cacheDir?.listFiles()?.forEach { if (it.extension == "bin") it.delete() }
    }

    fun getOrCreateTile(tx: Int, ty: Int): RasterTile {
        val coord = TileCoord(tx, ty)
        val tile = tiles.computeIfAbsent(coord) {
            RasterTile(coord)
        }
        tile.lastAccessTime = System.nanoTime()
        return tile
    }

    fun getExistingTile(tx: Int, ty: Int): RasterTile? {
        val tile = tiles[TileCoord(tx, ty)]
        tile?.lastAccessTime = System.nanoTime()
        return tile
    }

    fun getVisibleTiles(bounds: WorldRect): List<RasterTile> {
        val range = TileCoord.getTileRangeForBounds(bounds)
        val result = mutableListOf<RasterTile>()
        val now = System.nanoTime()
        range.forEach { tx, ty ->
            tiles[TileCoord(tx, ty)]?.let { tile ->
                tile.lastAccessTime = now
                if (tile.isOnDisk) {
                    tile.restoreFromDisk(cacheDir)
                }
                result.add(tile)
            }
        }
        return result
    }

    /**
     * Enforces the VRAM tile budget by paging out the least recently used
     * non-visible tiles to disk storage.
     * Must be called on the GL thread.
     */
    fun trimToBudget(visibleTiles: Collection<RasterTile>) {
        val visibleSet = visibleTiles.toSet()
        val residentNonVisible = tiles.values
            .filter { it.isInitialized && it !in visibleSet }

        val excess = residentCount - maxResidentTiles
        if (excess <= 0 || residentNonVisible.isEmpty()) return

        // Sort by lastAccessTime ascending (oldest first)
        val sorted = residentNonVisible.sortedBy { it.lastAccessTime }
        val toEvict = sorted.take(excess)
        for (tile in toEvict) {
            tile.evictToDisk(cacheDir)
        }
    }

    fun getAllTiles(): List<RasterTile> {
        return tiles.values.toList()
    }

    fun clearAll() {
        for (tile in tiles.values) {
            tile.clear()
            tile.deleteDiskSwap(cacheDir)
        }
    }

    fun releaseAll() {
        for (tile in tiles.values) {
            tile.releaseGl()
            tile.deleteDiskSwap(cacheDir)
        }
        tiles.clear()
        cacheDir?.deleteRecursively()
    }
}
