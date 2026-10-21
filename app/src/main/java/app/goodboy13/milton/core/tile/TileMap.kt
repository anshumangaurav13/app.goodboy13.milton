package app.goodboy13.milton.core.tile

import android.opengl.GLES30
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

    // Level 1 Pyramidal Overview Mipmap: covers 1024x1024 world units (2x2 child tiles)
    // Downsampled directly on the GPU using hardware bilinear filtering via glBlitFramebuffer
    private val overviewTiles = ConcurrentHashMap<Long, RasterTile>()

    @Volatile
    var hasPendingRestores: Boolean = false
        private set

    val count: Int get() = tiles.size

    val residentCount: Int get() = tiles.values.count { it.isInitialized }

    val onDiskCount: Int get() = tiles.values.count { it.isOnDisk }

    val overviewCount: Int get() = overviewTiles.size

    val residentOverviewCount: Int get() = overviewTiles.values.count { it.isInitialized }

    fun getAllResidentTiles(): List<RasterTile> = tiles.values.filter { it.isInitialized && it.hasContent }

    fun getOrCreateOverviewTile(otx: Int, oty: Int): RasterTile {
        val key = TileCoord.pack(otx, oty)
        return overviewTiles.computeIfAbsent(key) {
            RasterTile(TileCoord(otx, oty))
        }
    }

    fun getExistingOverviewTile(otx: Int, oty: Int): RasterTile? {
        return overviewTiles[TileCoord.pack(otx, oty)]
    }

    /**
     * Queries visible Level 1 overview tiles covering [bounds].
     * Overview tiles span 1024x1024 world space (2x2 Level 0 child tiles).
     */
    fun getVisibleOverviewTiles(bounds: WorldRect, outTiles: MutableList<RasterTile>): List<RasterTile> {
        val overviewTileSize = TileCoord.TILE_SIZE * 2f // 1024.0f
        val minOtx = floor(bounds.left / overviewTileSize).toInt()
        val maxOtx = floor(bounds.right / overviewTileSize).toInt()
        val minOty = floor(bounds.top / overviewTileSize).toInt()
        val maxOty = floor(bounds.bottom / overviewTileSize).toInt()

        outTiles.clear()
        for (tile in overviewTiles.values) {
            val otx = tile.coord.tx
            val oty = tile.coord.ty
            if (otx in minOtx..maxOtx && oty in minOty..maxOty) {
                if (tile.isInitialized && tile.hasContent) {
                    outTiles.add(tile)
                }
            }
        }
        return outTiles
    }

    /**
     * Updates Level 1 overview tiles on the GPU for the given modified Level 0 child tiles.
     * Downsamples each 512x512 child into its 256x256 quadrant in the 1024x1024 overview tile
     * using hardware bilinear box filtering in VRAM via glBlitFramebuffer.
     * Must be called on the GL render thread.
     */
    fun updateOverviewTiles(modifiedTiles: Collection<RasterTile>) {
        if (modifiedTiles.isEmpty()) return

        val prevReadFbo = IntArray(1)
        val prevDrawFbo = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_READ_FRAMEBUFFER_BINDING, prevReadFbo, 0)
        GLES30.glGetIntegerv(GLES30.GL_DRAW_FRAMEBUFFER_BINDING, prevDrawFbo, 0)

        for (child in modifiedTiles) {
            val tx = child.coord.tx
            val ty = child.coord.ty
            val otx = if (tx >= 0) tx / 2 else (tx - 1) / 2
            val oty = if (ty >= 0) ty / 2 else (ty - 1) / 2

            val overviewTile = getOrCreateOverviewTile(otx, oty)
            if (!overviewTile.isInitialized) {
                overviewTile.initGl()
            }

            val childX = tx - otx * 2
            val childY = ty - oty * 2
            val subX = childX * 256
            val subY = childY * 256

            if (child.isInitialized && child.hasContent) {
                GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, child.fboId)
                GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, overviewTile.fboId)
                GLES30.glBlitFramebuffer(
                    0, 0, TileCoord.TILE_SIZE, TileCoord.TILE_SIZE,
                    subX, subY, subX + 256, subY + 256,
                    GLES30.GL_COLOR_BUFFER_BIT,
                    GLES30.GL_LINEAR
                )
                overviewTile.hasContent = true
                overviewTile.isDirty = true
            } else {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, overviewTile.fboId)
                GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
                GLES30.glScissor(subX, subY, 256, 256)
                GLES30.glClearColor(0f, 0f, 0f, 0f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
                overviewTile.isDirty = true
            }
        }

        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, prevReadFbo[0])
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, prevDrawFbo[0])
    }

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
