package app.goodboy13.milton.core.liquify

import app.goodboy13.milton.core.constants.CanvasConstants
import app.goodboy13.milton.core.model.Vec2
import app.goodboy13.milton.core.model.WorldRect
import app.goodboy13.milton.core.native.MiltonNative
import app.goodboy13.milton.core.viewport.Viewport
import java.io.Closeable

/**
 * High-performance 2D deformation mesh for Liquify operations.
 * Constrained strictly to the bounding box and boundary polygon of a Lasso selection.
 * Fully backed by native Rust (`milton_core::liquify::LiquifyMesh`) for zero GC allocations.
 */
class LiquifyMesh(
    val srcBounds: WorldRect,
    val polygon: List<Vec2>,
    val gridCols: Int = CanvasConstants.LIQUIFY_GRID_SIZE,
    val gridRows: Int = CanvasConstants.LIQUIFY_GRID_SIZE
) : Closeable {
    val vertexCount = (gridCols + 1) * (gridRows + 1)
    val width = srcBounds.width
    val height = srcBounds.height

    // Original local coordinates relative to srcBounds top-left [0..width, 0..height]
    val initLocalX = FloatArray(vertexCount)
    val initLocalY = FloatArray(vertexCount)

    // Current deformed world positions
    val worldX = FloatArray(vertexCount)
    val worldY = FloatArray(vertexCount)

    // Polygon boundary weights [0.0..1.0] to anchor vertices outside/at the lasso edge
    val maskWeights = FloatArray(vertexCount)

    var nativePtr: Long = 0L
        private set

    init {
        check(MiltonNative.isLoaded) { "MiltonNative core library must be loaded for LiquifyMesh" }
        val polyX = FloatArray(polygon.size) { polygon[it].x }
        val polyY = FloatArray(polygon.size) { polygon[it].y }
        nativePtr = MiltonNative.liquifyMeshCreate(
            srcBounds.left, srcBounds.top, srcBounds.right, srcBounds.bottom,
            polyX, polyY, gridCols, gridRows
        )
        check(nativePtr != 0L) { "Failed to create native LiquifyMesh instance" }
        MiltonNative.liquifyMeshGetVertices(nativePtr, worldX, worldY, maskWeights)
        for (i in 0 until vertexCount) {
            initLocalX[i] = worldX[i] - srcBounds.left
            initLocalY[i] = worldY[i] - srcBounds.top
        }
    }

    fun reset() {
        if (nativePtr != 0L) {
            MiltonNative.liquifyMeshReset(nativePtr)
            MiltonNative.liquifyMeshGetVertices(nativePtr, worldX, worldY, null)
        }
    }

    override fun close() {
        destroy()
    }

    fun destroy() {
        if (nativePtr != 0L) {
            MiltonNative.liquifyMeshDestroy(nativePtr)
            nativePtr = 0L
        }
    }

    /**
     * Applies a continuous stylus drag segment from (x1, y1) to (x2, y2).
     */
    fun applyStroke(
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        radius: Float,
        strength: Float,
        mode: LiquifyMode
    ) {
        if (radius <= 0.001f || strength <= 0.001f || nativePtr == 0L) return
        MiltonNative.liquifyMeshApplyStroke(
            nativePtr, x1, y1, x2, y2, radius, strength, mode.ordinal
        )
        MiltonNative.liquifyMeshGetVertices(nativePtr, worldX, worldY, null)
    }

    /**
     * Projects world vertex coordinates to screen space for Canvas.drawBitmapMesh.
     */
    fun computeScreenVerts(viewport: Viewport, outArray: FloatArray) {
        var vi = 0
        for (i in 0 until vertexCount) {
            val sc = viewport.worldToScreen(worldX[i], worldY[i])
            outArray[vi] = sc.x
            outArray[vi + 1] = sc.y
            vi += 2
        }
    }

    /**
     * Converts world vertex coordinates to local patch space [0..width, 0..height] for baking.
     */
    fun computeLocalWarpedVerts(outArray: FloatArray) {
        if (nativePtr != 0L) {
            MiltonNative.liquifyMeshComputeLocalWarpedVerts(nativePtr, outArray)
        }
    }
}
