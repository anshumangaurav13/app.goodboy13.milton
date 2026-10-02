package com.antigrav.milton.core.brush

import kotlin.math.hypot
import kotlin.math.max

/**
 * Converts continuous stylus stroke coordinates into discrete equidistant raster dabs.
 */
class BrushEngine(val properties: BrushProperties = BrushProperties()) {

    private var lastX: Float = 0f
    private var lastY: Float = 0f
    private var lastPressure: Float = 1.0f
    private var residualDistance: Float = 0f
    private var isStrokeActive: Boolean = false

    fun startStroke(worldX: Float, worldY: Float, pressure: Float): List<BrushDab> {
        isStrokeActive = true
        lastX = worldX
        lastY = worldY
        lastPressure = pressure.coerceIn(0.01f, 1.0f)
        residualDistance = 0f

        val radius = max(properties.minRadius, (properties.size * 0.5f) * lastPressure)
        val initialDab = BrushDab(
            x = worldX,
            y = worldY,
            radius = radius,
            alpha = properties.opacity,
            colorRgb = properties.colorRgb
        )
        return listOf(initialDab)
    }

    fun addPoint(worldX: Float, worldY: Float, pressure: Float): List<BrushDab> {
        if (!isStrokeActive) {
            return startStroke(worldX, worldY, pressure)
        }

        val clampedPressure = pressure.coerceIn(0.01f, 1.0f)
        val dx = worldX - lastX
        val dy = worldY - lastY
        val segmentDist = hypot(dx.toDouble(), dy.toDouble()).toFloat()

        if (segmentDist < 0.1f) {
            return emptyList()
        }

        val dabs = mutableListOf<BrushDab>()
        var currentDist = residualDistance

        // Estimate current radius
        val avgPressure = (lastPressure + clampedPressure) * 0.5f
        val radius = max(properties.minRadius, (properties.size * 0.5f) * avgPressure)
        val stepSize = max(0.8f, radius * properties.spacing * 2.0f)

        while (currentDist + stepSize <= segmentDist) {
            currentDist += stepSize
            val t = (currentDist / segmentDist).coerceIn(0f, 1f)
            val ix = lastX + dx * t
            val iy = lastY + dy * t
            val ip = lastPressure + (clampedPressure - lastPressure) * t
            val ir = max(properties.minRadius, (properties.size * 0.5f) * ip)

            dabs.add(
                BrushDab(
                    x = ix,
                    y = iy,
                    radius = ir,
                    alpha = properties.opacity,
                    colorRgb = properties.colorRgb
                )
            )
        }

        residualDistance = segmentDist - currentDist
        lastX = worldX
        lastY = worldY
        lastPressure = clampedPressure

        return dabs
    }

    fun endStroke(): List<BrushDab> {
        isStrokeActive = false
        residualDistance = 0f
        return emptyList()
    }
}
