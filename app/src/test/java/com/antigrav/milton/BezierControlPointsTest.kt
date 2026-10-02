package com.antigrav.milton

import com.antigrav.milton.core.model.BezierControlPoints
import com.antigrav.milton.core.model.PressureCurveDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BezierControlPointsTest {

    @Test
    fun testEndpoints() {
        val curve = PressureCurveDefaults.STANDARD
        assertEquals(0.0f, curve.solveY(0.0f), 0.001f)
        assertEquals(1.0f, curve.solveY(1.0f), 0.001f)
    }

    @Test
    fun testLinearCurveIdentity() {
        val linear = PressureCurveDefaults.LINEAR
        for (i in 0..10) {
            val p = i / 10.0f
            assertEquals(p, linear.solveY(p), 0.02f)
        }
    }

    @Test
    fun testSoftCurveBoostsLightPressure() {
        val soft = PressureCurveDefaults.SOFT
        // At 0.25 raw pressure, soft curve should produce significantly higher output
        val mapped = soft.solveY(0.25f)
        assertTrue("Expected soft curve to boost pressure at 0.25, got $mapped", mapped > 0.25f)
    }

    @Test
    fun testFirmCurveResistsLightPressure() {
        val firm = PressureCurveDefaults.FIRM
        // At 0.35 raw pressure, firm curve should produce lower output
        val mapped = firm.solveY(0.35f)
        assertTrue("Expected firm curve to resist pressure at 0.35, got $mapped", mapped < 0.35f)
    }

    @Test
    fun testMonotonicity() {
        val presets = listOf(
            PressureCurveDefaults.STANDARD,
            PressureCurveDefaults.SOFT,
            PressureCurveDefaults.FIRM,
            PressureCurveDefaults.LINEAR
        )
        for (preset in presets) {
            var prev = -0.01f
            for (step in 0..20) {
                val input = step / 20.0f
                val output = preset.solveY(input)
                assertTrue("Curve should be monotonically increasing: input=$input, prev=$prev, output=$output", output >= prev - 0.001f)
                prev = output
            }
        }
    }

    @Test
    fun testMinMaxPercentScaling() {
        val curve = BezierControlPoints(minPercent = 0.20f, maxPercent = 0.80f)
        assertEquals(0.20f, curve.solveY(0.0f), 0.001f)
        assertEquals(0.80f, curve.solveY(1.0f), 0.001f)
        val mid = curve.solveY(0.5f)
        assertTrue("Output at 0.5 should be within [0.20, 0.80], got $mid", mid in 0.20f..0.80f)
    }
}
