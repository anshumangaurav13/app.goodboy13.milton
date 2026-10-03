package app.goodboy13.milton.core.storage

import android.content.Context
import android.os.Debug
import android.util.Log
import app.goodboy13.milton.ui.MiltonCanvasView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Summary descriptor of a saved project in the local Milton library.
 */
data class SavedProjectSummary(
    val id: String,
    val title: String,
    val modifiedAt: Long,
    val layerCount: Int,
    val sizeOnDiskBytes: Long
)

/**
 * Repository responsible for indexing, persisting, loading, and managing
 * projects within the user's local Milton artwork library.
 */
class ProjectCatalogRepository(private val context: Context) {

    companion object {
        private const val TAG = "ProjectCatalogRepository"
        private const val PROJECTS_DIR_NAME = "projects"
    }

    val projectsDir: File get() = File(context.filesDir, PROJECTS_DIR_NAME).apply { mkdirs() }
    var activeProjectId: String? = null

    /**
     * Lists all saved projects ordered from most recently modified to oldest.
     */
    fun listSavedProjects(): List<SavedProjectSummary> {
        val list = mutableListOf<SavedProjectSummary>()
        val dirs = projectsDir.listFiles() ?: emptyArray()
        for (dir in dirs) {
            if (dir.isDirectory) {
                val manifest = File(dir, AutosaveCoordinator.MANIFEST_FILE_NAME)
                if (manifest.exists()) {
                    try {
                        val meta = DocumentMetadata.fromJson(manifest.readText())
                        var size = manifest.length()
                        val tiles = File(dir, AutosaveCoordinator.TILES_DIR_NAME).listFiles() ?: emptyArray()
                        for (t in tiles) size += t.length()
                        list.add(
                            SavedProjectSummary(
                                id = dir.name,
                                title = meta.title,
                                modifiedAt = meta.modifiedAt,
                                layerCount = meta.layers.size,
                                sizeOnDiskBytes = size
                            )
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to read project in ${dir.name}", e)
                    }
                }
            }
        }
        return list.sortedByDescending { it.modifiedAt }
    }

    /**
     * Persists the current canvas session into the local projects library.
     */
    suspend fun saveCurrentProjectToLibrary(
        documentTitle: String,
        canvasView: MiltonCanvasView,
        autosaveCoordinator: AutosaveCoordinator
    ): SavedProjectSummary = withContext(Dispatchers.IO) {
        val projId = activeProjectId ?: "proj_${System.currentTimeMillis()}"
        activeProjectId = projId
        autosaveCoordinator.activeProjectId = projId
        autosaveCoordinator.hasUnsavedChanges = false

        autosaveCoordinator.flushAutosaveNow(documentTitle, canvasView)

        val targetDir = File(projectsDir, projId).apply { mkdirs() }
        val targetTilesDir = File(targetDir, AutosaveCoordinator.TILES_DIR_NAME).apply { mkdirs() }

        val meta = if (autosaveCoordinator.autosaveManifestFile.exists()) {
            val loadedMeta = DocumentMetadata.fromJson(autosaveCoordinator.autosaveManifestFile.readText())
            loadedMeta.projectId = projId
            loadedMeta.hasUnsavedChanges = false
            autosaveCoordinator.autosaveManifestFile.writeText(loadedMeta.toJson())
            loadedMeta
        } else {
            DocumentMetadata(title = documentTitle, projectId = projId, hasUnsavedChanges = false)
        }

        // Copy manifest
        if (autosaveCoordinator.autosaveManifestFile.exists()) {
            autosaveCoordinator.autosaveManifestFile.copyTo(
                File(targetDir, AutosaveCoordinator.MANIFEST_FILE_NAME),
                overwrite = true
            )
        }

        // Copy all active tiles
        val activeTileNames = mutableSetOf<String>()
        autosaveCoordinator.autosaveTilesDir.listFiles()?.forEach { tileFile ->
            if (tileFile.extension == "bin") {
                activeTileNames.add(tileFile.name)
                tileFile.copyTo(File(targetTilesDir, tileFile.name), overwrite = true)
            }
        }

        // Delete any obsolete tiles in target
        targetTilesDir.listFiles()?.forEach { file ->
            if (!activeTileNames.contains(file.name)) file.delete()
        }

        var totalSize = File(targetDir, AutosaveCoordinator.MANIFEST_FILE_NAME).length()
        targetTilesDir.listFiles()?.forEach { totalSize += it.length() }

        SavedProjectSummary(
            id = projId,
            title = meta.title,
            modifiedAt = meta.modifiedAt,
            layerCount = meta.layers.size,
            sizeOnDiskBytes = totalSize
        )
    }

    /**
     * Loads a project from the library into the active canvas session.
     */
    suspend fun loadProjectFromLibrary(
        projectId: String,
        canvasView: MiltonCanvasView,
        autosaveCoordinator: AutosaveCoordinator
    ): DocumentMetadata? = withContext(Dispatchers.IO) {
        val sourceDir = File(projectsDir, projectId)
        val sourceTilesDir = File(sourceDir, AutosaveCoordinator.TILES_DIR_NAME)
        val sourceManifest = File(sourceDir, AutosaveCoordinator.MANIFEST_FILE_NAME)
        if (!sourceManifest.exists()) return@withContext null

        activeProjectId = projectId
        autosaveCoordinator.activeProjectId = projectId
        autosaveCoordinator.hasUnsavedChanges = false

        // Replace autosave directory with library project
        autosaveCoordinator.autosaveTilesDir.listFiles()?.forEach { it.delete() }
        sourceTilesDir.listFiles()?.forEach {
            it.copyTo(File(autosaveCoordinator.autosaveTilesDir, it.name), overwrite = true)
        }

        val metadata = DocumentMetadata.fromJson(sourceManifest.readText())
        metadata.projectId = projectId
        metadata.hasUnsavedChanges = false
        autosaveCoordinator.autosaveManifestFile.writeText(metadata.toJson())

        withContext(Dispatchers.Main) {
            autosaveCoordinator.loadMetadataIntoCanvas(metadata, autosaveCoordinator.autosaveTilesDir, canvasView)
        }
        metadata
    }

    /**
     * Deletes a project from the local library.
     */
    suspend fun deleteProjectFromLibrary(projectId: String): Boolean = withContext(Dispatchers.IO) {
        val targetDir = File(projectsDir, projectId)
        if (activeProjectId == projectId) {
            activeProjectId = null
        }
        targetDir.deleteRecursively()
    }

    /**
     * Resets the active session and creates a fresh blank document on the canvas.
     */
    suspend fun createNewBlankProject(
        canvasView: MiltonCanvasView,
        autosaveCoordinator: AutosaveCoordinator
    ): DocumentMetadata = withContext(Dispatchers.IO) {
        activeProjectId = null
        autosaveCoordinator.activeProjectId = null
        autosaveCoordinator.hasUnsavedChanges = false
        autosaveCoordinator.clearAutosaveSession()

        val viewport = canvasView.renderer.viewport
        val defaultPanX = if (viewport.screenWidth > 1) viewport.screenWidth * 0.5f else 0f
        val defaultPanY = if (viewport.screenHeight > 1) viewport.screenHeight * 0.5f else 0f

        val newMeta = DocumentMetadata(
            title = "Untitled Artwork",
            backgroundColorRgb = 0xFFF6F4ED.toInt(),
            viewportPanX = defaultPanX,
            viewportPanY = defaultPanY,
            viewportZoom = 0.5f,
            viewportRotation = 0f,
            isFlippedHorizontally = false,
            activeLayerId = 1L
        )
        newMeta.layers.add(
            LayerDescriptor(
                id = 1L,
                name = "Layer 1",
                opacity = 1.0f,
                isVisible = true,
                tiles = emptyList()
            )
        )
        autosaveCoordinator.autosaveManifestFile.writeText(newMeta.toJson())

        withContext(Dispatchers.Main) {
            autosaveCoordinator.loadMetadataIntoCanvas(newMeta, autosaveCoordinator.autosaveTilesDir, canvasView)
            canvasView.renderer.viewport.reset()
            canvasView.requestRedraw()
            canvasView.onViewportChanged?.invoke(canvasView.renderer.viewport.zoom, canvasView.renderer.viewport.rotationDegrees)
        }
        newMeta
    }

    fun getTotalSavedProjectsDiskSizeBytes(): Long {
        var total = 0L
        projectsDir.listFiles()?.forEach { dir ->
            if (dir.isDirectory) {
                dir.listFiles()?.forEach { file ->
                    if (file.isDirectory) {
                        file.listFiles()?.forEach { total += it.length() }
                    } else {
                        total += file.length()
                    }
                }
            }
        }
        return total
    }

    fun getAppRamUsageMb(): Int {
        val pssKb = Debug.getPss()
        return if (pssKb > 0) {
            (pssKb / 1024L).toInt()
        } else {
            val runtime = Runtime.getRuntime()
            ((runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)).toInt()
        }
    }

    fun hasCanvasContent(canvasView: MiltonCanvasView): Boolean {
        for (layer in canvasView.layerManager.layers) {
            if (layer.tileMap.getAllTiles().any { it.hasContent }) return true
        }
        return false
    }

    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }
}
