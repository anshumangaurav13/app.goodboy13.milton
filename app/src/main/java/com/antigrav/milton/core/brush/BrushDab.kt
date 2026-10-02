package com.antigrav.milton.core.brush

import com.antigrav.milton.core.model.BezierControlPoints
import com.antigrav.milton.core.model.PressureCurveDefaults

/**
 * A single stamped raster dab in world coordinates.
 */
data class BrushDab(
    val x: Float,
    val y: Float,
    val radius: Float,
    val alpha: Float,
    val colorRgb: Int,
    val isEraser: Boolean = false,
    val hardness: Float = 0.85f,
    val brushMode: Int = 0, // 0 = Pen/Eraser, 1 = Pencil, 2 = Paintbrush
    val pressure: Float = 0.5f,
    val strokeId: Long = 0L
)

enum class BrushType(val displayName: String) {
    PEN("Pen"),
    PENCIL("Pencil"),
    PAINTBRUSH("Paintbrush"),
    ERASER("Eraser")
}

data class BrushProperties(
    var brushType: BrushType = BrushType.PEN,
    var size: Float = 16.0f,
    var colorRgb: Int = 0xFF111111.toInt(),
    var opacity: Float = 1.0f,
    var spacing: Float = 0.10f,
    var hardness: Float = 0.94f,
    var minRadius: Float = 1.5f,
    var isEraser: Boolean = false,
    var brushMode: Int = 0,
    var sizeBezierConfig: BezierControlPoints = PressureCurveDefaults.STANDARD,
    var opacityBezierConfig: BezierControlPoints = PressureCurveDefaults.LINEAR
) {
    // Legacy alias
    var bezierConfig: BezierControlPoints
        get() = sizeBezierConfig
        set(value) { sizeBezierConfig = value }

    private val savedSizes = mutableMapOf(
        BrushType.PEN to 16.0f,
        BrushType.PENCIL to 12.0f,
        BrushType.PAINTBRUSH to 250.0f,
        BrushType.ERASER to 64.0f
    )

    private val savedOpacities = mutableMapOf(
        BrushType.PEN to 1.0f,
        BrushType.PENCIL to 0.50f,
        BrushType.PAINTBRUSH to 0.60f,
        BrushType.ERASER to 1.0f
    )

    private val savedSizeBeziers = mutableMapOf(
        BrushType.PEN to PressureCurveDefaults.STANDARD,
        BrushType.PENCIL to PressureCurveDefaults.SOFT,
        BrushType.PAINTBRUSH to PressureCurveDefaults.STANDARD.copy(minPercent = 0.45f),
        BrushType.ERASER to PressureCurveDefaults.LINEAR
    )

    private val savedOpacityBeziers = mutableMapOf(
        BrushType.PEN to PressureCurveDefaults.LINEAR.copy(minPercent = 1.0f, maxPercent = 1.0f),
        BrushType.PENCIL to PressureCurveDefaults.SOFT.copy(minPercent = 0.20f, maxPercent = 1.0f),
        BrushType.PAINTBRUSH to PressureCurveDefaults.STANDARD.copy(minPercent = 0.25f, maxPercent = 1.0f),
        BrushType.ERASER to PressureCurveDefaults.LINEAR.copy(minPercent = 1.0f, maxPercent = 1.0f)
    )

    fun applyPreset(type: BrushType) {
        // Save current tool's settings before switching
        savedSizes[brushType] = size
        savedOpacities[brushType] = opacity
        savedSizeBeziers[brushType] = sizeBezierConfig
        savedOpacityBeziers[brushType] = opacityBezierConfig

        brushType = type
        size = savedSizes[type] ?: 16f
        opacity = savedOpacities[type] ?: 1.0f
        sizeBezierConfig = savedSizeBeziers[type] ?: PressureCurveDefaults.STANDARD
        opacityBezierConfig = savedOpacityBeziers[type] ?: PressureCurveDefaults.LINEAR
        minRadius = (size * 0.12f).coerceAtLeast(1.2f)

        when (type) {
            BrushType.PEN -> {
                hardness = 0.94f
                spacing = 0.10f
                isEraser = false
                brushMode = 0
            }
            BrushType.PENCIL -> {
                hardness = 0.40f
                spacing = 0.08f
                isEraser = false
                brushMode = 1
            }
            BrushType.PAINTBRUSH -> {
                hardness = 0.70f
                spacing = 0.08f
                isEraser = false
                brushMode = 2
            }
            BrushType.ERASER -> {
                hardness = 0.85f
                spacing = 0.12f
                isEraser = true
                brushMode = 0
            }
        }
    }
}
