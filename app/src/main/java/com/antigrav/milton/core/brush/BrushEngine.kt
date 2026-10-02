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
    private var currentStrokeId: Long = 0L

    fun startStroke(worldX: Float, worldY: Float, pressure: Float): List<BrushDab> {
        isStrokeActive = true
        currentStrokeId = System.nanoTime()
        lastX = worldX
        lastY = worldY
        val clampedP = if (pressure <= 0.001f) 0.05f else pressure.coerceIn(0.01f, 1.0f)
        lastPressure = properties.bezierConfig.solveY(clampedP)
        distanceFromLastDab = 0f

        val radius = max(properties.minRadius, (properties.size * 0.5f) * lastPressure)
        val dabAlpha = if (properties.brushMode == 2) {
            properties.opacity * (0.35f + 0.65f * lastPressure)
        } else {
            properties.opacity
        }

        val initialDab = BrushDab(
            x = worldX,
            y = worldY,
            radius = radius,
            alpha = dabAlpha,
            colorRgb = properties.colorRgb,
            isEraser = properties.isEraser,
            hardness = properties.hardness,
            brushMode = properties.brushMode,
            pressure = lastPressure,
            strokeId = currentStrokeId
        )
        return listOf(initialDab)
    }

    fun addPoint(worldX: Float, worldY: Float, pressure: Float): List<BrushDab> {
        if (!isStrokeActive) {
            return startStroke(worldX, worldY, pressure)
        }

        val rawP = if (pressure <= 0.001f) lastPressure else pressure.coerceIn(0.01f, 1.0f)
        val currentPressure = properties.bezierConfig.solveY(rawP)
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
            val dabAlpha = if (properties.brushMode == 2) {
                properties.opacity * (0.35f + 0.65f * ip)
            } else {
                properties.opacity
            }

            dabs.add(
                BrushDab(
                    x = ix,
                    y = iy,
                    radius = ir,
                    alpha = dabAlpha,
                    colorRgb = properties.colorRgb,
                    isEraser = properties.isEraser,
                    hardness = properties.hardness,
                    brushMode = properties.brushMode,
                    pressure = ip,
                    strokeId = currentStrokeId
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
