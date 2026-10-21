package app.goodboy13.milton.core.memory

import app.goodboy13.milton.core.brush.BrushDab
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * Pre-allocated off-heap direct buffer for stroke dabs in flight.
 * Eliminates Kotlin heap churn and young-generation Android ART GC pauses
 * during active 120Hz/240Hz stylus sketching.
 *
 * Stride (10 ints/floats = 40 bytes per dab):
 * [0] x: Float
 * [1] y: Float
 * [2] radius: Float
 * [3] alpha: Float
 * [4] colorRgb: Int
 * [5] hardness: Float
 * [6] brushMode: Int
 * [7] pressure: Float
 * [8] tilt: Float
 * [9] isEraser: Int (1 = true, 0 = false)
 */
class NativeDabRingBuffer(val capacity: Int = DEFAULT_CAPACITY) {

    companion object {
        const val STRIDE_FLOATS = 10
        const val STRIDE_BYTES = STRIDE_FLOATS * 4
        const val DEFAULT_CAPACITY = 16384 // 16k dabs = 640 KB direct off-heap memory
    }

    val byteBuffer: ByteBuffer = ByteBuffer.allocateDirect(capacity * STRIDE_BYTES).order(ByteOrder.nativeOrder())
    val floatBuffer: FloatBuffer = byteBuffer.asFloatBuffer()
    val intBuffer: IntBuffer = byteBuffer.asIntBuffer()

    var count: Int = 0
        private set

    val isEmpty: Boolean get() = count == 0
    val isNotEmpty: Boolean get() = count > 0

    /**
     * Appends a new dab directly into off-heap memory without any object instantiation.
     */
    fun pushDab(
        x: Float,
        y: Float,
        radius: Float,
        alpha: Float,
        colorRgb: Int,
        hardness: Float = 0.85f,
        brushMode: Int = 0,
        pressure: Float = 0.5f,
        tilt: Float = 0.0f,
        isEraser: Boolean = false
    ): Boolean {
        if (count >= capacity) return false
        val offset = count * STRIDE_FLOATS
        floatBuffer.put(offset + 0, x)
        floatBuffer.put(offset + 1, y)
        floatBuffer.put(offset + 2, radius)
        floatBuffer.put(offset + 3, alpha)
        intBuffer.put(offset + 4, colorRgb)
        floatBuffer.put(offset + 5, hardness)
        intBuffer.put(offset + 6, brushMode)
        floatBuffer.put(offset + 7, pressure)
        floatBuffer.put(offset + 8, tilt)
        intBuffer.put(offset + 9, if (isEraser) 1 else 0)
        count++
        return true
    }

    fun pushDab(dab: BrushDab): Boolean {
        return pushDab(
            x = dab.x,
            y = dab.y,
            radius = dab.radius,
            alpha = dab.alpha,
            colorRgb = dab.colorRgb,
            hardness = dab.hardness,
            brushMode = dab.brushMode,
            pressure = dab.pressure,
            tilt = dab.tilt,
            isEraser = dab.isEraser
        )
    }

    inline fun getX(index: Int): Float = floatBuffer.get(index * STRIDE_FLOATS + 0)
    inline fun getY(index: Int): Float = floatBuffer.get(index * STRIDE_FLOATS + 1)
    inline fun getRadius(index: Int): Float = floatBuffer.get(index * STRIDE_FLOATS + 2)
    inline fun getAlpha(index: Int): Float = floatBuffer.get(index * STRIDE_FLOATS + 3)
    inline fun getColorRgb(index: Int): Int = intBuffer.get(index * STRIDE_FLOATS + 4)
    inline fun getHardness(index: Int): Float = floatBuffer.get(index * STRIDE_FLOATS + 5)
    inline fun getBrushMode(index: Int): Int = intBuffer.get(index * STRIDE_FLOATS + 6)
    inline fun getPressure(index: Int): Float = floatBuffer.get(index * STRIDE_FLOATS + 7)
    inline fun getTilt(index: Int): Float = floatBuffer.get(index * STRIDE_FLOATS + 8)
    inline fun isEraser(index: Int): Boolean = intBuffer.get(index * STRIDE_FLOATS + 9) == 1

    /**
     * Iterates over all active dabs with an inlined zero-allocation callback.
     */
    inline fun forEachDab(
        startIndex: Int = 0,
        endIndex: Int = count,
        action: (
            index: Int,
            x: Float,
            y: Float,
            radius: Float,
            alpha: Float,
            colorRgb: Int,
            hardness: Float,
            brushMode: Int,
            pressure: Float,
            tilt: Float,
            isEraser: Boolean
        ) -> Unit
    ) {
        val end = endIndex.coerceAtMost(count)
        for (i in startIndex until end) {
            val offset = i * STRIDE_FLOATS
            action(
                i,
                floatBuffer.get(offset + 0),
                floatBuffer.get(offset + 1),
                floatBuffer.get(offset + 2),
                floatBuffer.get(offset + 3),
                intBuffer.get(offset + 4),
                floatBuffer.get(offset + 5),
                intBuffer.get(offset + 6),
                floatBuffer.get(offset + 7),
                floatBuffer.get(offset + 8),
                intBuffer.get(offset + 9) == 1
            )
        }
    }

    /**
     * Converts dabs into a list for legacy callers, recycling the target list.
     */
    fun populateList(outList: MutableList<BrushDab>, startIndex: Int = 0, endIndex: Int = count) {
        val end = endIndex.coerceAtMost(count)
        for (i in startIndex until end) {
            val offset = i * STRIDE_FLOATS
            outList.add(
                BrushDab(
                    x = floatBuffer.get(offset + 0),
                    y = floatBuffer.get(offset + 1),
                    radius = floatBuffer.get(offset + 2),
                    alpha = floatBuffer.get(offset + 3),
                    colorRgb = intBuffer.get(offset + 4),
                    hardness = floatBuffer.get(offset + 5),
                    brushMode = intBuffer.get(offset + 6),
                    pressure = floatBuffer.get(offset + 7),
                    tilt = floatBuffer.get(offset + 8),
                    isEraser = intBuffer.get(offset + 9) == 1
                )
            )
        }
    }

    /**
     * Fast O(1) buffer reset without reallocating off-heap storage.
     */
    fun reset() {
        count = 0
        byteBuffer.clear()
        floatBuffer.clear()
        intBuffer.clear()
    }
}
