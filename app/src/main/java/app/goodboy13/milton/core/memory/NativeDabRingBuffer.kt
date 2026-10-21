package app.goodboy13.milton.core.memory

import app.goodboy13.milton.core.brush.BrushDab
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * Pre-allocated off-heap direct circular buffer for stroke dabs in flight.
 * Eliminates Kotlin heap churn and young-generation Android ART GC pauses
 * during active 120Hz/240Hz stylus sketching.
 *
 * Implements a true monotonic circular ring buffer:
 * - Writes advance [headIndex] monotonically without resetting or zeroing off-heap memory.
 * - Readers consume explicit monotonic ranges [startHead, startHead + count).
 * - Slots map via modulo arithmetic (or bitwise AND when capacity is a power of 2).
 * - Asynchronous frames on the GL render thread never experience race conditions with
 *   subsequent strokes starting on the UI thread.
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
        const val DEFAULT_CAPACITY = 65536 // 64k dabs = 2.56 MB direct off-heap memory
    }

    val byteBuffer: ByteBuffer = ByteBuffer.allocateDirect(capacity * STRIDE_BYTES).order(ByteOrder.nativeOrder())
    val floatBuffer: FloatBuffer = byteBuffer.asFloatBuffer()
    val intBuffer: IntBuffer = byteBuffer.asIntBuffer()

    val isPowerOfTwo: Boolean = (capacity > 0) && ((capacity and (capacity - 1)) == 0)
    val mask: Long = (capacity - 1).toLong()

    /** Monotonic sequence counter of all dabs written. Only incremented by producer. */
    var headIndex: Long = 0L
        private set

    /**
     * Advances the monotonic head counter after native code batch writes into the direct buffer.
     */
    fun advanceHead(addedCount: Int) {
        if (addedCount > 0) {
            headIndex += addedCount
        }
    }

    /** Total count of resident dabs available (clamped to capacity). */
    val count: Int get() = minOf(headIndex, capacity.toLong()).toInt()

    val isEmpty: Boolean get() = headIndex == 0L
    val isNotEmpty: Boolean get() = headIndex > 0L

    @PublishedApi
    internal fun slotOf(seq: Long): Int {
        return if (isPowerOfTwo) {
            (seq and mask).toInt()
        } else {
            (seq % capacity.toLong()).toInt()
        }
    }

    /**
     * Appends a new dab directly into circular off-heap memory without any object instantiation.
     * Advances the monotonic [headIndex].
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
        val slot = slotOf(headIndex)
        val offset = slot * STRIDE_FLOATS
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
        headIndex++
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

    // Direct slot accessors
    inline fun getXBySlot(slot: Int): Float = floatBuffer.get(slot * STRIDE_FLOATS + 0)
    inline fun getYBySlot(slot: Int): Float = floatBuffer.get(slot * STRIDE_FLOATS + 1)
    inline fun getRadiusBySlot(slot: Int): Float = floatBuffer.get(slot * STRIDE_FLOATS + 2)
    inline fun getAlphaBySlot(slot: Int): Float = floatBuffer.get(slot * STRIDE_FLOATS + 3)
    inline fun getColorRgbBySlot(slot: Int): Int = intBuffer.get(slot * STRIDE_FLOATS + 4)
    inline fun getHardnessBySlot(slot: Int): Float = floatBuffer.get(slot * STRIDE_FLOATS + 5)
    inline fun getBrushModeBySlot(slot: Int): Int {
        val raw = intBuffer.get(slot * STRIDE_FLOATS + 6)
        return if (raw in 0..15) raw else floatBuffer.get(slot * STRIDE_FLOATS + 6).toInt()
    }
    inline fun getPressureBySlot(slot: Int): Float = floatBuffer.get(slot * STRIDE_FLOATS + 7)
    inline fun getTiltBySlot(slot: Int): Float = floatBuffer.get(slot * STRIDE_FLOATS + 8)
    inline fun isEraserBySlot(slot: Int): Boolean {
        val raw = intBuffer.get(slot * STRIDE_FLOATS + 9)
        return raw == 1 || floatBuffer.get(slot * STRIDE_FLOATS + 9) > 0.5f
    }

    // Monotonic sequence accessors
    inline fun getX(seq: Long): Float = getXBySlot(slotOf(seq))
    inline fun getY(seq: Long): Float = getYBySlot(slotOf(seq))
    inline fun getRadius(seq: Long): Float = getRadiusBySlot(slotOf(seq))
    inline fun getAlpha(seq: Long): Float = getAlphaBySlot(slotOf(seq))
    inline fun getColorRgb(seq: Long): Int = getColorRgbBySlot(slotOf(seq))
    inline fun getHardness(seq: Long): Float = getHardnessBySlot(slotOf(seq))
    inline fun getBrushMode(seq: Long): Int = getBrushModeBySlot(slotOf(seq))
    inline fun getPressure(seq: Long): Float = getPressureBySlot(slotOf(seq))
    inline fun getTilt(seq: Long): Float = getTiltBySlot(slotOf(seq))
    inline fun isEraser(seq: Long): Boolean = isEraserBySlot(slotOf(seq))

    // Int sequence overload for legacy compatibility
    inline fun getX(index: Int): Float = getX(index.toLong())
    inline fun getY(index: Int): Float = getY(index.toLong())
    inline fun getRadius(index: Int): Float = getRadius(index.toLong())
    inline fun getAlpha(index: Int): Float = getAlpha(index.toLong())
    inline fun getColorRgb(index: Int): Int = getColorRgb(index.toLong())
    inline fun getHardness(index: Int): Float = getHardness(index.toLong())
    inline fun getBrushMode(index: Int): Int = getBrushMode(index.toLong())
    inline fun getPressure(index: Int): Float = getPressure(index.toLong())
    inline fun getTilt(index: Int): Float = getTilt(index.toLong())
    inline fun isEraser(index: Int): Boolean = isEraser(index.toLong())

    /**
     * Iterates over a monotonic range [startSeq, startSeq + count) with an inlined zero-allocation callback.
     * Passes the buffer slot index along with all extracted dab fields.
     */
    inline fun forEachDab(
        startSeq: Long,
        count: Int,
        action: (
            slot: Int,
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
        for (i in 0 until count) {
            val seq = startSeq + i
            val slot = slotOf(seq)
            val offset = slot * STRIDE_FLOATS
            action(
                slot,
                floatBuffer.get(offset + 0),
                floatBuffer.get(offset + 1),
                floatBuffer.get(offset + 2),
                floatBuffer.get(offset + 3),
                intBuffer.get(offset + 4),
                floatBuffer.get(offset + 5),
                getBrushModeBySlot(slot),
                floatBuffer.get(offset + 7),
                floatBuffer.get(offset + 8),
                isEraserBySlot(slot)
            )
        }
    }

    /**
     * Legacy slice iterator for unit tests and backwards compatibility.
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
        val total = (endIndex - startIndex).coerceAtLeast(0)
        forEachDab(startIndex.toLong(), total) { _, x, y, r, a, c, h, m, p, t, e ->
            action(startIndex, x, y, r, a, c, h, m, p, t, e)
        }
    }

    /**
     * Converts dabs into a list for legacy callers, recycling the target list.
     */
    fun populateList(outList: MutableList<BrushDab>, startSeq: Long = 0L, count: Int = this.count) {
        forEachDab(startSeq, count) { _, x, y, radius, alpha, colorRgb, hardness, brushMode, pressure, tilt, isEraser ->
            outList.add(
                BrushDab(
                    x = x,
                    y = y,
                    radius = radius,
                    alpha = alpha,
                    colorRgb = colorRgb,
                    hardness = hardness,
                    brushMode = brushMode,
                    pressure = pressure,
                    tilt = tilt,
                    isEraser = isEraser
                )
            )
        }
    }

    /**
     * Fast O(1) buffer reset without reallocating off-heap storage.
     * Should only be used when closing or resetting the canvas document.
     */
    fun reset() {
        headIndex = 0L
        byteBuffer.clear()
        floatBuffer.clear()
        intBuffer.clear()
    }
}
