package app.goodboy13.milton.core.brush

import app.goodboy13.milton.core.memory.NativeDabRingBuffer
import app.goodboy13.milton.core.model.BezierControlPoints
import app.goodboy13.milton.core.native.MiltonNative

/**
 * Converts continuous stylus stroke coordinates into discrete equidistant raster dabs.
 * Supports rate-invariant, time-decay lazy brush stabilization.
 * Fully backed by native Rust (`milton_core::brush::BrushEngine`) writing directly
 * into off-heap [NativeDabRingBuffer] for zero-allocation performance.
 */
class BrushEngine(val properties: BrushProperties = BrushProperties()) {

    var nativeEnginePtr: Long = 0L
        private set

    private var isStrokeActive: Boolean = false
    private val localCompatRing = NativeDabRingBuffer(capacity = 8192)

    init {
        check(MiltonNative.isLoaded) { "MiltonNative core library must be loaded for BrushEngine" }
        nativeEnginePtr = MiltonNative.createBrushEngine(
            properties.size,
            properties.opacity,
            properties.colorRgb,
            properties.spacing,
            properties.hardness,
            properties.tilt,
            properties.minRadius,
            properties.isEraser,
            properties.brushMode,
            properties.stabilizer,
            properties.sizeBezierConfig.toFloatArray(),
            properties.opacityBezierConfig.toFloatArray(),
            properties.tiltBezierConfig.toFloatArray()
        )
        check(nativeEnginePtr != 0L) { "Failed to create native BrushEngine instance" }
    }

    private fun BezierControlPoints.toFloatArray(): FloatArray =
        floatArrayOf(cp1x, cp1y, cp2x, cp2y, minPercent, maxPercent)

    fun syncNativeProperties() {
        if (nativeEnginePtr != 0L) {
            MiltonNative.brushSetProperties(
                nativeEnginePtr,
                properties.size,
                properties.opacity,
                properties.colorRgb,
                properties.spacing,
                properties.hardness,
                properties.tilt,
                properties.minRadius,
                properties.isEraser,
                properties.brushMode,
                properties.stabilizer,
                properties.sizeBezierConfig.toFloatArray(),
                properties.opacityBezierConfig.toFloatArray(),
                properties.tiltBezierConfig.toFloatArray()
            )
        }
    }

    fun release() {
        if (nativeEnginePtr != 0L) {
            MiltonNative.destroyBrushEngine(nativeEnginePtr)
            nativeEnginePtr = 0L
        }
    }

    protected fun finalize() {
        release()
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
        if (nativeEnginePtr != 0L) {
            syncNativeProperties()
            isStrokeActive = true
            val placed = MiltonNative.brushStartStroke(
                nativeEnginePtr,
                worldX,
                worldY,
                pressure,
                eventTimeMillis,
                tilt,
                outRing.byteBuffer,
                outRing.headIndex,
                outRing.capacity
            )
            outRing.advanceHead(placed)
        }
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
        if (nativeEnginePtr != 0L) {
            val placed = MiltonNative.brushAddPoint(
                nativeEnginePtr,
                worldX,
                worldY,
                pressure,
                eventTimeMillis,
                tilt,
                outRing.byteBuffer,
                outRing.headIndex,
                outRing.capacity
            )
            outRing.advanceHead(placed)
        }
    }

    /**
     * Zero-allocation endStroke directly writing into off-heap [NativeDabRingBuffer].
     */
    fun endStroke(outRing: NativeDabRingBuffer) {
        if (nativeEnginePtr != 0L) {
            if (!isStrokeActive) return
            isStrokeActive = false
            val placed = MiltonNative.brushEndStroke(
                nativeEnginePtr,
                outRing.byteBuffer,
                outRing.headIndex,
                outRing.capacity
            )
            outRing.advanceHead(placed)
        }
    }

    // ========================================================================
    // Legacy List-based API overloads for tests and compatibility
    // ========================================================================

    @Synchronized
    fun startStroke(
        worldX: Float,
        worldY: Float,
        pressure: Float,
        eventTimeMillis: Long = -1L,
        tilt: Float = 0f,
        outDabs: MutableList<BrushDab>
    ) {
        val startHead = localCompatRing.headIndex
        startStroke(worldX, worldY, pressure, eventTimeMillis, tilt, localCompatRing)
        val count = (localCompatRing.headIndex - startHead).toInt()
        localCompatRing.populateList(outDabs, startHead, count)
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

    @Synchronized
    fun addPoint(
        worldX: Float,
        worldY: Float,
        pressure: Float,
        eventTimeMillis: Long = -1L,
        tilt: Float = 0f,
        outDabs: MutableList<BrushDab>
    ) {
        val startHead = localCompatRing.headIndex
        addPoint(worldX, worldY, pressure, eventTimeMillis, tilt, localCompatRing)
        val count = (localCompatRing.headIndex - startHead).toInt()
        localCompatRing.populateList(outDabs, startHead, count)
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

    @Synchronized
    fun endStroke(outDabs: MutableList<BrushDab>) {
        val startHead = localCompatRing.headIndex
        endStroke(localCompatRing)
        val count = (localCompatRing.headIndex - startHead).toInt()
        localCompatRing.populateList(outDabs, startHead, count)
    }

    fun endStroke(): List<BrushDab> {
        val dabs = mutableListOf<BrushDab>()
        endStroke(dabs)
        return dabs
    }
}
