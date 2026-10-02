package com.antigrav.milton.core.brush

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
    val hardness: Float = 0.85f
)

enum class BrushType(val displayName: String) {
    PEN("Pen"),
    PENCIL("Pencil"),
    ROUND_BRUSH("Round Brush"),
    MARKER("Marker"),
    ERASER("Eraser")
}

data class BrushProperties(
    var brushType: BrushType = BrushType.PEN,
    var size: Float = 10.0f,
    var colorRgb: Int = 0xFF111111.toInt(),
    var opacity: Float = 1.0f,
    var spacing: Float = 0.12f,
    var hardness: Float = 0.92f,
    var minRadius: Float = 1.5f,
    var isEraser: Boolean = false
) {
    private val savedSizes = mutableMapOf(
        BrushType.PEN to 10.0f,
        BrushType.PENCIL to 6.0f,
        BrushType.ROUND_BRUSH to 28.0f,
        BrushType.MARKER to 42.0f,
        BrushType.ERASER to 32.0f
    )

    private val savedOpacities = mutableMapOf(
        BrushType.PEN to 1.0f,
        BrushType.PENCIL to 0.55f,
        BrushType.ROUND_BRUSH to 1.0f,
        BrushType.MARKER to 0.35f,
        BrushType.ERASER to 1.0f
    )

    fun applyPreset(type: BrushType) {
        // Save current tool's settings before switching
        savedSizes[brushType] = size
        savedOpacities[brushType] = opacity

        brushType = type
        size = savedSizes[type] ?: 12f
        opacity = savedOpacities[type] ?: 1.0f
        minRadius = (size * 0.12f).coerceAtLeast(1.2f)

        when (type) {
            BrushType.PEN -> {
                hardness = 0.92f
                spacing = 0.12f
                isEraser = false
            }
            BrushType.PENCIL -> {
                hardness = 0.35f
                spacing = 0.08f
                isEraser = false
            }
            BrushType.ROUND_BRUSH -> {
                hardness = 0.70f
                spacing = 0.15f
                isEraser = false
            }
            BrushType.MARKER -> {
                hardness = 0.88f
                spacing = 0.10f
                isEraser = false
            }
            BrushType.ERASER -> {
                hardness = 0.85f
                spacing = 0.15f
                isEraser = true
            }
        }
    }
}
