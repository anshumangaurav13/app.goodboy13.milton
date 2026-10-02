package com.antigrav.milton.core.brush

import kotlin.math.hypot
import kotlin.math.max

/**
 * Converts continuous stylus stroke coordinates into discrete equidistant raster dabs.
 */
class BrushEngine(val properties: BrushProperties = BrushProperties()) {

    private var lastX: Float = 0f
    private var lastY: Float = 0f
    private var lastPressure: Float = 0.1f
    private var distanceFromLastDab: Float = 0f
    private var isStrokeActive: Boolean = false

    fun startStroke(worldX: Float, worldY: Float, pressure: Float): List<BrushDab> {
        isStrokeActive = true
        lastX = worldX
        lastY = worldY
        val clampedP = if (pressure <= 0.001f) 0.05f else pressure.coerceIn(0.01f, 1.0f)
        lastPressure = clampedP
        distanceFromLastDab = 0f

        val radius = max(properties.minRadius, (properties.size * 0.5f) * lastPressure)
        val initialDab = BrushDab(
            x = worldX,
            y = worldY,
            radius = radius,
            alpha = properties.opacity,
            colorRgb = properties.colorRgb,
            isEraser = properties.isEraser,
            hardness = properties.hardness
        )
        return listOf(initialDab)
    }

    fun addPoint(worldX: Float, worldY: Float, pressure: Float): List<BrushDab> {
        if (!isStrokeActive) {
            return startStroke(worldX, worldY, pressure)
        }

        val currentPressure = if (pressure <= 0.001f) lastPressure else pressure.coerceIn(0.01f, 1.0f)
        val prevP = lastPressure
        lastPressure = currentPressure

        val dx = worldX - lastX
        val dy = worldY - lastY
        val segmentDist = hypot(dx.toDouble(), dy.toDouble()).toFloat()

        if (segmentDist < 0.001f) {
            return emptyList()
        }

        val dabs = mutableListOf<BrushDab>()

        val avgPressure = (prevP + currentPressure) * 0.5f
        val radius = max(properties.minRadius, (properties.size * 0.5f) * avgPressure)
        val stepSize = max(0.8f, radius * properties.spacing * 2.0f)

        var d = stepSize - distanceFromLastDab
        var lastPlacedD = -1f

        while (d <= segmentDist) {
            val t = (d / segmentDist).coerceIn(0f, 1f)
            val ix = lastX + dx * t
            val iy = lastY + dy * t
            val ip = prevP + (currentPressure - prevP) * t
            val ir = max(properties.minRadius, (properties.size * 0.5f) * ip)

            dabs.add(
                BrushDab(
                    x = ix,
                    y = iy,
                    radius = ir,
                    alpha = properties.opacity,
                    colorRgb = properties.colorRgb,
                    isEraser = properties.isEraser,
                    hardness = properties.hardness
                )
            )
            lastPlacedD = d
            d += stepSize
        }

        if (lastPlacedD >= 0f) {
            distanceFromLastDab = segmentDist - lastPlacedD
        } else {
            distanceFromLastDab += segmentDist
        }

        lastX = worldX
        lastY = worldY

        return dabs
    }

    fun endStroke(): List<BrushDab> {
        isStrokeActive = false
        distanceFromLastDab = 0f
        return emptyList()
    }
}
