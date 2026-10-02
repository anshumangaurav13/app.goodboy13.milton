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
    val afterCompressed: ByteArray? = null
)

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

    // Tiles touched in the currently active stroke before stamping: (layerId to coord) -> compressed bytes
    private val pendingPreStrokeDeltas = mutableMapOf<Pair<Long, TileCoord>, ByteArray?>()

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
            if (!pendingPreStrokeDeltas.containsKey(key)) {
                val data = if (tile.isInitialized && tile.hasContent) {
                    compress(tile.readPixels())
                } else {
                    null // Tile was empty before this stroke
                }
                pendingPreStrokeDeltas[key] = data
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
        if (pendingPreStrokeDeltas.isEmpty()) return

        val deltas = mutableListOf<TileDelta>()
        val iter = pendingPreStrokeDeltas.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            val (entryLayerId, coord) = entry.key
            if (entryLayerId == layerId) {
                val beforeBytes = entry.value
                val tile = tileMap.getExistingTile(coord.tx, coord.ty)
                val afterBytes = if (tile != null && tile.isInitialized && tile.hasContent) {
                    compress(tile.readPixels())
                } else {
                    null
                }
                if (beforeBytes != null || afterBytes != null) {
                    deltas.add(TileDelta(layerId, coord, beforeBytes, afterBytes))
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
                        val decompressed = decompress(before)
                        tile.writePixels(decompressed)
                        tile.hasContent = true
                    } else {
                        tile.clear()
                        tile.deleteDiskSwap(layer.tileMap.cacheDir)
                    }
                }
                synchronized(lock) { redoStack.push(command) }
                onStateChangedListener?.invoke()
                val revertedDeltas = command.deltas.map {
                    TileDelta(it.layerId, it.coord, it.afterCompressed, it.beforeCompressed)
                }
                onTilesCommittedListener?.invoke(revertedDeltas)
            }
            is AddLayerCommand -> {
                // Undoing layer creation removes the layer
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
                // Restore layer in LayerManager at original storageIndex
                val restoredLayer = layerManager.restoreLayer(
                    id = command.layerId,
                    name = command.layerName,
                    opacity = command.layerOpacity,
                    isVisible = command.layerIsVisible,
                    storageIndex = command.storageIndex,
                    thumbnail = command.thumbnailBitmap
                )
                // Restore its tiles
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
                val decompressed = decompress(before)
                tile.writePixels(decompressed)
                tile.hasContent = true
            } else {
                tile.clear()
                tile.deleteDiskSwap(tileMap.cacheDir)
            }
        }

        synchronized(lock) { redoStack.push(command) }
        onStateChangedListener?.invoke()
        val revertedDeltas = command.deltas.map {
            TileDelta(it.layerId, it.coord, it.afterCompressed, it.beforeCompressed)
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
                        val decompressed = decompress(after)
                        tile.writePixels(decompressed)
                        tile.hasContent = true
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
                // Redoing layer creation restores the layer
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
                // Delete layer again
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
                val decompressed = decompress(after)
                tile.writePixels(decompressed)
                tile.hasContent = true
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
            pendingPreStrokeDeltas.clear()
        }
        onStateChangedListener?.invoke()
    }

    companion object {
        fun compress(input: ByteArray): ByteArray {
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
