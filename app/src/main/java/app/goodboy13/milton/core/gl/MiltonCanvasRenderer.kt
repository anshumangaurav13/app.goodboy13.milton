package app.goodboy13.milton.core.gl

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.graphics.lowlatency.BufferInfo
import androidx.graphics.lowlatency.GLFrontBufferedRenderer
import androidx.graphics.opengl.egl.EGLManager
import app.goodboy13.milton.core.brush.BrushDab
import app.goodboy13.milton.core.history.UndoManager
import app.goodboy13.milton.core.layer.LayerManager
import app.goodboy13.milton.core.memory.DirectBufferPool
import app.goodboy13.milton.core.tile.TileMap
import app.goodboy13.milton.core.viewport.Viewport
import app.goodboy13.milton.ui.MiltonCanvasView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

data class DabPacket(
    val dabs: List<BrushDab> = emptyList()
)

/**
 * High-level bridge and facade implementing [GLFrontBufferedRenderer.Callback].
 * Coordinates low-latency front buffering and multi-buffering by delegating
 * pure graphics pipeline operations to [GlSceneRenderer] and domain business
 * logic to [CanvasEngine].
 */
class MiltonCanvasRenderer(
    val viewport: Viewport = Viewport(),
    val layerManager: LayerManager = LayerManager()
) : GLFrontBufferedRenderer.Callback<DabPacket> {

    companion object {
        private const val TAG = "MiltonCanvasRenderer"
    }

    val sceneRenderer = GlSceneRenderer()
    val engine = CanvasEngine(
        layerManager = layerManager,
        viewport = viewport,
        undoManager = UndoManager(),
        sceneRenderer = sceneRenderer
    )

    val undoManager: UndoManager get() = engine.undoManager
    val undoRedoState get() = engine.undoRedoState
    val tileMap: TileMap get() = layerManager.activeLayer.tileMap

    val dabShader get() = sceneRenderer.dabShader
    val tileBlitShader get() = sceneRenderer.tileBlitShader
    val thumbnailRenderer get() = sceneRenderer.thumbnailRenderer
    val strokeCompositeShader get() = sceneRenderer.strokeCompositeShader

    // Clean neutral canvas background color (paper default)
    var backgroundColorRgb: Int = Color.rgb(248, 248, 247)

    var onLayerThumbnailUpdated: ((layerId: Long, bitmap: Bitmap?) -> Unit)?
        get() = engine.onLayerThumbnailUpdated
        set(value) { engine.onLayerThumbnailUpdated = value }

    var onColorPicked: ((Int) -> Unit)?
        get() = engine.onColorPicked
        set(value) { engine.onColorPicked = value }

    var isThumbnailCaptureEnabled: Boolean
        get() = engine.isThumbnailCaptureEnabled
        set(value) { engine.isThumbnailCaptureEnabled = value }

    fun runOnGlThread(block: () -> Unit) {
        engine.enqueueCommand(GlRenderCommand.ExecuteTask(block))
    }

    fun queueDabs(dabs: List<BrushDab>) {
        engine.enqueueCommand(GlRenderCommand.SubmitDabs(dabs, false))
    }

    fun clearPendingDabs() {
        engine.commandQueue.clear()
    }

    fun markStrokeFinished() {
        engine.markStrokeFinished()
    }

    fun requestUndo() {
        engine.enqueueCommand(GlRenderCommand.Undo)
    }

    fun requestRedo() {
        engine.enqueueCommand(GlRenderCommand.Redo)
    }

    fun requestColorPick(worldX: Float, worldY: Float) {
        engine.enqueueCommand(GlRenderCommand.PickColor(worldX, worldY))
    }

    fun requestTrimBudget() {
        engine.requestTrimBudget()
    }

    fun deleteLayer(canvasView: MiltonCanvasView, layerId: Long) {
        if (!layerManager.canDeleteLayer()) return
        engine.enqueueCommand(
            GlRenderCommand.DeleteLayer(layerId) {
                canvasView.post { canvasView.requestRedraw() }
            }
        )
        canvasView.requestRedraw()
    }

    fun clearLayer(canvasView: MiltonCanvasView, layerId: Long) {
        engine.enqueueCommand(
            GlRenderCommand.ClearLayer(layerId) {
                canvasView.post { canvasView.requestRedraw() }
            }
        )
        canvasView.requestRedraw()
    }

    fun pickColorAt(worldX: Float, worldY: Float): Int {
        return sceneRenderer.pickColorAt(layerManager.layers, worldX, worldY, backgroundColorRgb)
    }

    override fun onDrawFrontBufferedLayer(
        eglManager: EGLManager,
        width: Int,
        height: Int,
        bufferInfo: BufferInfo,
        transform: FloatArray,
        param: DabPacket
    ) {
        engine.renderFrontBuffer(param.dabs, bufferInfo, transform)
    }

    override fun onDrawMultiBufferedLayer(
        eglManager: EGLManager,
        width: Int,
        height: Int,
        bufferInfo: BufferInfo,
        transform: FloatArray,
        params: Collection<DabPacket>
    ) {
        engine.processFrame(bufferInfo, transform, backgroundColorRgb)
    }

    /**
     * GPU-accelerated export of the visible canvas area at canvas resolution.
     * Guaranteed zero seams, identical color mixing, and orientation as on-screen.
     */
    fun renderVisibleAreaGl(
        canvasView: MiltonCanvasView,
        maxDimension: Int = 8192,
        timeoutMs: Long = 2000
    ): Bitmap? {
        val screenW = viewport.screenWidth.toFloat()
        val screenH = viewport.screenHeight.toFloat()
        if (screenW <= 1f || screenH <= 1f) return null

        val rawWidth = (screenW / viewport.zoom).roundToInt().coerceAtLeast(64)
        val rawHeight = (screenH / viewport.zoom).roundToInt().coerceAtLeast(64)

        val scale = if (rawWidth > maxDimension || rawHeight > maxDimension) {
            val maxRaw = maxOf(rawWidth, rawHeight).toFloat()
            maxDimension / maxRaw
        } else {
            1.0f
        }

        val exportWidth = (rawWidth * scale).roundToInt().coerceIn(64, maxDimension)
        val exportHeight = (rawHeight * scale).roundToInt().coerceIn(64, maxDimension)

        val latch = CountDownLatch(1)
        var resultBitmap: Bitmap? = null

        runOnGlThread {
            try {
                resultBitmap = sceneRenderer.renderOffscreen(
                    layers = layerManager.layers,
                    viewport = viewport,
                    backgroundColorRgb = backgroundColorRgb,
                    exportWidth = exportWidth,
                    exportHeight = exportHeight
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed GL offscreen export", e)
            } finally {
                latch.countDown()
            }
        }
        canvasView.requestRedraw()

        val ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!ok) {
            Log.w(TAG, "GL offscreen export timed out after ${timeoutMs}ms, falling back to CPU")
        }
        return resultBitmap
    }

    fun cleanup() {
        sceneRenderer.releaseGl()
        layerManager.releaseAll()
        GlTexturePool.clear()
        DirectBufferPool.clear()
    }
}
