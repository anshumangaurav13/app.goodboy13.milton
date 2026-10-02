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
}
