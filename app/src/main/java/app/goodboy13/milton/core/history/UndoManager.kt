package app.goodboy13.milton.core.history

import android.opengl.GLES30
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
 * Powered 100% by native off-heap history (milton_core::history) with zero GC object churn.
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

    // Dedicated background coroutine scope for asynchronous tile delta compression
    private val undoScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Tiles touched in the currently active stroke before stamping:
    private val pendingPreStrokeRaw = mutableMapOf<Pair<Long, TileCoord>, ByteArray?>()

    var onStateChangedListener: (() -> Unit)? = null
    var onTilesCommittedListener: ((List<TileDelta>) -> Unit)? = null

    val canUndo: Boolean get() = nativeHistoryPtr != 0L && MiltonNative.historyCanUndo(nativeHistoryPtr)
    val canRedo: Boolean get() = nativeHistoryPtr != 0L && MiltonNative.historyCanRedo(nativeHistoryPtr)
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
     * Clears all pending pre-stroke snapshots.
     */
    fun clearPending() {
        pendingPreStrokeRaw.clear()
    }

    /**
     * Finalizes stroke commits asynchronously.
     * Fast pixel readback occurs on the GL thread (~1ms per tile), and heavy dirty-rect computation
     * and compression run on a background thread.
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

        undoScope.launch(Dispatchers.Default) {
            val deltas = mutableListOf<TileDelta>()

            for (item in workItems) {
                val beforeRaw = item.beforeRaw
                val afterRaw = item.afterRaw

                if (beforeRaw != null && afterRaw != null) {
                    val dirtyRect = MiltonNative.computeDirtyRect(beforeRaw, afterRaw)

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
                pushStroke(deltas)
                onTilesCommittedListener?.invoke(deltas)
                for (d in deltas) {
                    d.fullAfterForAutosave = null
                }
            }
        }
    }

    /**
     * Pushes a completed stroke (list of tile deltas) directly into native off-heap history.
     */
    fun pushStroke(deltas: List<TileDelta>) {
        if (deltas.isEmpty()) return
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
        onStateChangedListener?.invoke()
    }

    fun commitStroke(layerId: Long, tileMap: TileMap) {
        commitMultiLayerStroke(listOf(layerId to tileMap))
    }

    fun commitStroke(tileMap: TileMap) {
        commitStroke(1L, tileMap)
    }

    fun pushAddLayer(
        layerId: Long,
        name: String,
        opacity: Float,
        isVisible: Boolean,
        storageIndex: Int,
        activeLayerIdBefore: Long
    ) {
        if (nativeHistoryPtr != 0L) {
            MiltonNative.historyPushAddLayer(
                nativeHistoryPtr,
                layerId,
                name,
                opacity,
                isVisible,
                storageIndex,
                activeLayerIdBefore
            )
        }
        onStateChangedListener?.invoke()
    }

    fun pushDeleteLayer(
        layerId: Long,
        name: String,
        opacity: Float,
        isVisible: Boolean,
        storageIndex: Int,
        activeLayerIdBefore: Long,
        tilesSnapshot: Map<TileCoord, ByteArray>
    ) {
        if (nativeHistoryPtr != 0L) {
            MiltonNative.historyPushDeleteLayer(
                nativeHistoryPtr,
                layerId,
                name,
                opacity,
                isVisible,
                storageIndex,
                activeLayerIdBefore
            )
            for ((coord, bytes) in tilesSnapshot) {
                MiltonNative.historyAppendDeleteLayerTile(
                    nativeHistoryPtr,
                    coord.tx,
                    coord.ty,
                    bytes
                )
            }
        }
        onStateChangedListener?.invoke()
    }

    @Deprecated("Use native pushAddLayer or pushDeleteLayer directly")
    fun pushCustomCommand(command: UndoCommand) {
        when (command) {
            is AddLayerCommand -> pushAddLayer(
                command.layerId,
                command.layerName,
                command.layerOpacity,
                command.layerIsVisible,
                command.storageIndex,
                command.activeLayerIdBefore
            )
            is DeleteLayerCommand -> pushDeleteLayer(
                command.layerId,
                command.layerName,
                command.layerOpacity,
                command.layerIsVisible,
                command.storageIndex,
                command.activeLayerIdBefore,
                command.tilesSnapshot
            )
        }
    }

    /**
     * Reverts the most recent stroke or layer operation across layers. Must be executed on the GL thread.
     */
    fun undo(layerManager: LayerManager): Boolean {
        if (nativeHistoryPtr == 0L || !MiltonNative.historyCanUndo(nativeHistoryPtr)) {
            return false
        }
        val cmdType = MiltonNative.historyGetUndoCommandType(nativeHistoryPtr)
        when (cmdType) {
            1 -> {
                // Stroke command
                val count = MiltonNative.historyGetUndoDeltasCount(nativeHistoryPtr)
                val meta = IntArray(8)
                val layerIdBuf = LongArray(1)
                val deltasForAutosave = mutableListOf<TileDelta>()

                for (i in 0 until count) {
                    if (MiltonNative.historyGetUndoDeltaMetadata(nativeHistoryPtr, i, meta, layerIdBuf)) {
                        val layerId = layerIdBuf[0]
                        val tx = meta[0]
                        val ty = meta[1]
                        val layer = layerManager.getLayer(layerId) ?: continue
                        val tile = layer.tileMap.getOrCreateTile(tx, ty)
                        tile.ensureResident(layer.tileMap.cacheDir)
                        DirectBufferPool.useBuffer { directBuf ->
                            tile.readPixelsDirect(directBuf)
                            val hasContent = MiltonNative.historyApplyUndoTileDirect(nativeHistoryPtr, i, directBuf)
                            if (hasContent) {
                                tile.writePixelsDirect(directBuf)
                                tile.hasContent = true
                                val autosaveBytes = compress(directBuf)
                                deltasForAutosave.add(TileDelta(layerId, TileCoord(tx, ty), null, autosaveBytes))
                            } else {
                                tile.clear()
                                tile.deleteDiskSwap(layer.tileMap.cacheDir)
                                deltasForAutosave.add(TileDelta(layerId, TileCoord(tx, ty), null, null))
                            }
                        }
                    }
                }
                MiltonNative.historyCommitUndo(nativeHistoryPtr)
                onStateChangedListener?.invoke()
                onTilesCommittedListener?.invoke(deltasForAutosave)
                return true
            }
            2 -> {
                // AddLayer command: undoing an add-layer removes the created layer
                val outIds = LongArray(2)
                val outFloats = FloatArray(1)
                val outInts = IntArray(2)
                MiltonNative.historyGetUndoLayerMeta(nativeHistoryPtr, outIds, outFloats, outInts)
                val layerId = outIds[0]
                val activeBefore = outIds[1]
                val layer = layerManager.getLayer(layerId)
                if (layer != null) {
                    layer.tileMap.releaseAll()
                    layerManager.removeLayer(layerId)
                }
                layerManager.selectLayer(activeBefore)
                MiltonNative.historyCommitUndo(nativeHistoryPtr)
                onStateChangedListener?.invoke()
                return true
            }
            3 -> {
                // DeleteLayer command: undoing a delete-layer restores the deleted layer and its tiles
                val outIds = LongArray(2)
                val outFloats = FloatArray(1)
                val outInts = IntArray(2)
                val name = MiltonNative.historyGetUndoLayerMeta(nativeHistoryPtr, outIds, outFloats, outInts) ?: "Layer"
                val layerId = outIds[0]
                val activeBefore = outIds[1]
                val opacity = outFloats[0]
                val isVisible = outInts[0] == 1
                val storageIndex = outInts[1]

                val restoredLayer = layerManager.restoreLayer(
                    id = layerId,
                    name = name,
                    opacity = opacity,
                    isVisible = isVisible,
                    storageIndex = storageIndex,
                    thumbnail = null
                )

                val tileCount = MiltonNative.historyGetUndoDeleteLayerTilesCount(nativeHistoryPtr)
                val coordsBuf = IntArray(2)
                val restoredDeltas = mutableListOf<TileDelta>()

                for (i in 0 until tileCount) {
                    val compressed = MiltonNative.historyGetUndoDeleteLayerTile(nativeHistoryPtr, i, coordsBuf) ?: continue
                    val tx = coordsBuf[0]
                    val ty = coordsBuf[1]
                    val tile = restoredLayer.tileMap.getOrCreateTile(tx, ty)
                    DirectBufferPool.useBuffer { decompressedBuf ->
                        if (decompressDirect(compressed, decompressedBuf)) {
                            tile.writePixelsDirect(decompressedBuf)
                        } else {
                            val raw = decompress(compressed)
                            tile.writePixels(raw)
                        }
                    }
                    tile.hasContent = true
                    restoredDeltas.add(TileDelta(layerId, TileCoord(tx, ty), null, compressed))
                }

                layerManager.selectLayer(activeBefore)
                MiltonNative.historyCommitUndo(nativeHistoryPtr)
                onStateChangedListener?.invoke()
                onTilesCommittedListener?.invoke(restoredDeltas)
                return true
            }
            else -> return false
        }
    }

    /**
     * Reverts the most recent stroke for a single TileMap.
     */
    fun undo(tileMap: TileMap): Boolean {
        if (nativeHistoryPtr != 0L && MiltonNative.historyCanUndo(nativeHistoryPtr)) {
            val cmdType = MiltonNative.historyGetUndoCommandType(nativeHistoryPtr)
            if (cmdType == 1) {
                val count = MiltonNative.historyGetUndoDeltasCount(nativeHistoryPtr)
                val meta = IntArray(8)
                val layerIdBuf = LongArray(1)
                val deltasForAutosave = mutableListOf<TileDelta>()

                for (i in 0 until count) {
                    if (MiltonNative.historyGetUndoDeltaMetadata(nativeHistoryPtr, i, meta, layerIdBuf)) {
                        val layerId = layerIdBuf[0]
                        val tx = meta[0]
                        val ty = meta[1]
                        val tile = tileMap.getOrCreateTile(tx, ty)
                        tile.ensureResident(tileMap.cacheDir)
                        DirectBufferPool.useBuffer { directBuf ->
                            tile.readPixelsDirect(directBuf)
                            val hasContent = MiltonNative.historyApplyUndoTileDirect(nativeHistoryPtr, i, directBuf)
                            if (hasContent) {
                                tile.writePixelsDirect(directBuf)
                                tile.hasContent = true
                                val autosaveBytes = compress(directBuf)
                                deltasForAutosave.add(TileDelta(layerId, TileCoord(tx, ty), null, autosaveBytes))
                            } else {
                                tile.clear()
                                tile.deleteDiskSwap(tileMap.cacheDir)
                                deltasForAutosave.add(TileDelta(layerId, TileCoord(tx, ty), null, null))
                            }
                        }
                    }
                }
                MiltonNative.historyCommitUndo(nativeHistoryPtr)
                onStateChangedListener?.invoke()
                onTilesCommittedListener?.invoke(deltasForAutosave)
                return true
            }
        }
        return false
    }

    /**
     * Reapplies the most recently undone action across layers. Must be executed on the GL thread.
     */
    fun redo(layerManager: LayerManager): Boolean {
        if (nativeHistoryPtr == 0L || !MiltonNative.historyCanRedo(nativeHistoryPtr)) {
            return false
        }
        val cmdType = MiltonNative.historyGetRedoCommandType(nativeHistoryPtr)
        when (cmdType) {
            1 -> {
                // Stroke command
                val count = MiltonNative.historyGetRedoDeltasCount(nativeHistoryPtr)
                val meta = IntArray(8)
                val layerIdBuf = LongArray(1)
                val deltasForAutosave = mutableListOf<TileDelta>()

                for (i in 0 until count) {
                    if (MiltonNative.historyGetRedoDeltaMetadata(nativeHistoryPtr, i, meta, layerIdBuf)) {
                        val layerId = layerIdBuf[0]
                        val tx = meta[0]
                        val ty = meta[1]
                        val layer = layerManager.getLayer(layerId) ?: continue
                        val tile = layer.tileMap.getOrCreateTile(tx, ty)
                        tile.ensureResident(layer.tileMap.cacheDir)
                        DirectBufferPool.useBuffer { directBuf ->
                            tile.readPixelsDirect(directBuf)
                            val hasContent = MiltonNative.historyApplyRedoTileDirect(nativeHistoryPtr, i, directBuf)
                            if (hasContent) {
                                tile.writePixelsDirect(directBuf)
                                tile.hasContent = true
                                val autosaveBytes = compress(directBuf)
                                deltasForAutosave.add(TileDelta(layerId, TileCoord(tx, ty), null, autosaveBytes))
                            } else {
                                tile.clear()
                                tile.deleteDiskSwap(layer.tileMap.cacheDir)
                                deltasForAutosave.add(TileDelta(layerId, TileCoord(tx, ty), null, null))
                            }
                        }
                    }
                }
                MiltonNative.historyCommitRedo(nativeHistoryPtr)
                onStateChangedListener?.invoke()
                onTilesCommittedListener?.invoke(deltasForAutosave)
                return true
            }
            2 -> {
                // AddLayer command: redoing an add-layer restores the layer
                val outIds = LongArray(2)
                val outFloats = FloatArray(1)
                val outInts = IntArray(2)
                val name = MiltonNative.historyGetRedoLayerMeta(nativeHistoryPtr, outIds, outFloats, outInts) ?: "Layer"
                val layerId = outIds[0]
                val opacity = outFloats[0]
                val isVisible = outInts[0] == 1
                val storageIndex = outInts[1]
                layerManager.restoreLayer(
                    id = layerId,
                    name = name,
                    opacity = opacity,
                    isVisible = isVisible,
                    storageIndex = storageIndex,
                    thumbnail = null
                )
                layerManager.selectLayer(layerId)
                MiltonNative.historyCommitRedo(nativeHistoryPtr)
                onStateChangedListener?.invoke()
                return true
            }
            3 -> {
                // DeleteLayer command: redoing a delete-layer removes the layer again
                val outIds = LongArray(2)
                val outFloats = FloatArray(1)
                val outInts = IntArray(2)
                MiltonNative.historyGetRedoLayerMeta(nativeHistoryPtr, outIds, outFloats, outInts)
                val layerId = outIds[0]
                val activeBefore = outIds[1]
                val layer = layerManager.getLayer(layerId)
                if (layer != null) {
                    layer.tileMap.releaseAll()
                    layerManager.removeLayer(layerId)
                }
                layerManager.selectLayer(activeBefore)
                MiltonNative.historyCommitRedo(nativeHistoryPtr)
                onStateChangedListener?.invoke()
                return true
            }
            else -> return false
        }
    }

    /**
     * Reapplies the most recently undone stroke for a single TileMap.
     */
    fun redo(tileMap: TileMap): Boolean {
        if (nativeHistoryPtr != 0L && MiltonNative.historyCanRedo(nativeHistoryPtr)) {
            val cmdType = MiltonNative.historyGetRedoCommandType(nativeHistoryPtr)
            if (cmdType == 1) {
                val count = MiltonNative.historyGetRedoDeltasCount(nativeHistoryPtr)
                val meta = IntArray(8)
                val layerIdBuf = LongArray(1)
                val deltasForAutosave = mutableListOf<TileDelta>()

                for (i in 0 until count) {
                    if (MiltonNative.historyGetRedoDeltaMetadata(nativeHistoryPtr, i, meta, layerIdBuf)) {
                        val layerId = layerIdBuf[0]
                        val tx = meta[0]
                        val ty = meta[1]
                        val tile = tileMap.getOrCreateTile(tx, ty)
                        tile.ensureResident(tileMap.cacheDir)
                        DirectBufferPool.useBuffer { directBuf ->
                            tile.readPixelsDirect(directBuf)
                            val hasContent = MiltonNative.historyApplyRedoTileDirect(nativeHistoryPtr, i, directBuf)
                            if (hasContent) {
                                tile.writePixelsDirect(directBuf)
                                tile.hasContent = true
                                val autosaveBytes = compress(directBuf)
                                deltasForAutosave.add(TileDelta(layerId, TileCoord(tx, ty), null, autosaveBytes))
                            } else {
                                tile.clear()
                                tile.deleteDiskSwap(tileMap.cacheDir)
                                deltasForAutosave.add(TileDelta(layerId, TileCoord(tx, ty), null, null))
                            }
                        }
                    }
                }
                MiltonNative.historyCommitRedo(nativeHistoryPtr)
                onStateChangedListener?.invoke()
                onTilesCommittedListener?.invoke(deltasForAutosave)
                return true
            }
        }
        return false
    }

    fun clear() {
        if (nativeHistoryPtr != 0L) {
            MiltonNative.historyClear(nativeHistoryPtr)
        }
        synchronized(lock) {
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
