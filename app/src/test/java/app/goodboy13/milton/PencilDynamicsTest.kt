package app.goodboy13.milton

import app.goodboy13.milton.core.brush.PencilDynamics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class PencilDynamicsTest {

    @Test
    fun testHorizontalStrokeProducesHighAlignment() {
        val rawTilt = (PI / 4).toFloat() // 45 degrees tilt
        val orientation = 0f // Tilted toward user along Y axis

        // Horizontal stroke: dx = 10, dy = 0
        val alignment = PencilDynamics.computeAlignment(10f, 0f, rawTilt, orientation)
        assertEquals(1.0f, alignment, 0.001f)

        // Effective tilt should be 100% of raw normalized tilt
        val effectiveTilt = PencilDynamics.computeEffectiveTilt(0.8f, alignment)
        assertEquals(0.8f, effectiveTilt, 0.001f)
    }

    @Test
    fun testVerticalStrokeProducesZeroAlignment() {
        val rawTilt = (PI / 4).toFloat() // 45 degrees tilt
        val orientation = 0f // Tilted toward user along Y axis

        // Vertical stroke: dx = 0, dy = 10
        val alignment = PencilDynamics.computeAlignment(0f, 10f, rawTilt, orientation)
        assertEquals(0.0f, alignment, 0.001f)

        // Effective tilt should be 0 (no widening, stays narrow)
        val effectiveTilt = PencilDynamics.computeEffectiveTilt(0.8f, alignment)
        assertEquals(0.0f, effectiveTilt, 0.001f)
    }

    @Test
    fun testDiagonalStrokeIntermediate() {
        val rawTilt = (PI / 4).toFloat()
        val orientation = 0f

        // 45 degree stroke: dx = 10, dy = 10
        val alignment = PencilDynamics.computeAlignment(10f, 10f, rawTilt, orientation)
        assertEquals(0.7071f, alignment, 0.01f)

        // alignment^2 = 0.5
        val effectiveTilt = PencilDynamics.computeEffectiveTilt(1.0f, alignment)
        assertEquals(0.5f, effectiveTilt, 0.02f)
    }

    @Test
    fun testBothHorizontalDirectionsEqual() {
        val rawTilt = (PI / 3).toFloat()
        val orientation = 0f

        // Moving left (+dx) vs moving right (-dx)
        val alignRight = PencilDynamics.computeAlignment(15f, 0f, rawTilt, orientation)
        val alignLeft = PencilDynamics.computeAlignment(-15f, 0f, rawTilt, orientation)

        assertEquals(1.0f, alignRight, 0.001f)
        assertEquals(1.0f, alignLeft, 0.001f)
    }
}
