package app.goodboy13.milton.core.brush

import app.goodboy13.milton.core.memory.NativeDabRingBuffer
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow

/**
 * Converts continuous stylus stroke coordinates into discrete equidistant raster dabs.
 * Supports rate-invariant, time-decay lazy brush stabilization.
 * Supports zero-allocation stamping via [NativeDabRingBuffer].
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
    private var lastTilt: Float = 0f
    private var lastTiltScale: Float = 1.0f
    private var distanceFromLastDab: Float = 0f
    private var isStrokeActive: Boolean = false
    private var currentStrokeId: Long = 0L

    private fun computeAlpha(normY: Float): Float {
        val maxAlpha = properties.opacity.coerceIn(0.001f, 1.0f)
        val minAlpha = properties.opacityBezierConfig.minPercent.coerceIn(0f, 1f).coerceAtMost(maxAlpha)
        return (minAlpha + (maxAlpha - minAlpha) * normY).coerceIn(0.001f, 1.0f)
    }

    fun computeTiltScale(tilt: Float): Float {
        if (properties.brushMode != 1 || properties.tilt <= 0.001f) return 1.0f
        val tiltResponse = properties.tiltBezierConfig.solveY(tilt.coerceIn(0f, 1f))
        return 1.0f + tiltResponse * (properties.tilt * 3.5f)
    }

    /**
     * Zero-allocation startStroke directly writing into off-heap [NativeDabRingBuffer].
     */
    fun startStroke(
        worldX: Float,
        worldY: Float,
        pressure: Float,
        eventTimeMillis: Long = -1L,
        tilt: Float = 0f,
        outRing: NativeDabRingBuffer
    ) {
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

        val clampedTilt = tilt.coerceIn(0f, 1f)
        lastTilt = clampedTilt
        lastTiltScale = computeTiltScale(clampedTilt)
        val radius = max(properties.minRadius, (properties.size * 0.5f) * lastSizeP * lastTiltScale)
        val dabAlpha = computeAlpha(lastNormOpacityY)

        outRing.pushDab(
            x = worldX,
            y = worldY,
            radius = radius,
            alpha = dabAlpha,
            colorRgb = properties.colorRgb,
            hardness = properties.hardness,
            brushMode = properties.brushMode,
            pressure = lastSizeP,
            tilt = clampedTilt,
            isEraser = properties.isEraser
        )
    }

    fun startStroke(
        worldX: Float,
        worldY: Float,
        pressure: Float,
        eventTimeMillis: Long = -1L,
        tilt: Float = 0f,
        outDabs: MutableList<BrushDab>
    ) {
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

        val clampedTilt = tilt.coerceIn(0f, 1f)
        lastTilt = clampedTilt
        lastTiltScale = computeTiltScale(clampedTilt)
        val radius = max(properties.minRadius, (properties.size * 0.5f) * lastSizeP * lastTiltScale)
        val dabAlpha = computeAlpha(lastNormOpacityY)

        outDabs.add(
            BrushDab(
                x = worldX,
                y = worldY,
                radius = radius,
                alpha = dabAlpha,
                colorRgb = properties.colorRgb,
                isEraser = properties.isEraser,
                hardness = properties.hardness,
                brushMode = properties.brushMode,
                pressure = lastSizeP,
                tilt = clampedTilt,
                strokeId = currentStrokeId
            )
        )
    }

    fun startStroke(
        worldX: Float,
        worldY: Float,
        pressure: Float,
        eventTimeMillis: Long = -1L,
        tilt: Float = 0f
    ): List<BrushDab> {
        val dabs = mutableListOf<BrushDab>()
        startStroke(worldX, worldY, pressure, eventTimeMillis, tilt, dabs)
        return dabs
    }

    /**
     * Zero-allocation addPoint directly writing into off-heap [NativeDabRingBuffer].
     */
    fun addPoint(
        worldX: Float,
        worldY: Float,
        pressure: Float,
        eventTimeMillis: Long = -1L,
        tilt: Float = 0f,
        outRing: NativeDabRingBuffer
    ) {
        if (!isStrokeActive) {
            startStroke(worldX, worldY, pressure, eventTimeMillis, tilt, outRing)
            return
        }

        rawLastX = worldX
        rawLastY = worldY

        val targetX: Float
        val targetY: Float

        if (properties.stabilizer > 0.001f) {
            val stab = properties.stabilizer.coerceIn(0.001f, 1.0f)
            val tau = (stab.toDouble().pow(1.25) * 0.18).toFloat()

            val dtSeconds = if (lastEventTimeMillis >= 0L && eventTimeMillis > lastEventTimeMillis) {
                val dt = (eventTimeMillis - lastEventTimeMillis) / 1000.0f
                dt.coerceIn(0.001f, 0.1f)
            } else if (lastEventTimeMillis >= 0L && eventTimeMillis == lastEventTimeMillis) {
                0.004f
            } else {
                0.0083f
            }
            if (eventTimeMillis >= 0L) {
                lastEventTimeMillis = eventTimeMillis
            }

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
            return
        }

        val clampedTilt = tilt.coerceIn(0f, 1f)
        val currentTiltScale = computeTiltScale(clampedTilt)
        val prevTiltScale = lastTiltScale
        lastTilt = clampedTilt
        lastTiltScale = currentTiltScale

        val avgSizeP = (prevSizeP + currentSizeP) * 0.5f
        val avgTiltScale = (prevTiltScale + currentTiltScale) * 0.5f
        val radius = max(properties.minRadius, (properties.size * 0.5f) * avgSizeP * avgTiltScale)
        val stepSize = max(0.8f, radius * properties.spacing * 2.0f)

        var d = stepSize - distanceFromLastDab
        var lastPlacedD = -1f

        while (d <= segmentDist) {
            val t = (d / segmentDist).coerceIn(0f, 1f)
            val ix = lastX + dx * t
            val iy = lastY + dy * t
            val ipSize = prevSizeP + (currentSizeP - prevSizeP) * t
            val ipNormOpacityY = prevNormOpacityY + (currentNormOpacityY - prevNormOpacityY) * t
            val ipTiltScale = prevTiltScale + (currentTiltScale - prevTiltScale) * t
            val ir = max(properties.minRadius, (properties.size * 0.5f) * ipSize * ipTiltScale)
            val dabAlpha = computeAlpha(ipNormOpacityY)

            outRing.pushDab(
                x = ix,
                y = iy,
                radius = ir,
                alpha = dabAlpha,
                colorRgb = properties.colorRgb,
                hardness = properties.hardness,
                brushMode = properties.brushMode,
                pressure = ipSize,
                tilt = clampedTilt,
                isEraser = properties.isEraser
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
    }

    fun addPoint(
        worldX: Float,
        worldY: Float,
        pressure: Float,
        eventTimeMillis: Long = -1L,
        tilt: Float = 0f,
        outDabs: MutableList<BrushDab>
    ) {
        if (!isStrokeActive) {
            startStroke(worldX, worldY, pressure, eventTimeMillis, tilt, outDabs)
            return
        }

        rawLastX = worldX
        rawLastY = worldY

        val targetX: Float
        val targetY: Float

        if (properties.stabilizer > 0.001f) {
            val stab = properties.stabilizer.coerceIn(0.001f, 1.0f)
            val tau = (stab.toDouble().pow(1.25) * 0.18).toFloat()

            val dtSeconds = if (lastEventTimeMillis >= 0L && eventTimeMillis > lastEventTimeMillis) {
                val dt = (eventTimeMillis - lastEventTimeMillis) / 1000.0f
                dt.coerceIn(0.001f, 0.1f)
            } else if (lastEventTimeMillis >= 0L && eventTimeMillis == lastEventTimeMillis) {
                0.004f
            } else {
                0.0083f
            }
            if (eventTimeMillis >= 0L) {
                lastEventTimeMillis = eventTimeMillis
            }

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
            return
        }

        val clampedTilt = tilt.coerceIn(0f, 1f)
        val currentTiltScale = computeTiltScale(clampedTilt)
        val prevTiltScale = lastTiltScale
        lastTilt = clampedTilt
        lastTiltScale = currentTiltScale

        val avgSizeP = (prevSizeP + currentSizeP) * 0.5f
        val avgTiltScale = (prevTiltScale + currentTiltScale) * 0.5f
        val radius = max(properties.minRadius, (properties.size * 0.5f) * avgSizeP * avgTiltScale)
        val stepSize = max(0.8f, radius * properties.spacing * 2.0f)

        var d = stepSize - distanceFromLastDab
        var lastPlacedD = -1f

        while (d <= segmentDist) {
            val t = (d / segmentDist).coerceIn(0f, 1f)
            val ix = lastX + dx * t
            val iy = lastY + dy * t
            val ipSize = prevSizeP + (currentSizeP - prevSizeP) * t
            val ipNormOpacityY = prevNormOpacityY + (currentNormOpacityY - prevNormOpacityY) * t
            val ipTiltScale = prevTiltScale + (currentTiltScale - prevTiltScale) * t
            val ir = max(properties.minRadius, (properties.size * 0.5f) * ipSize * ipTiltScale)
            val dabAlpha = computeAlpha(ipNormOpacityY)

            outDabs.add(
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
                    tilt = clampedTilt,
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
    }

    fun addPoint(
        worldX: Float,
        worldY: Float,
        pressure: Float,
        eventTimeMillis: Long = -1L,
        tilt: Float = 0f
    ): List<BrushDab> {
        val dabs = mutableListOf<BrushDab>()
        addPoint(worldX, worldY, pressure, eventTimeMillis, tilt, dabs)
        return dabs
    }

    /**
     * Zero-allocation endStroke directly writing into off-heap [NativeDabRingBuffer].
     */
    fun endStroke(outRing: NativeDabRingBuffer) {
        if (!isStrokeActive) return
        isStrokeActive = false

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
                    outRing.pushDab(
                        x = ix,
                        y = iy,
                        radius = ir,
                        alpha = dabAlpha,
                        colorRgb = properties.colorRgb,
                        hardness = properties.hardness,
                        brushMode = properties.brushMode,
                        pressure = lastSizeP,
                        tilt = lastTilt,
                        isEraser = properties.isEraser
                    )
                    d += stepSize
                }
            }
        }

        distanceFromLastDab = 0f
        lastEventTimeMillis = 0L
    }

    fun endStroke(outDabs: MutableList<BrushDab>) {
        if (!isStrokeActive) return
        isStrokeActive = false

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
                    outDabs.add(
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
    }

    fun endStroke(): List<BrushDab> {
        val dabs = mutableListOf<BrushDab>()
        endStroke(dabs)
        return dabs
    }
}
