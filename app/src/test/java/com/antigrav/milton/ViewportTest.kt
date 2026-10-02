package com.antigrav.milton

import com.antigrav.milton.core.viewport.Viewport
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewportTest {

    @Test
    fun testScreenToWorldAndBack() {
        val viewport = Viewport().apply {
            updateScreenSize(1000, 1000)
            panX = 200f
            panY = 150f
            zoom = 1.5f
            rotationDegrees = 0f
        }

        val screenX = 500f
        val screenY = 400f

        val worldPoint = viewport.screenToWorld(screenX, screenY)
        val roundTripScreen = viewport.worldToScreen(worldPoint.x, worldPoint.y)

        assertEquals(screenX, roundTripScreen.x, 0.01f)
        assertEquals(screenY, roundTripScreen.y, 0.01f)
    }

    @Test
    fun testVisibleBoundsCalculation() {
        val viewport = Viewport().apply {
            updateScreenSize(800, 600)
            panX = 0f
            panY = 0f
            zoom = 1.0f
            rotationDegrees = 0f
        }

        val bounds = viewport.getVisibleWorldBounds()
        assertEquals(0f, bounds.left, 0.01f)
        assertEquals(0f, bounds.top, 0.01f)
        assertEquals(800f, bounds.right, 0.01f)
        assertEquals(600f, bounds.bottom, 0.01f)
    }

    @Test
    fun testRotationInvariance() {
        val viewport = Viewport().apply {
            updateScreenSize(1000, 1000)
            panX = 500f
            panY = 500f
            zoom = 2.0f
            rotationDegrees = 45f
        }

        val screenX = 600f
        val screenY = 700f

        val worldPoint = viewport.screenToWorld(screenX, screenY)
        val roundTripScreen = viewport.worldToScreen(worldPoint.x, worldPoint.y)

        assertEquals(screenX, roundTripScreen.x, 0.01f)
        assertEquals(screenY, roundTripScreen.y, 0.01f)
    }

    @Test
    fun testDefaultZoomIsFiftyPercent() {
        val viewport = Viewport()
        assertEquals(0.5f, viewport.zoom, 0.001f)
        viewport.zoom = 2.0f
        viewport.reset()
        assertEquals(0.5f, viewport.zoom, 0.001f)
    }
}
