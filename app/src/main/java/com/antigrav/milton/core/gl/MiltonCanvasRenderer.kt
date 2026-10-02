package com.antigrav.milton.core.gl

import android.graphics.Color
import android.opengl.GLES30
import android.opengl.Matrix
import android.util.Log
import androidx.graphics.lowlatency.BufferInfo
import androidx.graphics.lowlatency.GLFrontBufferedRenderer
import androidx.graphics.opengl.egl.EGLManager
import com.antigrav.milton.core.brush.BrushDab
import com.antigrav.milton.core.layer.LayerManager
import com.antigrav.milton.core.tile.RasterTile
import com.antigrav.milton.core.tile.TileCoord
import com.antigrav.milton.core.tile.TileMap
import com.antigrav.milton.core.history.UndoManager
import com.antigrav.milton.core.viewport.Viewport
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.floor
import kotlin.math.roundToInt

data class DabPacket(
    val dabs: List<BrushDab> = emptyList()
)

/**
 * OpenGL ES 3.0 renderer coordinating the sparse multi-layer tile maps,
 * low-latency front buffering, and camera viewport.
 */
class MiltonCanvasRenderer(
    val viewport: Viewport = Viewport(),
    val layerManager: LayerManager = LayerManager()
) : GLFrontBufferedRenderer.Callback<DabPacket> {

    val tileMap: TileMap get() = layerManager.activeLayer.tileMap

    companion object {
        private const val TAG = "MiltonCanvasRenderer"
    }

    private val dabShader = DabShader()
    private val tileBlitShader = TileBlitShader()
    private val thumbnailRenderer = LayerThumbnailRenderer()

    private val tileOrthoMatrix = FloatArray(16)
    private val screenOrthoMatrix = FloatArray(16)
    private val screenProjectionMatrix = FloatArray(16)
    private val worldToViewMatrix = FloatArray(16)
    private val finalMvpMatrix = FloatArray(16)

    private val pendingDabsForCommit = ConcurrentLinkedQueue<BrushDab>()
    val undoManager = UndoManager()
    private val pendingUndoCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val pendingRedoCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val pendingTrimBudget = AtomicBoolean(false)
    private val pendingStrokeFinished = AtomicBoolean(false)
    private val pendingColorPick = java.util.concurrent.atomic.AtomicReference<android.graphics.PointF?>()

    var onLayerThumbnailUpdated: ((layerId: Long, bitmap: android.graphics.Bitmap?) -> Unit)? = null
    var onColorPicked: ((Int) -> Unit)? = null
    var isThumbnailCaptureEnabled: Boolean = true

    fun requestUndo() {
        pendingUndoCount.incrementAndGet()
    }

    fun requestRedo() {
        pendingRedoCount.incrementAndGet()
    }

    fun markStrokeFinished() {
        pendingStrokeFinished.set(true)
    }

    fun requestColorPick(worldX: Float, worldY: Float) {
        pendingColorPick.set(android.graphics.PointF(worldX, worldY))
    }

    fun requestTrimBudget() {
        pendingTrimBudget.set(true)
    }

    private var isGlInitialized = false

    // Clean neutral canvas background color (paper default)
    var backgroundColorRgb: Int = Color.rgb(248, 248, 247)

    init {
        // Tile FBO orthographic projection: [0..512, 0..512] -> NDC with top-left origin (Y down)
        Matrix.orthoM(
            tileOrthoMatrix, 0,
            0f, TileCoord.TILE_SIZE.toFloat(),
            TileCoord.TILE_SIZE.toFloat(), 0f,
            -1f, 1f
        )
    }

    private fun ensureGlInitialized() {
        if (!isGlInitialized) {
            dabShader.initGl()
            tileBlitShader.initGl()
            thumbnailRenderer.initGl()
            isGlInitialized = true
            Log.d(TAG, "OpenGL ES shaders and VAOs initialized successfully")
        }
    }

    private fun computeFinalMvpMatrix(bufferInfo: BufferInfo, transform: FloatArray): FloatArray {
        // 1. screenOrthoMatrix: maps buffer pixels [0..bufW, 0..bufH] to buffer NDC [-1, 1]
        Matrix.orthoM(
            screenOrthoMatrix, 0,
            0f, bufferInfo.width.toFloat(),
            0f, bufferInfo.height.toFloat(),
            -1f, 1f
        )
        // 2. screenProjectionMatrix: maps view pixels -> buffer pixels -> buffer NDC
        Matrix.multiplyMM(screenProjectionMatrix, 0, screenOrthoMatrix, 0, transform, 0)

        // 3. worldToViewMatrix: maps canvas world coordinates -> view pixels
        Matrix.setIdentityM(worldToViewMatrix, 0)
        if (viewport.isFlippedHorizontally) {
            val cx = viewport.screenWidth * 0.5f
            Matrix.translateM(worldToViewMatrix, 0, cx, 0f, 0f)
            Matrix.scaleM(worldToViewMatrix, 0, -1f, 1f, 1f)
            Matrix.translateM(worldToViewMatrix, 0, -cx, 0f, 0f)
        }
        Matrix.translateM(worldToViewMatrix, 0, viewport.panX, viewport.panY, 0f)
        Matrix.scaleM(worldToViewMatrix, 0, viewport.zoom, viewport.zoom, 1.0f)
        if (viewport.rotationDegrees != 0f) {
            Matrix.rotateM(worldToViewMatrix, 0, viewport.rotationDegrees, 0f, 0f, 1f)
        }

        // 4. Final MVP: world coordinates -> buffer NDC
        Matrix.multiplyMM(finalMvpMatrix, 0, screenProjectionMatrix, 0, worldToViewMatrix, 0)
        return finalMvpMatrix
    }

    fun queueDabs(dabs: List<BrushDab>) {
        pendingDabsForCommit.addAll(dabs)
    }

    fun clearPendingDabs() {
        pendingDabsForCommit.clear()
    }

    override fun onDrawFrontBufferedLayer(
        eglManager: EGLManager,
        width: Int,
        height: Int,
        bufferInfo: BufferInfo,
        transform: FloatArray,
        param: DabPacket
    ) {
        ensureGlInitialized()
        if (param.dabs.isEmpty()) return

        val activeLayer = layerManager.activeLayer
        if (!activeLayer.isVisible || activeLayer.opacity <= 0.001f) return

        viewport.updateScreenSize(width, height)

        // Render dabs directly to the low-latency front buffer for instantaneous visual feedback
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bufferInfo.frameBufferId)
        GLES30.glViewport(0, 0, bufferInfo.width, bufferInfo.height)

        if (dabShader.usesFramebufferFetch) {
            GLES30.glDisable(GLES30.GL_BLEND)
        } else {
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFuncSeparate(
                GLES30.GL_ONE,
                GLES30.GL_ONE_MINUS_SRC_ALPHA,
                GLES30.GL_ONE,
                GLES30.GL_ONE_MINUS_SRC_ALPHA
            )
        }

        val mvp = computeFinalMvpMatrix(bufferInfo, transform)
        val activeOpacity = activeLayer.opacity
        for (dab in param.dabs) {
            if (dab.isEraser) continue // Never draw eraser dabs into front buffer overlay
            dabShader.renderDab(
                centerX = dab.x,
                centerY = dab.y,
                radius = dab.radius,
                colorRgb = dab.colorRgb,
                alpha = dab.alpha * activeOpacity,
                hardness = dab.hardness,
                brushMode = dab.brushMode,
                pressure = dab.pressure,
                projectionMatrix = mvp,
                isEraser = false
            )
        }
        if (dabShader.usesFramebufferFetch) {
            GLES30.glEnable(GLES30.GL_BLEND)
        }
    }

    override fun onDrawMultiBufferedLayer(
        eglManager: EGLManager,
        width: Int,
        height: Int,
        bufferInfo: BufferInfo,
        transform: FloatArray,
        params: Collection<DabPacket>
    ) {
        ensureGlInitialized()
        viewport.updateScreenSize(width, height)

        // 0. Handle requested undo / redo on the GL thread
        var undos = pendingUndoCount.getAndSet(0)
        val hadUndo = undos > 0
        while (undos > 0) {
            undoManager.undo(layerManager)
            undos--
        }
        var redos = pendingRedoCount.getAndSet(0)
        val hadRedo = redos > 0
        while (redos > 0) {
            undoManager.redo(layerManager)
            redos--
        }

        // 1. Drain and stamp all accumulated stroke dabs into active layer tile FBOs
        val dabsToStamp = mutableListOf<BrushDab>()
        while (true) {
            val dab = pendingDabsForCommit.poll() ?: break
            dabsToStamp.add(dab)
        }
        val isStrokeDone = pendingStrokeFinished.compareAndSet(true, false)
        var capturedLayerId = 0L
        if (hadUndo || hadRedo || isStrokeDone) {
            capturedLayerId = layerManager.activeLayer.id
        }
        if (dabsToStamp.isNotEmpty()) {
            val activeLayer = layerManager.activeLayer
            capturedLayerId = activeLayer.id
            val affectedTiles = mutableSetOf<RasterTile>()
            val tileSize = TileCoord.TILE_SIZE.toFloat()
            for (dab in dabsToStamp) {
                val minTx = floor((dab.x - dab.radius) / tileSize).toInt()
                val maxTx = floor((dab.x + dab.radius) / tileSize).toInt()
                val minTy = floor((dab.y - dab.radius) / tileSize).toInt()
                val maxTy = floor((dab.y + dab.radius) / tileSize).toInt()
                for (ty in minTy..maxTy) {
                    for (tx in minTx..maxTx) {
                        val tile = activeLayer.tileMap.getOrCreateTile(tx, ty)
                        tile.ensureResident(activeLayer.tileMap.cacheDir)
                        tile.hasContent = true
                        affectedTiles.add(tile)
                    }
                }
            }
            undoManager.capturePreStrokeTiles(activeLayer.id, affectedTiles)
            stampDabsIntoTiles(activeLayer.tileMap, dabsToStamp)
            if (isStrokeDone) {
                undoManager.commitStroke(activeLayer.id, activeLayer.tileMap)
                requestTrimBudget()
            }
        } else if (isStrokeDone) {
            val activeLayer = layerManager.activeLayer
            undoManager.commitStroke(activeLayer.id, activeLayer.tileMap)
            requestTrimBudget()
        }

        // 2. Clear backbuffer with background paper color
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bufferInfo.frameBufferId)
        GLES30.glViewport(0, 0, bufferInfo.width, bufferInfo.height)

        val r = Color.red(backgroundColorRgb) / 255.0f
        val g = Color.green(backgroundColorRgb) / 255.0f
        val b = Color.blue(backgroundColorRgb) / 255.0f
        GLES30.glClearColor(r, g, b, 1.0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        // 3. Query and blit all visible tiles across all visible layers bottom-to-top
        val visibleBounds = viewport.getVisibleWorldBounds()
        val allLayers = layerManager.layers

        val mvp = computeFinalMvpMatrix(bufferInfo, transform)
        tileBlitShader.begin(mvp)

        for (layer in allLayers) {
            if (!layer.isVisible || layer.opacity <= 0.001f) continue
            val visibleTiles = layer.tileMap.getVisibleTiles(visibleBounds)
            if (visibleTiles.isEmpty()) continue

            tileBlitShader.setOpacity(layer.opacity)
            for (tile in visibleTiles) {
                if (!tile.isInitialized || !tile.hasContent) continue
                tileBlitShader.renderTile(
                    worldLeft = tile.coord.worldLeft,
                    worldTop = tile.coord.worldTop,
                    textureId = tile.textureId
                )
            }
        }
        tileBlitShader.end()

        // 4. Capture thumbnail offscreen only when a stroke is fully committed or on undo/redo
        if (capturedLayerId != 0L && isThumbnailCaptureEnabled && (isStrokeDone || hadUndo || hadRedo)) {
            val layer = layerManager.getLayer(capturedLayerId)
            if (layer != null) {
                val thumb = thumbnailRenderer.captureLayerThumbnail(
                    layer = layer,
                    visibleBounds = visibleBounds,
                    tileBlitShader = tileBlitShader,
                    restoreFboId = bufferInfo.frameBufferId,
                    restoreWidth = bufferInfo.width,
                    restoreHeight = bufferInfo.height
                )
                onLayerThumbnailUpdated?.invoke(capturedLayerId, thumb)
            }
        }

        // 5. Eyedropper pixel sampling request
        val pickPt = pendingColorPick.getAndSet(null)
        if (pickPt != null) {
            val pickedColor = pickColorAt(pickPt.x, pickPt.y)
            onColorPicked?.invoke(pickedColor)
        }

        // 6. Enforce VRAM tile budget only when requested (stroke committed or gesture ended)
        if (pendingTrimBudget.compareAndSet(true, false)) {
            layerManager.trimAllToBudget(visibleBounds)
        }
    }

    fun pickColorAt(worldX: Float, worldY: Float): Int {
        val tileSize = TileCoord.TILE_SIZE.toFloat()
        val tx = floor(worldX / tileSize).toInt()
        val ty = floor(worldY / tileSize).toInt()
        val localX = (worldX - tx * tileSize).toInt().coerceIn(0, 511)
        val localY = (worldY - ty * tileSize).toInt().coerceIn(0, 511)
        val glY = 511 - localY

        var accColor = backgroundColorRgb

        val pixelBuf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())

        for (layer in layerManager.layers) {
            if (!layer.isVisible || layer.opacity <= 0.001f) continue
            val tile = layer.tileMap.getExistingTile(tx, ty) ?: continue
            if (!tile.isInitialized || !tile.hasContent) continue

            tile.bindFbo()
            pixelBuf.position(0)
            GLES30.glReadPixels(localX, glY, 1, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixelBuf)
            tile.unbindFbo()

            pixelBuf.position(0)
            val r = pixelBuf.get().toInt() and 0xFF
            val g = pixelBuf.get().toInt() and 0xFF
            val b = pixelBuf.get().toInt() and 0xFF
            val rawAlpha = pixelBuf.get().toInt() and 0xFF
            val a = (rawAlpha / 255.0f) * layer.opacity

            if (a > 0.001f) {
                val layerCol = Color.rgb(r, g, b)
                accColor = PigmentColorMixing.mixColorsInt(accColor, layerCol, a)
            }
        }

        return accColor
    }

    private fun stampDabsIntoTiles(targetTileMap: TileMap, dabs: List<BrushDab>) {
        if (dabs.isEmpty()) return

        val tileSize = TileCoord.TILE_SIZE.toFloat()
        val tileDabsMap = mutableMapOf<Pair<Int, Int>, MutableList<BrushDab>>()
        for (dab in dabs) {
            val minTx = floor((dab.x - dab.radius) / tileSize).toInt()
            val maxTx = floor((dab.x + dab.radius) / tileSize).toInt()
            val minTy = floor((dab.y - dab.radius) / tileSize).toInt()
            val maxTy = floor((dab.y + dab.radius) / tileSize).toInt()
            for (ty in minTy..maxTy) {
                for (tx in minTx..maxTx) {
                    tileDabsMap.getOrPut(tx to ty) { mutableListOf() }.add(dab)
                }
            }
        }

        for ((coord, tileDabs) in tileDabsMap) {
            val (tx, ty) = coord
            val tile = targetTileMap.getOrCreateTile(tx, ty)
            tile.ensureResident(targetTileMap.cacheDir)
            tile.hasContent = true

            tile.bindFbo()
            if (dabShader.usesFramebufferFetch) {
                GLES30.glDisable(GLES30.GL_BLEND)
            } else {
                GLES30.glEnable(GLES30.GL_BLEND)
                GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
            }

            for (dab in tileDabs) {
                if (!dabShader.usesFramebufferFetch) {
                    if (dab.isEraser) {
                        GLES30.glBlendFunc(GLES30.GL_ZERO, GLES30.GL_ONE_MINUS_SRC_ALPHA)
                    } else {
                        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
                    }
                }

                val localX = dab.x - (tx * tileSize)
                val localY = dab.y - (ty * tileSize)
                val color = if (dab.isEraser) 0 else dab.colorRgb
                dabShader.renderDab(
                    centerX = localX,
                    centerY = localY,
                    radius = dab.radius,
                    colorRgb = color,
                    alpha = dab.alpha,
                    hardness = dab.hardness,
                    brushMode = dab.brushMode,
                    pressure = dab.pressure,
                    worldOffsetX = tx * tileSize,
                    worldOffsetY = ty * tileSize,
                    projectionMatrix = tileOrthoMatrix,
                    isEraser = dab.isEraser
                )
            }
            tile.unbindFbo()
            if (dabShader.usesFramebufferFetch) {
                GLES30.glEnable(GLES30.GL_BLEND)
            }
        }
    }

    fun cleanup() {
        if (isGlInitialized) {
            dabShader.releaseGl()
            tileBlitShader.releaseGl()
            thumbnailRenderer.releaseGl()
            layerManager.releaseAll()
            isGlInitialized = false
        }
    }
}
