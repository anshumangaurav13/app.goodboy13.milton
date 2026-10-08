package app.goodboy13.milton.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.goodboy13.milton.core.brush.BrushType
import app.goodboy13.milton.core.layer.Layer
import app.goodboy13.milton.core.model.BezierControlPoints
import app.goodboy13.milton.core.model.PressureCurveDefaults
import app.goodboy13.milton.core.storage.CanvasExportEngine
import app.goodboy13.milton.core.storage.DocumentMetadata
import app.goodboy13.milton.core.storage.DocumentStorageManager
import app.goodboy13.milton.core.storage.SavedProjectSummary
import app.goodboy13.milton.ui.state.CanvasUiState
import app.goodboy13.milton.ui.state.DocumentUiState
import app.goodboy13.milton.ui.state.LayersUiState
import app.goodboy13.milton.ui.state.ToolUiState
import app.goodboy13.milton.ui.state.ViewportUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Android ViewModel implementing Unidirectional Data Flow (UDF) for the Milton Canvas.
 * Centralizes UI state, background storage coordination, project indexing, and export pipeline.
 */
class CanvasViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(
        CanvasUiState(
            tool = ToolUiState(
                brushType = BrushType.PENCIL,
                brushSize = 35f,
                brushOpacity = 0.75f,
                brushStabilizer = 0.10f,
                brushColorRgb = 0xFF333333.toInt(),
                sizeBezierConfig = PressureCurveDefaults.STANDARD.copy(minPercent = 0.05f),
                opacityBezierConfig = PressureCurveDefaults.SOFT.copy(minPercent = 0.20f),
                recentColors = listOf(
                    0xFF333333.toInt(), // Graphite Default
                    0xFF111111.toInt(), // Ink Black
                    0xFFFFFFFF.toInt(), // Paper White
                    0xFFD32F2F.toInt(), // Crimson Red
                    0xFF1976D2.toInt(), // Cobalt Blue
                    0xFF388E3C.toInt(), // Forest Green
                    0xFFF57C00.toInt(), // Amber Orange
                    0xFF78909C.toInt()  // Slate Gray
                )
            ),
            viewport = ViewportUiState(
                zoomLevel = 0.5f,
                rotationDegrees = 0f,
                isCanvasFlipped = false,
                isZoomLocked = false,
                isRotationLocked = false,
                canUndo = false,
                canRedo = false,
                isZenMode = false
            ),
            document = DocumentUiState(
                documentTitle = "Untitled Artwork",
                canvasBackgroundColor = 0xFFF6F4ED.toInt()
            )
        )
    )
    val uiState: StateFlow<CanvasUiState> = _uiState.asStateFlow()

    private val _hasUnsavedChanges = MutableStateFlow(false)
    val hasUnsavedChanges: StateFlow<Boolean> = _hasUnsavedChanges.asStateFlow()

    private val _showNewProjectDialog = MutableStateFlow(false)
    val showNewProjectDialog: StateFlow<Boolean> = _showNewProjectDialog.asStateFlow()

    private val _showLoadProjectConfirmationDialog = MutableStateFlow(false)
    val showLoadProjectConfirmationDialog: StateFlow<Boolean> = _showLoadProjectConfirmationDialog.asStateFlow()

    private val _pendingLoadProjectId = MutableStateFlow<String?>(null)
    val pendingLoadProjectId: StateFlow<String?> = _pendingLoadProjectId.asStateFlow()

    private val _pendingLoadProjectTitle = MutableStateFlow<String?>("Project")
    val pendingLoadProjectTitle: StateFlow<String?> = _pendingLoadProjectTitle.asStateFlow()

    private val _isTextInputActive = MutableStateFlow(false)
    val isTextInputActive: StateFlow<Boolean> = _isTextInputActive.asStateFlow()

    private var appContext: Context? = null

    fun setTextInputActive(active: Boolean) {
        _isTextInputActive.value = active
    }

    fun markUnsavedChanges() {
        _hasUnsavedChanges.value = true
    }

    fun isCurrentProjectSaved(canvasView: MiltonCanvasView, storageManager: DocumentStorageManager): Boolean {
        if (!storageManager.hasCanvasContent(canvasView)) {
            return true
        }
        return storageManager.activeProjectId != null && !_hasUnsavedChanges.value
    }

    // -------------------------------------------------------------
    // Initial Session Binding
    // -------------------------------------------------------------

    fun bindCanvasEvents(canvasView: MiltonCanvasView, storageManager: DocumentStorageManager) {
        appContext = canvasView.context.applicationContext
        loadToolPreferences(canvasView.context.applicationContext, canvasView)
        initFromCanvasView(canvasView, storageManager)

        canvasView.onBrushSizeChangedInteractively = { newSize ->
            setBrushSize(newSize, canvasView)
        }

        viewModelScope.launch {
            canvasView.renderer.undoRedoState.collect { state ->
                setUndoRedoState(state.canUndo, state.canRedo)
            }
        }

        viewModelScope.launch {
            canvasView.selectionManager.state.collect { selState ->
                _uiState.update { it.copy(selectionState = selState) }
            }
        }

        canvasView.renderer.undoManager.onTilesCommittedListener = { deltas ->
            markUnsavedChanges()
            storageManager.onTilesCommitted(deltas, _uiState.value.document.documentTitle, canvasView)
        }

        canvasView.renderer.onLayerThumbnailUpdated = { layerId, bitmap ->
            viewModelScope.launch(Dispatchers.Main) {
                canvasView.layerManager.updateLayerThumbnail(layerId, bitmap)
                updateLayers(canvasView.layerManager.layers.toList(), canvasView.layerManager.activeLayerId)
            }
        }

        canvasView.layerManager.onLayersChangedListener = {
            viewModelScope.launch(Dispatchers.Main) {
                markUnsavedChanges()
                canvasView.requestRedraw()
                updateLayers(canvasView.layerManager.layers.toList(), canvasView.layerManager.activeLayerId)
                storageManager.scheduleAutosave(_uiState.value.document.documentTitle, canvasView)
            }
        }

        canvasView.onViewportChanged = { zoom, rot ->
            viewModelScope.launch(Dispatchers.Main) {
                onViewportChanged(zoom, rot)
            }
        }

        canvasView.onColorPicked = { pickedColor ->
            viewModelScope.launch(Dispatchers.Main) {
                onColorPicked(pickedColor, canvasView)
            }
        }

        canvasView.onEyedropperReticleChanged = { state ->
            viewModelScope.launch(Dispatchers.Main) {
                onEyedropperReticleChanged(state)
            }
        }

        canvasView.onLiquifyReticleChanged = { state ->
            viewModelScope.launch(Dispatchers.Main) {
                updateLiquifyReticle(state)
            }
        }

        canvasView.onStrokeCompleted = { strokeColor ->
            viewModelScope.launch(Dispatchers.Main) {
                markUnsavedChanges()
                onStrokeCompleted(strokeColor, storageManager)
            }
        }
    }

    fun loadToolPreferences(context: Context, canvasView: MiltonCanvasView) {
        canvasView.loadToolPreferences(context)
        val activeType = canvasView.brushType
        val color = app.goodboy13.milton.core.preferences.ToolPreferences.loadBrushColor(context, canvasView.brushColorRgb)
        val recents = app.goodboy13.milton.core.preferences.ToolPreferences.loadRecentColors(context)
        _uiState.update { state ->
            state.copy(
                tool = state.tool.copy(
                    brushType = activeType,
                    brushSize = canvasView.brushSize,
                    brushOpacity = canvasView.brushOpacity,
                    brushStabilizer = canvasView.brushStabilizer,
                    brushColorRgb = color,
                    sizeBezierConfig = canvasView.sizeBezierConfig,
                    opacityBezierConfig = canvasView.opacityBezierConfig,
                    recentColors = recents
                )
            )
        }
    }

    fun initFromCanvasView(canvasView: MiltonCanvasView, storageManager: DocumentStorageManager) {
        _uiState.update { state ->
            state.copy(
                tool = state.tool.copy(
                    brushSize = canvasView.brushSize,
                    brushOpacity = canvasView.brushOpacity,
                    brushStabilizer = canvasView.brushStabilizer,
                    sizeBezierConfig = canvasView.sizeBezierConfig,
                    opacityBezierConfig = canvasView.opacityBezierConfig,
                    liquifyMode = canvasView.liquifyMode
                ),
                viewport = state.viewport.copy(
                    isCanvasFlipped = canvasView.isCanvasFlipped
                ),
                document = state.document.copy(
                    canvasBackgroundColor = canvasView.backgroundColorRgb
                ),
                layers = LayersUiState(
                    layers = canvasView.layerManager.layers.toList(),
                    activeLayerId = canvasView.layerManager.activeLayerId
                )
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            if (storageManager.hasAutosaveSession()) {
                val restored = storageManager.restoreAutosave(canvasView)
                if (restored != null) {
                    _hasUnsavedChanges.value = restored.hasUnsavedChanges
                    withContext(Dispatchers.Main) {
                        applyDocumentMetadata(restored, canvasView)
                    }
                }
            } else {
                _hasUnsavedChanges.value = false
            }
            refreshMetricsInternal(storageManager)
        }
    }

    private fun applyDocumentMetadata(metadata: DocumentMetadata, canvasView: MiltonCanvasView? = null) {
        _uiState.update { state ->
            val layersState = if (canvasView != null) {
                LayersUiState(
                    layers = canvasView.layerManager.layers.toList(),
                    activeLayerId = canvasView.layerManager.activeLayerId
                )
            } else {
                state.layers
            }
            state.copy(
                document = state.document.copy(
                    documentTitle = metadata.title,
                    canvasBackgroundColor = metadata.backgroundColorRgb
                ),
                viewport = state.viewport.copy(
                    zoomLevel = metadata.viewportZoom,
                    rotationDegrees = metadata.viewportRotation,
                    isCanvasFlipped = metadata.isFlippedHorizontally,
                    canUndo = false,
                    canRedo = false
                ),
                layers = layersState
            )
        }
    }

    // -------------------------------------------------------------
    // Brush & Tool Operations
    // -------------------------------------------------------------

    fun setBrushType(type: BrushType, canvasView: MiltonCanvasView) {
        if (canvasView.brushType == BrushType.LASSO && type != BrushType.LASSO) {
            canvasView.selectionManager.cancelTransform(canvasView)
        }
        canvasView.brushType = type
        if (type == BrushType.LASSO || type == BrushType.LIQUIFY) {
            val defaultTargets = setOf(_uiState.value.layers.activeLayerId)
            _uiState.update { it.copy(selectedTransformLayerIds = defaultTargets) }
            canvasView.selectedTransformLayerIds = defaultTargets
        }
        appContext?.let { ctx ->
            app.goodboy13.milton.core.preferences.ToolPreferences.saveActiveBrushType(ctx, type)
        }
        _uiState.update {
            it.copy(
                tool = it.tool.copy(
                    brushType = type,
                    brushSize = canvasView.brushSize,
                    brushOpacity = canvasView.brushOpacity,
                    brushStabilizer = canvasView.brushStabilizer,
                    sizeBezierConfig = canvasView.sizeBezierConfig,
                    opacityBezierConfig = canvasView.opacityBezierConfig,
                    liquifyMode = canvasView.liquifyMode
                )
            )
        }
    }

    fun selectLasso(canvasView: MiltonCanvasView) {
        setBrushType(BrushType.LASSO, canvasView)
    }

    fun selectLiquify(canvasView: MiltonCanvasView) {
        setBrushType(BrushType.LIQUIFY, canvasView)
    }

    fun setLiquifyMode(mode: app.goodboy13.milton.core.native.MiltonNative.LiquifyMode, canvasView: MiltonCanvasView) {
        canvasView.liquifyMode = mode
        _uiState.update { state ->
            state.copy(tool = state.tool.copy(liquifyMode = mode))
        }
    }

    fun updateLiquifyReticle(reticle: app.goodboy13.milton.ui.LiquifyReticleState) {
        _uiState.update { state ->
            state.copy(tool = state.tool.copy(liquifyReticleState = reticle))
        }
    }

    fun setModifierHeld(isHeld: Boolean) {
        if (_uiState.value.viewport.isModifierHeld != isHeld) {
            _uiState.update { state ->
                state.copy(viewport = state.viewport.copy(isModifierHeld = isHeld))
            }
        }
    }

    fun toggleTransformLayer(layerId: Long, canvasView: MiltonCanvasView) {
        val currentSet = _uiState.value.selectedTransformLayerIds.toMutableSet()
        if (currentSet.contains(layerId)) {
            if (currentSet.size > 1) {
                currentSet.remove(layerId)
            }
        } else {
            currentSet.add(layerId)
        }
        _uiState.update { it.copy(selectedTransformLayerIds = currentSet) }
        canvasView.selectedTransformLayerIds = currentSet
    }

    fun updateTransform(
        dx: Float?, dy: Float?, sx: Float?, sy: Float?, rot: Float?, flipH: Boolean?, flipV: Boolean?,
        canvasView: MiltonCanvasView
    ) {
        canvasView.selectionManager.updateTransform(dx, dy, sx, sy, rot, flipH, flipV)
    }

    fun commitTransform(canvasView: MiltonCanvasView) {
        canvasView.selectionManager.commitTransform(canvasView)
    }

    fun cancelTransform(canvasView: MiltonCanvasView) {
        canvasView.selectionManager.cancelTransform(canvasView)
    }

    fun resetTransform(canvasView: MiltonCanvasView) {
        canvasView.selectionManager.resetSessionTransform()
    }

    fun setBrushSize(size: Float, canvasView: MiltonCanvasView) {
        canvasView.brushSize = size
        appContext?.let { ctx ->
            app.goodboy13.milton.core.preferences.ToolPreferences.saveToolSize(ctx, canvasView.brushType, size)
        }
        _uiState.update { it.copy(tool = it.tool.copy(brushSize = size)) }
    }

    fun setBrushOpacity(opacity: Float, canvasView: MiltonCanvasView) {
        canvasView.brushOpacity = opacity
        appContext?.let { ctx ->
            app.goodboy13.milton.core.preferences.ToolPreferences.saveToolOpacity(ctx, canvasView.brushType, opacity)
        }
        _uiState.update { it.copy(tool = it.tool.copy(brushOpacity = opacity)) }
    }

    fun setBrushStabilizer(stabilizer: Float, canvasView: MiltonCanvasView) {
        canvasView.brushStabilizer = stabilizer
        appContext?.let { ctx ->
            app.goodboy13.milton.core.preferences.ToolPreferences.saveToolStabilizer(ctx, canvasView.brushType, stabilizer)
        }
        _uiState.update { it.copy(tool = it.tool.copy(brushStabilizer = stabilizer)) }
    }

    fun setBrushColor(colorRgb: Int, canvasView: MiltonCanvasView) {
        canvasView.brushColorRgb = colorRgb
        appContext?.let { ctx ->
            app.goodboy13.milton.core.preferences.ToolPreferences.saveBrushColor(ctx, colorRgb)
        }
        _uiState.update { it.copy(tool = it.tool.copy(brushColorRgb = colorRgb)) }
    }

    fun setSizeBezierConfig(config: BezierControlPoints, canvasView: MiltonCanvasView) {
        canvasView.sizeBezierConfig = config
        appContext?.let { ctx ->
            app.goodboy13.milton.core.preferences.ToolPreferences.saveToolSizeBezier(ctx, canvasView.brushType, config)
        }
        _uiState.update { it.copy(tool = it.tool.copy(sizeBezierConfig = config)) }
    }

    fun setOpacityBezierConfig(config: BezierControlPoints, canvasView: MiltonCanvasView) {
        canvasView.opacityBezierConfig = config
        appContext?.let { ctx ->
            app.goodboy13.milton.core.preferences.ToolPreferences.saveToolOpacityBezier(ctx, canvasView.brushType, config)
        }
        _uiState.update { it.copy(tool = it.tool.copy(opacityBezierConfig = config)) }
    }

    fun toggleEyedropper(canvasView: MiltonCanvasView) {
        val newActive = !_uiState.value.tool.isEyedropperActive
        canvasView.isEyedropperMode = newActive
        _uiState.update { it.copy(tool = it.tool.copy(isEyedropperActive = newActive)) }
    }

    fun onColorPicked(colorRgb: Int, canvasView: MiltonCanvasView) {
        canvasView.brushColorRgb = colorRgb
        canvasView.isEyedropperMode = false
        appContext?.let { ctx ->
            app.goodboy13.milton.core.preferences.ToolPreferences.saveBrushColor(ctx, colorRgb)
        }
        _uiState.update {
            it.copy(tool = it.tool.copy(brushColorRgb = colorRgb, isEyedropperActive = false))
        }
    }

    fun onEyedropperReticleChanged(reticle: EyedropperReticleState) {
        _uiState.update { it.copy(tool = it.tool.copy(eyedropperReticleState = reticle)) }
    }

    fun onStrokeCompleted(colorRgb: Int, storageManager: DocumentStorageManager) {
        addRecentColor(colorRgb)
        refreshProjectMetrics(storageManager)
    }

    fun addRecentColor(colorRgb: Int) {
        _uiState.update { state ->
            val updated = (listOf(colorRgb) + state.tool.recentColors.filter { it != colorRgb }).take(12)
            appContext?.let { ctx ->
                app.goodboy13.milton.core.preferences.ToolPreferences.saveRecentColors(ctx, updated)
            }
            state.copy(tool = state.tool.copy(recentColors = updated))
        }
    }

    private var previousBrushBeforeEraser: BrushType = BrushType.PENCIL

    fun cyclePenPencil(canvasView: MiltonCanvasView) {
        val current = _uiState.value.tool.brushType
        val next = if (current == BrushType.PEN) BrushType.PENCIL else BrushType.PEN
        setBrushType(next, canvasView)
    }

    fun cycleEraser(canvasView: MiltonCanvasView) {
        val current = _uiState.value.tool.brushType
        if (current == BrushType.ERASER) {
            setBrushType(previousBrushBeforeEraser, canvasView)
        } else {
            previousBrushBeforeEraser = current
            setBrushType(BrushType.ERASER, canvasView)
        }
    }

    fun selectPaintbrush(canvasView: MiltonCanvasView) {
        setBrushType(BrushType.PAINTBRUSH, canvasView)
    }

    fun swapRecentColor(canvasView: MiltonCanvasView) {
        val recents = _uiState.value.tool.recentColors
        val current = _uiState.value.tool.brushColorRgb
        val target = recents.firstOrNull { it != current }
        if (target != null) {
            setBrushColor(target, canvasView)
        }
    }

    fun resetDefaultColor(canvasView: MiltonCanvasView) {
        setBrushColor(0xFF333333.toInt(), canvasView)
    }

    fun selectLayerAbove(canvasView: MiltonCanvasView) {
        val layers = canvasView.layerManager.layers
        val currentIdx = layers.indexOfFirst { it.id == canvasView.layerManager.activeLayerId }
        if (currentIdx in 0 until layers.size - 1) {
            val nextId = layers[currentIdx + 1].id
            canvasView.layerManager.selectLayer(nextId)
            updateLayers(layers, nextId)
        }
    }

    fun selectLayerBelow(canvasView: MiltonCanvasView) {
        val layers = canvasView.layerManager.layers
        val currentIdx = layers.indexOfFirst { it.id == canvasView.layerManager.activeLayerId }
        if (currentIdx > 0) {
            val prevId = layers[currentIdx - 1].id
            canvasView.layerManager.selectLayer(prevId)
            updateLayers(layers, prevId)
        }
    }

    fun clearActiveLayer(canvasView: MiltonCanvasView) {
        val activeId = canvasView.layerManager.activeLayerId
        canvasView.clearLayer(activeId)
        markUnsavedChanges()
    }

    fun addNewLayer(canvasView: MiltonCanvasView) {
        canvasView.addLayer()
        updateLayers(canvasView.layerManager.layers.toList(), canvasView.layerManager.activeLayerId)
    }

    // -------------------------------------------------------------
    // Viewport & Navigation Operations
    // -------------------------------------------------------------

    fun onViewportChanged(zoom: Float, rotationDegrees: Float) {
        val current = _uiState.value.viewport
        if (current.zoomLevel == zoom && current.rotationDegrees == rotationDegrees) return
        _uiState.update {
            it.copy(viewport = it.viewport.copy(zoomLevel = zoom, rotationDegrees = rotationDegrees))
        }
    }

    fun toggleZoomLock(canvasView: MiltonCanvasView) {
        val newLocked = !_uiState.value.viewport.isZoomLocked
        canvasView.isZoomLocked = newLocked
        _uiState.update { it.copy(viewport = it.viewport.copy(isZoomLocked = newLocked)) }
    }

    fun toggleRotationLock(canvasView: MiltonCanvasView) {
        val newLocked = !_uiState.value.viewport.isRotationLocked
        canvasView.isRotationLocked = newLocked
        _uiState.update { it.copy(viewport = it.viewport.copy(isRotationLocked = newLocked)) }
    }

    fun toggleCanvasFlip(canvasView: MiltonCanvasView) {
        val flipped = canvasView.toggleCanvasFlip()
        _uiState.update { it.copy(viewport = it.viewport.copy(isCanvasFlipped = flipped)) }
    }

    fun setZenMode(isZen: Boolean) {
        _uiState.update { it.copy(viewport = it.viewport.copy(isZenMode = isZen)) }
    }

    fun setUndoRedoState(canUndo: Boolean, canRedo: Boolean) {
        _uiState.update {
            it.copy(viewport = it.viewport.copy(canUndo = canUndo, canRedo = canRedo))
        }
    }

    fun resetCanvas(canvasView: MiltonCanvasView) {
        canvasView.resetCanvas()
    }

    // -------------------------------------------------------------
    // Layers Operations
    // -------------------------------------------------------------

    fun updateLayers(layers: List<Layer>, activeLayerId: Long) {
        _uiState.update {
            it.copy(layers = LayersUiState(layers = layers, activeLayerId = activeLayerId))
        }
    }

    // -------------------------------------------------------------
    // Document & Project Lifecycle Operations
    // -------------------------------------------------------------

    fun setDocumentTitle(title: String, canvasView: MiltonCanvasView, storageManager: DocumentStorageManager) {
        markUnsavedChanges()
        _uiState.update { it.copy(document = it.document.copy(documentTitle = title)) }
        storageManager.scheduleAutosave(title, canvasView)
    }

    fun setCanvasBackgroundColor(colorRgb: Int, canvasView: MiltonCanvasView, storageManager: DocumentStorageManager) {
        markUnsavedChanges()
        canvasView.backgroundColorRgb = colorRgb
        _uiState.update { it.copy(document = it.document.copy(canvasBackgroundColor = colorRgb)) }
        storageManager.scheduleAutosave(_uiState.value.document.documentTitle, canvasView)
    }

    fun setShowNewProjectDialog(show: Boolean) {
        _showNewProjectDialog.value = show
    }

    fun handleNewProjectClick(canvasView: MiltonCanvasView, storageManager: DocumentStorageManager, onCreatedDirectly: () -> Unit) {
        if (isCurrentProjectSaved(canvasView, storageManager)) {
            createNewBlankProject(canvasView, storageManager, onCreatedDirectly)
        } else {
            _showNewProjectDialog.value = true
        }
    }

    fun setShowLoadProjectConfirmationDialog(show: Boolean) {
        _showLoadProjectConfirmationDialog.value = show
        if (!show) {
            _pendingLoadProjectId.value = null
            _pendingLoadProjectTitle.value = "Project"
        }
    }

    fun handleLoadProjectClick(
        canvasView: MiltonCanvasView,
        storageManager: DocumentStorageManager,
        projectId: String,
        projectTitle: String? = null,
        onLoadedDirectly: (String) -> Unit
    ) {
        if (isCurrentProjectSaved(canvasView, storageManager)) {
            loadProjectFromLibrary(canvasView, storageManager, projectId, onLoadedDirectly)
        } else {
            _pendingLoadProjectId.value = projectId
            _pendingLoadProjectTitle.value = projectTitle ?: "Project"
            _showLoadProjectConfirmationDialog.value = true
        }
    }

    fun createNewBlankProject(
        canvasView: MiltonCanvasView,
        storageManager: DocumentStorageManager,
        onCreated: () -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val newMeta = storageManager.createNewBlankProject(canvasView)
            _hasUnsavedChanges.value = false
            refreshMetricsInternal(storageManager)
            withContext(Dispatchers.Main) {
                applyDocumentMetadata(newMeta, canvasView)
                onCreated()
            }
        }
    }

    fun saveCurrentProjectToLibrary(
        canvasView: MiltonCanvasView,
        storageManager: DocumentStorageManager,
        onSaved: () -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val title = _uiState.value.document.documentTitle
            storageManager.saveCurrentProjectToLibrary(title, canvasView)
            _hasUnsavedChanges.value = false
            refreshMetricsInternal(storageManager)
            withContext(Dispatchers.Main) {
                onSaved()
            }
        }
    }

    fun loadProjectFromLibrary(
        canvasView: MiltonCanvasView,
        storageManager: DocumentStorageManager,
        projectId: String,
        onLoaded: (String) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = storageManager.loadProjectFromLibrary(projectId, canvasView)
            _hasUnsavedChanges.value = false
            refreshMetricsInternal(storageManager)
            withContext(Dispatchers.Main) {
                if (loaded != null) {
                    applyDocumentMetadata(loaded, canvasView)
                    onLoaded(loaded.title)
                }
            }
        }
    }

    fun deleteProjectFromLibrary(
        storageManager: DocumentStorageManager,
        projectId: String,
        onDeleted: () -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            storageManager.deleteProjectFromLibrary(projectId)
            refreshMetricsInternal(storageManager)
            withContext(Dispatchers.Main) {
                onDeleted()
            }
        }
    }

    fun importFromMiltonZip(
        canvasView: MiltonCanvasView,
        storageManager: DocumentStorageManager,
        inputStream: InputStream,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                inputStream.use { stream ->
                    val imported = storageManager.importFromMiltonZip(stream, canvasView)
                    refreshMetricsInternal(storageManager)
                    withContext(Dispatchers.Main) {
                        if (imported != null) {
                            _hasUnsavedChanges.value = false
                            applyDocumentMetadata(imported, canvasView)
                            onSuccess(imported.title)
                        } else {
                            onError("Failed to import .milton project")
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onError(e.message ?: "Unknown error")
                }
            }
        }
    }

    fun exportArtworkImage(
        canvasView: MiltonCanvasView,
        storageManager: DocumentStorageManager,
        context: Context,
        isPng: Boolean,
        onReadyToShare: (cacheFile: File, mimeType: String, docTitle: String) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val title = _uiState.value.document.documentTitle
            storageManager.flushAutosaveNow(title, canvasView)

            val bitmap = canvasView.renderer.renderVisibleAreaGl(
                canvasView = canvasView,
                maxDimension = 8192
            ) ?: CanvasExportEngine.renderVisibleAreaToBitmap(
                viewport = canvasView.renderer.viewport,
                layerManager = canvasView.layerManager,
                backgroundColorRgb = canvasView.backgroundColorRgb,
                storageManager = storageManager
            )

            val ext = if (isPng) "png" else "jpg"
            val format = if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val quality = if (isPng) 100 else 94
            val safeTitle = title.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifEmpty { "artwork" }
            val filename = "${safeTitle}_${System.currentTimeMillis()}.$ext"

            val cacheFile = CanvasExportEngine.saveToExportCache(
                context,
                bitmap,
                filename,
                format,
                quality
            )
            CanvasExportEngine.saveToPublicPictures(
                context,
                bitmap,
                filename,
                isPng
            )

            withContext(Dispatchers.Main) {
                val mimeType = if (isPng) "image/png" else "image/jpeg"
                onReadyToShare(cacheFile, mimeType, title)
            }
        }
    }

    fun exportMiltonProject(
        canvasView: MiltonCanvasView,
        storageManager: DocumentStorageManager,
        context: Context,
        onReadyToShare: (miltonFile: File, docTitle: String) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val title = _uiState.value.document.documentTitle
            storageManager.flushAutosaveNow(title, canvasView)

            val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val safeTitle = title.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifEmpty { "artwork" }
            val miltonFile = File(exportDir, "${safeTitle}.milton")

            storageManager.exportToMiltonZip(title, canvasView, miltonFile)
            CanvasExportEngine.saveToPublicDownloads(context, miltonFile, "${safeTitle}.milton")

            withContext(Dispatchers.Main) {
                onReadyToShare(miltonFile, title)
            }
        }
    }

    fun refreshProjectMetrics(storageManager: DocumentStorageManager) {
        viewModelScope.launch(Dispatchers.IO) {
            refreshMetricsInternal(storageManager)
        }
    }

    private fun refreshMetricsInternal(storageManager: DocumentStorageManager) {
        val list = storageManager.listSavedProjects()
        val totalBytes = storageManager.getTotalSavedProjectsDiskSizeBytes()
        val curBytes = storageManager.getCurrentProjectDiskSizeBytes()
        val ram = storageManager.getAppRamUsageMb()

        _uiState.update { state ->
            state.copy(
                document = state.document.copy(
                    savedProjects = list,
                    totalSavedProjectsSize = storageManager.formatFileSize(totalBytes),
                    currentProjectDiskSize = storageManager.formatFileSize(curBytes),
                    appRamUsageMb = ram
                )
            )
        }
    }
}
