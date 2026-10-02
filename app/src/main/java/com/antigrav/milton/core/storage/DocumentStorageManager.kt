package com.antigrav.milton.core.storage

import android.content.Context
import android.util.Log
import com.antigrav.milton.core.history.UndoManager
import com.antigrav.milton.core.layer.LayerManager
import com.antigrav.milton.core.viewport.Viewport
import com.antigrav.milton.ui.MiltonCanvasView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Manages low-overhead background auto-saving, project serialization, and .milton archive handling.
 */
class DocumentStorageManager(private val context: Context) {

    companion object {
        private const val TAG = "DocumentStorageManager"
        private const val AUTOSAVE_DIR_NAME = "autosave_project"
        private const val MANIFEST_FILE_NAME = "manifest.json"
        private const val TILES_DIR_NAME = "tiles"
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var autosaveJob: Job? = null

    val autosaveDir: File get() = File(context.filesDir, AUTOSAVE_DIR_NAME).apply { mkdirs() }
    val autosaveTilesDir: File get() = File(autosaveDir, TILES_DIR_NAME).apply { mkdirs() }
    val autosaveManifestFile: File get() = File(autosaveDir, MANIFEST_FILE_NAME)

    /**
     * Staging queue of compressed dirty tile bytes extracted during stroke commits.
     * Key: Pair(layerId, Pair(tx, ty)), Value: Compressed ByteArray or null if cleared.
     */
    val pendingCompressedTiles = ConcurrentHashMap<Pair<Long, Pair<Int, Int>>, ByteArray?>()

    /**
     * Enqueues committed tile deltas from UndoManager without re-reading or re-compressing pixels.
     */
    fun onTilesCommitted(
        deltas: List<com.antigrav.milton.core.history.TileDelta>,
        documentTitle: String,
        canvasView: MiltonCanvasView
    ) {
        for (delta in deltas) {
            val key = delta.layerId to (delta.coord.tx to delta.coord.ty)
            pendingCompressedTiles[key] = delta.afterCompressed
        }
        scheduleAutosave(documentTitle, canvasView)
    }

    /**
     * Enqueues dirty tile bytes for asynchronous compression and disk persistence.
     */
    fun enqueueDirtyTile(layerId: Long, tx: Int, ty: Int, rawBytes: ByteArray) {
        pendingCompressedTiles[layerId to (tx to ty)] = UndoManager.compress(rawBytes)
    }

    /**
     * Retrieves decompressed tile bytes from pending staging, autosave disk, or layer swap.
     */
    fun getTileBytes(layerId: Long, tx: Int, ty: Int, fallbackCacheDir: File?): ByteArray? {
        val key = layerId to (tx to ty)
        if (pendingCompressedTiles.containsKey(key)) {
            val bytes = pendingCompressedTiles[key]
            return if (bytes != null && bytes.isNotEmpty()) UndoManager.decompress(bytes) else null
        }
        val autoFile = File(autosaveTilesDir, "tile_${layerId}_${tx}_${ty}.bin")
        if (autoFile.exists()) {
            return try {
                UndoManager.decompress(autoFile.readBytes())
            } catch (e: Exception) {
                null
            }
        }
        if (fallbackCacheDir != null) {
            val swapFile = File(fallbackCacheDir, "tile_${tx}_${ty}.bin")
            if (swapFile.exists()) {
                return try {
                    UndoManager.decompress(swapFile.readBytes())
                } catch (e: Exception) {
                    null
                }
            }
        }
        return null
    }

    /**
     * Schedules a debounced background autosave (1200ms delay).
     * If user draws again within the interval, the timer resets, ensuring ZERO CPU/battery usage
     * during active drawing gestures.
     */
    fun scheduleAutosave(
        documentTitle: String,
        canvasView: MiltonCanvasView
    ) {
        autosaveJob?.cancel()
        autosaveJob = scope.launch {
            delay(1200L)
            performAutosave(documentTitle, canvasView)
        }
    }

    /**
     * Immediately flushes all dirty tiles and metadata to disk (e.g. on app pause or manual save).
     */
    suspend fun flushAutosaveNow(
        documentTitle: String,
        canvasView: MiltonCanvasView
    ) = withContext(Dispatchers.IO) {
        autosaveJob?.cancel()
        performAutosave(documentTitle, canvasView)
    }

    private fun performAutosave(
        documentTitle: String,
        canvasView: MiltonCanvasView
    ) {
        try {
            val renderer = canvasView.renderer
            val layerManager = renderer.layerManager
            val viewport = renderer.viewport

            // 1. Drain and commit all staged compressed tile buffers to disk
            val tilesSnapshot = HashMap(pendingCompressedTiles)
            for ((key, compressed) in tilesSnapshot) {
                val (layerId, coord) = key
                val (tx, ty) = coord
                val tileFile = File(autosaveTilesDir, "tile_${layerId}_${tx}_${ty}.bin")
                if (compressed != null && compressed.isNotEmpty()) {
                    tileFile.writeBytes(compressed)
                } else {
                    tileFile.delete()
                }
                pendingCompressedTiles.remove(key, compressed)
            }

            // 2. Also ensure any swapped tiles on disk are present in the autosave directory
            for (layer in layerManager.layers) {
                val layerCacheDir = layer.tileMap.cacheDir
                for (tile in layer.tileMap.getAllTiles()) {
                    if (tile.hasContent) {
                        val autoFile = File(autosaveTilesDir, "tile_${layer.id}_${tile.coord.tx}_${tile.coord.ty}.bin")
                        if (!autoFile.exists() && layerCacheDir != null) {
                            val swap = File(layerCacheDir, "tile_${tile.coord.tx}_${tile.coord.ty}.bin")
                            if (swap.exists()) {
                                swap.copyTo(autoFile, overwrite = true)
                            }
                        }
                    }
                }
            }

            // 3. Assemble document metadata
            val metadata = DocumentMetadata(
                title = documentTitle,
                backgroundColorRgb = canvasView.backgroundColorRgb,
                viewportPanX = viewport.panX,
                viewportPanY = viewport.panY,
                viewportZoom = viewport.zoom,
                viewportRotation = viewport.rotationDegrees,
                isFlippedHorizontally = viewport.isFlippedHorizontally,
                activeLayerId = layerManager.activeLayerId
            )

            for (layer in layerManager.layers) {
                val coords = layer.tileMap.getAllTiles()
                    .filter { it.hasContent }
                    .map { it.coord.tx to it.coord.ty }
                metadata.layers.add(
                    LayerDescriptor(
                        id = layer.id,
                        name = layer.name,
                        opacity = layer.opacity,
                        isVisible = layer.isVisible,
                        tiles = coords
                    )
                )
            }

            // 4. Clean up any orphaned tile binaries that no longer belong to any active layer
            val validTileFileNames = mutableSetOf<String>()
            for (l in metadata.layers) {
                for ((tx, ty) in l.tiles) {
                    validTileFileNames.add("tile_${l.id}_${tx}_${ty}.bin")
                }
            }
            autosaveTilesDir.listFiles()?.forEach { file ->
                if (file.extension == "bin" && !validTileFileNames.contains(file.name)) {
                    file.delete()
                }
            }

            // 5. Atomically commit manifest
            val tempManifest = File(autosaveDir, "manifest.json.tmp")
            tempManifest.writeText(metadata.toJson())
            tempManifest.renameTo(autosaveManifestFile)

            Log.i(TAG, "Autosaved project '${documentTitle}' with ${metadata.layers.size} layers successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Autosave failed", e)
        }
    }

    /**
     * Checks if an autosave session exists from a previous app run.
     */
    fun hasAutosaveSession(): Boolean {
        return autosaveManifestFile.exists() && autosaveManifestFile.length() > 0
    }

    /**
     * Restores the complete artwork state from the autosave directory onto the canvas.
     */
    fun restoreAutosave(canvasView: MiltonCanvasView): DocumentMetadata? {
        if (!hasAutosaveSession()) return null
        return try {
            val jsonStr = autosaveManifestFile.readText()
            val metadata = DocumentMetadata.fromJson(jsonStr)
            loadMetadataIntoCanvas(metadata, autosaveTilesDir, canvasView)
            metadata
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore autosave", e)
            null
        }
    }

    /**
     * Restores canvas layers, tiles, background, and viewport from a parsed metadata descriptor.
     */
    fun loadMetadataIntoCanvas(
        metadata: DocumentMetadata,
        sourceTilesDir: File,
        canvasView: MiltonCanvasView
    ) {
        val renderer = canvasView.renderer
        val layerManager = renderer.layerManager
        val viewport = renderer.viewport

        // 1. Restore background color
        canvasView.backgroundColorRgb = metadata.backgroundColorRgb
        renderer.backgroundColorRgb = metadata.backgroundColorRgb

        // 2. Restore viewport
        viewport.panX = metadata.viewportPanX
        viewport.panY = metadata.viewportPanY
        viewport.zoom = metadata.viewportZoom
        viewport.rotationDegrees = metadata.viewportRotation
        viewport.isFlippedHorizontally = metadata.isFlippedHorizontally

        // 3. Clear existing layers
        while (layerManager.canDeleteLayer()) {
            layerManager.deleteLayer(layerManager.layers.last().id)
        }
        val baseLayer = layerManager.layers.first()

        // 4. Reconstruct layer stack
        if (metadata.layers.isNotEmpty()) {
            // Reconfigure base layer with descriptor 0
            val firstDesc = metadata.layers[0]
            baseLayer.name = firstDesc.name
            baseLayer.opacity = firstDesc.opacity
            baseLayer.isVisible = firstDesc.isVisible
            restoreLayerTiles(baseLayer, firstDesc, sourceTilesDir)

            // Add remaining layers
            for (i in 1 until metadata.layers.size) {
                val desc = metadata.layers[i]
                val newLayer = layerManager.addLayer(name = desc.name, insertAboveActive = false)
                if (newLayer != null) {
                    newLayer.opacity = desc.opacity
                    newLayer.isVisible = desc.isVisible
                    restoreLayerTiles(newLayer, desc, sourceTilesDir)
                }
            }
        }

        // 5. Select active layer and clear undo history
        layerManager.selectLayer(metadata.activeLayerId)
        renderer.undoManager.clear()
        canvasView.requestRedraw()
    }

    private fun restoreLayerTiles(
        layer: com.antigrav.milton.core.layer.Layer,
        desc: LayerDescriptor,
        sourceTilesDir: File
    ) {
        val layerCacheDir = layer.tileMap.cacheDir
        layerCacheDir?.mkdirs()

        for ((tx, ty) in desc.tiles) {
            val tileFile = File(sourceTilesDir, "tile_${desc.id}_${tx}_${ty}.bin")
            if (tileFile.exists() && layerCacheDir != null) {
                // Copy into layer's disk swap location
                val swapFile = File(layerCacheDir, "tile_${tx}_${ty}.bin")
                tileFile.copyTo(swapFile, overwrite = true)
                val tile = layer.tileMap.getOrCreateTile(tx, ty)
                tile.hasContent = true
                tile.isOnDisk = true
            }
        }
    }

    /**
     * Packages the current project into a standalone .milton ZIP file.
     */
    suspend fun exportToMiltonZip(
        documentTitle: String,
        canvasView: MiltonCanvasView,
        destinationFile: File
    ) = withContext(Dispatchers.IO) {
        // Ensure latest state is flushed to disk
        performAutosave(documentTitle, canvasView)

        ZipOutputStream(FileOutputStream(destinationFile)).use { zos ->
            // 1. Write manifest
            if (autosaveManifestFile.exists()) {
                val entry = ZipEntry(MANIFEST_FILE_NAME)
                zos.putNextEntry(entry)
                autosaveManifestFile.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }

            // 2. Write all tile binaries
            val tileFiles = autosaveTilesDir.listFiles() ?: emptyArray()
            for (file in tileFiles) {
                if (file.extension == "bin") {
                    val entry = ZipEntry("$TILES_DIR_NAME/${file.name}")
                    zos.putNextEntry(entry)
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
        Log.i(TAG, "Exported .milton archive to ${destinationFile.absolutePath}")
    }

    /**
     * Imports a .milton ZIP file and unpacks it into the active workspace.
     */
    suspend fun importFromMiltonZip(
        inputStream: InputStream,
        canvasView: MiltonCanvasView
    ): DocumentMetadata? = withContext(Dispatchers.IO) {
        val tempUnpackDir = File(context.cacheDir, "unpack_${System.currentTimeMillis()}").apply { mkdirs() }
        val tempTilesDir = File(tempUnpackDir, TILES_DIR_NAME).apply { mkdirs() }
        var manifestText: String? = null

        try {
            ZipInputStream(inputStream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (name == MANIFEST_FILE_NAME) {
                        manifestText = zis.bufferedReader().readText()
                    } else if (name.startsWith("$TILES_DIR_NAME/") && name.endsWith(".bin")) {
                        val fileName = name.substringAfterLast("/")
                        val outFile = File(tempTilesDir, fileName)
                        FileOutputStream(outFile).use { fos ->
                            zis.copyTo(fos)
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            if (manifestText == null) {
                Log.e(TAG, "Import failed: Missing manifest.json in .milton archive")
                return@withContext null
            }

            val metadata = DocumentMetadata.fromJson(manifestText!!)

            // Replace autosave directory with imported content
            autosaveTilesDir.listFiles()?.forEach { it.delete() }
            tempTilesDir.listFiles()?.forEach {
                it.copyTo(File(autosaveTilesDir, it.name), overwrite = true)
            }
            autosaveManifestFile.writeText(manifestText!!)

            withContext(Dispatchers.Main) {
                loadMetadataIntoCanvas(metadata, autosaveTilesDir, canvasView)
            }
            metadata
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import .milton archive", e)
            null
        } finally {
            tempUnpackDir.deleteRecursively()
        }
    }
}
