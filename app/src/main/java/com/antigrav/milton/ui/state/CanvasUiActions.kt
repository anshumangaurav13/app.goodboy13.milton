package com.antigrav.milton.ui.state

import com.antigrav.milton.core.brush.BrushType
import com.antigrav.milton.core.model.BezierControlPoints

/**
 * Callbacks for stylus brush and color operations.
 */
data class ToolActions(
    val onBrushTypeChange: (BrushType) -> Unit = {},
    val onBrushSizeChange: (Float) -> Unit = {},
    val onBrushOpacityChange: (Float) -> Unit = {},
    val onBrushStabilizerChange: (Float) -> Unit = {},
    val onBrushColorChange: (Int) -> Unit = {},
    val onSizeBezierConfigChange: (BezierControlPoints) -> Unit = {},
    val onOpacityBezierConfigChange: (BezierControlPoints) -> Unit = {},
    val onToggleEyedropper: () -> Unit = {}
)

/**
 * Callbacks for viewport navigation, locks, undo/redo, and zen mode.
 */
data class ViewportActions(
    val onUndo: () -> Unit = {},
    val onRedo: () -> Unit = {},
    val onToggleFlipCanvas: () -> Unit = {},
    val onToggleZoomLock: () -> Unit = {},
    val onToggleRotationLock: () -> Unit = {},
    val onResetCanvas: () -> Unit = {},
    val onToggleZenMode: (Boolean) -> Unit = {}
)

/**
 * Callbacks for document metadata, project management, and exports.
 */
data class DocumentActions(
    val onTitleChange: (String) -> Unit = {},
    val onCanvasBackgroundColorChange: (Int) -> Unit = {},
    val onNewProjectClick: () -> Unit = {},
    val onManualSave: () -> Unit = {},
    val onLoadProject: (String) -> Unit = {},
    val onDeleteSavedProject: (String) -> Unit = {},
    val onMenuOpened: () -> Unit = {},
    val onExportMilton: () -> Unit = {},
    val onExportPng: () -> Unit = {},
    val onExportJpg: () -> Unit = {},
    val onImportMilton: () -> Unit = {}
)

/**
 * Callbacks for layer hierarchy management and reordering.
 */
data class LayerActions(
    val onSelectLayer: (Long) -> Unit = {},
    val onAddLayer: () -> Unit = {},
    val onDeleteLayer: (Long) -> Unit = {},
    val onClearLayer: (Long) -> Unit = {},
    val onToggleLayerVisibility: (Long, Boolean) -> Unit = { _, _ -> },
    val onLayerOpacityChange: (Long, Float) -> Unit = { _, _ -> },
    val onMoveLayerUp: (Long) -> Unit = {},
    val onMoveLayerDown: (Long) -> Unit = {},
    val onReorderLayer: (Int, Int) -> Unit = { _, _ -> },
    val onReorderLayers: (List<Long>) -> Unit = {}
)

/**
 * Consolidated action bundle passed to MiltonTabletUi.
 */
data class CanvasUiActions(
    val tool: ToolActions = ToolActions(),
    val viewport: ViewportActions = ViewportActions(),
    val document: DocumentActions = DocumentActions(),
    val layers: LayerActions = LayerActions()
)
