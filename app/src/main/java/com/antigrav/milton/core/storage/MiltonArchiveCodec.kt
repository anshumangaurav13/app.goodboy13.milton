package com.antigrav.milton.core.storage

import android.content.Context
import android.util.Log
import com.antigrav.milton.ui.MiltonCanvasView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Dedicated compression and decompression codec for standalone .milton ZIP archives.
 */
class MiltonArchiveCodec(private val context: Context) {

    companion object {
        private const val TAG = "MiltonArchiveCodec"
    }

    /**
     * Packages the current project into a standalone .milton ZIP archive.
     */
    suspend fun exportToMiltonZip(
        documentTitle: String,
        canvasView: MiltonCanvasView,
        autosaveCoordinator: AutosaveCoordinator,
        destinationFile: File
    ) = withContext(Dispatchers.IO) {
        // Ensure latest state is flushed to disk
        autosaveCoordinator.performAutosave(documentTitle, canvasView)

        ZipOutputStream(FileOutputStream(destinationFile)).use { zos ->
            // 1. Write manifest
            if (autosaveCoordinator.autosaveManifestFile.exists()) {
                val entry = ZipEntry(AutosaveCoordinator.MANIFEST_FILE_NAME)
                zos.putNextEntry(entry)
                autosaveCoordinator.autosaveManifestFile.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }

            // 2. Write all tile binaries
            val tileFiles = autosaveCoordinator.autosaveTilesDir.listFiles() ?: emptyArray()
            for (file in tileFiles) {
                if (file.extension == "bin") {
                    val entry = ZipEntry("${AutosaveCoordinator.TILES_DIR_NAME}/${file.name}")
                    zos.putNextEntry(entry)
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
        Log.i(TAG, "Exported .milton archive to ${destinationFile.absolutePath}")
    }

    /**
     * Imports a .milton ZIP archive and unpacks it into the active workspace.
     */
    suspend fun importFromMiltonZip(
        inputStream: InputStream,
        canvasView: MiltonCanvasView,
        autosaveCoordinator: AutosaveCoordinator
    ): DocumentMetadata? = withContext(Dispatchers.IO) {
        val tempUnpackDir = File(context.cacheDir, "unpack_${System.currentTimeMillis()}").apply { mkdirs() }
        val tempTilesDir = File(tempUnpackDir, AutosaveCoordinator.TILES_DIR_NAME).apply { mkdirs() }
        var manifestText: String? = null

        try {
            ZipInputStream(inputStream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (name == AutosaveCoordinator.MANIFEST_FILE_NAME) {
                        manifestText = zis.bufferedReader().readText()
                    } else if (name.startsWith("${AutosaveCoordinator.TILES_DIR_NAME}/") && name.endsWith(".bin")) {
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
            autosaveCoordinator.autosaveTilesDir.listFiles()?.forEach { it.delete() }
            tempTilesDir.listFiles()?.forEach {
                it.copyTo(File(autosaveCoordinator.autosaveTilesDir, it.name), overwrite = true)
            }
            autosaveCoordinator.autosaveManifestFile.writeText(manifestText!!)

            withContext(Dispatchers.Main) {
                autosaveCoordinator.loadMetadataIntoCanvas(metadata, autosaveCoordinator.autosaveTilesDir, canvasView)
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
