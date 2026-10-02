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

    @Test
    fun testHorizontalFlipScreenToWorldRoundTrip() {
        val viewport = Viewport().apply {
            updateScreenSize(1000, 1000)
            panX = 300f
            panY = 200f
            zoom = 1.2f
            rotationDegrees = 30f
            isFlippedHorizontally = true
        }

        val screenX = 250f
        val screenY = 650f

        val worldPoint = viewport.screenToWorld(screenX, screenY)
        val roundTripScreen = viewport.worldToScreen(worldPoint.x, worldPoint.y)

        assertEquals(screenX, roundTripScreen.x, 0.01f)
        assertEquals(screenY, roundTripScreen.y, 0.01f)
    }

    @Test
    fun testHorizontalFlipCenterlineInvariant() {
        val viewport = Viewport().apply {
            updateScreenSize(1000, 800)
            panX = 500f
            panY = 400f
            zoom = 1.0f
            rotationDegrees = 0f
            isFlippedHorizontally = false
        }

        // Center line is at x = 500
        val centerPt = viewport.screenToWorld(500f, 400f)

        viewport.isFlippedHorizontally = true
        val centerPtFlipped = viewport.screenToWorld(500f, 400f)

        // Point at center line must map to identical world coordinate
        assertEquals(centerPt.x, centerPtFlipped.x, 0.01f)
        assertEquals(centerPt.y, centerPtFlipped.y, 0.01f)
    }

    @Test
    fun testHorizontalFlipGesturePanPreserved() {
        val viewport = Viewport().apply {
            updateScreenSize(1000, 1000)
            panX = 500f
            panY = 500f
            zoom = 1.0f
            rotationDegrees = 0f
            isFlippedHorizontally = true
        }

        // Under horizontal flip, finger drags right (+50px on screen)
        // The image on screen should move right (+50px)
        val beforeScreenPt = viewport.worldToScreen(0f, 0f)
        viewport.applyGesture(
            prevFocalX = 400f,
            prevFocalY = 500f,
            curFocalX = 450f, // Dragged right by +50px
            curFocalY = 500f,
            zoomFactor = 1.0f,
            angleDelta = 0f
        )
        val afterScreenPt = viewport.worldToScreen(0f, 0f)

        assertEquals(beforeScreenPt.x + 50f, afterScreenPt.x, 0.01f)
        assertEquals(beforeScreenPt.y, afterScreenPt.y, 0.01f)
    }
}
