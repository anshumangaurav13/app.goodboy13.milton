package app.goodboy13.milton.core.storage

import android.content.Context
import android.util.Log
import app.goodboy13.milton.core.native.MiltonNative
import app.goodboy13.milton.ui.MiltonCanvasView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Dedicated high-speed compression and decompression codec for standalone .milton ZIP archives.
 * Driven 100% by native Rust (`milton_core::archive`) for high-throughput zero-copy archive packaging.
 */
class MiltonArchiveCodec(private val context: Context) {

    companion object {
        private const val TAG = "MiltonArchiveCodec"
    }

    /**
     * Packages the current project into a standalone .milton ZIP archive via native Rust.
     */
    suspend fun exportToMiltonZip(
        documentTitle: String,
        canvasView: MiltonCanvasView,
        autosaveCoordinator: AutosaveCoordinator,
        destinationFile: File
    ) = withContext(Dispatchers.IO) {
        // Ensure latest state is flushed to disk
        autosaveCoordinator.performAutosave(documentTitle, canvasView)

        check(autosaveCoordinator.autosaveManifestFile.exists()) {
            "Manifest file does not exist for export"
        }

        val manifestJson = autosaveCoordinator.autosaveManifestFile.readText()
        val ok = MiltonNative.exportArchive(
            manifestJson = manifestJson,
            tilesDir = autosaveCoordinator.autosaveTilesDir.absolutePath,
            destinationZip = destinationFile.absolutePath
        )
        check(ok) {
            "Native export to .milton archive failed for ${destinationFile.absolutePath}"
        }
        Log.i(TAG, "Native exported .milton archive to ${destinationFile.absolutePath}")
    }

    /**
     * Imports a .milton ZIP archive and unpacks it into the active workspace via native Rust.
     */
    suspend fun importFromMiltonZip(
        inputStream: InputStream,
        canvasView: MiltonCanvasView,
        autosaveCoordinator: AutosaveCoordinator
    ): DocumentMetadata? = withContext(Dispatchers.IO) {
        val tempZip = File(context.cacheDir, "import_temp_${System.currentTimeMillis()}.mltn")
        try {
            FileOutputStream(tempZip).use { fos -> inputStream.copyTo(fos) }
            autosaveCoordinator.autosaveTilesDir.listFiles()?.forEach { it.delete() }

            val manifestText = MiltonNative.importArchive(
                sourceZip = tempZip.absolutePath,
                destinationTilesDir = autosaveCoordinator.autosaveTilesDir.absolutePath
            )
            check(!manifestText.isNullOrBlank()) {
                "Failed to unpack .milton archive from ${tempZip.absolutePath}"
            }

            val metadata = DocumentMetadata.fromJson(manifestText)
            autosaveCoordinator.autosaveManifestFile.writeText(manifestText)
            withContext(Dispatchers.Main) {
                autosaveCoordinator.loadMetadataIntoCanvas(metadata, autosaveCoordinator.autosaveTilesDir, canvasView)
            }
            Log.i(TAG, "Native imported .milton archive from ${tempZip.absolutePath}")
            metadata
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import .milton archive", e)
            null
        } finally {
            tempZip.delete()
        }
    }
}
