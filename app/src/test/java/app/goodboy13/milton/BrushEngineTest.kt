package app.goodboy13.milton

import app.goodboy13.milton.core.brush.BrushDab
import app.goodboy13.milton.core.brush.BrushEngine
import app.goodboy13.milton.core.brush.BrushProperties
import app.goodboy13.milton.core.brush.BrushType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrushEngineTest {

    @Test
    fun testStartStrokeGeneratesInitialDab() {
        val engine = BrushEngine(BrushProperties(size = 20f))
        val dabs = engine.startStroke(100f, 200f, 0.5f)

        assertTrue(dabs.isNotEmpty())
        val first = dabs[0]
        assertTrue(first.x == 100f)
        assertTrue(first.y == 200f)
        assertTrue(first.radius > 0f)
    }

    @Test
    fun testStrokeInterpolation() {
        val engine = BrushEngine(BrushProperties(size = 20f, spacing = 0.2f, stabilizer = 0f))
        engine.startStroke(0f, 0f, 1f)
        val dabs = engine.addPoint(100f, 0f, 1f)

        // Over 100 pixels with radius 10 and spacing 0.2 (step = 4px), we should get around 25 dabs
        assertTrue(dabs.size >= 15)
        // Verify points move along X
        assertTrue(dabs.first().x > 0f)
        assertTrue(dabs.last().x <= 100f)
    }

    @Test
    fun testMicroMovementInterpolation() {
        // High frequency events (e.g. 240Hz stylus) where each event moves only 0.5px
        val engine = BrushEngine(BrushProperties(size = 20f, spacing = 0.15f, stabilizer = 0f))
        engine.startStroke(0f, 0f, 1f)

        var totalDabs = 0
        // Move 100px in 200 micro-steps of 0.5px each
        for (i in 1..200) {
            val dabs = engine.addPoint(i * 0.5f, 0f, 1f)
            totalDabs += dabs.size
        }

        // With radius 10 and spacing 0.15, stepSize is 3.0px. Over 100px, expect ~33 dabs
        assertTrue("Expected ~33 dabs across micro-movements but got $totalDabs", totalDabs in 30..35)
    }

    @Test
    fun testBrushPresetsApplyCorrectProperties() {
        val props = BrushProperties()
        props.applyPreset(app.goodboy13.milton.core.brush.BrushType.PEN)
        assertTrue(props.hardness > 0.90f)
        assertTrue(!props.isEraser)
        assertTrue(props.brushMode == 0)
        assertTrue(props.size == 30.0f)
        assertTrue(props.opacity == 1.0f)
        assertTrue(props.sizeBezierConfig.minPercent == 0.15f)
        assertTrue(props.opacityBezierConfig.minPercent == 1.0f)

        props.applyPreset(app.goodboy13.milton.core.brush.BrushType.PENCIL)
        assertEquals(0.70f, props.hardness, 0.001f)
        assertEquals(0.12f, props.spacing, 0.001f)
        assertTrue(!props.isEraser)
        assertTrue(props.brushMode == 1)
        assertEquals(24.0f, props.size, 0.001f)
        assertEquals(0.90f, props.opacity, 0.001f)
        assertEquals(0.40f, props.sizeBezierConfig.minPercent, 0.001f)
        assertEquals(0.35f, props.opacityBezierConfig.minPercent, 0.001f)

        props.applyPreset(app.goodboy13.milton.core.brush.BrushType.PAINTBRUSH)
        assertTrue(props.hardness == 0.70f)
        assertTrue(!props.isEraser)
        assertTrue(props.brushMode == 2)
        assertTrue(props.size == 250.0f)
        assertTrue(props.opacity == 0.60f)
        assertTrue(props.bezierConfig.minPercent == 0.45f)

        props.applyPreset(app.goodboy13.milton.core.brush.BrushType.ERASER)
        assertTrue(props.isEraser)
        assertTrue(props.brushMode == 0)
        assertTrue(props.size == 120.0f)
        assertTrue(props.sizeBezierConfig.minPercent == 0.45f)
        assertTrue(props.opacity == 1.0f)
        assertTrue(props.opacityBezierConfig.minPercent == 0.0f)
    }

    @Test
    fun testLinearOpacityCalculation() {
        val pencilProps = BrushProperties(
            opacity = 0.40f,
            opacityBezierConfig = app.goodboy13.milton.core.model.PressureCurveDefaults.LINEAR.copy(minPercent = 0.20f)
        )
        val engine = BrushEngine(pencilProps)
        val dabsLow = engine.startStroke(0f, 0f, 0.01f)
        // At near zero pressure, opacity should be minPercent (0.20), NOT 0.40 * 0.20 = 0.08
        assertEquals(0.20f, dabsLow.first().alpha, 0.02f)

        val dabsHigh = engine.startStroke(0f, 0f, 1.0f)
        // At full pressure, opacity should be max opacity (0.40)
        assertEquals(0.40f, dabsHigh.first().alpha, 0.02f)
    }

    @Test
    fun testDirectPressureResponseNoEma() {
        // Without EMA, a pressure step should immediately reflect on the next dab
        val engine = BrushEngine(BrushProperties(size = 20f, spacing = 0.5f, stabilizer = 0f))
        engine.startStroke(0f, 0f, 0.2f)
        val dabs = engine.addPoint(50f, 0f, 0.9f)

        assertTrue(dabs.isNotEmpty())
        // The last dab in the step should reach maximum radius corresponding to 0.9f pressure immediately
        val lastRadius = dabs.last().radius
        val expectedRadius = 10f * 0.9f
        assertTrue(
            "Expected dab radius near $expectedRadius, got $lastRadius",
            kotlin.math.abs(lastRadius - expectedRadius) < 1.0f
        )
    }

    @Test
    fun testStabilizerSmoothesMotionAndFlushesOnLift() {
        val engine = BrushEngine(BrushProperties(size = 20f, spacing = 0.1f, stabilizer = 0.5f))
        engine.startStroke(0f, 0f, 1f, 0L)

        // Abrupt jump to (100, 0) over 100ms
        val dabs = engine.addPoint(100f, 0f, 1f, 100L)
        assertTrue(dabs.isNotEmpty())
        val lastX = dabs.last().x
        assertTrue("Expected smoothed position < 80f, got $lastX", lastX < 80f)

        // Ending stroke flushes to the raw endpoint
        val endDabs = engine.endStroke()
        assertTrue(endDabs.isNotEmpty())
        assertTrue("End dab should approach raw endpoint 100f, got ${endDabs.last().x}", endDabs.last().x >= 90f)
    }

    @Test
    fun testDefaultStabilizerValues() {
        val props = BrushProperties()
        // Default tool is Pencil: 10%
        org.junit.Assert.assertEquals(0.10f, props.stabilizer, 0.001f)

        props.applyPreset(BrushType.PEN)
        org.junit.Assert.assertEquals(0.05f, props.stabilizer, 0.001f)

        props.applyPreset(BrushType.PENCIL)
        org.junit.Assert.assertEquals(0.10f, props.stabilizer, 0.001f)

        props.applyPreset(BrushType.PAINTBRUSH)
        org.junit.Assert.assertEquals(0.05f, props.stabilizer, 0.001f)

        props.applyPreset(BrushType.ERASER)
        org.junit.Assert.assertEquals(0.0f, props.stabilizer, 0.001f)
    }

    @Test
    fun testStabilizerRateInvariance() {
        // High polling rate (10 steps of 4ms = 40ms) vs 1 step of 40ms
        val engineFast = BrushEngine(BrushProperties(size = 20f, spacing = 0.1f, stabilizer = 0.15f))
        engineFast.startStroke(0f, 0f, 1f, 0L)
        var lastFastX = 0f
        for (i in 1..10) {
            val dabs = engineFast.addPoint(100f, 0f, 1f, i * 4L)
            if (dabs.isNotEmpty()) lastFastX = dabs.last().x
        }

        val engineSlow = BrushEngine(BrushProperties(size = 20f, spacing = 0.1f, stabilizer = 0.15f))
        engineSlow.startStroke(0f, 0f, 1f, 0L)
        val dabsSlow = engineSlow.addPoint(100f, 0f, 1f, 40L)
        val lastSlowX = if (dabsSlow.isNotEmpty()) dabsSlow.last().x else 0f

        // Positions should be closely aligned regardless of sampling frequency
        org.junit.Assert.assertEquals(lastSlowX, lastFastX, 2.0f)
    }

    @Test
    fun testTiltRadiusScaling() {
        val pencilProps = BrushProperties(
            brushType = BrushType.PENCIL,
            size = 20f,
            tilt = 0.50f,
            sizeBezierConfig = app.goodboy13.milton.core.model.PressureCurveDefaults.LINEAR.copy(minPercent = 1.0f),
            tiltBezierConfig = app.goodboy13.milton.core.model.PressureCurveDefaults.LINEAR.copy(minPercent = 0.0f, maxPercent = 1.0f)
        )
        val engine = BrushEngine(pencilProps)
        // Upright stylus: tilt = 0f
        val dabsUpright = engine.startStroke(0f, 0f, 1f, 0L, tilt = 0f)
        assertEquals(10f, dabsUpright.first().radius, 0.01f)

        // Fully tilted stylus: tilt = 1f
        // tiltResponse = 1.0f, tiltScale = 1.0 + 1.0 * (0.5 * 3.5) = 2.75f
        val dabsTilted = engine.startStroke(0f, 0f, 1f, 0L, tilt = 1.0f)
        val expectedRadius = 10f * (1.0f + 0.5f * 3.5f)
        assertEquals(expectedRadius, dabsTilted.first().radius, 0.01f)
    }

    @Test
    fun testDiscreteLeadGradeMapping() {
        assertEquals("HB", app.goodboy13.milton.ui.components.LEAD_GRADES[9])
        assertEquals("9H", app.goodboy13.milton.ui.components.LEAD_GRADES.first())
        assertEquals("9B", app.goodboy13.milton.ui.components.LEAD_GRADES.last())
        assertEquals(19, app.goodboy13.milton.ui.components.LEAD_GRADES.size)

        // HB should map to ~0.45f hardness
        val hbHardness = app.goodboy13.milton.ui.components.gradeToHardness(9)
        assertEquals(0.45f, hbHardness, 0.01f)

        // 2B (index 11)
        val b2Hardness = app.goodboy13.milton.ui.components.gradeToHardness(11)
        val b2Index = app.goodboy13.milton.ui.components.hardnessToGradeIndex(b2Hardness)
        assertEquals(11, b2Index)
    }

    @Test
    fun testNonAllocatingDestinationListDabs() {
        val engine = BrushEngine(BrushProperties(size = 20f, spacing = 0.2f))
        val dabs = mutableListOf<BrushDab>()

        engine.startStroke(0f, 0f, 1f, 0L, 0f, dabs)
        assertEquals(1, dabs.size)

        engine.addPoint(50f, 0f, 1f, 10L, 0f, dabs)
        assertTrue(dabs.size > 1)

        val beforeEnd = dabs.size
        engine.endStroke(dabs)
        assertTrue(dabs.size >= beforeEnd)
    }
}
