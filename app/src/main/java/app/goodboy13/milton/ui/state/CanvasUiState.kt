package app.goodboy13.milton.ui.state

import app.goodboy13.milton.core.brush.BrushType
import app.goodboy13.milton.core.layer.Layer
import app.goodboy13.milton.core.model.BezierControlPoints
import app.goodboy13.milton.core.model.PressureCurveDefaults
import app.goodboy13.milton.core.storage.SavedProjectSummary
import app.goodboy13.milton.ui.EyedropperReticleState
import app.goodboy13.milton.ui.QUICK_PALETTE_COLORS

/**
 * State representing the active stylus brush, color, and stroke dynamics.
 */
data class ToolUiState(
    val brushType: BrushType = BrushType.PENCIL,
    val brushSize: Float = 35f,
    val brushOpacity: Float = 0.75f,
    val brushStabilizer: Float = 0.10f,
    val brushColorRgb: Int = 0xFF333333.toInt(),
    val sizeBezierConfig: BezierControlPoints = PressureCurveDefaults.STANDARD.copy(minPercent = 0.05f),
    val opacityBezierConfig: BezierControlPoints = PressureCurveDefaults.SOFT.copy(minPercent = 0.20f),
    val isEraserMode: Boolean = false,
    val isEyedropperActive: Boolean = false,
    val recentColors: List<Int> = QUICK_PALETTE_COLORS,
    val eyedropperReticleState: EyedropperReticleState = EyedropperReticleState(),
    val liquifyReticleState: app.goodboy13.milton.ui.LiquifyReticleState = app.goodboy13.milton.ui.LiquifyReticleState(),
    val liquifyMode: app.goodboy13.milton.core.native.MiltonNative.LiquifyMode = app.goodboy13.milton.core.native.MiltonNative.LiquifyMode.PUSH
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
    val canvasBackgroundColor: Int = 0xFFF6F4ED.toInt(),
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
