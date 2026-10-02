package app.goodboy13.milton

import app.goodboy13.milton.core.viewport.Viewport
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

    @Test
    fun testExportVisibleAreaScreenFramingAt90Degrees() {
        val viewport = Viewport().apply {
            updateScreenSize(2560, 1600)
            panX = 1280f
            panY = 800f
            zoom = 1.0f
            rotationDegrees = 90f
        }

        val p0 = viewport.screenToWorld(0f, 0f)
        val p1 = viewport.screenToWorld(2560f, 0f)
        val p2 = viewport.screenToWorld(2560f, 1600f)
        val p3 = viewport.screenToWorld(0f, 1600f)

        // At 90 deg rotation, screen top-left should be rotated 90 deg relative to center
        // Center is at (1280, 800) -> world (0, 0)
        val center = viewport.screenToWorld(1280f, 800f)
        assertEquals(0f, center.x, 0.01f)
        assertEquals(0f, center.y, 0.01f)

        // Center must be preserved
        val roundTripCenter = viewport.worldToScreen(0f, 0f)
        assertEquals(1280f, roundTripCenter.x, 0.01f)
        assertEquals(800f, roundTripCenter.y, 0.01f)

        // Verify that roundtrip screen -> world -> screen is exact for all 4 corners
        val rt0 = viewport.worldToScreen(p0.x, p0.y)
        assertEquals(0f, rt0.x, 0.01f)
        assertEquals(0f, rt0.y, 0.01f)

        val rt1 = viewport.worldToScreen(p1.x, p1.y)
        assertEquals(2560f, rt1.x, 0.01f)
        assertEquals(0f, rt1.y, 0.01f)

        val rt2 = viewport.worldToScreen(p2.x, p2.y)
        assertEquals(2560f, rt2.x, 0.01f)
        assertEquals(1600f, rt2.y, 0.01f)

        val rt3 = viewport.worldToScreen(p3.x, p3.y)
        assertEquals(0f, rt3.x, 0.01f)
        assertEquals(1600f, rt3.y, 0.01f)
    }

    @Test
    fun testExportVisibleAreaAspectMatchesScreen() {
        val viewport = Viewport().apply {
            updateScreenSize(2560, 1600)
            zoom = 0.5f // Zoomed out 2x
        }

        val rawWidth = (viewport.screenWidth / viewport.zoom).toInt()
        val rawHeight = (viewport.screenHeight / viewport.zoom).toInt()

        // 2560 / 0.5 = 5120, 1600 / 0.5 = 3200
        assertEquals(5120, rawWidth)
        assertEquals(3200, rawHeight)

        // Aspect ratio must identically equal screen aspect ratio
        val screenAspect = 2560f / 1600f
        val exportAspect = rawWidth.toFloat() / rawHeight.toFloat()
        assertEquals(screenAspect, exportAspect, 0.001f)
    }
}
