package app.goodboy13.milton

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import app.goodboy13.milton.core.brush.BrushType
import app.goodboy13.milton.core.storage.CanvasExportEngine
import app.goodboy13.milton.core.storage.DocumentStorageManager
import app.goodboy13.milton.ui.CanvasViewModel
import app.goodboy13.milton.ui.MiltonCanvasView
import app.goodboy13.milton.ui.MiltonTabletUi
import app.goodboy13.milton.ui.state.CanvasUiActions
import app.goodboy13.milton.ui.state.DocumentActions
import app.goodboy13.milton.ui.state.LayerActions
import app.goodboy13.milton.ui.state.ToolActions
import app.goodboy13.milton.ui.state.ViewportActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Host Activity for Milton Canvas.
 * Follows MVI/UDF pattern: delegates business logic, storage coordination, and state management
 * to [CanvasViewModel] while managing system window insets, Android view hierarchies, and intents.
 */
class MainActivity : ComponentActivity() {

    private lateinit var canvasView: MiltonCanvasView
    private lateinit var storageManager: DocumentStorageManager
    private lateinit var viewModel: CanvasViewModel
    private lateinit var shortcutHandler: app.goodboy13.milton.input.KeyboardShortcutHandler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storageManager = DocumentStorageManager(applicationContext)
        viewModel = ViewModelProvider(this)[CanvasViewModel::class.java]

        // Clear window background so SurfaceView is directly visible
        window.setBackgroundDrawable(null)

        // Immersive sticky fullscreen for full drawing surface
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(WindowInsetsCompat.Type.systemBars())

        canvasView = MiltonCanvasView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            loadToolPreferences(applicationContext)
            isFocusable = true
            isFocusableInTouchMode = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                isAutoHandwritingEnabled = false
            }
        }

        shortcutHandler = app.goodboy13.milton.input.KeyboardShortcutHandler(object : app.goodboy13.milton.input.KeyboardShortcutActions {
            override fun onModifierChanged(
                isSpaceHeld: Boolean,
                isCtrlHeld: Boolean,
                isAltHeld: Boolean,
                isRHeld: Boolean,
                isShiftHeld: Boolean
            ) {
                canvasView.setModifierState(isSpaceHeld, isCtrlHeld, isAltHeld, isRHeld, isShiftHeld)
                viewModel.setModifierHeld(isSpaceHeld || isCtrlHeld || isAltHeld || isRHeld)
            }

            override fun onToggleCanvasFlip() {
                viewModel.toggleCanvasFlip(canvasView)
            }

            override fun onResetView() {
                viewModel.resetCanvas(canvasView)
            }

            override fun onToggleZoom50or100() {
                canvasView.toggleZoom50or100()
            }

            override fun onResetRotation() {
                canvasView.resetRotation()
            }

            override fun onSelectPaintbrush() {
                viewModel.selectPaintbrush(canvasView)
            }

            override fun onCyclePenPencil() {
                viewModel.cyclePenPencil(canvasView)
            }

            override fun onCycleEraser() {
                viewModel.cycleEraser(canvasView)
            }

            override fun onToggleEyedropper() {
                viewModel.toggleEyedropper(canvasView)
            }

            override fun onSelectLasso() {
                viewModel.selectLasso(canvasView)
            }

            override fun onSelectLiquify() {
                viewModel.selectLiquify(canvasView)
            }

            override fun onStepBrushSize(increase: Boolean) {
                canvasView.stepBrushSize(increase)
            }

            override fun onSetBrushOpacity(opacity: Float) {
                viewModel.setBrushOpacity(opacity, canvasView)
            }

            override fun onSwapRecentColor() {
                viewModel.swapRecentColor(canvasView)
            }

            override fun onResetDefaultColor() {
                viewModel.resetDefaultColor(canvasView)
            }

            override fun onUndo() {
                canvasView.undo()
            }

            override fun onRedo() {
                canvasView.redo()
            }

            override fun onSaveProject() {
                viewModel.saveCurrentProjectToLibrary(canvasView, storageManager) {
                    Toast.makeText(this@MainActivity, "Saved project to library", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onAddNewLayer() {
                viewModel.addNewLayer(canvasView)
            }

            override fun onClearActiveLayer() {
                viewModel.clearActiveLayer(canvasView)
            }

            override fun onSelectLayerAbove() {
                viewModel.selectLayerAbove(canvasView)
            }

            override fun onSelectLayerBelow() {
                viewModel.selectLayerBelow(canvasView)
            }

            override fun onToggleZenMode() {
                viewModel.setZenMode(!viewModel.uiState.value.viewport.isZenMode)
            }
        })

        setContent {
            MaterialTheme {
                val uiState by viewModel.uiState.collectAsState()
                val showNewProjectDialog by viewModel.showNewProjectDialog.collectAsState()
                val showLoadProjectConfirmationDialog by viewModel.showLoadProjectConfirmationDialog.collectAsState()
                val pendingLoadProjectTitle by viewModel.pendingLoadProjectTitle.collectAsState()
                val layerManager = canvasView.layerManager

                // Import .milton project archive launcher
                val importMiltonLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri ->
                    if (uri != null) {
                        try {
                            val stream = contentResolver.openInputStream(uri)
                            if (stream != null) {
                                viewModel.importFromMiltonZip(
                                    canvasView = canvasView,
                                    storageManager = storageManager,
                                    inputStream = stream,
                                    onSuccess = { title ->
                                        Toast.makeText(this@MainActivity, "Imported '$title'", Toast.LENGTH_SHORT).show()
                                    },
                                    onError = { error ->
                                        Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                                    }
                                )
                            }
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "Error importing file: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }

                // Bind canvas engine and events to ViewModel
                LaunchedEffect(canvasView) {
                    viewModel.bindCanvasEvents(canvasView, storageManager)
                }

                val actions = CanvasUiActions(
                    tool = ToolActions(
                        onBrushTypeChange = { viewModel.setBrushType(it, canvasView) },
                        onBrushSizeChange = { viewModel.setBrushSize(it, canvasView) },
                        onBrushOpacityChange = { viewModel.setBrushOpacity(it, canvasView) },
                        onBrushStabilizerChange = { viewModel.setBrushStabilizer(it, canvasView) },
                        onBrushColorChange = { viewModel.setBrushColor(it, canvasView) },
                        onSizeBezierConfigChange = { viewModel.setSizeBezierConfig(it, canvasView) },
                        onOpacityBezierConfigChange = { viewModel.setOpacityBezierConfig(it, canvasView) },
                        onToggleEyedropper = { viewModel.toggleEyedropper(canvasView) },
                        onLiquifyModeChange = { viewModel.setLiquifyMode(it, canvasView) }
                    ),
                    viewport = ViewportActions(
                        onUndo = { canvasView.undo() },
                        onRedo = { canvasView.redo() },
                        onToggleFlipCanvas = { viewModel.toggleCanvasFlip(canvasView) },
                        onToggleZoomLock = { viewModel.toggleZoomLock(canvasView) },
                        onToggleRotationLock = { viewModel.toggleRotationLock(canvasView) },
                        onResetCanvas = { viewModel.resetCanvas(canvasView) },
                        onToggleZenMode = { viewModel.setZenMode(it) }
                    ),
                    document = DocumentActions(
                        onTitleChange = { viewModel.setDocumentTitle(it, canvasView, storageManager) },
                        onCanvasBackgroundColorChange = { viewModel.setCanvasBackgroundColor(it, canvasView, storageManager) },
                        onNewProjectClick = {
                            viewModel.handleNewProjectClick(canvasView, storageManager) {
                                Toast.makeText(this@MainActivity, "Created new project", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onManualSave = {
                            viewModel.saveCurrentProjectToLibrary(canvasView, storageManager) {
                                Toast.makeText(this@MainActivity, "Saved project to library", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onLoadProject = { projId ->
                            val target = storageManager.listSavedProjects().find { it.id == projId }
                            val title = target?.title ?: "Project"
                            viewModel.handleLoadProjectClick(canvasView, storageManager, projId, title) { loadedTitle ->
                                Toast.makeText(this@MainActivity, "Loaded '$loadedTitle'", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onDeleteSavedProject = { projId ->
                            viewModel.deleteProjectFromLibrary(storageManager, projId) {
                                Toast.makeText(this@MainActivity, "Project deleted", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onMenuOpened = { viewModel.refreshProjectMetrics(storageManager) },
                        onExportMilton = {
                            viewModel.exportMiltonProject(canvasView, storageManager, this@MainActivity) { file, title ->
                                val shareIntent = CanvasExportEngine.createShareIntent(
                                    this@MainActivity,
                                    file,
                                    "application/octet-stream",
                                    title
                                )
                                startActivity(Intent.createChooser(shareIntent, "Export .milton Project"))
                                Toast.makeText(this@MainActivity, "Exported ${file.name} to Downloads", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onExportPng = {
                            viewModel.exportArtworkImage(canvasView, storageManager, this@MainActivity, true) { cacheFile, mime, title ->
                                val shareIntent = CanvasExportEngine.createShareIntent(this@MainActivity, cacheFile, mime, title)
                                startActivity(Intent.createChooser(shareIntent, "Export Artwork"))
                                Toast.makeText(this@MainActivity, "Saved to Pictures/Milton & ready to share", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onExportJpg = {
                            viewModel.exportArtworkImage(canvasView, storageManager, this@MainActivity, false) { cacheFile, mime, title ->
                                val shareIntent = CanvasExportEngine.createShareIntent(this@MainActivity, cacheFile, mime, title)
                                startActivity(Intent.createChooser(shareIntent, "Export Artwork"))
                                Toast.makeText(this@MainActivity, "Saved to Pictures/Milton & ready to share", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onImportMilton = { importMiltonLauncher.launch(arrayOf("*/*")) },
                        onTextInputActiveChanged = { viewModel.setTextInputActive(it) }
                    ),
                    layers = LayerActions(
                        onSelectLayer = { id ->
                            layerManager.selectLayer(id)
                            viewModel.updateLayers(layerManager.layers.toList(), id)
                        },
                        onAddLayer = {
                            canvasView.addLayer()
                            viewModel.updateLayers(layerManager.layers.toList(), layerManager.activeLayerId)
                        },
                        onDeleteLayer = { id ->
                            canvasView.deleteLayer(id)
                            viewModel.updateLayers(layerManager.layers.toList(), layerManager.activeLayerId)
                        },
                        onClearLayer = { id ->
                            canvasView.clearLayer(id)
                            viewModel.markUnsavedChanges()
                        },
                        onToggleLayerVisibility = { id, isVis ->
                            layerManager.setLayerVisibility(id, isVis)
                            viewModel.updateLayers(layerManager.layers.toList(), layerManager.activeLayerId)
                        },
                        onLayerOpacityChange = { id, op ->
                            layerManager.setLayerOpacity(id, op)
                            viewModel.updateLayers(layerManager.layers.toList(), layerManager.activeLayerId)
                        },
                        onMoveLayerUp = { id ->
                            layerManager.moveLayerUp(id)
                            viewModel.updateLayers(layerManager.layers.toList(), layerManager.activeLayerId)
                        },
                        onMoveLayerDown = { id ->
                            layerManager.moveLayerDown(id)
                            viewModel.updateLayers(layerManager.layers.toList(), layerManager.activeLayerId)
                        },
                        onReorderLayer = { from, to ->
                            layerManager.moveLayer(from, to)
                            viewModel.updateLayers(layerManager.layers.toList(), layerManager.activeLayerId)
                        },
                        onReorderLayers = { newOrderIds ->
                            layerManager.reorderLayersById(newOrderIds)
                            viewModel.updateLayers(layerManager.layers.toList(), layerManager.activeLayerId)
                        },
                        onToggleTransformLayer = { id ->
                            viewModel.toggleTransformLayer(id, canvasView)
                        }
                    ),
                    transform = app.goodboy13.milton.ui.state.TransformActions(
                        onUpdateTransform = { dx, dy, sx, sy, rot, flipH, flipV ->
                            viewModel.updateTransform(dx, dy, sx, sy, rot, flipH, flipV, canvasView)
                        },
                        onCommitTransform = { viewModel.commitTransform(canvasView) },
                        onCancelTransform = { viewModel.cancelTransform(canvasView) },
                        onResetTransform = { viewModel.resetTransform(canvasView) }
                    )
                )

                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { canvasView },
                        modifier = Modifier.fillMaxSize()
                    )

                    MiltonTabletUi(
                        state = uiState,
                        actions = actions,
                        viewport = canvasView.renderer.viewport
                    )

                    if (showNewProjectDialog) {
                        AlertDialog(
                            onDismissRequest = { viewModel.setShowNewProjectDialog(false) },
                            title = {
                                Text("Start New Project?", color = Color.White, fontWeight = FontWeight.Bold)
                            },
                            text = {
                                Text(
                                    "Do you want to save the current artwork before starting a new project, or discard changes?",
                                    color = Color(0xDDFFFFFF)
                                )
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        viewModel.setShowNewProjectDialog(false)
                                        viewModel.saveCurrentProjectToLibrary(canvasView, storageManager) {
                                            viewModel.createNewBlankProject(canvasView, storageManager) {
                                                Toast.makeText(this@MainActivity, "Saved and created new project", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                ) {
                                    Text("Save & New", color = Color(0xFF64B5F6), fontWeight = FontWeight.Bold)
                                }
                            },
                            dismissButton = {
                                Row {
                                    TextButton(
                                        onClick = { viewModel.setShowNewProjectDialog(false) }
                                    ) {
                                        Text("Cancel", color = Color(0x99FFFFFF))
                                    }
                                    TextButton(
                                        onClick = {
                                            viewModel.setShowNewProjectDialog(false)
                                            viewModel.createNewBlankProject(canvasView, storageManager) {
                                                Toast.makeText(this@MainActivity, "Created new project", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    ) {
                                        Text("Discard & New", color = Color(0xFFFF5252))
                                    }
                                }
                            },
                            containerColor = Color(0xFF22262E),
                            shape = RoundedCornerShape(16.dp)
                        )
                    }

                    if (showLoadProjectConfirmationDialog) {
                        val pendingTitle = pendingLoadProjectTitle ?: "Project"
                        AlertDialog(
                            onDismissRequest = { viewModel.setShowLoadProjectConfirmationDialog(false) },
                            title = {
                                Text("Open Project?", color = Color.White, fontWeight = FontWeight.Bold)
                            },
                            text = {
                                Text(
                                    "Do you want to save the current artwork before opening '$pendingTitle', or discard changes?",
                                    color = Color(0xDDFFFFFF)
                                )
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        val targetId = viewModel.pendingLoadProjectId.value
                                        viewModel.setShowLoadProjectConfirmationDialog(false)
                                        if (targetId != null) {
                                            viewModel.saveCurrentProjectToLibrary(canvasView, storageManager) {
                                                viewModel.loadProjectFromLibrary(canvasView, storageManager, targetId) { loadedTitle ->
                                                    Toast.makeText(this@MainActivity, "Saved and loaded '$loadedTitle'", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    }
                                ) {
                                    Text("Save & Open", color = Color(0xFF64B5F6), fontWeight = FontWeight.Bold)
                                }
                            },
                            dismissButton = {
                                Row {
                                    TextButton(
                                        onClick = { viewModel.setShowLoadProjectConfirmationDialog(false) }
                                    ) {
                                        Text("Cancel", color = Color(0x99FFFFFF))
                                    }
                                    TextButton(
                                        onClick = {
                                            val targetId = viewModel.pendingLoadProjectId.value
                                            viewModel.setShowLoadProjectConfirmationDialog(false)
                                            if (targetId != null) {
                                                viewModel.loadProjectFromLibrary(canvasView, storageManager, targetId) { loadedTitle ->
                                                    Toast.makeText(this@MainActivity, "Loaded '$loadedTitle'", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    ) {
                                        Text("Discard & Open", color = Color(0xFFFF5252))
                                    }
                                }
                            },
                            containerColor = Color(0xFF22262E),
                            shape = RoundedCornerShape(16.dp)
                        )
                    }
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (::viewModel.isInitialized && viewModel.isTextInputActive.value) {
            return super.dispatchKeyEvent(event)
        }

        if (::shortcutHandler.isInitialized) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                if (shortcutHandler.handleKeyDown(event.keyCode, event)) {
                    return true
                }
            } else if (event.action == android.view.KeyEvent.ACTION_UP) {
                if (shortcutHandler.handleKeyUp(event.keyCode, event)) {
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && ::shortcutHandler.isInitialized) {
            shortcutHandler.resetModifiers()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::shortcutHandler.isInitialized) {
            shortcutHandler.resetModifiers()
        }
        if (::canvasView.isInitialized) {
            canvasView.saveToolPreferences(applicationContext)
        }
        if (::canvasView.isInitialized && ::storageManager.isInitialized && ::viewModel.isInitialized) {
            runBlocking(Dispatchers.IO) {
                storageManager.flushAutosaveNow(viewModel.uiState.value.document.documentTitle, canvasView)
            }
        }
    }
}
