package app.goodboy13.milton.core.history

import app.goodboy13.milton.core.tile.RasterTile
import app.goodboy13.milton.core.tile.TileCoord
import app.goodboy13.milton.core.tile.TileMap
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.zip.Deflater
import java.util.zip.Inflater

import app.goodboy13.milton.core.layer.LayerManager

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
    val forAutosaveCompressed: ByteArray? get() = fullAfterForAutosave ?: afterCompressed
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

    // Tiles touched in the currently active stroke before stamping: (layerId to coord) -> raw RGBA bytes
    private val pendingPreStrokeRaw = mutableMapOf<Pair<Long, TileCoord>, ByteArray?>()

    var onStateChangedListener: (() -> Unit)? = null
    var onTilesCommittedListener: ((List<TileDelta>) -> Unit)? = null

    val canUndo: Boolean get() = synchronized(lock) { undoStack.isNotEmpty() }
    val canRedo: Boolean get() = synchronized(lock) { redoStack.isNotEmpty() }

    /**
     * Call before stamping stroke dabs to snapshot pre-stroke state of affected tiles.
     */
    fun capturePreStrokeTiles(layerId: Long, tiles: Collection<RasterTile>) {
        for (tile in tiles) {
            val key = layerId to tile.coord
            if (!pendingPreStrokeRaw.containsKey(key)) {
                val data = if (tile.isInitialized && tile.hasContent) {
                    tile.readPixels()
                } else {
                    null // Tile was empty before this stroke
                }
                pendingPreStrokeRaw[key] = data
            }
        }
    }

    fun capturePreStrokeTiles(tiles: Collection<RasterTile>) {
        capturePreStrokeTiles(1L, tiles)
    }

    /**
     * Call after stamping stroke dabs to finalize the stroke command into the undo stack.
     */
    fun commitStroke(layerId: Long, tileMap: TileMap) {
        if (pendingPreStrokeRaw.isEmpty()) return

        val deltas = mutableListOf<TileDelta>()
        val iter = pendingPreStrokeRaw.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            val (entryLayerId, coord) = entry.key
            if (entryLayerId == layerId) {
                val beforeRaw = entry.value
                val tile = tileMap.getExistingTile(coord.tx, coord.ty)
                val afterRaw = if (tile != null && tile.isInitialized && tile.hasContent) {
                    tile.readPixels()
                } else {
                    null
                }

                if (beforeRaw != null && afterRaw != null) {
                    val dirtyRect = if (app.goodboy13.milton.core.native.MiltonNative.isLoaded) {
                        app.goodboy13.milton.core.native.MiltonNative.computeDirtyRect(beforeRaw, afterRaw)
                    } else null

                    if (dirtyRect != null && dirtyRect.size == 4) {
                        val minX = dirtyRect[0]
                        val minY = dirtyRect[1]
                        val width = dirtyRect[2]
                        val height = dirtyRect[3]

                        if (width > 0 && height > 0) {
                            val beforePatch = app.goodboy13.milton.core.native.MiltonNative.createSubTilePatch(
                                beforeRaw, minX, minY, width, height
                            )
                            val afterPatch = app.goodboy13.milton.core.native.MiltonNative.createSubTilePatch(
                                afterRaw, minX, minY, width, height
                            )
                            val fullAfter = compress(afterRaw)
                            deltas.add(
                                TileDelta(
                                    layerId = layerId,
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
                        val beforeFull = compress(beforeRaw)
                        val afterFull = compress(afterRaw)
                        deltas.add(
                            TileDelta(
                                layerId = layerId,
                                coord = coord,
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
                            layerId = layerId,
                            coord = coord,
                            beforeCompressed = null,
                            afterCompressed = fullAfter,
                            fullAfterForAutosave = fullAfter
                        )
                    )
                } else if (beforeRaw != null && afterRaw == null) {
                    val fullBefore = compress(beforeRaw)
                    deltas.add(
                        TileDelta(
                            layerId = layerId,
                            coord = coord,
                            beforeCompressed = fullBefore,
                            afterCompressed = null,
                            fullAfterForAutosave = null
                        )
                    )
                }
                iter.remove()
            }
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
                for (delta in command.deltas) {
                    val layer = layerManager.getLayer(delta.layerId) ?: continue
                    val tile = layer.tileMap.getOrCreateTile(delta.coord.tx, delta.coord.ty)
                    tile.ensureResident(layer.tileMap.cacheDir)
                    val before = delta.beforeCompressed
                    if (before != null) {
                        if (delta.isSubTile && app.goodboy13.milton.core.native.MiltonNative.isLoaded) {
                            val currentPixels = tile.readPixels()
                            val ok = app.goodboy13.milton.core.native.MiltonNative.applySubTilePatch(
                                currentPixels, before,
                                delta.minX, delta.minY, delta.patchWidth, delta.patchHeight
                            )
                            if (ok) {
                                tile.writePixels(currentPixels)
                                tile.hasContent = true
                            } else {
                                val decompressed = decompress(before)
                                tile.writePixels(decompressed)
                                tile.hasContent = true
                            }
                        } else {
                            val decompressed = decompress(before)
                            tile.writePixels(decompressed)
                            tile.hasContent = true
                        }
                    } else {
                        tile.clear()
                        tile.deleteDiskSwap(layer.tileMap.cacheDir)
                    }
                }
                synchronized(lock) { redoStack.push(command) }
                onStateChangedListener?.invoke()
                val revertedDeltas = command.deltas.map {
                    TileDelta(
                        layerId = it.layerId,
                        coord = it.coord,
                        beforeCompressed = it.afterCompressed,
                        afterCompressed = it.beforeCompressed,
                        minX = it.minX,
                        minY = it.minY,
                        patchWidth = it.patchWidth,
                        patchHeight = it.patchHeight
                    )
                }
                onTilesCommittedListener?.invoke(revertedDeltas)
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
                    val raw = decompress(compressed)
                    tile.writePixels(raw)
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
        for (delta in command.deltas) {
            val tile = tileMap.getOrCreateTile(delta.coord.tx, delta.coord.ty)
            tile.ensureResident(tileMap.cacheDir)
            val before = delta.beforeCompressed
            if (before != null) {
                if (delta.isSubTile && app.goodboy13.milton.core.native.MiltonNative.isLoaded) {
                    val currentPixels = tile.readPixels()
                    val ok = app.goodboy13.milton.core.native.MiltonNative.applySubTilePatch(
                        currentPixels, before,
                        delta.minX, delta.minY, delta.patchWidth, delta.patchHeight
                    )
                    if (ok) {
                        tile.writePixels(currentPixels)
                        tile.hasContent = true
                    } else {
                        val decompressed = decompress(before)
                        tile.writePixels(decompressed)
                        tile.hasContent = true
                    }
                } else {
                    val decompressed = decompress(before)
                    tile.writePixels(decompressed)
                    tile.hasContent = true
                }
            } else {
                tile.clear()
                tile.deleteDiskSwap(tileMap.cacheDir)
            }
        }

        synchronized(lock) { redoStack.push(command) }
        onStateChangedListener?.invoke()
        val revertedDeltas = command.deltas.map {
            TileDelta(
                layerId = it.layerId,
                coord = it.coord,
                beforeCompressed = it.afterCompressed,
                afterCompressed = it.beforeCompressed,
                minX = it.minX,
                minY = it.minY,
                patchWidth = it.patchWidth,
                patchHeight = it.patchHeight
            )
        }
        onTilesCommittedListener?.invoke(revertedDeltas)
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
                for (delta in command.deltas) {
                    val layer = layerManager.getLayer(delta.layerId) ?: continue
                    val tile = layer.tileMap.getOrCreateTile(delta.coord.tx, delta.coord.ty)
                    tile.ensureResident(layer.tileMap.cacheDir)
                    val after = delta.afterCompressed
                    if (after != null) {
                        if (delta.isSubTile && app.goodboy13.milton.core.native.MiltonNative.isLoaded) {
                            val currentPixels = tile.readPixels()
                            val ok = app.goodboy13.milton.core.native.MiltonNative.applySubTilePatch(
                                currentPixels, after,
                                delta.minX, delta.minY, delta.patchWidth, delta.patchHeight
                            )
                            if (ok) {
                                tile.writePixels(currentPixels)
                                tile.hasContent = true
                            } else {
                                val decompressed = decompress(after)
                                tile.writePixels(decompressed)
                                tile.hasContent = true
                            }
                        } else {
                            val decompressed = decompress(after)
                            tile.writePixels(decompressed)
                            tile.hasContent = true
                        }
                    } else {
                        tile.clear()
                        tile.deleteDiskSwap(layer.tileMap.cacheDir)
                    }
                }
                synchronized(lock) { undoStack.push(command) }
                onStateChangedListener?.invoke()
                onTilesCommittedListener?.invoke(command.deltas)
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
        for (delta in command.deltas) {
            val tile = tileMap.getOrCreateTile(delta.coord.tx, delta.coord.ty)
            tile.ensureResident(tileMap.cacheDir)
            val after = delta.afterCompressed
            if (after != null) {
                if (delta.isSubTile && app.goodboy13.milton.core.native.MiltonNative.isLoaded) {
                    val currentPixels = tile.readPixels()
                    val ok = app.goodboy13.milton.core.native.MiltonNative.applySubTilePatch(
                        currentPixels, after,
                        delta.minX, delta.minY, delta.patchWidth, delta.patchHeight
                    )
                    if (ok) {
                        tile.writePixels(currentPixels)
                        tile.hasContent = true
                    } else {
                        val decompressed = decompress(after)
                        tile.writePixels(decompressed)
                        tile.hasContent = true
                    }
                } else {
                    val decompressed = decompress(after)
                    tile.writePixels(decompressed)
                    tile.hasContent = true
                }
            } else {
                tile.clear()
                tile.deleteDiskSwap(tileMap.cacheDir)
            }
        }

        synchronized(lock) { undoStack.push(command) }
        onStateChangedListener?.invoke()
        onTilesCommittedListener?.invoke(command.deltas)
        return true
    }

    fun clear() {
        synchronized(lock) {
            undoStack.clear()
            redoStack.clear()
            pendingPreStrokeRaw.clear()
        }
        onStateChangedListener?.invoke()
    }

    companion object {
        fun compress(input: ByteArray): ByteArray {
            if (app.goodboy13.milton.core.native.MiltonNative.isLoaded) {
                try {
                    val result = app.goodboy13.milton.core.native.MiltonNative.compressTile(input)
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

        fun decompress(input: ByteArray): ByteArray {
            if (app.goodboy13.milton.core.native.MiltonNative.isLoaded) {
                try {
                    val result = app.goodboy13.milton.core.native.MiltonNative.decompressTile(input)
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
    }
}
