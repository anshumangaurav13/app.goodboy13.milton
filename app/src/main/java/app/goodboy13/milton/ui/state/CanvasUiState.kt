package app.goodboy13.milton.ui.state

import app.goodboy13.milton.core.brush.BrushType
import app.goodboy13.milton.core.layer.Layer
import app.goodboy13.milton.core.model.BezierControlPoints
import app.goodboy13.milton.core.storage.SavedProjectSummary
import app.goodboy13.milton.ui.EyedropperReticleState
import app.goodboy13.milton.ui.QUICK_PALETTE_COLORS

/**
 * State representing the active stylus brush, color, and stroke dynamics.
 */
data class ToolUiState(
    val brushType: BrushType = BrushType.PENCIL,
    val brushSize: Float = 10f,
    val brushOpacity: Float = 1.0f,
    val brushStabilizer: Float = 0.10f,
    val brushColorRgb: Int = 0xFF333333.toInt(),
    val sizeBezierConfig: BezierControlPoints = BezierControlPoints(),
    val opacityBezierConfig: BezierControlPoints = BezierControlPoints(),
    val isEraserMode: Boolean = false,
    val isEyedropperActive: Boolean = false,
    val recentColors: List<Int> = QUICK_PALETTE_COLORS,
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
    val isZenMode: Boolean = false
)

/**
 * State representing document metadata, projects library catalog, and system storage metrics.
 */
data class DocumentUiState(
    val documentTitle: String = "Untitled Artwork",
    val activeProjectId: String? = null,
    val canvasBackgroundColor: Int = 0xFFF8F8F7.toInt(),
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
    val layers: LayersUiState = LayersUiState()
)
