package com.antigrav.milton.core.gl

import android.graphics.Color
import android.opengl.GLES30
import android.opengl.Matrix
import android.util.Log
import androidx.graphics.lowlatency.BufferInfo
import androidx.graphics.lowlatency.GLFrontBufferedRenderer
import androidx.graphics.opengl.egl.EGLManager
import com.antigrav.milton.core.brush.BrushDab
import com.antigrav.milton.core.tile.RasterTile
import com.antigrav.milton.core.tile.TileCoord
import com.antigrav.milton.core.tile.TileMap
import com.antigrav.milton.core.history.UndoManager
import com.antigrav.milton.core.viewport.Viewport
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.floor

data class DabPacket(
    val dabs: List<BrushDab> = emptyList()
)

/**
 * OpenGL ES 3.0 renderer coordinating the sparse tile map,
 * low-latency front buffering, and camera viewport.
 */
class MiltonCanvasRenderer(
    val viewport: Viewport = Viewport(),
    val tileMap: TileMap = TileMap()
) : GLFrontBufferedRenderer.Callback<DabPacket> {

    companion object {
        private const val TAG = "MiltonCanvasRenderer"
    }

    private val dabShader = DabShader()
    private val tileBlitShader = TileBlitShader()

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

    fun requestUndo() {
        pendingUndoCount.incrementAndGet()
    }

    fun requestRedo() {
        pendingRedoCount.incrementAndGet()
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

        Log.i(TAG, "onDrawFrontBufferedLayer: dabs=${param.dabs.size}, buffer=${bufferInfo.width}x${bufferInfo.height}, fbo=${bufferInfo.frameBufferId}")
        viewport.updateScreenSize(width, height)
        pendingDabsForCommit.addAll(param.dabs)

        // Render dabs directly to the low-latency front buffer for instantaneous visual feedback
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bufferInfo.frameBufferId)
        GLES30.glViewport(0, 0, bufferInfo.width, bufferInfo.height)

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFuncSeparate(
            GLES30.GL_ONE,
            GLES30.GL_ONE_MINUS_SRC_ALPHA,
            GLES30.GL_ONE,
            GLES30.GL_ONE_MINUS_SRC_ALPHA
        )

        val mvp = computeFinalMvpMatrix(bufferInfo, transform)
        for (dab in param.dabs) {
            val dabColor = if (dab.isEraser) backgroundColorRgb else dab.colorRgb
            dabShader.renderDab(
                centerX = dab.x,
                centerY = dab.y,
                radius = dab.radius,
                colorRgb = dabColor,
                alpha = dab.alpha,
                hardness = dab.hardness,
                brushMode = dab.brushMode,
                pressure = dab.pressure,
                projectionMatrix = mvp
            )
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
        while (undos > 0) {
            undoManager.undo(tileMap)
            undos--
        }
        var redos = pendingRedoCount.getAndSet(0)
        while (redos > 0) {
            undoManager.redo(tileMap)
            redos--
        }

        for (packet in params) {
            if (packet.dabs.isNotEmpty()) {
                pendingDabsForCommit.addAll(packet.dabs)
            }
        }

        // 1. Drain and stamp all accumulated stroke dabs into the tile FBOs on this render thread
        val dabsToStamp = mutableListOf<BrushDab>()
        while (true) {
            val dab = pendingDabsForCommit.poll() ?: break
            dabsToStamp.add(dab)
        }
        Log.i(TAG, "onDrawMultiBufferedLayer: buffer=${bufferInfo.width}x${bufferInfo.height}, dabsToStamp=${dabsToStamp.size}")
        if (dabsToStamp.isNotEmpty()) {
            val affectedTiles = mutableSetOf<RasterTile>()
            val tileSize = TileCoord.TILE_SIZE.toFloat()
            for (dab in dabsToStamp) {
                val minTx = floor((dab.x - dab.radius) / tileSize).toInt()
                val maxTx = floor((dab.x + dab.radius) / tileSize).toInt()
                val minTy = floor((dab.y - dab.radius) / tileSize).toInt()
                val maxTy = floor((dab.y + dab.radius) / tileSize).toInt()
                for (ty in minTy..maxTy) {
                    for (tx in minTx..maxTx) {
                        val tile = tileMap.getOrCreateTile(tx, ty)
                        tile.ensureResident(tileMap.cacheDir)
                        tile.hasContent = true
                        affectedTiles.add(tile)
                    }
                }
            }
            undoManager.capturePreStrokeTiles(affectedTiles)
            stampDabsIntoTiles(dabsToStamp)
            undoManager.commitStroke(tileMap)
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

        // 3. Query and blit all visible tiles
        val visibleBounds = viewport.getVisibleWorldBounds()
        val visibleTiles = tileMap.getVisibleTiles(visibleBounds)

        if (visibleTiles.isNotEmpty()) {
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)

            val mvp = computeFinalMvpMatrix(bufferInfo, transform)
            tileBlitShader.begin(mvp)

            for (tile in visibleTiles) {
                if (!tile.isInitialized || !tile.hasContent) continue
                tileBlitShader.renderTile(
                    worldLeft = tile.coord.worldLeft,
                    worldTop = tile.coord.worldTop,
                    textureId = tile.textureId
                )
            }
            tileBlitShader.end()
        }

        // 4. Enforce VRAM tile budget only when requested (stroke committed or gesture ended)
        if (pendingTrimBudget.compareAndSet(true, false)) {
            tileMap.trimToBudget(visibleTiles)
        }
    }

    private var scratchFboId = 0
    private var scratchTextureId = 0

    private fun initScratchFbo() {
        if (scratchFboId != 0) return
        val texs = IntArray(1)
        GLES30.glGenTextures(1, texs, 0)
        scratchTextureId = texs[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, scratchTextureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8,
            TileCoord.TILE_SIZE, TileCoord.TILE_SIZE, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
        )

        val fbos = IntArray(1)
        GLES30.glGenFramebuffers(1, fbos, 0)
        scratchFboId = fbos[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, scratchFboId)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, scratchTextureId, 0
        )
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    private fun stampDabsIntoTiles(dabs: List<BrushDab>) {
        if (dabs.isEmpty()) return
        initScratchFbo()

        val tileSize = TileCoord.TILE_SIZE.toFloat()
        // Group by strokeId so overlapping dabs within the same stroke do not multiply into solid opacity
        val strokeGroups = dabs.groupBy { it.strokeId }

        for ((_, strokeDabs) in strokeGroups) {
            val tileDabsMap = mutableMapOf<Pair<Int, Int>, MutableList<BrushDab>>()
            for (dab in strokeDabs) {
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
                val tile = tileMap.getOrCreateTile(tx, ty)
                tile.ensureResident(tileMap.cacheDir)
                tile.hasContent = true

                val eraserDabs = tileDabs.filter { it.isEraser }
                val paintDabs = tileDabs.filter { !it.isEraser }

                if (eraserDabs.isNotEmpty()) {
                    tile.bindFbo()
                    GLES30.glEnable(GLES30.GL_BLEND)
                    GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
                    GLES30.glBlendFunc(GLES30.GL_ZERO, GLES30.GL_ONE_MINUS_SRC_ALPHA)
                    for (dab in eraserDabs) {
                        val localX = dab.x - (tx * tileSize)
                        val localY = dab.y - (ty * tileSize)
                        dabShader.renderDab(
                            centerX = localX,
                            centerY = localY,
                            radius = dab.radius,
                            colorRgb = 0,
                            alpha = dab.alpha,
                            hardness = dab.hardness,
                            brushMode = dab.brushMode,
                            pressure = dab.pressure,
                            worldOffsetX = tx * tileSize,
                            worldOffsetY = ty * tileSize,
                            projectionMatrix = tileOrthoMatrix
                        )
                    }
                    tile.unbindFbo()
                }

                if (paintDabs.isNotEmpty()) {
                    // Accumulate dabs into scratch FBO with GL_MAX so the stroke does not saturate to solid black
                    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, scratchFboId)
                    GLES30.glViewport(0, 0, TileCoord.TILE_SIZE, TileCoord.TILE_SIZE)
                    GLES30.glClearColor(0f, 0f, 0f, 0f)
                    GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

                    GLES30.glEnable(GLES30.GL_BLEND)
                    GLES30.glBlendEquation(GLES30.GL_MAX)

                    for (dab in paintDabs) {
                        val localX = dab.x - (tx * tileSize)
                        val localY = dab.y - (ty * tileSize)
                        dabShader.renderDab(
                            centerX = localX,
                            centerY = localY,
                            radius = dab.radius,
                            colorRgb = dab.colorRgb,
                            alpha = dab.alpha,
                            hardness = dab.hardness,
                            brushMode = dab.brushMode,
                            pressure = dab.pressure,
                            worldOffsetX = tx * tileSize,
                            worldOffsetY = ty * tileSize,
                            projectionMatrix = tileOrthoMatrix
                        )
                    }

                    // Blit stroke texture into real tile with premultiplied blend (flipY = true matches tile FBO orientation)
                    tile.bindFbo()
                    GLES30.glEnable(GLES30.GL_BLEND)
                    GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
                    GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)

                    tileBlitShader.begin(tileOrthoMatrix, flipY = true)
                    tileBlitShader.renderTile(0f, 0f, scratchTextureId)
                    tileBlitShader.end()

                    tile.unbindFbo()
                }
            }
        }
    }

    fun cleanup() {
        if (isGlInitialized) {
            if (scratchFboId != 0) {
                GLES30.glDeleteFramebuffers(1, intArrayOf(scratchFboId), 0)
                scratchFboId = 0
            }
            if (scratchTextureId != 0) {
                GLES30.glDeleteTextures(1, intArrayOf(scratchTextureId), 0)
                scratchTextureId = 0
            }
            dabShader.releaseGl()
            tileBlitShader.releaseGl()
            tileMap.releaseAll()
            isGlInitialized = false
        }
    }
}
