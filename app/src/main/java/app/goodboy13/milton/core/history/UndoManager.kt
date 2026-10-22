package app.goodboy13.milton.core.history

import android.opengl.GLES30
import app.goodboy13.milton.core.gl.GlTexturePool
import app.goodboy13.milton.core.layer.LayerManager
import app.goodboy13.milton.core.memory.DirectBufferPool
import app.goodboy13.milton.core.native.MiltonNative
import app.goodboy13.milton.core.tile.RasterTile
import app.goodboy13.milton.core.tile.TileCoord
import app.goodboy13.milton.core.tile.TileMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.ArrayDeque

/**
 * Tile delta entry containing compressed RGBA pixel snapshots.
 * If pixelData is null, the tile was uninitialized/empty prior to the stroke.
 */
class TileDelta(
    val layerId: Long = 1L,
    val coord: TileCoord,
    val beforeCompressed: ByteArray?,
    val afterCompressed: ByteArray? = null,
    val minX: Int = 0,
    val minY: Int = 0,
    val patchWidth: Int = TileCoord.TILE_SIZE,
    val patchHeight: Int = TileCoord.TILE_SIZE,
    var fullAfterForAutosave: ByteArray? = null
) {
    val isSubTile: Boolean get() = patchWidth < TileCoord.TILE_SIZE || patchHeight < TileCoord.TILE_SIZE
    val forAutosaveCompressed: ByteArray? get() = fullAfterForAutosave ?: (if (!isSubTile) afterCompressed else null)
}

/**
 * Base sealed interface for undoable actions on the canvas.
 */
sealed interface UndoCommand

/**
 * A single stroke undo record covering all tiles mutated by that stroke.
 */
class StrokeCommand(
    val deltas: List<TileDelta>
) : UndoCommand

/**
 * An undo record for layer creation, preserving the created layer ID, its metadata,
 * its storage index, and the active layer ID prior to creation.
 */
class AddLayerCommand(
    val layerId: Long,
    val layerName: String,
    val layerOpacity: Float,
    val layerIsVisible: Boolean,
    val storageIndex: Int,
    val activeLayerIdBefore: Long
) : UndoCommand

/**
 * An undo record for layer deletion, preserving layer metadata and tile snapshots.
 */
class DeleteLayerCommand(
    val layerId: Long,
    val layerName: String,
    val layerOpacity: Float,
    val layerIsVisible: Boolean,
    val storageIndex: Int,
    val activeLayerIdBefore: Long,
    val tilesSnapshot: Map<TileCoord, ByteArray>,
    val thumbnailBitmap: android.graphics.Bitmap? = null
) : UndoCommand

/**
 * High-performance, memory-efficient undo/redo manager for the infinite raster canvas.
 * Compresses tile deltas with Deflater (typically 1MB -> <15KB) for negligible RAM overhead.
 */
data class UndoWorkItem(
    val layerId: Long,
    val coord: TileCoord,
    val beforeRaw: ByteArray?,
    val afterRaw: ByteArray?
)

class UndoManager(val maxHistorySize: Int = 30) {

    private var nativeHistoryPtr: Long = 0L

    init {
        check(MiltonNative.isLoaded) { "MiltonNative core library must be loaded for UndoManager" }
        nativeHistoryPtr = MiltonNative.createHistoryManager(maxHistorySize)
        check(nativeHistoryPtr != 0L) { "Failed to create native HistoryManager" }
    }

    fun release() {
        if (nativeHistoryPtr != 0L) {
            MiltonNative.destroyHistoryManager(nativeHistoryPtr)
            nativeHistoryPtr = 0L
        }
    }

    protected fun finalize() {
        release()
    }

    private val lock = Any()
    private val undoStack = ArrayDeque<UndoCommand>()
    private val redoStack = ArrayDeque<UndoCommand>()

    // Dedicated background coroutine scope for asynchronous tile delta compression
    private val undoScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Tiles touched in the currently active stroke before stamping:
    private val pendingPreStrokeRaw = mutableMapOf<Pair<Long, TileCoord>, ByteArray?>()

    var onStateChangedListener: (() -> Unit)? = null
    var onTilesCommittedListener: ((List<TileDelta>) -> Unit)? = null

    val canUndo: Boolean get() = synchronized(lock) { undoStack.isNotEmpty() }
    val canRedo: Boolean get() = synchronized(lock) { redoStack.isNotEmpty() }
    val nativeMemoryBytes: Long get() = if (nativeHistoryPtr != 0L) MiltonNative.historyTotalMemoryBytes(nativeHistoryPtr) else 0L

    /**
     * Call before stamping stroke dabs to snapshot pre-stroke state of affected tiles.
     * Operates via fast DirectBufferPool memory transfer without in-stroke CPU compression stalls.
     */
    fun capturePreStrokeTiles(layerId: Long, tiles: Collection<RasterTile>) {
        val prevFbo = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
        try {
            for (tile in tiles) {
                val key = layerId to tile.coord
                if (pendingPreStrokeRaw.containsKey(key)) {
                    continue
                }
                if (tile.isInitialized && tile.hasContent) {
                    val raw = ByteArray(TileCoord.TILE_SIZE * TileCoord.TILE_SIZE * 4)
                    DirectBufferPool.useBuffer { directBuf ->
                        tile.readPixelsDirect(directBuf)
                        directBuf.get(raw)
                        directBuf.rewind()
                    }
                    pendingPreStrokeRaw[key] = raw
                } else {
                    pendingPreStrokeRaw[key] = null
                }
            }
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, prevFbo[0])
        }
    }

    fun capturePreStrokeTiles(tiles: Collection<RasterTile>) {
        capturePreStrokeTiles(1L, tiles)
    }

    /**
     * Explicitly registers a tile's pre-modification raw bytes for undo tracking (e.g. before cut/transform).
     */
    fun registerPreModifiedTile(layerId: Long, coord: TileCoord, rawBytes: ByteArray?) {
        val key = layerId to coord
        if (!pendingPreStrokeRaw.containsKey(key)) {
            pendingPreStrokeRaw[key] = rawBytes
        }
    }

    /**
     * Clears and recycles all pending pre-stroke snapshots without pushing to undo stack.
     */
    fun clearPending() {
        pendingPreStrokeRaw.clear()
    }

    /**
     * Finalizes stroke commits asynchronously.
     * Fast pixel readback occurs on the GL thread (~1ms per tile), and heavy dirty-rect computation
     * and ZSTD compression run on a background thread.
     * Prevents the GL render thread from ever hanging when pen is lifted.
     */
    fun commitMultiLayerStroke(layers: Collection<Pair<Long, TileMap>>) {
        if (pendingPreStrokeRaw.isEmpty()) return

        val layerMap = layers.associate { it.first to it.second }
        val workItems = ArrayList<UndoWorkItem>(pendingPreStrokeRaw.size)

        // Step 1: Read all "after" pixels quickly on the GL thread
        val prevFbo = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
        try {
            for ((key, beforeRaw) in pendingPreStrokeRaw) {
                val (entryLayerId, coord) = key
                val tileMap = layerMap[entryLayerId] ?: continue
                val tile = tileMap.getExistingTile(coord.tx, coord.ty)
                val afterRaw = if (tile != null && tile.isInitialized && tile.hasContent) {
                    val raw = ByteArray(TileCoord.TILE_SIZE * TileCoord.TILE_SIZE * 4)
                    DirectBufferPool.useBuffer { directBuf ->
                        tile.readPixelsDirect(directBuf)
                        directBuf.get(raw)
                        directBuf.rewind()
                    }
                    raw
                } else {
                    null
                }
                workItems.add(UndoWorkItem(entryLayerId, coord, beforeRaw, afterRaw))
            }
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, prevFbo[0])
        }
        pendingPreStrokeRaw.clear()

        if (workItems.isEmpty()) return

        // Step 2: Offload dirty rect computing and ZSTD compression to background worker thread
        undoScope.launch(Dispatchers.Default) {
            val deltas = mutableListOf<TileDelta>()

            for (item in workItems) {
                val beforeRaw = item.beforeRaw
                val afterRaw = item.afterRaw

                if (beforeRaw != null && afterRaw != null) {
                    val dirtyRect = if (MiltonNative.isLoaded) {
                        MiltonNative.computeDirtyRect(beforeRaw, afterRaw)
                    } else null

                    if (dirtyRect != null && dirtyRect.size == 4 && dirtyRect[2] > 0 && dirtyRect[3] > 0) {
                        val minX = dirtyRect[0]
                        val minY = dirtyRect[1]
                        val width = dirtyRect[2]
                        val height = dirtyRect[3]

                        val beforePatch = MiltonNative.createSubTilePatch(beforeRaw, minX, minY, width, height)
                        val afterPatch = MiltonNative.createSubTilePatch(afterRaw, minX, minY, width, height)
                        val fullAfter = compress(afterRaw)
                        deltas.add(
                            TileDelta(
                                layerId = item.layerId,
                                coord = item.coord,
                                beforeCompressed = beforePatch,
                                afterCompressed = afterPatch,
                                minX = minX,
                                minY = minY,
                                patchWidth = width,
                                patchHeight = height,
                                fullAfterForAutosave = fullAfter
                            )
                        )
                    } else {
                        val beforeFull = compress(beforeRaw)
                        val afterFull = compress(afterRaw)
                        deltas.add(
                            TileDelta(
                                layerId = item.layerId,
                                coord = item.coord,
                                beforeCompressed = beforeFull,
                                afterCompressed = afterFull,
                                fullAfterForAutosave = afterFull
                            )
                        )
                    }
                } else if (beforeRaw == null && afterRaw != null) {
                    val fullAfter = compress(afterRaw)
                    deltas.add(
                        TileDelta(
                            layerId = item.layerId,
                            coord = item.coord,
                            beforeCompressed = null,
                            afterCompressed = fullAfter,
                            fullAfterForAutosave = fullAfter
                        )
                    )
                } else if (beforeRaw != null && afterRaw == null) {
                    val fullBefore = compress(beforeRaw)
                    deltas.add(
                        TileDelta(
                            layerId = item.layerId,
                            coord = item.coord,
                            beforeCompressed = fullBefore,
                            afterCompressed = null,
                            fullAfterForAutosave = null
                        )
                    )
                }
            }

            if (deltas.isNotEmpty()) {
                if (nativeHistoryPtr != 0L) {
                    for (i in deltas.indices) {
                        val d = deltas[i]
                        MiltonNative.historyPushTileDelta(
                            nativeHistoryPtr,
                            isStartOfCommand = (i == 0),
                            layerId = d.layerId,
                            tx = d.coord.tx,
                            ty = d.coord.ty,
                            minX = d.minX,
                            minY = d.minY,
                            width = d.patchWidth,
                            height = d.patchHeight,
                            beforePatch = d.beforeCompressed,
                            afterPatch = d.afterCompressed
                        )
                    }
                }
                synchronized(lock) {
                    redoStack.clear()
                    undoStack.push(StrokeCommand(deltas))
                    if (undoStack.size > maxHistorySize) {
                        undoStack.removeLast()
                    }
                }
                onStateChangedListener?.invoke()
                onTilesCommittedListener?.invoke(deltas)
                for (d in deltas) {
                    d.fullAfterForAutosave = null
                }
            }
        }
    }

    /**
     * Call after stamping stroke dabs to finalize the stroke command into the undo stack.
     */
    fun commitStroke(layerId: Long, tileMap: TileMap) {
        commitMultiLayerStroke(listOf(layerId to tileMap))
    }

    fun commitStroke(tileMap: TileMap) {
        commitStroke(1L, tileMap)
    }

    fun pushCustomCommand(command: UndoCommand) {
        synchronized(lock) {
            redoStack.clear()
            undoStack.push(command)
            if (undoStack.size > maxHistorySize) {
                undoStack.removeLast()
            }
        }
        onStateChangedListener?.invoke()
    }

    private fun applyDelta(
        tile: RasterTile,
        cacheDir: java.io.File?,
        targetCompressed: ByteArray?,
        oppositeCompressed: ByteArray?,
        delta: TileDelta,
        isUndo: Boolean
    ): TileDelta {
        tile.ensureResident(cacheDir)
        if (targetCompressed != null) {
            if (delta.isSubTile && MiltonNative.isLoaded) {
                var applied = false
                var fullAutosaveTile: ByteArray? = null
                DirectBufferPool.useBuffer { directBuf ->
                    tile.readPixelsDirect(directBuf)
                    val ok = MiltonNative.applySubTilePatchDirect(
                        directBuf, targetCompressed,
                        delta.minX, delta.minY, delta.patchWidth, delta.patchHeight
                    )
                    if (ok) {
                        tile.writePixelsDirect(directBuf)
                        tile.hasContent = true
                        fullAutosaveTile = compress(directBuf)
                        applied = true
                    }
                }
                if (applied) {
                    return TileDelta(
                        layerId = delta.layerId,
                        coord = delta.coord,
                        beforeCompressed = if (isUndo) delta.afterCompressed else delta.beforeCompressed,
                        afterCompressed = targetCompressed,
                        minX = delta.minX,
                        minY = delta.minY,
                        patchWidth = delta.patchWidth,
                        patchHeight = delta.patchHeight,
                        fullAfterForAutosave = fullAutosaveTile
                    )
                }
            }
            // Full tile decompression
            DirectBufferPool.useBuffer { decompressedBuf ->
                if (decompressDirect(targetCompressed, decompressedBuf)) {
                    tile.writePixelsDirect(decompressedBuf)
                    tile.hasContent = true
                } else {
                    val decompressed = decompress(targetCompressed)
                    tile.writePixels(decompressed)
                    tile.hasContent = true
                }
            }
            return TileDelta(
                layerId = delta.layerId,
                coord = delta.coord,
                beforeCompressed = if (isUndo) delta.afterCompressed else delta.beforeCompressed,
                afterCompressed = targetCompressed,
                fullAfterForAutosave = targetCompressed
            )
        } else {
            tile.clear()
            tile.deleteDiskSwap(cacheDir)
            return TileDelta(
                layerId = delta.layerId,
                coord = delta.coord,
                beforeCompressed = oppositeCompressed,
                afterCompressed = null,
                fullAfterForAutosave = null
            )
        }
    }

    /**
     * Reverts the most recent stroke or layer operation across layers. Must be executed on the GL thread.
     */
    fun undo(layerManager: LayerManager): Boolean {
        val command = synchronized(lock) {
            if (undoStack.isEmpty()) return false
            undoStack.pop()
        }
        when (command) {
            is StrokeCommand -> {
                val deltasForAutosave = mutableListOf<TileDelta>()
                for (delta in command.deltas) {
                    val layer = layerManager.getLayer(delta.layerId) ?: continue
                    val tile = layer.tileMap.getOrCreateTile(delta.coord.tx, delta.coord.ty)
                    val committedDelta = applyDelta(
                        tile = tile,
                        cacheDir = layer.tileMap.cacheDir,
                        targetCompressed = delta.beforeCompressed,
                        oppositeCompressed = delta.afterCompressed,
                        delta = delta,
                        isUndo = true
                    )
                    deltasForAutosave.add(committedDelta)
                }
                synchronized(lock) { redoStack.push(command) }
                onStateChangedListener?.invoke()
                onTilesCommittedListener?.invoke(deltasForAutosave)
                for (d in deltasForAutosave) {
                    d.fullAfterForAutosave = null
                }
            }
            is AddLayerCommand -> {
                val layer = layerManager.getLayer(command.layerId)
                if (layer != null) {
                    layer.tileMap.releaseAll()
                    layerManager.removeLayer(command.layerId)
                }
                layerManager.selectLayer(command.activeLayerIdBefore)
                synchronized(lock) { redoStack.push(command) }
                onStateChangedListener?.invoke()
            }
            is DeleteLayerCommand -> {
                val restoredLayer = layerManager.restoreLayer(
                    id = command.layerId,
                    name = command.layerName,
                    opacity = command.layerOpacity,
                    isVisible = command.layerIsVisible,
                    storageIndex = command.storageIndex,
                    thumbnail = command.thumbnailBitmap
                )
                for ((coord, compressed) in command.tilesSnapshot) {
                    val tile = restoredLayer.tileMap.getOrCreateTile(coord.tx, coord.ty)
                    DirectBufferPool.useBuffer { decompressedBuf ->
                        if (decompressDirect(compressed, decompressedBuf)) {
                            tile.writePixelsDirect(decompressedBuf)
                        } else {
                            val raw = decompress(compressed)
                            tile.writePixels(raw)
                        }
                    }
                    tile.hasContent = true
                }
                layerManager.selectLayer(command.activeLayerIdBefore)
                synchronized(lock) { redoStack.push(command) }
                onStateChangedListener?.invoke()
                val restoredDeltas = command.tilesSnapshot.map { (coord, compressed) ->
                    TileDelta(command.layerId, coord, null, compressed)
                }
                onTilesCommittedListener?.invoke(restoredDeltas)
            }
        }
        return true
    }

    /**
     * Reverts the most recent stroke for a single TileMap.
     */
    fun undo(tileMap: TileMap): Boolean {
        val command = synchronized(lock) {
            if (undoStack.isEmpty()) return false
            undoStack.pop() as? StrokeCommand ?: return false
        }
        val deltasForAutosave = mutableListOf<TileDelta>()
        for (delta in command.deltas) {
            val tile = tileMap.getOrCreateTile(delta.coord.tx, delta.coord.ty)
            val committedDelta = applyDelta(
                tile = tile,
                cacheDir = tileMap.cacheDir,
                targetCompressed = delta.beforeCompressed,
                oppositeCompressed = delta.afterCompressed,
                delta = delta,
                isUndo = true
            )
            deltasForAutosave.add(committedDelta)
        }
        synchronized(lock) { redoStack.push(command) }
        onStateChangedListener?.invoke()
        onTilesCommittedListener?.invoke(deltasForAutosave)
        for (d in deltasForAutosave) {
            d.fullAfterForAutosave = null
        }
        return true
    }

    /**
     * Reapplies the most recently undone action across layers. Must be executed on the GL thread.
     */
    fun redo(layerManager: LayerManager): Boolean {
        val command = synchronized(lock) {
            if (redoStack.isEmpty()) return false
            redoStack.pop()
        }
        when (command) {
            is StrokeCommand -> {
                val deltasForAutosave = mutableListOf<TileDelta>()
                for (delta in command.deltas) {
                    val layer = layerManager.getLayer(delta.layerId) ?: continue
                    val tile = layer.tileMap.getOrCreateTile(delta.coord.tx, delta.coord.ty)
                    val committedDelta = applyDelta(
                        tile = tile,
                        cacheDir = layer.tileMap.cacheDir,
                        targetCompressed = delta.afterCompressed,
                        oppositeCompressed = delta.beforeCompressed,
                        delta = delta,
                        isUndo = false
                    )
                    deltasForAutosave.add(committedDelta)
                }
                synchronized(lock) { undoStack.push(command) }
                onStateChangedListener?.invoke()
                onTilesCommittedListener?.invoke(deltasForAutosave)
                for (d in deltasForAutosave) {
                    d.fullAfterForAutosave = null
                }
            }
            is AddLayerCommand -> {
                layerManager.restoreLayer(
                    id = command.layerId,
                    name = command.layerName,
                    opacity = command.layerOpacity,
                    isVisible = command.layerIsVisible,
                    storageIndex = command.storageIndex,
                    thumbnail = null
                )
                layerManager.selectLayer(command.layerId)
                synchronized(lock) { undoStack.push(command) }
                onStateChangedListener?.invoke()
            }
            is DeleteLayerCommand -> {
                val layer = layerManager.getLayer(command.layerId)
                if (layer != null) {
                    layer.tileMap.releaseAll()
                    layerManager.removeLayer(command.layerId)
                }
                synchronized(lock) { undoStack.push(command) }
                onStateChangedListener?.invoke()
                val clearedDeltas = command.tilesSnapshot.map { (coord, compressed) ->
                    TileDelta(command.layerId, coord, compressed, null)
                }
                onTilesCommittedListener?.invoke(clearedDeltas)
            }
        }
        return true
    }

    /**
     * Reapplies the most recently undone stroke for a single TileMap.
     */
    fun redo(tileMap: TileMap): Boolean {
        val command = synchronized(lock) {
            if (redoStack.isEmpty()) return false
            redoStack.pop() as? StrokeCommand ?: return false
        }
        val deltasForAutosave = mutableListOf<TileDelta>()
        for (delta in command.deltas) {
            val tile = tileMap.getOrCreateTile(delta.coord.tx, delta.coord.ty)
            val committedDelta = applyDelta(
                tile = tile,
                cacheDir = tileMap.cacheDir,
                targetCompressed = delta.afterCompressed,
                oppositeCompressed = delta.beforeCompressed,
                delta = delta,
                isUndo = false
            )
            deltasForAutosave.add(committedDelta)
        }
        synchronized(lock) { undoStack.push(command) }
        onStateChangedListener?.invoke()
        onTilesCommittedListener?.invoke(deltasForAutosave)
        for (d in deltasForAutosave) {
            d.fullAfterForAutosave = null
        }
        return true
    }

    fun clear() {
        if (nativeHistoryPtr != 0L) {
            MiltonNative.historyClear(nativeHistoryPtr)
        }
        synchronized(lock) {
            undoStack.clear()
            redoStack.clear()
            clearPending()
        }
        onStateChangedListener?.invoke()
    }

    companion object {
        fun compress(input: ByteArray): ByteArray {
            return MiltonNative.compressTile(input)
        }

        fun compress(directBuf: ByteBuffer): ByteArray {
            return MiltonNative.compressTileDirect(directBuf)
        }

        fun decompress(input: ByteArray): ByteArray {
            return MiltonNative.decompressTile(input)
        }

        fun decompressDirect(input: ByteArray, outDirectBuf: ByteBuffer): Boolean {
            return MiltonNative.decompressTileDirect(input, outDirectBuf)
        }
    }
}
