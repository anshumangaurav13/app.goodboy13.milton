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
    val isEraser: Boolean = false
)

data class BrushProperties(
    var size: Float = 24.0f,
    var colorRgb: Int = 0xFF111111.toInt(),
    var opacity: Float = 1.0f,
    var spacing: Float = 0.15f,
    var hardness: Float = 0.85f,
    var minRadius: Float = 2.0f,
    var isEraser: Boolean = false
)
