package app.goodboy13.milton.core.history

import android.opengl.GLES30
import app.goodboy13.milton.core.gl.GlTexturePool
import app.goodboy13.milton.core.layer.LayerManager
import app.goodboy13.milton.core.memory.DirectBufferPool
import app.goodboy13.milton.core.native.MiltonNative
import app.goodboy13.milton.core.tile.RasterTile
import app.goodboy13.milton.core.tile.TileCoord
import app.goodboy13.milton.core.tile.TileMap
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.zip.Deflater
import java.util.zip.Inflater

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
class UndoManager(val maxHistorySize: Int = 30) {

    private val lock = Any()
    private val undoStack = ArrayDeque<UndoCommand>()
    private val redoStack = ArrayDeque<UndoCommand>()

    // Tiles touched in the currently active stroke before stamping:
    // Compressed off-heap snapshots to prevent JVM heap bloat and GC/OOM crashes during zoomed-out sketching
    private val pendingPreStrokeCompressed = mutableMapOf<Pair<Long, TileCoord>, ByteArray?>()

    var onStateChangedListener: (() -> Unit)? = null
    var onTilesCommittedListener: ((List<TileDelta>) -> Unit)? = null

    val canUndo: Boolean get() = synchronized(lock) { undoStack.isNotEmpty() }
    val canRedo: Boolean get() = synchronized(lock) { redoStack.isNotEmpty() }

    /**
     * Call before stamping stroke dabs to snapshot pre-stroke state of affected tiles.
     * Operates using reusable off-heap direct ByteBuffers and immediate ZSTD compression
     * to eliminate JVM heap allocations.
     */
    fun capturePreStrokeTiles(layerId: Long, tiles: Collection<RasterTile>) {
        for (tile in tiles) {
            val key = layerId to tile.coord
            if (pendingPreStrokeCompressed.containsKey(key)) {
                continue
            }
            if (tile.isInitialized && tile.hasContent) {
                DirectBufferPool.useBuffer { directBuf ->
                    tile.readPixelsDirect(directBuf)
                    pendingPreStrokeCompressed[key] = compress(directBuf)
                }
            } else {
                pendingPreStrokeCompressed[key] = null
            }
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
        if (!pendingPreStrokeCompressed.containsKey(key)) {
            pendingPreStrokeCompressed[key] = if (rawBytes != null) compress(rawBytes) else null
        }
    }

    /**
     * Clears and recycles all pending pre-stroke snapshots without pushing to undo stack.
     */
    fun clearPending() {
        pendingPreStrokeCompressed.clear()
    }

    /**
     * Call after stamping stroke dabs or committing a multi-layer action to finalize the stroke command into the undo stack.
     */
    fun commitMultiLayerStroke(layers: Collection<Pair<Long, TileMap>>) {
        if (pendingPreStrokeCompressed.isEmpty()) return

        val layerMap = layers.associate { it.first to it.second }
        val deltas = mutableListOf<TileDelta>()

        try {
            if (pendingPreStrokeCompressed.isNotEmpty()) {
                val rawIter = pendingPreStrokeCompressed.iterator()
                while (rawIter.hasNext()) {
                    val entry = rawIter.next()
                    val (entryLayerId, coord) = entry.key
                    val tileMap = layerMap[entryLayerId] ?: continue

                    val beforeCompressed = entry.value
                    val tile = tileMap.getExistingTile(coord.tx, coord.ty)
                    val afterRaw = if (tile != null && tile.isInitialized && tile.hasContent) {
                        tile.readPixels()
                    } else {
                        null
                    }

                    if (beforeCompressed != null && afterRaw != null) {
                        val beforeRaw = decompress(beforeCompressed)
                        val dirtyRect = if (MiltonNative.isLoaded) {
                            MiltonNative.computeDirtyRect(beforeRaw, afterRaw)
                        } else null

                        if (dirtyRect != null && dirtyRect.size == 4) {
                            val minX = dirtyRect[0]
                            val minY = dirtyRect[1]
                            val width = dirtyRect[2]
                            val height = dirtyRect[3]

                            if (width > 0 && height > 0) {
                                val beforePatch = MiltonNative.createSubTilePatch(
                                    beforeRaw, minX, minY, width, height
                                )
                                val afterPatch = MiltonNative.createSubTilePatch(
                                    afterRaw, minX, minY, width, height
                                )
                                val fullAfter = compress(afterRaw)
                                deltas.add(
                                    TileDelta(
                                        layerId = entryLayerId,
                                        coord = coord,
                                        beforeCompressed = beforePatch,
                                        afterCompressed = afterPatch,
                                        minX = minX,
                                        minY = minY,
                                        patchWidth = width,
                                        patchHeight = height,
                                        fullAfterForAutosave = fullAfter
                                    )
                                )
                            }
                        } else {
                            val fullAfter = compress(afterRaw)
                            deltas.add(
                                TileDelta(
                                    layerId = entryLayerId,
                                    coord = coord,
                                    beforeCompressed = beforeCompressed,
                                    afterCompressed = fullAfter,
                                    fullAfterForAutosave = fullAfter
                                )
                            )
                        }
                    } else if (beforeCompressed == null && afterRaw != null) {
                        val fullAfter = compress(afterRaw)
                        deltas.add(
                            TileDelta(
                                layerId = entryLayerId,
                                coord = coord,
                                beforeCompressed = null,
                                afterCompressed = fullAfter,
                                fullAfterForAutosave = fullAfter
                            )
                        )
                    } else if (beforeCompressed != null && afterRaw == null) {
                        deltas.add(
                            TileDelta(
                                layerId = entryLayerId,
                                coord = coord,
                                beforeCompressed = beforeCompressed,
                                afterCompressed = null,
                                fullAfterForAutosave = null
                            )
                        )
                    }
                    rawIter.remove()
                }
            }
        } finally {
            pendingPreStrokeCompressed.clear()
        }

        if (deltas.isEmpty()) return

        synchronized(lock) {
            redoStack.clear()
            undoStack.push(StrokeCommand(deltas))
            if (undoStack.size > maxHistorySize) {
                undoStack.removeLast()
            }
        }

        onStateChangedListener?.invoke()
        onTilesCommittedListener?.invoke(deltas)

        // Drop full tile buffers from deltas after autosave staging to minimize undo stack RAM
        for (delta in deltas) {
            delta.fullAfterForAutosave = null
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
        synchronized(lock) {
            undoStack.clear()
            redoStack.clear()
            clearPending()
        }
        onStateChangedListener?.invoke()
    }

    companion object {
        fun compress(input: ByteArray): ByteArray {
            if (MiltonNative.isLoaded) {
                try {
                    val result = MiltonNative.compressTile(input)
                    if (result.isNotEmpty()) return result
                } catch (e: Throwable) {
                    // Fall back to Java Deflater
                }
            }
            val deflater = Deflater(Deflater.BEST_SPEED)
            deflater.setInput(input)
            deflater.finish()

            val bos = ByteArrayOutputStream(input.size / 10)
            val buffer = ByteArray(16384)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                bos.write(buffer, 0, count)
            }
            deflater.end()
            return bos.toByteArray()
        }

        fun compress(directBuf: ByteBuffer): ByteArray {
            if (MiltonNative.isLoaded) {
                try {
                    val result = MiltonNative.compressTileDirect(directBuf)
                    if (result.isNotEmpty()) return result
                } catch (e: Throwable) {
                    // Fall back to heap array
                }
            }
            val arr = ByteArray(TileCoord.TILE_SIZE * TileCoord.TILE_SIZE * 4)
            directBuf.position(0)
            directBuf.get(arr)
            directBuf.rewind()
            return compress(arr)
        }

        fun decompress(input: ByteArray): ByteArray {
            if (MiltonNative.isLoaded) {
                try {
                    val result = MiltonNative.decompressTile(input)
                    if (result.isNotEmpty()) return result
                } catch (e: Throwable) {
                    // Fall back to Java Inflater
                }
            }
            val inflater = Inflater()
            inflater.setInput(input)

            val bos = ByteArrayOutputStream(TileCoord.TILE_SIZE * TileCoord.TILE_SIZE * 4)
            val buffer = ByteArray(16384)
            try {
                while (!inflater.finished()) {
                    val count = inflater.inflate(buffer)
                    if (count == 0) {
                        if (inflater.needsInput() || inflater.needsDictionary()) break
                    }
                    bos.write(buffer, 0, count)
                }
            } finally {
                inflater.end()
            }
            return bos.toByteArray()
        }

        fun decompressDirect(input: ByteArray, outDirectBuf: ByteBuffer): Boolean {
            if (MiltonNative.isLoaded) {
                try {
                    if (MiltonNative.decompressTileDirect(input, outDirectBuf)) {
                        return true
                    }
                } catch (e: Throwable) {
                    // Fall back
                }
            }
            val raw = decompress(input)
            outDirectBuf.clear()
            outDirectBuf.put(raw)
            outDirectBuf.rewind()
            return true
        }
    }
}
