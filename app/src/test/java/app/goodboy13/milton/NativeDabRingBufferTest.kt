package app.goodboy13.milton

import app.goodboy13.milton.core.brush.BrushDab
import app.goodboy13.milton.core.memory.NativeDabRingBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeDabRingBufferTest {

    @Test
    fun testPushAndReadDabs() {
        val ring = NativeDabRingBuffer(capacity = 64)
        assertEquals(0, ring.count)
        assertTrue(ring.isEmpty)
        assertFalse(ring.isNotEmpty)

        val pushed = ring.pushDab(
            x = 100.5f,
            y = 200.5f,
            radius = 12.0f,
            alpha = 0.85f,
            colorRgb = 0xFF112233.toInt(),
            hardness = 0.9f,
            brushMode = 1,
            pressure = 0.75f,
            tilt = 0.2f,
            isEraser = false
        )

        assertTrue(pushed)
        assertEquals(1, ring.count)
        assertTrue(ring.isNotEmpty)
        assertFalse(ring.isEmpty)

        assertEquals(100.5f, ring.getX(0), 0.001f)
        assertEquals(200.5f, ring.getY(0), 0.001f)
        assertEquals(12.0f, ring.getRadius(0), 0.001f)
        assertEquals(0.85f, ring.getAlpha(0), 0.001f)
        assertEquals(0xFF112233.toInt(), ring.getColorRgb(0))
        assertEquals(0.9f, ring.getHardness(0), 0.001f)
        assertEquals(1, ring.getBrushMode(0))
        assertEquals(0.75f, ring.getPressure(0), 0.001f)
        assertEquals(0.2f, ring.getTilt(0), 0.001f)
        assertFalse(ring.isEraser(0))
    }

    @Test
    fun testPushBrushDabObject() {
        val ring = NativeDabRingBuffer(capacity = 16)
        val dab = BrushDab(
            x = 50f,
            y = 75f,
            radius = 5f,
            alpha = 1.0f,
            colorRgb = 0xFF00FF00.toInt(),
            isEraser = true,
            hardness = 0.8f,
            brushMode = 2,
            pressure = 0.4f,
            tilt = 0.1f
        )

        ring.pushDab(dab)
        assertEquals(1, ring.count)
        assertEquals(50f, ring.getX(0), 0.001f)
        assertEquals(75f, ring.getY(0), 0.001f)
        assertTrue(ring.isEraser(0))
        assertEquals(2, ring.getBrushMode(0))
    }

    @Test
    fun testForEachDabIteration() {
        val ring = NativeDabRingBuffer(capacity = 32)
        for (i in 0 until 10) {
            ring.pushDab(
                x = i * 10f,
                y = i * 20f,
                radius = 4f,
                alpha = 0.5f,
                colorRgb = 0
            )
        }
        assertEquals(10, ring.count)

        var iteratedCount = 0
        ring.forEachDab(startIndex = 2, endIndex = 7) { idx, x, y, _, _, _, _, _, _, _, _ ->
            assertEquals((iteratedCount + 2) * 10f, x, 0.001f)
            assertEquals((iteratedCount + 2) * 20f, y, 0.001f)
            iteratedCount++
        }
        assertEquals(5, iteratedCount)
    }

    @Test
    fun testReset() {
        val ring = NativeDabRingBuffer(capacity = 8)
        ring.pushDab(1f, 2f, 3f, 4f, 5)
        assertEquals(1, ring.count)
        ring.reset()
        assertEquals(0, ring.count)
        assertTrue(ring.isEmpty)
    }

    @Test
    fun testCircularWrapping() {
        val ring = NativeDabRingBuffer(capacity = 4)
        ring.pushDab(10f, 10f, 1f, 1f, 1)
        ring.pushDab(20f, 20f, 2f, 2f, 2)
        ring.pushDab(30f, 30f, 3f, 3f, 3)
        ring.pushDab(40f, 40f, 4f, 4f, 4)
        assertEquals(4, ring.count)
        assertEquals(4L, ring.headIndex)

        // Push 5th dab: wraps to slot 0
        ring.pushDab(50f, 50f, 5f, 5f, 5)
        assertEquals(4, ring.count)
        assertEquals(5L, ring.headIndex)
        assertEquals(50f, ring.getX(4L), 0.001f)
    }

    @Test
    fun testFloatFallbackForBrushModeAndEraser() {
        val ring = NativeDabRingBuffer(capacity = 4)
        ring.pushDab(10f, 10f, 1f, 1f, 1)

        // Directly write IEEE-754 float 1.0f into offset 6 (brushMode) and offset 9 (isEraser)
        // to simulate any legacy/mismatched float writer
        val slot = 0
        val offset = slot * NativeDabRingBuffer.STRIDE_FLOATS
        ring.floatBuffer.put(offset + 6, 1.0f)
        ring.floatBuffer.put(offset + 9, 1.0f)

        assertEquals(1, ring.getBrushMode(0))
        assertTrue(ring.isEraser(0))

        var readBrushMode = -1
        var readEraser = false
        ring.forEachDab(0L, 1) { _, _, _, _, _, _, _, brushMode, _, _, isEraser ->
            readBrushMode = brushMode
            readEraser = isEraser
        }
        assertEquals(1, readBrushMode)
        assertTrue(readEraser)
    }
}
