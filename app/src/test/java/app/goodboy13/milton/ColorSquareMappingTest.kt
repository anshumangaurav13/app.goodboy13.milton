package app.goodboy13.milton

import app.goodboy13.milton.ui.components.ColorSquareMapping
import org.junit.Assert.assertEquals
import org.junit.Test

class ColorSquareMappingTest {

    @Test
    fun testCorners() {
        // Top-Left (0, 0) -> Pure White (S=0, V=1)
        val (sTL, vTL) = ColorSquareMapping.touchToSv(0f, 0f)
        assertEquals(0f, sTL, 1e-4f)
        assertEquals(1f, vTL, 1e-4f)

        // Bottom-Left (0, 1) -> Pure Black (S=0, V=0)
        val (sBL, vBL) = ColorSquareMapping.touchToSv(0f, 1f)
        assertEquals(0f, sBL, 1e-4f)
        assertEquals(0f, vBL, 1e-4f)

        // Top-Right (1, 0) -> Pure Hue (S=1, V=1)
        val (sTR, vTR) = ColorSquareMapping.touchToSv(1f, 0f)
        assertEquals(1f, sTR, 1e-4f)
        assertEquals(1f, vTR, 1e-4f)

        // Bottom-Right (1, 1) -> Pure Hue (S=1, V=1)
        val (sBR, vBR) = ColorSquareMapping.touchToSv(1f, 1f)
        assertEquals(1f, sBR, 1e-4f)
        assertEquals(1f, vBR, 1e-4f)
    }

    @Test
    fun testLeftEdgeGrayscale() {
        // Left edge nx=0: pure grayscale from White (top) to Black (bottom)
        for (i in 0..10) {
            val ny = i / 10f
            val (s, v) = ColorSquareMapping.touchToSv(0f, ny)
            assertEquals("Saturation should be 0 on left edge at ny=$ny", 0f, s, 1e-4f)
            assertEquals("Value should be 1-ny on left edge at ny=$ny", 1f - ny, v, 1e-4f)
        }
    }

    @Test
    fun testRightEdgePureHue() {
        // Right edge nx=1: pure hue all the way down
        for (i in 0..10) {
            val ny = i / 10f
            val (s, v) = ColorSquareMapping.touchToSv(1f, ny)
            assertEquals("Saturation should be 1 on right edge at ny=$ny", 1f, s, 1e-4f)
            assertEquals("Value should be 1 on right edge at ny=$ny", 1f, v, 1e-4f)
        }
    }

    @Test
    fun testTopEdgeTintRamp() {
        // Top edge ny=0: tint ramp from White (nx=0) to Pure Hue (nx=1)
        for (i in 0..10) {
            val nx = i / 10f
            val (s, v) = ColorSquareMapping.touchToSv(nx, 0f)
            assertEquals("Value should be 1 on top edge at nx=$nx", 1f, v, 1e-4f)
            assertEquals("Saturation should equal nx on top edge at nx=$nx", nx, s, 1e-4f)
        }
    }

    @Test
    fun testBottomEdgeShadeRamp() {
        // Bottom edge ny=1: shade ramp from Black (nx=0) to Pure Hue (nx=1)
        for (i in 1..10) {
            val nx = i / 10f
            val (s, v) = ColorSquareMapping.touchToSv(nx, 1f)
            assertEquals("Saturation should be 1 on bottom edge at nx=$nx", 1f, s, 1e-4f)
            assertEquals("Value should equal nx on bottom edge at nx=$nx", nx, v, 1e-4f)
        }
    }

    @Test
    fun testRoundTripBijection() {
        // Grid search over the square verifying round-trip mapping
        for (ix in 0..20) {
            val nx = ix / 20f
            for (iy in 0..20) {
                val ny = iy / 20f
                val (s, v) = ColorSquareMapping.touchToSv(nx, ny)
                val (backX, backY) = ColorSquareMapping.svToCoordinates(s, v, fallbackY = ny)

                assertEquals("X round-trip failed for ($nx, $ny)", nx, backX, 1e-3f)
                if (nx < 0.99f && v > 0.01f) {
                    assertEquals("Y round-trip failed for ($nx, $ny)", ny, backY, 1e-3f)
                }
            }
        }
    }
}
