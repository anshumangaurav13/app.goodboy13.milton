package app.goodboy13.milton.core.gl

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES30
import android.opengl.Matrix
import android.util.Log
import androidx.graphics.lowlatency.BufferInfo
import app.goodboy13.milton.core.brush.BrushDab
import app.goodboy13.milton.core.layer.Layer
import app.goodboy13.milton.core.model.WorldRect
import app.goodboy13.milton.core.tile.TileCoord
import app.goodboy13.milton.core.tile.TileMap
import app.goodboy13.milton.core.viewport.Viewport
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Pure low-level OpenGL ES 3.0 graphics pipeline.
 * Encapsulates shaders, framebuffers, matrix transformations, tile compositing,
 * low-latency front-buffering, and offscreen exports.
 * Contains ZERO domain logic (undo history, layer editing, project files).
 */
class GlSceneRenderer {

    companion object {
        private const val TAG = "GlSceneRenderer"
    }

    val dabShader = DabShader()
    val tileBlitShader = TileBlitShader()
    val thumbnailRenderer = LayerThumbnailRenderer()
    val strokeCompositeShader = StrokeCompositeShader()

    private var scratchFboId: Int = 0
    private var scratchTextureId: Int = 0
    private val pickPixelBuf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())

    private val tileOrthoMatrix = FloatArray(16)
    private val screenOrthoMatrix = FloatArray(16)
    private val screenProjectionMatrix = FloatArray(16)
    private val worldToViewMatrix = FloatArray(16)
    private val finalMvpMatrix = FloatArray(16)

    var isGlInitialized = false
        private set

    init {
        // Tile FBO orthographic projection: [0..512, 0..512] -> NDC with top-left origin (Y down)
        Matrix.orthoM(
            tileOrthoMatrix, 0,
            0f, TileCoord.TILE_SIZE.toFloat(),
            TileCoord.TILE_SIZE.toFloat(), 0f,
            -1f, 1f
        )
    }

    fun ensureGlInitialized() {
        if (!isGlInitialized) {
            dabShader.initGl()
            tileBlitShader.initGl()
            thumbnailRenderer.initGl()
            strokeCompositeShader.initGl()
            initScratchTileGl()
            isGlInitialized = true
            Log.d(TAG, "OpenGL ES shaders, FBOs, and VAOs initialized successfully")
        }
    }

    private fun initScratchTileGl() {
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        scratchTextureId = textures[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, scratchTextureId)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8,
            TileCoord.TILE_SIZE, TileCoord.TILE_SIZE, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

        val fbos = IntArray(1)
        GLES30.glGenFramebuffers(1, fbos, 0)
        scratchFboId = fbos[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, scratchFboId)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, scratchTextureId, 0
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "Scratch FBO is incomplete: status=$status")
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    fun computeFinalMvpMatrix(viewport: Viewport, bufferInfo: BufferInfo, transform: FloatArray): FloatArray {
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

    /**
     * Renders dabs directly to the front buffer overlay for immediate feedback.
     */
    fun renderFrontBufferDabs(
        dabs: List<BrushDab>,
        activeLayerOpacity: Float,
        bufferInfo: BufferInfo,
        transform: FloatArray,
        viewport: Viewport
    ) {
        if (dabs.isEmpty()) return

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

        val mvp = computeFinalMvpMatrix(viewport, bufferInfo, transform)
        for (dab in dabs) {
            if (dab.isEraser) continue // Eraser is never drawn into the front buffer overlay

            dabShader.renderDab(
                centerX = dab.x,
                centerY = dab.y,
                radius = dab.radius,
                colorRgb = dab.colorRgb,
                alpha = dab.alpha * activeLayerOpacity,
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

    /**
     * Clears the back buffer with the background paper color.
     */
    fun clearScreen(bufferInfo: BufferInfo, backgroundColorRgb: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bufferInfo.frameBufferId)
        GLES30.glViewport(0, 0, bufferInfo.width, bufferInfo.height)

        val r = Color.red(backgroundColorRgb) / 255.0f
        val g = Color.green(backgroundColorRgb) / 255.0f
        val b = Color.blue(backgroundColorRgb) / 255.0f
        GLES30.glClearColor(r, g, b, 1.0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
    }

    /**
     * Renders all visible layers and their initialized tiles into the backbuffer.
     */
    fun blitLayers(
        layers: List<Layer>,
        visibleBounds: WorldRect,
        backgroundColorRgb: Int,
        bufferInfo: BufferInfo,
        transform: FloatArray,
        viewport: Viewport
    ) {
        val mvp = computeFinalMvpMatrix(viewport, bufferInfo, transform)
        tileBlitShader.begin(mvp, true, backgroundColorRgb)

        for (layer in layers) {
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
    }

    /**
     * Two-pass scratch FBO composition: accumulates brush dabs into a scratch FBO ribbon,
     * then composites onto destination tile FBO in a single pass. Directly attenuates for eraser dabs.
     */
    fun stampDabsIntoTiles(targetTileMap: TileMap, dabs: List<BrushDab>) {
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

            val eraserDabs = tileDabs.filter { it.isEraser }
            val brushDabs = tileDabs.filter { !it.isEraser }

            if (brushDabs.isNotEmpty()) {
                tile.hasContent = true
            }

            // 1. Eraser dabs directly attenuate the tile FBO
            if (eraserDabs.isNotEmpty()) {
                tile.bindFbo()
                if (dabShader.usesFramebufferFetch) {
                    GLES30.glDisable(GLES30.GL_BLEND)
                } else {
                    GLES30.glEnable(GLES30.GL_BLEND)
                    GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
                    GLES30.glBlendFunc(GLES30.GL_ZERO, GLES30.GL_ONE_MINUS_SRC_ALPHA)
                }

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
                        projectionMatrix = tileOrthoMatrix,
                        isEraser = true
                    )
                }
                tile.unbindFbo()
                if (dabShader.usesFramebufferFetch) {
                    GLES30.glEnable(GLES30.GL_BLEND)
                }
            }

            // 2. Brush dabs: Two-pass scratch composition
            if (brushDabs.isNotEmpty()) {
                // Pass 1: Render all brush dabs for this tile into the scratch FBO
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, scratchFboId)
                GLES30.glViewport(0, 0, TileCoord.TILE_SIZE, TileCoord.TILE_SIZE)
                GLES30.glClearColor(0f, 0f, 0f, 0f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

                if (dabShader.usesFramebufferFetch) {
                    GLES30.glDisable(GLES30.GL_BLEND)
                } else {
                    GLES30.glEnable(GLES30.GL_BLEND)
                    GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
                    GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
                }

                for (dab in brushDabs) {
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
                        projectionMatrix = tileOrthoMatrix,
                        isEraser = false
                    )
                }

                if (dabShader.usesFramebufferFetch) {
                    GLES30.glEnable(GLES30.GL_BLEND)
                }
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

                // Pass 2: Composite the scratch stroke ribbon onto destination tile FBO in a single pass
                tile.bindFbo()
                GLES30.glViewport(0, 0, TileCoord.TILE_SIZE, TileCoord.TILE_SIZE)
                strokeCompositeShader.render(strokeTextureId = scratchTextureId)
                tile.unbindFbo()
            }
        }
    }

    /**
     * Captures a layer thumbnail offscreen.
     */
    fun captureThumbnail(
        layer: Layer,
        visibleBounds: WorldRect,
        restoreFboId: Int,
        restoreWidth: Int,
        restoreHeight: Int
    ): Bitmap? {
        return thumbnailRenderer.captureLayerThumbnail(
            layer = layer,
            visibleBounds = visibleBounds,
            tileBlitShader = tileBlitShader,
            restoreFboId = restoreFboId,
            restoreWidth = restoreWidth,
            restoreHeight = restoreHeight
        )
    }

    /**
     * Samples pixel color at world coordinate (worldX, worldY).
     */
    fun pickColorAt(layers: List<Layer>, worldX: Float, worldY: Float, backgroundColorRgb: Int): Int {
        val tileSize = TileCoord.TILE_SIZE.toFloat()
        val tx = floor(worldX / tileSize).toInt()
        val ty = floor(worldY / tileSize).toInt()
        val localX = (worldX - tx * tileSize).toInt().coerceIn(0, 511)
        val localY = (worldY - ty * tileSize).toInt().coerceIn(0, 511)
        val glY = 511 - localY

        var accColor = backgroundColorRgb
        val pixelBuf = pickPixelBuf

        for (layer in layers) {
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
                val dr = Color.red(accColor)
                val dg = Color.green(accColor)
                val db = Color.blue(accColor)
                val outR = kotlin.math.round((dr * (1f - a) + r * a)).toInt().coerceIn(0, 255)
                val outG = kotlin.math.round((dg * (1f - a) + g * a)).toInt().coerceIn(0, 255)
                val outB = kotlin.math.round((db * (1f - a) + b * a)).toInt().coerceIn(0, 255)
                accColor = Color.rgb(outR, outG, outB)
            }
        }

        return accColor
    }

    /**
     * Renders visible canvas area offscreen into a Bitmap.
     */
    fun renderOffscreen(
        layers: List<Layer>,
        viewport: Viewport,
        backgroundColorRgb: Int,
        exportWidth: Int,
        exportHeight: Int
    ): Bitmap? {
        ensureGlInitialized()

        val fbos = IntArray(1)
        GLES30.glGenFramebuffers(1, fbos, 0)
        val fboId = fbos[0]

        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        val texId = textures[0]

        try {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8,
                exportWidth, exportHeight, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
            )
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, texId, 0
            )

            val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                Log.e(TAG, "Export FBO is incomplete: status=$status")
                return null
            }

            GLES30.glViewport(0, 0, exportWidth, exportHeight)
            val r = Color.red(backgroundColorRgb) / 255.0f
            val g = Color.green(backgroundColorRgb) / 255.0f
            val b = Color.blue(backgroundColorRgb) / 255.0f
            GLES30.glClearColor(r, g, b, 1.0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

            val screenW = viewport.screenWidth.toFloat()
            val screenH = viewport.screenHeight.toFloat()

            val exportOrtho = FloatArray(16)
            Matrix.orthoM(exportOrtho, 0, 0f, screenW, 0f, screenH, -1f, 1f)

            val worldToView = FloatArray(16)
            Matrix.setIdentityM(worldToView, 0)
            if (viewport.isFlippedHorizontally) {
                val cx = viewport.screenWidth * 0.5f
                Matrix.translateM(worldToView, 0, cx, 0f, 0f)
                Matrix.scaleM(worldToView, 0, -1f, 1f, 1f)
                Matrix.translateM(worldToView, 0, -cx, 0f, 0f)
            }
            Matrix.translateM(worldToView, 0, viewport.panX, viewport.panY, 0f)
            Matrix.scaleM(worldToView, 0, viewport.zoom, viewport.zoom, 1.0f)
            if (viewport.rotationDegrees != 0f) {
                Matrix.rotateM(worldToView, 0, viewport.rotationDegrees, 0f, 0f, 1f)
            }

            val exportMvp = FloatArray(16)
            Matrix.multiplyMM(exportMvp, 0, exportOrtho, 0, worldToView, 0)

            val visibleBounds = viewport.getVisibleWorldBounds()
            tileBlitShader.begin(exportMvp, flipY = true, backgroundColorRgb = backgroundColorRgb)
            for (layer in layers) {
                if (!layer.isVisible || layer.opacity <= 0.001f) continue
                val visibleTiles = layer.tileMap.getVisibleTiles(visibleBounds)
                if (visibleTiles.isEmpty()) continue

                tileBlitShader.setOpacity(layer.opacity)
                for (tile in visibleTiles) {
                    if (!tile.isInitialized || !tile.hasContent) {
                        if (tile.isOnDisk) {
                            tile.ensureResident(layer.tileMap.cacheDir)
                        }
                    }
                    if (tile.isInitialized && tile.hasContent) {
                        tileBlitShader.renderTile(
                            worldLeft = tile.coord.worldLeft,
                            worldTop = tile.coord.worldTop,
                            textureId = tile.textureId
                        )
                    }
                }
            }
            tileBlitShader.end()

            val buf = ByteBuffer.allocateDirect(exportWidth * exportHeight * 4)
                .order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, exportWidth, exportHeight, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)

            val bitmap = Bitmap.createBitmap(exportWidth, exportHeight, Bitmap.Config.ARGB_8888)
            buf.position(0)
            bitmap.copyPixelsFromBuffer(buf)
            return bitmap
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            if (fboId != 0) {
                GLES30.glDeleteFramebuffers(1, fbos, 0)
            }
            if (texId != 0) {
                GLES30.glDeleteTextures(1, textures, 0)
            }
        }
    }

    fun releaseGl() {
        if (isGlInitialized) {
            if (scratchTextureId != 0) {
                GLES30.glDeleteTextures(1, intArrayOf(scratchTextureId), 0)
                scratchTextureId = 0
            }
            if (scratchFboId != 0) {
                GLES30.glDeleteFramebuffers(1, intArrayOf(scratchFboId), 0)
                scratchFboId = 0
            }
            strokeCompositeShader.releaseGl()
            dabShader.releaseGl()
            tileBlitShader.releaseGl()
            thumbnailRenderer.releaseGl()
            isGlInitialized = false
        }
    }
}
