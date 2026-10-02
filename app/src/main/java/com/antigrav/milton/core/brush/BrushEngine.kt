package com.antigrav.milton.core.brush

import kotlin.math.hypot
import kotlin.math.max

/**
 * Converts continuous stylus stroke coordinates into discrete equidistant raster dabs.
 */
class BrushEngine(val properties: BrushProperties = BrushProperties()) {

    private var lastX: Float = 0f
    private var lastY: Float = 0f
    private var smoothedPressure: Float = 0.1f
    private var distanceFromLastDab: Float = 0f
    private var isStrokeActive: Boolean = false

    // EMA smoothing factor: 0.35 gives smooth, natural pressure response without latency
    private val pressureFilter = 0.35f

    fun startStroke(worldX: Float, worldY: Float, pressure: Float): List<BrushDab> {
        isStrokeActive = true
        lastX = worldX
        lastY = worldY
        val clampedP = if (pressure <= 0.001f) 0.05f else pressure.coerceIn(0.01f, 1.0f)
        smoothedPressure = clampedP
        distanceFromLastDab = 0f

        val radius = max(properties.minRadius, (properties.size * 0.5f) * smoothedPressure)
        val initialDab = BrushDab(
            x = worldX,
            y = worldY,
            radius = radius,
            alpha = properties.opacity,
            colorRgb = properties.colorRgb,
            isEraser = properties.isEraser
        )
        return listOf(initialDab)
    }

    fun addPoint(worldX: Float, worldY: Float, pressure: Float): List<BrushDab> {
        if (!isStrokeActive) {
            return startStroke(worldX, worldY, pressure)
        }

        val targetP = if (pressure <= 0.001f) smoothedPressure else pressure.coerceIn(0.01f, 1.0f)
        val prevP = smoothedPressure
        smoothedPressure = prevP + pressureFilter * (targetP - prevP)

        val dx = worldX - lastX
        val dy = worldY - lastY
        val segmentDist = hypot(dx.toDouble(), dy.toDouble()).toFloat()

        if (segmentDist < 0.001f) {
            return emptyList()
        }

        val dabs = mutableListOf<BrushDab>()

        val avgPressure = (prevP + smoothedPressure) * 0.5f
        val radius = max(properties.minRadius, (properties.size * 0.5f) * avgPressure)
        val stepSize = max(0.8f, radius * properties.spacing * 2.0f)

        var d = stepSize - distanceFromLastDab
        var lastPlacedD = -1f

        while (d <= segmentDist) {
            val t = (d / segmentDist).coerceIn(0f, 1f)
            val ix = lastX + dx * t
            val iy = lastY + dy * t
            val ip = prevP + (smoothedPressure - prevP) * t
            val ir = max(properties.minRadius, (properties.size * 0.5f) * ip)

            dabs.add(
                BrushDab(
                    x = ix,
                    y = iy,
                    radius = ir,
                    alpha = properties.opacity,
                    colorRgb = properties.colorRgb,
                    isEraser = properties.isEraser
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
