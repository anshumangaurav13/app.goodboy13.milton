package app.goodboy13.milton.core.constants

import app.goodboy13.milton.core.brush.BrushType
import app.goodboy13.milton.core.model.BezierControlPoints
import app.goodboy13.milton.core.model.PressureCurveDefaults

/**
 * Encapsulates the complete default configuration for a drawing or manipulation tool.
 */
data class ToolPreset(
    val size: Float,
    val opacity: Float,
    val stabilizer: Float,
    val hardness: Float,
    val tilt: Float,
    val spacing: Float,
    val brushMode: Int = 0,
    val isEraser: Boolean = false,
    val sizeBezier: BezierControlPoints = PressureCurveDefaults.STANDARD,
    val opacityBezier: BezierControlPoints = PressureCurveDefaults.STANDARD,
    val tiltBezier: BezierControlPoints = PressureCurveDefaults.STANDARD.copy(minPercent = 0.0f, maxPercent = 1.0f)
)

/**
 * Single Source of Truth for all brush presets, dynamics, and fallback parameters.
 */
object BrushDefaults {

    val PEN = ToolPreset(
        size = 30.0f,
        opacity = 1.0f,
        stabilizer = 0.05f,
        hardness = 0.94f,
        tilt = 0.0f,
        spacing = 0.10f,
        brushMode = 0,
        isEraser = false,
        sizeBezier = PressureCurveDefaults.STANDARD.copy(minPercent = 0.15f),
        opacityBezier = PressureCurveDefaults.LINEAR.copy(minPercent = 1.0f, maxPercent = 1.0f),
        tiltBezier = PressureCurveDefaults.STANDARD.copy(minPercent = 0.0f, maxPercent = 1.0f)
    )

    val PENCIL = ToolPreset(
        size = 24.0f,
        opacity = 0.90f,
        stabilizer = 0.10f,
        hardness = 0.70f,
        tilt = 0.50f,
        spacing = 0.12f,
        brushMode = 1,
        isEraser = false,
        sizeBezier = PressureCurveDefaults.STANDARD.copy(minPercent = 0.40f),
        opacityBezier = PressureCurveDefaults.SOFT.copy(minPercent = 0.35f),
        tiltBezier = PressureCurveDefaults.STANDARD.copy(minPercent = 0.0f, maxPercent = 1.0f)
    )

    val PAINTBRUSH = ToolPreset(
        size = 250.0f,
        opacity = 0.60f,
        stabilizer = 0.05f,
        hardness = 0.70f,
        tilt = 0.20f,
        spacing = 0.08f,
        brushMode = 2,
        isEraser = false,
        sizeBezier = PressureCurveDefaults.STANDARD.copy(minPercent = 0.45f),
        opacityBezier = PressureCurveDefaults.STANDARD.copy(minPercent = 0.25f),
        tiltBezier = PressureCurveDefaults.STANDARD.copy(minPercent = 0.0f, maxPercent = 1.0f)
    )

    val ERASER = ToolPreset(
        size = 120.0f,
        opacity = 1.0f,
        stabilizer = 0.0f,
        hardness = 0.85f,
        tilt = 0.0f,
        spacing = 0.10f,
        brushMode = 0,
        isEraser = true,
        sizeBezier = PressureCurveDefaults.STANDARD.copy(minPercent = 0.45f),
        opacityBezier = PressureCurveDefaults.STANDARD.copy(minPercent = 0.0f),
        tiltBezier = PressureCurveDefaults.STANDARD.copy(minPercent = 0.0f, maxPercent = 1.0f)
    )



    val LASSO = ToolPreset(
        size = 24.0f,
        opacity = 1.0f,
        stabilizer = 0.0f,
        hardness = 1.0f,
        tilt = 0.0f,
        spacing = 0.10f,
        brushMode = 0,
        isEraser = false
    )

    val LIQUIFY = ToolPreset(
        size = 150.0f,
        opacity = 1.0f,
        stabilizer = 0.0f,
        hardness = 0.70f,
        tilt = 0.0f,
        spacing = 0.10f,
        brushMode = 0,
        isEraser = false
    )

    val PRESETS: Map<BrushType, ToolPreset> = mapOf(
        BrushType.PEN to PEN,
        BrushType.PENCIL to PENCIL,
        BrushType.PAINTBRUSH to PAINTBRUSH,
        BrushType.ERASER to ERASER,
        BrushType.LASSO to LASSO,
        BrushType.LIQUIFY to LIQUIFY
    )

    fun getPreset(type: BrushType): ToolPreset = PRESETS[type] ?: PENCIL

    fun getDefaultSize(type: BrushType): Float = getPreset(type).size
    fun getDefaultOpacity(type: BrushType): Float = getPreset(type).opacity
    fun getDefaultStabilizer(type: BrushType): Float = getPreset(type).stabilizer
    fun getDefaultHardness(type: BrushType): Float = getPreset(type).hardness
    fun getDefaultTilt(type: BrushType): Float = getPreset(type).tilt
    fun getDefaultSpacing(type: BrushType): Float = getPreset(type).spacing
    fun getDefaultBrushMode(type: BrushType): Int = getPreset(type).brushMode
    fun getDefaultIsEraser(type: BrushType): Boolean = getPreset(type).isEraser
    fun getDefaultSizeBezier(type: BrushType): BezierControlPoints = getPreset(type).sizeBezier
    fun getDefaultOpacityBezier(type: BrushType): BezierControlPoints = getPreset(type).opacityBezier
    fun getDefaultTiltBezier(type: BrushType): BezierControlPoints = getPreset(type).tiltBezier
}
