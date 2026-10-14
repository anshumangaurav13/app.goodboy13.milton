package app.goodboy13.milton.ui.state

import app.goodboy13.milton.core.brush.BrushType
import app.goodboy13.milton.core.constants.BrushDefaults
import app.goodboy13.milton.core.constants.PaletteDefaults
import app.goodboy13.milton.core.layer.Layer
import app.goodboy13.milton.core.model.BezierControlPoints
import app.goodboy13.milton.core.model.PressureCurveDefaults
import app.goodboy13.milton.core.storage.SavedProjectSummary
import app.goodboy13.milton.ui.EyedropperReticleState

/**
 * State representing the active stylus brush, color, and stroke dynamics.
 */
data class ToolUiState(
    val brushType: BrushType = BrushType.PENCIL,
    val brushSize: Float = BrushDefaults.getDefaultSize(BrushType.PENCIL),
    val brushOpacity: Float = BrushDefaults.getDefaultOpacity(BrushType.PENCIL),
    val brushStabilizer: Float = BrushDefaults.getDefaultStabilizer(BrushType.PENCIL),
    val brushHardness: Float = BrushDefaults.getDefaultHardness(BrushType.PENCIL),
    val brushTilt: Float = BrushDefaults.getDefaultTilt(BrushType.PENCIL),
    val brushColorRgb: Int = PaletteDefaults.DEFAULT_BRUSH_COLOR,
    val sizeBezierConfig: BezierControlPoints = BrushDefaults.getDefaultSizeBezier(BrushType.PENCIL),
    val opacityBezierConfig: BezierControlPoints = BrushDefaults.getDefaultOpacityBezier(BrushType.PENCIL),
    val tiltBezierConfig: BezierControlPoints = BrushDefaults.getDefaultTiltBezier(BrushType.PENCIL),
    val isEraserMode: Boolean = BrushDefaults.getDefaultIsEraser(BrushType.PENCIL),
    val isEyedropperActive: Boolean = false,
    val recentColors: List<Int> = PaletteDefaults.DEFAULT_RECENT_COLORS,
    val eyedropperReticleState: EyedropperReticleState = EyedropperReticleState()
)

/**
 * State representing camera viewport transform, locks, and history availability.
 */
data class ViewportUiState(
    val zoomLevel: Float = 0.5f,
    val rotationDegrees: Float = 0f,
    val isCanvasFlipped: Boolean = false,
    val isZoomLocked: Boolean = false,
    val isRotationLocked: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val isZenMode: Boolean = false,
    val isModifierHeld: Boolean = false
)

/**
 * State representing document metadata, projects library catalog, and system storage metrics.
 */
data class DocumentUiState(
    val documentTitle: String = "Untitled Artwork",
    val activeProjectId: String? = null,
    val canvasBackgroundColor: Int = PaletteDefaults.CANVAS_BACKGROUND_DEFAULT,
    val savedProjects: List<SavedProjectSummary> = emptyList(),
    val totalSavedProjectsSize: String = "0 KB",
    val currentProjectDiskSize: String = "0 KB",
    val appRamUsageMb: Int = 0
)

/**
 * State representing the layer hierarchy and selection.
 */
data class LayersUiState(
    val layers: List<Layer> = emptyList(),
    val activeLayerId: Long = 1L
)

/**
 * Consolidated immutable state object for the entire Milton Canvas UI.
 */
data class CanvasUiState(
    val tool: ToolUiState = ToolUiState(),
    val viewport: ViewportUiState = ViewportUiState(),
    val document: DocumentUiState = DocumentUiState(),
    val layers: LayersUiState = LayersUiState(),
    val selectionState: app.goodboy13.milton.core.selection.SelectionState = app.goodboy13.milton.core.selection.SelectionState.Idle,
    val selectedTransformLayerIds: Set<Long> = emptySet()
)
