package app.goodboy13.milton

import app.goodboy13.milton.core.liquify.LiquifyMesh
import app.goodboy13.milton.core.liquify.LiquifyMode
import app.goodboy13.milton.core.model.Vec2
import app.goodboy13.milton.core.model.WorldRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class LiquifyMeshTest {

    @Test
    fun testMeshInitializationAndGridDimensions() {
        val bounds = WorldRect(0f, 0f, 100f, 100f)
        val polygon = listOf(
            Vec2(10f, 10f),
            Vec2(90f, 10f),
            Vec2(90f, 90f),
            Vec2(10f, 90f)
        )
        val mesh = LiquifyMesh(bounds, polygon, gridCols = 10, gridRows = 10)

        // (10+1) * (10+1) = 121 vertices
        assertEquals(121, mesh.vertexCount)
        assertEquals(100f, mesh.width, 0.01f)
        assertEquals(100f, mesh.height, 0.01f)

        // Corner vertices (0,0) and (100,100) are outside the polygon [10..90, 10..90]
        assertEquals(0f, mesh.maskWeights[0], 0.001f)
        assertEquals(0f, mesh.maskWeights[120], 0.001f)

        // Center vertex (50, 50) is inside the polygon
        val centerIdx = 5 * 11 + 5
        assertEquals(50f, mesh.worldX[centerIdx], 0.01f)
        assertEquals(50f, mesh.worldY[centerIdx], 0.01f)
        assertTrue("Center vertex must have non-zero weight", mesh.maskWeights[centerIdx] > 0.5f)
    }

    @Test
    fun testPushStrokeMovesVerticesStrictlyInsideLasso() {
        val bounds = WorldRect(0f, 0f, 100f, 100f)
        val polygon = listOf(
            Vec2(20f, 20f),
            Vec2(80f, 20f),
            Vec2(80f, 80f),
            Vec2(20f, 80f)
        )
        val mesh = LiquifyMesh(bounds, polygon, gridCols = 10, gridRows = 10)

        val centerIdx = 5 * 11 + 5
        val initCenterX = mesh.worldX[centerIdx]
        val initCenterY = mesh.worldY[centerIdx]

        // Push stroke to the right from (45, 50) to (55, 50)
        mesh.applyStroke(
            x1 = 45f, y1 = 50f,
            x2 = 55f, y2 = 50f,
            radius = 25f,
            strength = 0.8f,
            mode = LiquifyMode.PUSH
        )

        // Center vertex should have moved right (+X)
        assertTrue("Center vertex X must increase after push right", mesh.worldX[centerIdx] > initCenterX)
        assertEquals(initCenterY, mesh.worldY[centerIdx], 0.1f)

        // Corner vertex outside lasso (0, 0) MUST NOT move
        assertEquals(0f, mesh.worldX[0], 0.001f)
        assertEquals(0f, mesh.worldY[0], 0.001f)
    }

    @Test
    fun testBloatAndPinchOpposites() {
        val bounds = WorldRect(0f, 0f, 100f, 100f)
        val polygon = listOf(
            Vec2(10f, 10f),
            Vec2(90f, 10f),
            Vec2(90f, 90f),
            Vec2(10f, 90f)
        )
        val bloatMesh = LiquifyMesh(bounds, polygon, gridCols = 10, gridRows = 10)
        val pinchMesh = LiquifyMesh(bounds, polygon, gridCols = 10, gridRows = 10)

        // Point to the right of center (e.g. index row 5, col 6 -> x=60, y=50)
        val targetIdx = 5 * 11 + 6
        val origX = bloatMesh.worldX[targetIdx]

        // Bloat at center (50, 50)
        bloatMesh.applyStroke(
            x1 = 50f, y1 = 50f,
            x2 = 50f, y2 = 50f,
            radius = 30f,
            strength = 0.7f,
            mode = LiquifyMode.BLOAT
        )
        // Vertex should expand outwards (+X from center)
        assertTrue("Bloat should push vertex outwards", bloatMesh.worldX[targetIdx] > origX)

        // Pinch at center (50, 50)
        pinchMesh.applyStroke(
            x1 = 50f, y1 = 50f,
            x2 = 50f, y2 = 50f,
            radius = 30f,
            strength = 0.7f,
            mode = LiquifyMode.PINCH
        )
        // Vertex should contract inwards (-X towards center)
        assertTrue("Pinch should pull vertex inwards", pinchMesh.worldX[targetIdx] < origX)
    }

    @Test
    fun testTwirlCwAndCcwRotations() {
        val bounds = WorldRect(0f, 0f, 100f, 100f)
        val polygon = listOf(
            Vec2(10f, 10f),
            Vec2(90f, 10f),
            Vec2(90f, 90f),
            Vec2(10f, 90f)
        )
        val cwMesh = LiquifyMesh(bounds, polygon, gridCols = 10, gridRows = 10)
        val ccwMesh = LiquifyMesh(bounds, polygon, gridCols = 10, gridRows = 10)

        // Point directly to right of center (60, 50)
        val targetIdx = 5 * 11 + 6
        val origY = cwMesh.worldY[targetIdx] // 50

        // CW rotation at center (50, 50): point at (+dx, 0) should rotate clockwise (downwards, +Y in screen space)
        cwMesh.applyStroke(
            x1 = 50f, y1 = 50f,
            x2 = 50f, y2 = 50f,
            radius = 30f,
            strength = 0.8f,
            mode = LiquifyMode.TWIRL_CW
        )
        assertTrue("CW twirl should displace right neighbor downwards (+Y)", cwMesh.worldY[targetIdx] > origY)

        // CCW rotation at center (50, 50): point at (+dx, 0) should rotate counter-clockwise (upwards, -Y)
        ccwMesh.applyStroke(
            x1 = 50f, y1 = 50f,
            x2 = 50f, y2 = 50f,
            radius = 30f,
            strength = 0.8f,
            mode = LiquifyMode.TWIRL_CCW
        )
        assertTrue("CCW twirl should displace right neighbor upwards (-Y)", ccwMesh.worldY[targetIdx] < origY)
    }

    @Test
    fun testResetRestoresInitialMesh() {
        val bounds = WorldRect(0f, 0f, 100f, 100f)
        val polygon = listOf(
            Vec2(10f, 10f),
            Vec2(90f, 10f),
            Vec2(90f, 90f),
            Vec2(10f, 90f)
        )
        val mesh = LiquifyMesh(bounds, polygon, gridCols = 10, gridRows = 10)
        val centerIdx = 5 * 11 + 5

        mesh.applyStroke(40f, 40f, 60f, 60f, 30f, 1f, LiquifyMode.PUSH)
        assertTrue("Vertex moved", mesh.worldX[centerIdx] != 50f)

        mesh.reset()
        assertEquals(50f, mesh.worldX[centerIdx], 0.001f)
        assertEquals(50f, mesh.worldY[centerIdx], 0.001f)
    }
}
