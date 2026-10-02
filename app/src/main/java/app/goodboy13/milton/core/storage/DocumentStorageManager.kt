package app.goodboy13.milton.core.storage

import android.content.Context
import app.goodboy13.milton.core.history.TileDelta
import app.goodboy13.milton.ui.MiltonCanvasView
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Unified storage facade coordinating autosave persistence, project catalog
 * indexing, and .milton archive packaging across dedicated modular services.
 */
class DocumentStorageManager(private val context: Context) {

    val autosaveCoordinator = AutosaveCoordinator(context)
    val projectCatalog = ProjectCatalogRepository(context)
    val archiveCodec = MiltonArchiveCodec(context)

    // Autosave properties
    val autosaveDir: File get() = autosaveCoordinator.autosaveDir
    val autosaveTilesDir: File get() = autosaveCoordinator.autosaveTilesDir
    val autosaveManifestFile: File get() = autosaveCoordinator.autosaveManifestFile
    val pendingCompressedTiles: ConcurrentHashMap<Pair<Long, Pair<Int, Int>>, ByteArray>
        get() = autosaveCoordinator.pendingCompressedTiles

    // Library properties
    val projectsDir: File get() = projectCatalog.projectsDir
    var activeProjectId: String?
        get() = projectCatalog.activeProjectId
        set(value) { projectCatalog.activeProjectId = value }

    // --- Autosave Coordinator Operations ---

    fun onTilesCommitted(
        deltas: List<TileDelta>,
        documentTitle: String,
        canvasView: MiltonCanvasView
    ) {
        autosaveCoordinator.onTilesCommitted(deltas, documentTitle, canvasView)
    }

    fun enqueueDirtyTile(layerId: Long, tx: Int, ty: Int, rawBytes: ByteArray) {
        autosaveCoordinator.enqueueDirtyTile(layerId, tx, ty, rawBytes)
    }

    fun getTileBytes(layerId: Long, tx: Int, ty: Int, fallbackCacheDir: File?): ByteArray? {
        return autosaveCoordinator.getTileBytes(layerId, tx, ty, fallbackCacheDir)
    }

    fun scheduleAutosave(documentTitle: String, canvasView: MiltonCanvasView) {
        autosaveCoordinator.scheduleAutosave(documentTitle, canvasView)
    }

    suspend fun flushAutosaveNow(documentTitle: String, canvasView: MiltonCanvasView) {
        autosaveCoordinator.flushAutosaveNow(documentTitle, canvasView)
    }

    fun hasAutosaveSession(): Boolean {
        return autosaveCoordinator.hasAutosaveSession()
    }

    fun restoreAutosave(canvasView: MiltonCanvasView): DocumentMetadata? {
        return autosaveCoordinator.restoreAutosave(canvasView)
    }

    fun loadMetadataIntoCanvas(
        metadata: DocumentMetadata,
        sourceTilesDir: File,
        canvasView: MiltonCanvasView
    ) {
        autosaveCoordinator.loadMetadataIntoCanvas(metadata, sourceTilesDir, canvasView)
    }

    // --- Archive Codec Operations ---

    suspend fun exportToMiltonZip(
        documentTitle: String,
        canvasView: MiltonCanvasView,
        destinationFile: File
    ) {
        archiveCodec.exportToMiltonZip(documentTitle, canvasView, autosaveCoordinator, destinationFile)
    }

    suspend fun importFromMiltonZip(
        inputStream: InputStream,
        canvasView: MiltonCanvasView
    ): DocumentMetadata? {
        return archiveCodec.importFromMiltonZip(inputStream, canvasView, autosaveCoordinator)
    }

    // --- Project Catalog & Storage Metrics ---

    fun getAppRamUsageMb(): Int {
        return projectCatalog.getAppRamUsageMb()
    }

    fun getCurrentProjectDiskSizeBytes(): Long {
        return autosaveCoordinator.getCurrentProjectDiskSizeBytes()
    }

    fun getTotalSavedProjectsDiskSizeBytes(): Long {
        return projectCatalog.getTotalSavedProjectsDiskSizeBytes()
    }

    fun listSavedProjects(): List<SavedProjectSummary> {
        return projectCatalog.listSavedProjects()
    }

    suspend fun saveCurrentProjectToLibrary(
        documentTitle: String,
        canvasView: MiltonCanvasView
    ): SavedProjectSummary {
        return projectCatalog.saveCurrentProjectToLibrary(documentTitle, canvasView, autosaveCoordinator)
    }

    suspend fun loadProjectFromLibrary(
        projectId: String,
        canvasView: MiltonCanvasView
    ): DocumentMetadata? {
        return projectCatalog.loadProjectFromLibrary(projectId, canvasView, autosaveCoordinator)
    }

    suspend fun deleteProjectFromLibrary(projectId: String): Boolean {
        return projectCatalog.deleteProjectFromLibrary(projectId)
    }

    suspend fun createNewBlankProject(canvasView: MiltonCanvasView): DocumentMetadata {
        return projectCatalog.createNewBlankProject(canvasView, autosaveCoordinator)
    }

    fun hasCanvasContent(canvasView: MiltonCanvasView): Boolean {
        return projectCatalog.hasCanvasContent(canvasView)
    }

    fun formatFileSize(bytes: Long): String {
        return projectCatalog.formatFileSize(bytes)
    }
}
