package app.goodboy13.milton.core.brush

import app.goodboy13.milton.core.model.BezierControlPoints
import app.goodboy13.milton.core.model.PressureCurveDefaults

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
    var brushType: BrushType = BrushType.PENCIL,
    var size: Float = 35.0f,
    var colorRgb: Int = 0xFF333333.toInt(), // Nice graphite default
    var opacity: Float = 0.40f,
    var spacing: Float = 0.08f,
    var hardness: Float = 0.40f,
    var minRadius: Float = 1.5f,
    var isEraser: Boolean = false,
    var brushMode: Int = 1,
    var stabilizer: Float = 0.10f,
    var sizeBezierConfig: BezierControlPoints = PressureCurveDefaults.STANDARD.copy(minPercent = 0.20f),
    var opacityBezierConfig: BezierControlPoints = PressureCurveDefaults.SOFT.copy(minPercent = 0.20f)
) {
    // Legacy alias
    var bezierConfig: BezierControlPoints
        get() = sizeBezierConfig
        set(value) { sizeBezierConfig = value }

    private val savedSizes = mutableMapOf(
        BrushType.PEN to 30.0f,
        BrushType.PENCIL to 35.0f,
        BrushType.PAINTBRUSH to 250.0f,
        BrushType.ERASER to 64.0f
    )

    private val savedOpacities = mutableMapOf(
        BrushType.PEN to 1.0f,
        BrushType.PENCIL to 0.40f,
        BrushType.PAINTBRUSH to 0.60f,
        BrushType.ERASER to 1.0f
    )

    private val savedStabilizers = mutableMapOf(
        BrushType.PEN to 0.05f,
        BrushType.PENCIL to 0.10f,
        BrushType.PAINTBRUSH to 0.05f,
        BrushType.ERASER to 0.0f
    )

    private val savedSizeBeziers = mutableMapOf(
        BrushType.PEN to PressureCurveDefaults.STANDARD.copy(minPercent = 0.15f),
        BrushType.PENCIL to PressureCurveDefaults.STANDARD.copy(minPercent = 0.20f),
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
        savedStabilizers[brushType] = stabilizer
        savedSizeBeziers[brushType] = sizeBezierConfig
        savedOpacityBeziers[brushType] = opacityBezierConfig

        brushType = type
        size = savedSizes[type] ?: 35f
        opacity = savedOpacities[type] ?: 0.40f
        stabilizer = savedStabilizers[type] ?: 0.0f
        sizeBezierConfig = savedSizeBeziers[type] ?: PressureCurveDefaults.STANDARD
        opacityBezierConfig = savedOpacityBeziers[type] ?: PressureCurveDefaults.STANDARD
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
