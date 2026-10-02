package com.antigrav.milton.core.brush

import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow

/**
 * Converts continuous stylus stroke coordinates into discrete equidistant raster dabs.
 * Supports rate-invariant, time-decay lazy brush stabilization.
 */
class BrushEngine(val properties: BrushProperties = BrushProperties()) {

    private var lastX: Float = 0f
    private var lastY: Float = 0f
    private var rawLastX: Float = 0f
    private var rawLastY: Float = 0f
    private var lastEventTimeMillis: Long = -1L
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

    fun startStroke(
        worldX: Float,
        worldY: Float,
        pressure: Float,
        eventTimeMillis: Long = -1L
    ): List<BrushDab> {
        isStrokeActive = true
        currentStrokeId = System.nanoTime()
        lastX = worldX
        lastY = worldY
        rawLastX = worldX
        rawLastY = worldY
        lastEventTimeMillis = eventTimeMillis
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

    fun addPoint(
        worldX: Float,
        worldY: Float,
        pressure: Float,
        eventTimeMillis: Long = -1L
    ): List<BrushDab> {
        if (!isStrokeActive) {
            return startStroke(worldX, worldY, pressure, eventTimeMillis)
        }

        rawLastX = worldX
        rawLastY = worldY

        val targetX: Float
        val targetY: Float

        if (properties.stabilizer > 0.001f) {
            val stab = properties.stabilizer.coerceIn(0.001f, 1.0f)
            // Rate-invariant time constant (seconds):
            // 5% -> ~59ms, 10% -> ~106ms, 15% -> ~150ms, 50% -> ~416ms, 100% -> ~750ms
            val tau = (stab.toDouble().pow(0.85) * 0.75).toFloat()

            val dtSeconds = if (lastEventTimeMillis >= 0L && eventTimeMillis > lastEventTimeMillis) {
                val dt = (eventTimeMillis - lastEventTimeMillis) / 1000.0f
                dt.coerceIn(0.001f, 0.1f)
            } else if (lastEventTimeMillis >= 0L && eventTimeMillis == lastEventTimeMillis) {
                // Sub-frame historical event at identical millisecond: nominal 4ms (240Hz)
                0.004f
            } else {
                // Initial fallback (nominal 120Hz = 8.3ms)
                0.0083f
            }
            if (eventTimeMillis >= 0L) {
                lastEventTimeMillis = eventTimeMillis
            }

            // Continuous time-decay weight independent of polling rate: weight = 1 - exp(-dt / tau)
            val weight = (1.0 - exp(-dtSeconds / tau)).toFloat().coerceIn(0.005f, 1.0f)

            targetX = lastX + (worldX - lastX) * weight
            targetY = lastY + (worldY - lastY) * weight
        } else {
            if (eventTimeMillis >= 0L) {
                lastEventTimeMillis = eventTimeMillis
            }
            targetX = worldX
            targetY = worldY
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
        lastEventTimeMillis = 0L
        return dabs
    }
}
