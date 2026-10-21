package app.goodboy13.milton.core.tile

import android.opengl.GLES30
import app.goodboy13.milton.core.constants.CanvasConstants
import app.goodboy13.milton.core.model.WorldRect
import app.goodboy13.milton.core.native.MiltonNative
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

/**
 * Manages the sparse 2D collection of RasterTiles on the infinite plane.
 * Uses primitive 64-bit Long packed keys (TileCoord.pack(tx, ty)) to completely
 * eliminate temporary TileCoord object allocations on the heap during hot spatial lookups.
 * Enforces an LRU VRAM memory budget backed by native Rust `SpatialTileMap`.
 */
class TileMap(
    val cacheDir: File? = null,
    val maxResidentTiles: Int = CanvasConstants.DEFAULT_MAX_RESIDENT_TILES
) {
    var nativePtr: Long = 0L
        private set

    // Key: 64-bit packed Long ((tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL))
    private val tiles = ConcurrentHashMap<Long, RasterTile>()

    // Level 1 Pyramidal Overview Mipmap: covers 1024x1024 world units (2x2 child tiles)
    private val overviewTiles = ConcurrentHashMap<Long, RasterTile>()

    private var queryBuffer = LongArray(1024)
    private val candidateEvictKeys = LongArray(CanvasConstants.MAX_TILE_EVICTIONS_PER_FRAME)

    var onTileStateChanged: ((tx: Int, ty: Int, sliceIndex: Int, hasContent: Boolean) -> Unit)? = null
        set(value) {
            field = value
            if (value != null) {
                for (tile in tiles.values) {
                    value(tile.coord.tx, tile.coord.ty, tile.sliceIndex, tile.hasContent)
                }
            }
        }

    var onOverviewTileStateChanged: ((otx: Int, oty: Int, sliceIndex: Int, hasContent: Boolean) -> Unit)? = null
        set(value) {
            field = value
            if (value != null) {
                for (tile in overviewTiles.values) {
                    value(tile.coord.tx, tile.coord.ty, tile.sliceIndex, tile.hasContent)
                }
            }
        }

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
            RasterTile(TileCoord(otx, oty)) { tile ->
                onOverviewTileStateChanged?.invoke(tile.coord.tx, tile.coord.ty, tile.sliceIndex, tile.hasContent)
            }
        }
    }

    fun getExistingOverviewTile(otx: Int, oty: Int): RasterTile? {
        return overviewTiles[TileCoord.pack(otx, oty)]
    }

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
        if (MiltonNative.isLoaded) {
            nativePtr = MiltonNative.spatialMapCreate(maxResidentTiles)
        }
        cacheDir?.mkdirs()
        cacheDir?.listFiles()?.forEach { if (it.extension == "bin") it.delete() }
    }

    protected fun finalize() {
        if (nativePtr != 0L) {
            MiltonNative.spatialMapDestroy(nativePtr)
            nativePtr = 0L
        }
    }

    fun getOrCreateTile(tx: Int, ty: Int): RasterTile {
        val key = TileCoord.pack(tx, ty)
        val tile = tiles.computeIfAbsent(key) {
            RasterTile(TileCoord(tx, ty)) { t ->
                if (nativePtr != 0L) {
                    MiltonNative.spatialMapUpdateTileState(
                        nativePtr,
                        t.coord.tx,
                        t.coord.ty,
                        t.sliceIndex,
                        t.hasContent,
                        t.isResident
                    )
                }
                onTileStateChanged?.invoke(t.coord.tx, t.coord.ty, t.sliceIndex, t.hasContent)
            }
        }
        val now = System.nanoTime()
        tile.lastAccessTime = now
        if (nativePtr != 0L) {
            MiltonNative.spatialMapTouchTile(nativePtr, tx, ty, now)
        }
        return tile
    }

    fun getOrCreateTile(packed: Long): RasterTile {
        val tile = tiles.computeIfAbsent(packed) {
            val tx = TileCoord.unpackTx(packed)
            val ty = TileCoord.unpackTy(packed)
            RasterTile(TileCoord(tx, ty)) { t ->
                if (nativePtr != 0L) {
                    MiltonNative.spatialMapUpdateTileState(
                        nativePtr,
                        t.coord.tx,
                        t.coord.ty,
                        t.sliceIndex,
                        t.hasContent,
                        t.isResident
                    )
                }
                onTileStateChanged?.invoke(t.coord.tx, t.coord.ty, t.sliceIndex, t.hasContent)
            }
        }
        val now = System.nanoTime()
        tile.lastAccessTime = now
        if (nativePtr != 0L) {
            MiltonNative.spatialMapTouchTile(nativePtr, tile.coord.tx, tile.coord.ty, now)
        }
        return tile
    }

    fun getExistingTile(tx: Int, ty: Int): RasterTile? {
        val tile = tiles[TileCoord.pack(tx, ty)]
        if (tile != null) {
            val now = System.nanoTime()
            tile.lastAccessTime = now
            if (nativePtr != 0L) {
                MiltonNative.spatialMapTouchTile(nativePtr, tx, ty, now)
            }
        }
        return tile
    }

    fun getExistingTile(packed: Long): RasterTile? {
        val tile = tiles[packed]
        if (tile != null) {
            val now = System.nanoTime()
            tile.lastAccessTime = now
            if (nativePtr != 0L) {
                MiltonNative.spatialMapTouchTile(nativePtr, tile.coord.tx, tile.coord.ty, now)
            }
        }
        return tile
    }

    fun getVisibleTiles(bounds: WorldRect, outTiles: MutableList<RasterTile>, maxRestores: Int = Int.MAX_VALUE): List<RasterTile> {
        val minTx = floor(bounds.left / TileCoord.TILE_SIZE.toFloat()).toInt()
        val maxTx = floor(bounds.right / TileCoord.TILE_SIZE.toFloat()).toInt()
        val minTy = floor(bounds.top / TileCoord.TILE_SIZE.toFloat()).toInt()
        val maxTy = floor(bounds.bottom / TileCoord.TILE_SIZE.toFloat()).toInt()

        outTiles.clear()
        val now = System.nanoTime()
        var restoreCount = 0
        var pendingFound = false

        if (nativePtr != 0L) {
            var keyCount = MiltonNative.spatialMapQueryVisible(
                nativePtr, minTx, minTy, maxTx, maxTy, now, queryBuffer
            )
            if (keyCount > queryBuffer.size) {
                queryBuffer = LongArray(keyCount + 256)
                keyCount = MiltonNative.spatialMapQueryVisible(
                    nativePtr, minTx, minTy, maxTx, maxTy, now, queryBuffer
                )
            }
            for (i in 0 until keyCount) {
                val packed = queryBuffer[i]
                val tile = tiles[packed] ?: continue
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
            hasPendingRestores = pendingFound
            return outTiles
        }

        // Pure Kotlin fallback
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

    fun getVisibleTiles(bounds: WorldRect, maxRestores: Int = Int.MAX_VALUE): List<RasterTile> {
        return getVisibleTiles(bounds, mutableListOf(), maxRestores)
    }

    /**
     * Enforces the VRAM tile budget by paging out the least recently used
     * non-visible tiles to disk storage.
     * Backed by native Rust LRU candidate evaluation.
     */
    fun trimToBudget(visibleTiles: Collection<RasterTile>) {
        if (nativePtr != 0L) {
            val now = System.nanoTime()
            val visCount = visibleTiles.size
            val visibleKeys = LongArray(visCount)
            var vi = 0
            for (vt in visibleTiles) {
                visibleKeys[vi++] = vt.coord.packed
            }
            val evictCount = MiltonNative.spatialMapFindEvictionCandidates(
                nativePtr,
                visibleKeys,
                now,
                CanvasConstants.MAX_TILE_EVICTIONS_PER_FRAME,
                candidateEvictKeys
            )
            for (i in 0 until evictCount) {
                val packed = candidateEvictKeys[i]
                val tile = tiles[packed]
                tile?.evictToDisk(cacheDir)
            }
            return
        }

        // Pure Kotlin fallback
        val slack = maxResidentTiles / CanvasConstants.TILE_EVICTION_SLACK_DIVISOR
        val excess = residentCount - (maxResidentTiles + slack)
        if (excess <= 0) return

        val visibleSet = visibleTiles.toSet()
        val now = System.nanoTime()
        val gracePeriodNs = CanvasConstants.TILE_EVICTION_GRACE_PERIOD_NS
        val enforceGracePeriod = residentCount <= maxResidentTiles * 2

        val residentNonVisible = tiles.values
            .filter { it.isInitialized && it !in visibleSet && (!enforceGracePeriod || (now - it.lastAccessTime) > gracePeriodNs) }

        if (residentNonVisible.isEmpty()) return

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
        if (nativePtr != 0L) {
            MiltonNative.spatialMapClear(nativePtr)
        }
        for (tile in tiles.values) {
            tile.clear()
            tile.deleteDiskSwap(cacheDir)
        }
    }

    fun clearTiles(renderer: app.goodboy13.milton.core.gl.MiltonCanvasRenderer? = null) {
        if (nativePtr != 0L) {
            MiltonNative.spatialMapClear(nativePtr)
        }
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
        if (nativePtr != 0L) {
            MiltonNative.spatialMapClear(nativePtr)
        }
        for (tile in tiles.values) {
            tile.releaseGl()
            tile.deleteDiskSwap(cacheDir)
        }
        tiles.clear()
        cacheDir?.deleteRecursively()
    }
}
