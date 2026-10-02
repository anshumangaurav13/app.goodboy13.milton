package com.antigrav.milton

import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.antigrav.milton.core.brush.BrushType
import com.antigrav.milton.core.storage.CanvasExportEngine
import com.antigrav.milton.core.storage.DocumentStorageManager
import com.antigrav.milton.ui.MiltonCanvasView
import com.antigrav.milton.ui.MiltonTabletUi
import com.antigrav.milton.ui.QUICK_PALETTE_COLORS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var canvasView: MiltonCanvasView
    private lateinit var storageManager: DocumentStorageManager
    private var currentDocumentTitle: String = "Untitled Artwork"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storageManager = DocumentStorageManager(applicationContext)

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
            brushType = BrushType.PENCIL
            brushColorRgb = 0xFF333333.toInt()
            isFocusable = true
            isFocusableInTouchMode = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                isAutoHandwritingEnabled = false
            }
        }

        setContent {
            MaterialTheme {
                val layerManager = canvasView.layerManager
                val structuralRevision = layerManager.structuralRevision
                val layersList = remember(structuralRevision) { layerManager.layers.toList() }
                val activeLayerId = layerManager.activeLayerId

                var brushType by remember { mutableStateOf(BrushType.PENCIL) }
                var brushSize by remember { mutableFloatStateOf(canvasView.brushSize) }
                var brushOpacity by remember { mutableFloatStateOf(canvasView.brushOpacity) }
                var brushStabilizer by remember { mutableFloatStateOf(canvasView.brushStabilizer) }
                var brushColor by remember { mutableIntStateOf(0xFF333333.toInt()) } // Graphite default
                var canUndo by remember { mutableStateOf(false) }
                var canRedo by remember { mutableStateOf(false) }
                var zoomLevel by remember { mutableFloatStateOf(0.5f) }
                var rotationDegrees by remember { mutableFloatStateOf(0f) }
                var isCanvasFlipped by remember { mutableStateOf(canvasView.isCanvasFlipped) }
                var isZoomLocked by remember { mutableStateOf(false) }
                var isRotationLocked by remember { mutableStateOf(false) }
                var isZenMode by remember { mutableStateOf(false) }
                var sizeBezierConfig by remember { mutableStateOf(canvasView.sizeBezierConfig) }
                var opacityBezierConfig by remember { mutableStateOf(canvasView.opacityBezierConfig) }
                var isEyedropperActive by remember { mutableStateOf(false) }
                var canvasBackgroundColor by remember { mutableIntStateOf(canvasView.backgroundColorRgb) }
                var eyedropperReticleState by remember { mutableStateOf(com.antigrav.milton.ui.EyedropperReticleState()) }

                var recentColors by remember {
                    mutableStateOf(
                        listOf(
                            0xFF333333.toInt(), // Graphite Default
                            0xFF111111.toInt(), // Ink Black
                            0xFFFFFFFF.toInt(), // Paper White
                            0xFFD32F2F.toInt(), // Crimson Red
                            0xFF1976D2.toInt(), // Cobalt Blue
                            0xFF388E3C.toInt(), // Forest Green
                            0xFFF57C00.toInt(), // Amber Orange
                            0xFF78909C.toInt()  // Slate Gray
                        )
                    )
                }

                fun addRecentColor(color: Int) {
                    recentColors = (listOf(color) + recentColors.filter { it != color }).take(12)
                }

                var documentTitle by remember { mutableStateOf(currentDocumentTitle) }
                val scope = rememberCoroutineScope()

                // Activity Result Launcher for importing .milton archives
                val importMiltonLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri ->
                    if (uri != null) {
                        scope.launch(Dispatchers.IO) {
                            try {
                                contentResolver.openInputStream(uri)?.use { stream ->
                                    val imported = storageManager.importFromMiltonZip(stream, canvasView)
                                    withContext(Dispatchers.Main) {
                                        if (imported != null) {
                                            documentTitle = imported.title
                                            currentDocumentTitle = imported.title
                                            canvasBackgroundColor = imported.backgroundColorRgb
                                            zoomLevel = imported.viewportZoom
                                            rotationDegrees = imported.viewportRotation
                                            isCanvasFlipped = imported.isFlippedHorizontally
                                            Toast.makeText(
                                                this@MainActivity,
                                                "Imported '${imported.title}'",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        } else {
                                            Toast.makeText(
                                                this@MainActivity,
                                                "Failed to import .milton project",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Error importing file: ${e.message}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    }
                }

                // Restore autosaved artwork from previous session on initial launch
                LaunchedEffect(Unit) {
                    if (storageManager.hasAutosaveSession()) {
                        val restored = storageManager.restoreAutosave(canvasView)
                        if (restored != null) {
                            documentTitle = restored.title
                            currentDocumentTitle = restored.title
                            canvasBackgroundColor = restored.backgroundColorRgb
                            zoomLevel = restored.viewportZoom
                            rotationDegrees = restored.viewportRotation
                            isCanvasFlipped = restored.isFlippedHorizontally
                        }
                    }
                }

                fun exportImage(isPng: Boolean) {
                    scope.launch(Dispatchers.IO) {
                        storageManager.flushAutosaveNow(documentTitle, canvasView)
                        val bitmap = CanvasExportEngine.renderVisibleAreaToBitmap(
                            viewport = canvasView.renderer.viewport,
                            layerManager = canvasView.layerManager,
                            backgroundColorRgb = canvasView.backgroundColorRgb,
                            storageManager = storageManager
                        )
                        val ext = if (isPng) "png" else "jpg"
                        val format = if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                        val quality = if (isPng) 100 else 94
                        val safeTitle = documentTitle.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifEmpty { "artwork" }
                        val filename = "${safeTitle}_${System.currentTimeMillis()}.$ext"

                        val cacheFile = CanvasExportEngine.saveToExportCache(
                            this@MainActivity,
                            bitmap,
                            filename,
                            format,
                            quality
                        )
                        CanvasExportEngine.saveToPublicPictures(
                            this@MainActivity,
                            bitmap,
                            filename,
                            isPng
                        )
                        withContext(Dispatchers.Main) {
                            val mimeType = if (isPng) "image/png" else "image/jpeg"
                            val shareIntent = CanvasExportEngine.createShareIntent(
                                this@MainActivity,
                                cacheFile,
                                mimeType,
                                documentTitle
                            )
                            startActivity(Intent.createChooser(shareIntent, "Export Artwork"))
                            Toast.makeText(
                                this@MainActivity,
                                "Saved to Pictures/Milton & ready to share",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }

                DisposableEffect(canvasView) {
                    val handler = Handler(Looper.getMainLooper())
                    canvasView.renderer.undoManager.onStateChangedListener = {
                        handler.post {
                            canUndo = canvasView.renderer.undoManager.canUndo
                            canRedo = canvasView.renderer.undoManager.canRedo
                        }
                    }
                    canvasView.renderer.undoManager.onTilesCommittedListener = { deltas ->
                        storageManager.onTilesCommitted(deltas, documentTitle, canvasView)
                    }
                    canvasView.renderer.onLayerThumbnailUpdated = { layerId, bitmap ->
                        handler.post {
                            layerManager.updateLayerThumbnail(layerId, bitmap)
                        }
                    }
                    layerManager.onLayersChangedListener = {
                        handler.post {
                            canvasView.requestRedraw()
                            storageManager.scheduleAutosave(documentTitle, canvasView)
                        }
                    }
                    canvasView.onViewportChanged = { zoom, rot ->
                        handler.post {
                            zoomLevel = zoom
                            rotationDegrees = rot
                        }
                    }
                    canvasView.onColorPicked = { pickedColor ->
                        handler.post {
                            brushColor = pickedColor
                            canvasView.brushColorRgb = pickedColor
                            isEyedropperActive = false
                            canvasView.isEyedropperMode = false
                        }
                    }
                    canvasView.onEyedropperReticleChanged = { state ->
                        handler.post {
                            eyedropperReticleState = state
                        }
                    }
                    canvasView.onStrokeCompleted = { strokeColor ->
                        handler.post {
                            addRecentColor(strokeColor)
                        }
                    }
                    onDispose {
                        canvasView.renderer.undoManager.onStateChangedListener = null
                        canvasView.renderer.undoManager.onTilesCommittedListener = null
                        canvasView.renderer.onLayerThumbnailUpdated = null
                        layerManager.onLayersChangedListener = null
                        canvasView.onViewportChanged = null
                        canvasView.onColorPicked = null
                        canvasView.onEyedropperReticleChanged = null
                        canvasView.onStrokeCompleted = null
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { canvasView },
                        modifier = Modifier.fillMaxSize()
                    )

                    MiltonTabletUi(
                        brushType = brushType,
                        onBrushTypeChange = { type ->
                            brushType = type
                            canvasView.brushType = type
                            brushSize = canvasView.brushSize
                            brushOpacity = canvasView.brushOpacity
                            brushStabilizer = canvasView.brushStabilizer
                            sizeBezierConfig = canvasView.sizeBezierConfig
                            opacityBezierConfig = canvasView.opacityBezierConfig
                        },
                        brushSize = brushSize,
                        onBrushSizeChange = { size ->
                            brushSize = size
                            canvasView.brushSize = size
                        },
                        brushOpacity = brushOpacity,
                        onBrushOpacityChange = { opacity ->
                            brushOpacity = opacity
                            canvasView.brushOpacity = opacity
                        },
                        brushStabilizer = brushStabilizer,
                        onBrushStabilizerChange = { stab ->
                            brushStabilizer = stab
                            canvasView.brushStabilizer = stab
                        },
                        brushColorRgb = brushColor,
                        onBrushColorChange = { color ->
                            brushColor = color
                            canvasView.brushColorRgb = color
                        },
                        sizeBezierConfig = sizeBezierConfig,
                        onSizeBezierConfigChange = { cfg ->
                            sizeBezierConfig = cfg
                            canvasView.sizeBezierConfig = cfg
                        },
                        opacityBezierConfig = opacityBezierConfig,
                        onOpacityBezierConfigChange = { cfg ->
                            opacityBezierConfig = cfg
                            canvasView.opacityBezierConfig = cfg
                        },
                        canUndo = canUndo,
                        onUndo = { canvasView.undo() },
                        canRedo = canRedo,
                        onRedo = { canvasView.redo() },
                        zoomLevel = zoomLevel,
                        isZoomLocked = isZoomLocked,
                        onToggleZoomLock = {
                            isZoomLocked = !isZoomLocked
                            canvasView.isZoomLocked = isZoomLocked
                        },
                        rotationDegrees = rotationDegrees,
                        isRotationLocked = isRotationLocked,
                        onToggleRotationLock = {
                            isRotationLocked = !isRotationLocked
                            canvasView.isRotationLocked = isRotationLocked
                        },
                        onResetCanvas = { canvasView.resetCanvas() },
                        isZenMode = isZenMode,
                        onToggleZenMode = { isZenMode = it },
                        layers = layersList,
                        activeLayerId = activeLayerId,
                        onSelectLayer = { id ->
                            layerManager.selectLayer(id)
                        },
                        onAddLayer = {
                            layerManager.addLayer()
                        },
                        onDeleteLayer = { id ->
                            layerManager.deleteLayer(id)
                        },
                        onToggleLayerVisibility = { id, isVis ->
                            layerManager.setLayerVisibility(id, isVis)
                        },
                        onLayerOpacityChange = { id, op ->
                            layerManager.setLayerOpacity(id, op)
                        },
                        onMoveLayerUp = { id ->
                            layerManager.moveLayerUp(id)
                        },
                        onMoveLayerDown = { id ->
                            layerManager.moveLayerDown(id)
                        },
                        onReorderLayer = { fromStorage, toStorage ->
                            layerManager.moveLayer(fromStorage, toStorage)
                        },
                        onReorderLayers = { newOrderIds ->
                            layerManager.reorderLayersById(newOrderIds)
                        },
                        isCanvasFlipped = isCanvasFlipped,
                        onToggleFlipCanvas = {
                            isCanvasFlipped = canvasView.toggleCanvasFlip()
                        },
                        recentColors = recentColors,
                        canvasBackgroundColor = canvasBackgroundColor,
                        onCanvasBackgroundColorChange = { color ->
                            canvasBackgroundColor = color
                            canvasView.backgroundColorRgb = color
                            storageManager.scheduleAutosave(documentTitle, canvasView)
                        },
                        isEyedropperActive = isEyedropperActive,
                        onToggleEyedropper = {
                            isEyedropperActive = !isEyedropperActive
                            canvasView.isEyedropperMode = isEyedropperActive
                        },
                        eyedropperReticleState = eyedropperReticleState,
                        documentTitle = documentTitle,
                        onTitleChange = { newTitle ->
                            documentTitle = newTitle
                            currentDocumentTitle = newTitle
                            storageManager.scheduleAutosave(newTitle, canvasView)
                        },
                        onManualSave = {
                            scope.launch(Dispatchers.IO) {
                                storageManager.flushAutosaveNow(documentTitle, canvasView)
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@MainActivity, "Project saved", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onExportMilton = {
                            scope.launch(Dispatchers.IO) {
                                storageManager.flushAutosaveNow(documentTitle, canvasView)
                                val exportDir = File(cacheDir, "exports").apply { mkdirs() }
                                val safeTitle = documentTitle.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifEmpty { "artwork" }
                                val miltonFile = File(exportDir, "${safeTitle}.milton")
                                storageManager.exportToMiltonZip(documentTitle, canvasView, miltonFile)
                                CanvasExportEngine.saveToPublicDownloads(this@MainActivity, miltonFile, "${safeTitle}.milton")
                                withContext(Dispatchers.Main) {
                                    val shareIntent = CanvasExportEngine.createShareIntent(
                                        this@MainActivity,
                                        miltonFile,
                                        "application/octet-stream",
                                        documentTitle
                                    )
                                    startActivity(Intent.createChooser(shareIntent, "Export .milton Project"))
                                    Toast.makeText(this@MainActivity, "Exported ${miltonFile.name} to Downloads", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onExportPng = { exportImage(isPng = true) },
                        onExportJpg = { exportImage(isPng = false) },
                        onImportMilton = { importMiltonLauncher.launch(arrayOf("*/*")) }
                    )
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (::canvasView.isInitialized && ::storageManager.isInitialized) {
            runBlocking(Dispatchers.IO) {
                storageManager.flushAutosaveNow(currentDocumentTitle, canvasView)
            }
        }
    }
}
