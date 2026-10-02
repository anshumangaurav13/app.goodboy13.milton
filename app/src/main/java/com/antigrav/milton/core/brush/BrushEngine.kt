package com.antigrav.milton.core.brush

import kotlin.math.hypot
import kotlin.math.max

/**
 * Converts continuous stylus stroke coordinates into discrete equidistant raster dabs.
 */
class BrushEngine(val properties: BrushProperties = BrushProperties()) {

    private var lastX: Float = 0f
    private var lastY: Float = 0f
    private var rawLastX: Float = 0f
    private var rawLastY: Float = 0f
    private var lastSizeP: Float = 0.5f
    private var lastNormOpacityY: Float = 1.0f
    private var lastRawP: Float = 0.5f
    private var distanceFromLastDab: Float = 0f
    private var isStrokeActive: Boolean = false
    private var currentStrokeId: Long = 0L

    private fun computeAlpha(normY: Float): Float {
        val maxAlpha = properties.opacity.coerceIn(0.001f, 1.0f)
        val minAlpha = properties.opacityBezierConfig.minPercent.coerceIn(0f, 1f).coerceAtMost(maxAlpha)
        return (minAlpha + (maxAlpha - minAlpha) * normY).coerceIn(0.001f, 1.0f)
    }

    fun startStroke(worldX: Float, worldY: Float, pressure: Float): List<BrushDab> {
        isStrokeActive = true
        currentStrokeId = System.nanoTime()
        lastX = worldX
        lastY = worldY
        rawLastX = worldX
        rawLastY = worldY
        val clampedP = if (pressure <= 0.001f) 0.05f else pressure.coerceIn(0.01f, 1.0f)
        lastRawP = clampedP
        lastSizeP = properties.sizeBezierConfig.solveY(clampedP)
        lastNormOpacityY = properties.opacityBezierConfig.solveNormalizedY(clampedP)
        distanceFromLastDab = 0f

        val radius = max(properties.minRadius, (properties.size * 0.5f) * lastSizeP)
        val dabAlpha = computeAlpha(lastNormOpacityY)

        val initialDab = BrushDab(
            x = worldX,
            y = worldY,
            radius = radius,
            alpha = dabAlpha,
            colorRgb = properties.colorRgb,
            isEraser = properties.isEraser,
            hardness = properties.hardness,
            brushMode = properties.brushMode,
            pressure = lastSizeP,
            strokeId = currentStrokeId
        )
        return listOf(initialDab)
    }

    fun addPoint(worldX: Float, worldY: Float, pressure: Float): List<BrushDab> {
        if (!isStrokeActive) {
            return startStroke(worldX, worldY, pressure)
        }

        rawLastX = worldX
        rawLastY = worldY

        val targetX = if (properties.stabilizer > 0.001f) {
            val stab = properties.stabilizer.coerceIn(0f, 0.95f)
            val weight = 1.0f - stab * 0.85f
            lastX + (worldX - lastX) * weight
        } else {
            worldX
        }

        val targetY = if (properties.stabilizer > 0.001f) {
            val stab = properties.stabilizer.coerceIn(0f, 0.95f)
            val weight = 1.0f - stab * 0.85f
            lastY + (worldY - lastY) * weight
        } else {
            worldY
        }

        val rawP = if (pressure <= 0.001f) lastRawP else pressure.coerceIn(0.01f, 1.0f)
        val currentSizeP = properties.sizeBezierConfig.solveY(rawP)
        val currentNormOpacityY = properties.opacityBezierConfig.solveNormalizedY(rawP)
        val prevSizeP = lastSizeP
        val prevNormOpacityY = lastNormOpacityY
        lastRawP = rawP
        lastSizeP = currentSizeP
        lastNormOpacityY = currentNormOpacityY

        val dx = targetX - lastX
        val dy = targetY - lastY
        val segmentDist = hypot(dx.toDouble(), dy.toDouble()).toFloat()

        if (segmentDist < 0.001f) {
            return emptyList()
        }

        val dabs = mutableListOf<BrushDab>()

        val avgSizeP = (prevSizeP + currentSizeP) * 0.5f
        val radius = max(properties.minRadius, (properties.size * 0.5f) * avgSizeP)
        val stepSize = max(0.8f, radius * properties.spacing * 2.0f)

        var d = stepSize - distanceFromLastDab
        var lastPlacedD = -1f

        while (d <= segmentDist) {
            val t = (d / segmentDist).coerceIn(0f, 1f)
            val ix = lastX + dx * t
            val iy = lastY + dy * t
            val ipSize = prevSizeP + (currentSizeP - prevSizeP) * t
            val ipNormOpacityY = prevNormOpacityY + (currentNormOpacityY - prevNormOpacityY) * t
            val ir = max(properties.minRadius, (properties.size * 0.5f) * ipSize)
            val dabAlpha = computeAlpha(ipNormOpacityY)

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
                    pressure = ipSize,
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

        lastX = targetX
        lastY = targetY

        return dabs
    }

    fun endStroke(): List<BrushDab> {
        if (!isStrokeActive) return emptyList()
        isStrokeActive = false

        val dabs = mutableListOf<BrushDab>()
        if (properties.stabilizer > 0.001f) {
            val dx = rawLastX - lastX
            val dy = rawLastY - lastY
            val segmentDist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (segmentDist >= 0.8f) {
                val radius = max(properties.minRadius, (properties.size * 0.5f) * lastSizeP)
                val stepSize = max(0.8f, radius * properties.spacing * 2.0f)
                var d = stepSize - distanceFromLastDab
                while (d <= segmentDist) {
                    val t = (d / segmentDist).coerceIn(0f, 1f)
                    val ix = lastX + dx * t
                    val iy = lastY + dy * t
                    val ir = max(properties.minRadius, (properties.size * 0.5f) * lastSizeP)
                    val dabAlpha = computeAlpha(lastNormOpacityY)
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
                            pressure = lastSizeP,
                            strokeId = currentStrokeId
                        )
                    )
                    d += stepSize
                }
            }
        }

        distanceFromLastDab = 0f
        return dabs
    }
}
