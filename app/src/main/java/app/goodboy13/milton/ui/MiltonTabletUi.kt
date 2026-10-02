package app.goodboy13.milton.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.goodboy13.milton.core.brush.BrushType
import app.goodboy13.milton.core.layer.Layer
import app.goodboy13.milton.core.model.BezierControlPoints
import app.goodboy13.milton.core.storage.SavedProjectSummary
import app.goodboy13.milton.ui.components.LayersFloatingWindow
import app.goodboy13.milton.ui.components.ToolParametersFloatingWindow
import app.goodboy13.milton.ui.components.rememberFloatingWindowState
import app.goodboy13.milton.ui.palette.ColorPaletteFloatingWindow
import app.goodboy13.milton.ui.rail.TabletToolRail
import app.goodboy13.milton.ui.reticle.EyedropperReticleOverlay
import app.goodboy13.milton.ui.state.CanvasUiActions
import app.goodboy13.milton.ui.state.CanvasUiState
import app.goodboy13.milton.ui.state.DocumentActions
import app.goodboy13.milton.ui.state.DocumentUiState
import app.goodboy13.milton.ui.state.LayerActions
import app.goodboy13.milton.ui.state.LayersUiState
import app.goodboy13.milton.ui.state.ToolActions
import app.goodboy13.milton.ui.state.ToolUiState
import app.goodboy13.milton.ui.state.ViewportActions
import app.goodboy13.milton.ui.state.ViewportUiState
import app.goodboy13.milton.ui.topbar.TabletTopBar
import app.goodboy13.milton.ui.topbar.TabletTopLeftBar

val QUICK_PALETTE_COLORS = listOf(
    0xFF111111.toInt(), // Ink Black
    0xFF4A4A4A.toInt(), // Graphite
    0xFF78909C.toInt(), // Slate Gray
    0xFFD32F2F.toInt(), // Crimson Red
    0xFF1976D2.toInt(), // Cobalt Blue
    0xFF388E3C.toInt(), // Forest Green
    0xFFF57C00.toInt(), // Amber Orange
    0xFFFFFFFF.toInt()  // Paper White
)

/**
 * Clean, decomposed Milton Tablet UI using consolidated [CanvasUiState] and [CanvasUiActions].
 */
@Composable
fun MiltonTabletUi(
    state: CanvasUiState,
    actions: CanvasUiActions,
    modifier: Modifier = Modifier
) {
    var showColorPaletteWindow by remember { mutableStateOf(false) }
    var showLayersWindow by remember { mutableStateOf(false) }
    var showToolParametersWindow by remember { mutableStateOf(false) }

    // Preserve window open/closed states across fullscreen (Zen mode) toggles
    var preZenToolParams by remember { mutableStateOf(false) }
    var preZenColorPalette by remember { mutableStateOf(false) }
    var preZenLayers by remember { mutableStateOf(false) }

    val toolParamsWindowState = rememberFloatingWindowState(initialX = 64f, initialY = 100f)
    val colorWindowState = rememberFloatingWindowState(initialX = 64f, initialY = 120f)
    val layersWindowState = rememberFloatingWindowState(initialX = 1400f, initialY = 40f)

    LaunchedEffect(state.viewport.isZenMode) {
        if (state.viewport.isZenMode) {
            preZenToolParams = showToolParametersWindow
            preZenColorPalette = showColorPaletteWindow
            preZenLayers = showLayersWindow
            showToolParametersWindow = false
            showColorPaletteWindow = false
            showLayersWindow = false
        } else {
            showToolParametersWindow = preZenToolParams
            showColorPaletteWindow = preZenColorPalette
            showLayersWindow = preZenLayers
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val containerW = constraints.maxWidth
        val containerH = constraints.maxHeight

        // 0. Top Left Bar (docked to top-left corner, flat against top and left edges, zero gap)
        AnimatedVisibility(
            visible = !state.viewport.isZenMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 0.dp, start = 0.dp)
        ) {
            TabletTopLeftBar(
                documentTitle = state.document.documentTitle,
                onTitleChange = actions.document.onTitleChange,
                onNewProjectClick = actions.document.onNewProjectClick,
                onManualSave = actions.document.onManualSave,
                savedProjects = state.document.savedProjects,
                totalSavedProjectsSize = state.document.totalSavedProjectsSize,
                currentProjectDiskSize = state.document.currentProjectDiskSize,
                appRamUsageMb = state.document.appRamUsageMb,
                onLoadProject = actions.document.onLoadProject,
                onDeleteSavedProject = actions.document.onDeleteSavedProject,
                onMenuOpened = actions.document.onMenuOpened,
                onExportMilton = actions.document.onExportMilton,
                onExportPng = actions.document.onExportPng,
                onExportJpg = actions.document.onExportJpg,
                onImportMilton = actions.document.onImportMilton
            )
        }

        // 1. Left Vertical Tool Rail (docked to left screen edge)
        AnimatedVisibility(
            visible = !state.viewport.isZenMode,
            enter = fadeIn() + slideInHorizontally { -it },
            exit = fadeOut() + slideOutHorizontally { -it },
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 0.dp)
        ) {
            TabletToolRail(
                activeBrush = state.tool.brushType,
                onSelectBrush = actions.tool.onBrushTypeChange,
                onOpenToolParameters = { showToolParametersWindow = !showToolParametersWindow },
                size = state.tool.brushSize,
                onSizeChange = actions.tool.onBrushSizeChange,
                opacity = state.tool.brushOpacity,
                onOpacityChange = actions.tool.onBrushOpacityChange,
                colorRgb = state.tool.brushColorRgb,
                onOpenColorPicker = { showColorPaletteWindow = !showColorPaletteWindow }
            )
        }

        // 2. Floating Draggable Tool Parameters Window (confined within app bounds)
        if (!state.viewport.isZenMode && showToolParametersWindow) {
            ToolParametersFloatingWindow(
                brushType = state.tool.brushType,
                brushSize = state.tool.brushSize,
                onBrushSizeChange = actions.tool.onBrushSizeChange,
                brushOpacity = state.tool.brushOpacity,
                onBrushOpacityChange = actions.tool.onBrushOpacityChange,
                brushStabilizer = state.tool.brushStabilizer,
                onBrushStabilizerChange = actions.tool.onBrushStabilizerChange,
                brushColorRgb = state.tool.brushColorRgb,
                sizeBezierConfig = state.tool.sizeBezierConfig,
                onSizeBezierConfigChange = actions.tool.onSizeBezierConfigChange,
                opacityBezierConfig = state.tool.opacityBezierConfig,
                onOpacityBezierConfigChange = actions.tool.onOpacityBezierConfigChange,
                onClose = { showToolParametersWindow = false },
                containerWidth = containerW,
                containerHeight = containerH,
                state = toolParamsWindowState
            )
        }

        // 3. Top Right Header Bar (docked to top-right corner, flat against top and right edges, zero gap)
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 0.dp, end = 0.dp)
        ) {
            if (!state.viewport.isZenMode) {
                TabletTopBar(
                    canUndo = state.viewport.canUndo,
                    onUndo = actions.viewport.onUndo,
                    canRedo = state.viewport.canRedo,
                    onRedo = actions.viewport.onRedo,
                    isCanvasFlipped = state.viewport.isCanvasFlipped,
                    onToggleFlipCanvas = actions.viewport.onToggleFlipCanvas,
                    zoomLevel = state.viewport.zoomLevel,
                    isZoomLocked = state.viewport.isZoomLocked,
                    onToggleZoomLock = actions.viewport.onToggleZoomLock,
                    rotationDegrees = state.viewport.rotationDegrees,
                    isRotationLocked = state.viewport.isRotationLocked,
                    onToggleRotationLock = actions.viewport.onToggleRotationLock,
                    isLayersOpen = showLayersWindow,
                    onToggleLayers = { showLayersWindow = !showLayersWindow },
                    onResetCanvas = actions.viewport.onResetCanvas,
                    onToggleZenMode = { actions.viewport.onToggleZenMode(true) }
                )
            } else {
                val zenExitShape = RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp, bottomStart = 14.dp, bottomEnd = 0.dp)
                Surface(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(zenExitShape)
                        .clickable { actions.viewport.onToggleZenMode(false) }
                        .drawBehind {
                            val sw = 1.dp.toPx()
                            val r = 14.dp.toPx()
                            val w = size.width
                            val h = size.height
                            val path = androidx.compose.ui.graphics.Path().apply {
                                moveTo(sw / 2f, 0f)
                                lineTo(sw / 2f, h - r)
                                arcTo(
                                    rect = androidx.compose.ui.geometry.Rect(
                                        sw / 2f,
                                        h - 2f * r + sw / 2f,
                                        2f * r - sw / 2f,
                                        h - sw / 2f
                                    ),
                                    startAngleDegrees = 180f,
                                    sweepAngleDegrees = -90f,
                                    forceMoveTo = false
                                )
                                lineTo(w, h - sw / 2f)
                            }
                            drawPath(
                                path = path,
                                color = Color(0x35FFFFFF),
                                style = Stroke(width = sw)
                            )
                        },
                    color = Color(0xD0181A1F),
                    shadowElevation = 8.dp,
                    shape = zenExitShape
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.FullscreenExit,
                            contentDescription = "Exit Fullscreen",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // 4. Floating Draggable Color Palette Window (confined within app bounds)
        if (!state.viewport.isZenMode && showColorPaletteWindow) {
            ColorPaletteFloatingWindow(
                currentColorRgb = state.tool.brushColorRgb,
                onColorSelected = actions.tool.onBrushColorChange,
                recentColors = state.tool.recentColors,
                isEyedropperActive = state.tool.isEyedropperActive,
                onToggleEyedropper = actions.tool.onToggleEyedropper,
                onClose = { showColorPaletteWindow = false },
                containerWidth = containerW,
                containerHeight = containerH,
                state = colorWindowState
            )
        }

        // 5. Floating Draggable Layers Window (confined within app bounds)
        if (!state.viewport.isZenMode && showLayersWindow) {
            LayersFloatingWindow(
                layers = state.layers.layers,
                activeLayerId = state.layers.activeLayerId,
                onSelectLayer = actions.layers.onSelectLayer,
                onAddLayer = actions.layers.onAddLayer,
                onDeleteLayer = actions.layers.onDeleteLayer,
                onClearLayer = actions.layers.onClearLayer,
                onToggleVisibility = actions.layers.onToggleLayerVisibility,
                onOpacityChange = actions.layers.onLayerOpacityChange,
                onMoveLayerUp = actions.layers.onMoveLayerUp,
                onMoveLayerDown = actions.layers.onMoveLayerDown,
                onReorderLayer = actions.layers.onReorderLayer,
                onReorderLayers = actions.layers.onReorderLayers,
                backgroundColorRgb = state.document.canvasBackgroundColor,
                onChangeBackgroundColor = actions.document.onCanvasBackgroundColorChange,
                onClose = { showLayersWindow = false },
                containerWidth = containerW,
                containerHeight = containerH,
                state = layersWindowState
            )
        }

        // 6. Live Eyedropper Reticle Overlay
        if (state.tool.eyedropperReticleState.isVisible) {
            EyedropperReticleOverlay(
                state = state.tool.eyedropperReticleState
            )
        }
    }
}

/**
 * Backwards-compatible overload mapping individual parameters into [CanvasUiState] and [CanvasUiActions].
 */
@Composable
fun MiltonTabletUi(
    brushType: BrushType,
    onBrushTypeChange: (BrushType) -> Unit,
    brushSize: Float,
    onBrushSizeChange: (Float) -> Unit,
    brushOpacity: Float,
    onBrushOpacityChange: (Float) -> Unit,
    brushStabilizer: Float = 0.10f,
    onBrushStabilizerChange: (Float) -> Unit = {},
    brushColorRgb: Int,
    onBrushColorChange: (Int) -> Unit,
    sizeBezierConfig: BezierControlPoints = BezierControlPoints(),
    onSizeBezierConfigChange: (BezierControlPoints) -> Unit = {},
    opacityBezierConfig: BezierControlPoints = BezierControlPoints(),
    onOpacityBezierConfigChange: (BezierControlPoints) -> Unit = {},
    bezierConfig: BezierControlPoints = sizeBezierConfig,
    onBezierConfigChange: (BezierControlPoints) -> Unit = onSizeBezierConfigChange,
    canUndo: Boolean,
    onUndo: () -> Unit,
    canRedo: Boolean,
    onRedo: () -> Unit,
    zoomLevel: Float,
    isZoomLocked: Boolean,
    onToggleZoomLock: () -> Unit,
    rotationDegrees: Float,
    isRotationLocked: Boolean,
    onToggleRotationLock: () -> Unit,
    onResetCanvas: () -> Unit,
    isZenMode: Boolean,
    onToggleZenMode: (Boolean) -> Unit,
    layers: List<Layer>,
    activeLayerId: Long,
    onSelectLayer: (Long) -> Unit,
    onAddLayer: () -> Unit,
    onDeleteLayer: (Long) -> Unit,
    onClearLayer: (Long) -> Unit = {},
    onToggleLayerVisibility: (Long, Boolean) -> Unit,
    onLayerOpacityChange: (Long, Float) -> Unit,
    onMoveLayerUp: (Long) -> Unit,
    onMoveLayerDown: (Long) -> Unit,
    recentColors: List<Int>,
    canvasBackgroundColor: Int = 0xFFFFFFFF.toInt(),
    onCanvasBackgroundColorChange: (Int) -> Unit = {},
    isCanvasFlipped: Boolean = false,
    onToggleFlipCanvas: () -> Unit = {},
    onReorderLayer: (Int, Int) -> Unit = { _, _ -> },
    onReorderLayers: (List<Long>) -> Unit = {},
    isEyedropperActive: Boolean = false,
    onToggleEyedropper: () -> Unit = {},
    eyedropperReticleState: EyedropperReticleState = EyedropperReticleState(),
    documentTitle: String = "Untitled Artwork",
    onTitleChange: (String) -> Unit = {},
    onNewProjectClick: () -> Unit = {},
    onManualSave: () -> Unit = {},
    savedProjects: List<SavedProjectSummary> = emptyList(),
    totalSavedProjectsSize: String = "0 KB",
    currentProjectDiskSize: String = "0 KB",
    appRamUsageMb: Int = 0,
    onLoadProject: (String) -> Unit = {},
    onDeleteSavedProject: (String) -> Unit = {},
    onMenuOpened: () -> Unit = {},
    onExportMilton: () -> Unit = {},
    onExportPng: () -> Unit = {},
    onExportJpg: () -> Unit = {},
    onImportMilton: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state = CanvasUiState(
        tool = ToolUiState(
            brushType = brushType,
            brushSize = brushSize,
            brushOpacity = brushOpacity,
            brushStabilizer = brushStabilizer,
            brushColorRgb = brushColorRgb,
            sizeBezierConfig = sizeBezierConfig,
            opacityBezierConfig = opacityBezierConfig,
            isEyedropperActive = isEyedropperActive,
            recentColors = recentColors,
            eyedropperReticleState = eyedropperReticleState
        ),
        viewport = ViewportUiState(
            zoomLevel = zoomLevel,
            rotationDegrees = rotationDegrees,
            isCanvasFlipped = isCanvasFlipped,
            isZoomLocked = isZoomLocked,
            isRotationLocked = isRotationLocked,
            canUndo = canUndo,
            canRedo = canRedo,
            isZenMode = isZenMode
        ),
        document = DocumentUiState(
            documentTitle = documentTitle,
            canvasBackgroundColor = canvasBackgroundColor,
            savedProjects = savedProjects,
            totalSavedProjectsSize = totalSavedProjectsSize,
            currentProjectDiskSize = currentProjectDiskSize,
            appRamUsageMb = appRamUsageMb
        ),
        layers = LayersUiState(
            layers = layers,
            activeLayerId = activeLayerId
        )
    )

    val actions = CanvasUiActions(
        tool = ToolActions(
            onBrushTypeChange = onBrushTypeChange,
            onBrushSizeChange = onBrushSizeChange,
            onBrushOpacityChange = onBrushOpacityChange,
            onBrushStabilizerChange = onBrushStabilizerChange,
            onBrushColorChange = onBrushColorChange,
            onSizeBezierConfigChange = onSizeBezierConfigChange,
            onOpacityBezierConfigChange = onOpacityBezierConfigChange,
            onToggleEyedropper = onToggleEyedropper
        ),
        viewport = ViewportActions(
            onUndo = onUndo,
            onRedo = onRedo,
            onToggleFlipCanvas = onToggleFlipCanvas,
            onToggleZoomLock = onToggleZoomLock,
            onToggleRotationLock = onToggleRotationLock,
            onResetCanvas = onResetCanvas,
            onToggleZenMode = onToggleZenMode
        ),
        document = DocumentActions(
            onTitleChange = onTitleChange,
            onCanvasBackgroundColorChange = onCanvasBackgroundColorChange,
            onNewProjectClick = onNewProjectClick,
            onManualSave = onManualSave,
            onLoadProject = onLoadProject,
            onDeleteSavedProject = onDeleteSavedProject,
            onMenuOpened = onMenuOpened,
            onExportMilton = onExportMilton,
            onExportPng = onExportPng,
            onExportJpg = onExportJpg,
            onImportMilton = onImportMilton
        ),
        layers = LayerActions(
            onSelectLayer = onSelectLayer,
            onAddLayer = onAddLayer,
            onDeleteLayer = onDeleteLayer,
            onClearLayer = onClearLayer,
            onToggleLayerVisibility = onToggleLayerVisibility,
            onLayerOpacityChange = onLayerOpacityChange,
            onMoveLayerUp = onMoveLayerUp,
            onMoveLayerDown = onMoveLayerDown,
            onReorderLayer = onReorderLayer,
            onReorderLayers = onReorderLayers
        )
    )

    MiltonTabletUi(state = state, actions = actions, modifier = modifier)
}
