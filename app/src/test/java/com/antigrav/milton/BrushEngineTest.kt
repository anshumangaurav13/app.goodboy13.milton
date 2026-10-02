package com.antigrav.milton

import com.antigrav.milton.core.brush.BrushEngine
import com.antigrav.milton.core.brush.BrushProperties
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
        val engine = BrushEngine(BrushProperties(size = 20f, spacing = 0.2f))
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
        val engine = BrushEngine(BrushProperties(size = 20f, spacing = 0.15f))
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
        props.applyPreset(com.antigrav.milton.core.brush.BrushType.PEN)
        assertTrue(props.hardness > 0.90f)
        assertTrue(!props.isEraser)

        props.applyPreset(com.antigrav.milton.core.brush.BrushType.PENCIL)
        assertTrue(props.hardness < 0.50f)
        assertTrue(props.opacity < 0.80f)
        assertTrue(!props.isEraser)

        props.applyPreset(com.antigrav.milton.core.brush.BrushType.ROUND_BRUSH)
        assertTrue(props.hardness in 0.65f..0.75f)
        assertTrue(!props.isEraser)

        props.applyPreset(com.antigrav.milton.core.brush.BrushType.MARKER)
        assertTrue(props.opacity < 0.50f)
        assertTrue(!props.isEraser)

        props.applyPreset(com.antigrav.milton.core.brush.BrushType.ERASER)
        assertTrue(props.isEraser)
    }

    @Test
    fun testDirectPressureResponseNoEma() {
        // Without EMA, a pressure step should immediately reflect on the next dab
        val engine = BrushEngine(BrushProperties(size = 20f, spacing = 0.5f))
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
}
