package app.goodboy13.milton

import app.goodboy13.milton.core.model.Vec2
import app.goodboy13.milton.core.model.WorldRect
import app.goodboy13.milton.core.selection.SelectionState
import app.goodboy13.milton.core.selection.SelectionTransformManager
import app.goodboy13.milton.core.selection.TransformSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionTransformTest {

    @Test
    fun testInitialStateIsIdle() {
        val manager = SelectionTransformManager()
        assertEquals(SelectionState.Idle, manager.state.value)
    }

    @Test
    fun testDrawingLassoAppendsPoints() {
        val manager = SelectionTransformManager()
        manager.startLasso(Vec2(10f, 10f))

        val s1 = manager.state.value
        assertTrue(s1 is SelectionState.DrawingLasso)
        assertEquals(1, (s1 as SelectionState.DrawingLasso).points.size)

        manager.addLassoPoint(Vec2(50f, 50f))
        val s2 = manager.state.value as SelectionState.DrawingLasso
        assertEquals(2, s2.points.size)

        manager.cancelLasso()
        assertEquals(SelectionState.Idle, manager.state.value)
    }

    @Test
    fun testTransformSessionDestBoundsCalculation() {
        val srcBounds = WorldRect(0f, 0f, 100f, 100f)
        val session = TransformSession(
            polygon = listOf(Vec2(0f, 0f), Vec2(100f, 0f), Vec2(100f, 100f), Vec2(0f, 100f)),
            srcBounds = srcBounds,
            targetLayerIds = setOf(1L),
            originalTiles = emptyMap(),
            layerPatches = emptyMap(),
            translationX = 20f,
            translationY = 30f,
            scaleX = 1f,
            scaleY = 1f,
            rotationRad = 0f
        )

        val bounds = session.calculateDestBounds()
        assertEquals(20f, bounds.left, 0.01f)
        assertEquals(30f, bounds.top, 0.01f)
        assertEquals(120f, bounds.right, 0.01f)
        assertEquals(130f, bounds.bottom, 0.01f)
    }

    @Test
    fun testTransformSessionScaling() {
        val srcBounds = WorldRect(0f, 0f, 100f, 100f)
        val session = TransformSession(
            polygon = listOf(Vec2(0f, 0f), Vec2(100f, 0f), Vec2(100f, 100f), Vec2(0f, 100f)),
            srcBounds = srcBounds,
            targetLayerIds = setOf(1L),
            originalTiles = emptyMap(),
            layerPatches = emptyMap(),
            scaleX = 2f,
            scaleY = 2f
        )

        // Pivot is center (50, 50). Scaled by 2x: size becomes 200x200, centered at (50, 50)
        // Left = 50 - 100 = -50, Right = 50 + 100 = 150
        val bounds = session.calculateDestBounds()
        assertEquals(-50f, bounds.left, 0.01f)
        assertEquals(-50f, bounds.top, 0.01f)
        assertEquals(150f, bounds.right, 0.01f)
        assertEquals(150f, bounds.bottom, 0.01f)
    }
}
