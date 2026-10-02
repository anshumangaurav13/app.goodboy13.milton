package com.antigrav.milton.core.history

import com.antigrav.milton.core.tile.RasterTile
import com.antigrav.milton.core.tile.TileCoord
import com.antigrav.milton.core.tile.TileMap
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.zip.Deflater
import java.util.zip.Inflater

import com.antigrav.milton.core.layer.LayerManager

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
 * A single stroke undo record covering all tiles mutated by that stroke.
 */
class StrokeCommand(
    val deltas: List<TileDelta>
)

/**
 * High-performance, memory-efficient undo/redo manager for the infinite raster canvas.
 * Compresses tile deltas with Deflater (typically 1MB -> <15KB) for negligible RAM overhead.
 */
class UndoManager(val maxHistorySize: Int = 30) {

    private val undoStack = ArrayDeque<StrokeCommand>()
    private val redoStack = ArrayDeque<StrokeCommand>()

    // Tiles touched in the currently active stroke before stamping: (layerId to coord) -> compressed bytes
    private val pendingPreStrokeDeltas = mutableMapOf<Pair<Long, TileCoord>, ByteArray?>()

    var onStateChangedListener: (() -> Unit)? = null

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /**
     * Call before stamping stroke dabs to snapshot pre-stroke state of affected tiles.
     */
    fun capturePreStrokeTiles(layerId: Long, tiles: Collection<RasterTile>) {
        for (tile in tiles) {
            val key = layerId to tile.coord
            if (!pendingPreStrokeDeltas.containsKey(key)) {
                val data = if (tile.isInitialized) {
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
                val afterBytes = if (tile != null && tile.isInitialized) {
                    compress(tile.readPixels())
                } else {
                    null
                }
                deltas.add(TileDelta(layerId, coord, beforeBytes, afterBytes))
                iter.remove()
            }
        }

        if (deltas.isEmpty()) return

        redoStack.clear()
        undoStack.push(StrokeCommand(deltas))
        if (undoStack.size > maxHistorySize) {
            undoStack.removeLast()
        }

        onStateChangedListener?.invoke()
    }

    fun commitStroke(tileMap: TileMap) {
        commitStroke(1L, tileMap)
    }

    /**
     * Reverts the most recent stroke across layers. Must be executed on the GL thread.
     */
    fun undo(layerManager: LayerManager): Boolean {
        if (undoStack.isEmpty()) return false

        val command = undoStack.pop()
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

        redoStack.push(command)
        onStateChangedListener?.invoke()
        return true
    }

    /**
     * Reverts the most recent stroke for a single TileMap.
     */
    fun undo(tileMap: TileMap): Boolean {
        if (undoStack.isEmpty()) return false

        val command = undoStack.pop()
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

        redoStack.push(command)
        onStateChangedListener?.invoke()
        return true
    }

    /**
     * Reapplies the most recently undone stroke across layers. Must be executed on the GL thread.
     */
    fun redo(layerManager: LayerManager): Boolean {
        if (redoStack.isEmpty()) return false

        val command = redoStack.pop()
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

        undoStack.push(command)
        onStateChangedListener?.invoke()
        return true
    }

    /**
     * Reapplies the most recently undone stroke for a single TileMap.
     */
    fun redo(tileMap: TileMap): Boolean {
        if (redoStack.isEmpty()) return false

        val command = redoStack.pop()
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

        undoStack.push(command)
        onStateChangedListener?.invoke()
        return true
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        pendingPreStrokeDeltas.clear()
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
