package app.goodboy13.milton.core.tile

import app.goodboy13.milton.core.constants.CanvasConstants
import app.goodboy13.milton.core.model.WorldRect
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

/**
 * Manages the sparse 2D collection of RasterTiles on the infinite plane.
 * Uses primitive 64-bit Long packed keys (TileCoord.pack(tx, ty)) to completely
 * eliminate temporary TileCoord object allocations on the heap during hot spatial lookups.
 * Enforces an LRU VRAM memory budget (default 256 tiles = ~256MB) by paging
 * cold, non-visible tiles to compressed disk swap storage.
 */
class TileMap(
    val cacheDir: File? = null,
    val maxResidentTiles: Int = CanvasConstants.DEFAULT_MAX_RESIDENT_TILES
) {
    // Key: 64-bit packed Long ((tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL))
    private val tiles = ConcurrentHashMap<Long, RasterTile>()

    @Volatile
    var hasPendingRestores: Boolean = false
        private set

    val count: Int get() = tiles.size

    val residentCount: Int get() = tiles.values.count { it.isInitialized }

    val onDiskCount: Int get() = tiles.values.count { it.isOnDisk }

    init {
        cacheDir?.mkdirs()
        // Clean up any stale swap files from past sessions on startup
        cacheDir?.listFiles()?.forEach { if (it.extension == "bin") it.delete() }
    }

    fun getOrCreateTile(tx: Int, ty: Int): RasterTile {
        val key = TileCoord.pack(tx, ty)
        val tile = tiles.computeIfAbsent(key) {
            RasterTile(TileCoord(tx, ty))
        }
        tile.lastAccessTime = System.nanoTime()
        return tile
    }

    fun getOrCreateTile(packed: Long): RasterTile {
        val tile = tiles.computeIfAbsent(packed) {
            RasterTile(TileCoord(TileCoord.unpackTx(packed), TileCoord.unpackTy(packed)))
        }
        tile.lastAccessTime = System.nanoTime()
        return tile
    }

    fun getExistingTile(tx: Int, ty: Int): RasterTile? {
        val tile = tiles[TileCoord.pack(tx, ty)]
        tile?.lastAccessTime = System.nanoTime()
        return tile
    }

    fun getExistingTile(packed: Long): RasterTile? {
        val tile = tiles[packed]
        tile?.lastAccessTime = System.nanoTime()
        return tile
    }

    fun getVisibleTiles(bounds: WorldRect, outTiles: MutableList<RasterTile>, maxRestores: Int = CanvasConstants.MAX_TILE_RESTORES_PER_FRAME): List<RasterTile> {
        val minTx = floor(bounds.left / TileCoord.TILE_SIZE.toFloat()).toInt()
        val maxTx = floor(bounds.right / TileCoord.TILE_SIZE.toFloat()).toInt()
        val minTy = floor(bounds.top / TileCoord.TILE_SIZE.toFloat()).toInt()
        val maxTy = floor(bounds.bottom / TileCoord.TILE_SIZE.toFloat()).toInt()

        outTiles.clear()
        val now = System.nanoTime()
        var restoreCount = 0
        var pendingFound = false

        // Iterate actual existing tiles directly instead of empty coordinate space.
        // This avoids allocating thousands of TileCoord objects and hash lookups when zoomed out.
        for (tile in tiles.values) {
            val tx = tile.coord.tx
            val ty = tile.coord.ty
            if (tx in minTx..maxTx && ty in minTy..maxTy) {
                tile.lastAccessTime = now
                if (tile.isOnDisk) {
                    if (restoreCount < maxRestores) {
                        tile.restoreFromDisk(cacheDir)
                        restoreCount++
                    } else {
                        pendingFound = true
                    }
                }
                outTiles.add(tile)
            }
        }
        hasPendingRestores = pendingFound
        return outTiles
    }

    fun getVisibleTiles(bounds: WorldRect, maxRestores: Int = 4): List<RasterTile> {
        return getVisibleTiles(bounds, mutableListOf(), maxRestores)
    }

    /**
     * Enforces the VRAM tile budget by paging out the least recently used
     * non-visible tiles to disk storage.
     * Incorporates hysteresis and a grace period to prevent cache thrashing.
     * Must be called on the GL thread.
     */
    fun trimToBudget(visibleTiles: Collection<RasterTile>) {
        val slack = maxResidentTiles / CanvasConstants.TILE_EVICTION_SLACK_DIVISOR
        val excess = residentCount - (maxResidentTiles + slack)
        if (excess <= 0) return

        val visibleSet = visibleTiles.toSet()
        val now = System.nanoTime()
        val gracePeriodNs = CanvasConstants.TILE_EVICTION_GRACE_PERIOD_NS // Prevent thrashing on edge pans
        val enforceGracePeriod = residentCount <= maxResidentTiles * 2

        val residentNonVisible = tiles.values
            .filter { it.isInitialized && it !in visibleSet && (!enforceGracePeriod || (now - it.lastAccessTime) > gracePeriodNs) }

        if (residentNonVisible.isEmpty()) return

        // Sort by lastAccessTime ascending (oldest first)
        val sorted = residentNonVisible.sortedBy { it.lastAccessTime }
        val toEvict = sorted.take(minOf(excess, CanvasConstants.MAX_TILE_EVICTIONS_PER_FRAME))
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

    fun clearTiles(renderer: app.goodboy13.milton.core.gl.MiltonCanvasRenderer? = null) {
        val oldTiles = tiles.values.toList()
        for (tile in oldTiles) {
            tile.hasContent = false
            tile.deleteDiskSwap(cacheDir)
        }
        tiles.clear()
        cacheDir?.deleteRecursively()
        cacheDir?.mkdirs()
        if (renderer != null) {
            renderer.runOnGlThread {
                for (tile in oldTiles) {
                    tile.releaseGl()
                }
            }
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
