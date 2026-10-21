package app.goodboy13.milton.core.gl

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES30
import android.opengl.Matrix
import android.util.Log
import androidx.graphics.lowlatency.BufferInfo
import app.goodboy13.milton.core.brush.BrushDab
import app.goodboy13.milton.core.memory.NativeDabRingBuffer
import app.goodboy13.milton.core.layer.Layer
import app.goodboy13.milton.core.model.WorldRect
import app.goodboy13.milton.core.tile.RasterTile
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
    val tileArrayBlitShader = TileArrayBlitShader()
    val thumbnailRenderer = LayerThumbnailRenderer()
    val strokeCompositeShader = StrokeCompositeShader()
    val patchBlitShader = PatchBlitShader()

    private var scratchFboId: Int = 0
    private var scratchTextureId: Int = 0
    private val pickPixelBuf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())

    private val tileOrthoMatrix = FloatArray(16)
    private val screenOrthoMatrix = FloatArray(16)
    private val screenProjectionMatrix = FloatArray(16)
    private val worldToViewMatrix = FloatArray(16)
    private val finalMvpMatrix = FloatArray(16)

    // Reusable buffers on GL render thread to eliminate frame-rate GC allocations
    private val reusableVisibleTiles = mutableListOf<RasterTile>()
    var lastDrawCalls: Int = 0
        private set
    private val reusableBatchTiles = ArrayList<RasterTile>()
    private val reusableAffectedTiles = mutableListOf<RasterTile>()
    private val tileDabsBucketMap = HashMap<Long, TileDabBucket>()
    private val bucketPool = ArrayList<TileDabBucket>()
    private val activeBuckets = ArrayList<TileDabBucket>()

    private fun packTileCoord(tx: Int, ty: Int): Long = (tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL)

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
            GlTexturePool.ensureGlInitialized()
            dabShader.initGl()
            tileBlitShader.initGl()
            tileArrayBlitShader.initGl()
            thumbnailRenderer.initGl()
            strokeCompositeShader.initGl()
            patchBlitShader.initGl()
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
     * Zero-allocation front-buffer overlay rendering directly reading from [NativeDabRingBuffer].
     */
    fun renderFrontBufferRingDabs(
        ringBuffer: NativeDabRingBuffer,
        startHead: Long,
        count: Int,
        activeLayerOpacity: Float,
        bufferInfo: BufferInfo,
        transform: FloatArray,
        viewport: Viewport
    ) {
        if (count <= 0) return

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
        ringBuffer.forEachDab(startHead, count) { _, x, y, radius, alpha, colorRgb, hardness, brushMode, pressure, _, isEraser ->
            if (!isEraser) {
                dabShader.renderDab(
                    centerX = x,
                    centerY = y,
                    radius = radius,
                    colorRgb = colorRgb,
                    alpha = alpha * activeLayerOpacity,
                    hardness = hardness,
                    brushMode = brushMode,
                    pressure = pressure,
                    projectionMatrix = mvp,
                    isEraser = false
                )
            }
        }
        if (dabShader.usesFramebufferFetch) {
            GLES30.glEnable(GLES30.GL_BLEND)
        }
    }

    fun renderFrontBufferRingDabs(
        ringBuffer: NativeDabRingBuffer,
        startIndex: Int,
        count: Int,
        activeLayerOpacity: Float,
        bufferInfo: BufferInfo,
        transform: FloatArray,
        viewport: Viewport
    ) {
        renderFrontBufferRingDabs(ringBuffer, startIndex.toLong(), count, activeLayerOpacity, bufferInfo, transform, viewport)
    }

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
     * Integrates Free Transform and Liquify patches directly at each layer's z-depth,
     * maintaining strict document layer stacking order during interactive editing.
     */
    fun blitLayers(
        layers: List<Layer>,
        visibleBounds: WorldRect,
        backgroundColorRgb: Int,
        bufferInfo: BufferInfo,
        transform: FloatArray,
        viewport: Viewport,
        selectionState: app.goodboy13.milton.core.selection.SelectionState? = null
    ) {
        var drawCallCounter = 0
        val showBorders = app.goodboy13.milton.core.telemetry.TelemetryManager.cvarShowTileBorders
        val forceOverview = app.goodboy13.milton.core.telemetry.TelemetryManager.cvarForceOverviewLod
        val allowArrayBlit = app.goodboy13.milton.core.telemetry.TelemetryManager.cvarUseTextureArrayBlit

        val mvp = computeFinalMvpMatrix(viewport, bufferInfo, transform)
        val textureArrayId = GlTexturePool.textureArrayId
        val useArrayBlit = allowArrayBlit && GlTexturePool.usesTextureArray && tileArrayBlitShader.isReady && textureArrayId != 0

        if (useArrayBlit) {
            tileArrayBlitShader.begin(mvp, true, textureArrayId, showBorders)

            for (layer in layers) {
                if (!layer.isVisible || layer.opacity <= 0.001f) continue

                val visibleTiles = layer.tileMap.getVisibleTiles(visibleBounds, reusableVisibleTiles)
                if (visibleTiles.isNotEmpty()) {
                    val isLayerLiquifying = selectionState is app.goodboy13.milton.core.selection.SelectionState.ActiveLiquify &&
                        selectionState.session.targetLayerIds.contains(layer.id)
                    val liquifyMinTx = if (isLayerLiquifying) {
                        floor(selectionState.session.srcBounds.left / TileCoord.TILE_SIZE).toInt()
                    } else Int.MAX_VALUE
                    val liquifyMaxTx = if (isLayerLiquifying) {
                        floor((selectionState.session.srcBounds.right - 1f) / TileCoord.TILE_SIZE).toInt()
                    } else Int.MIN_VALUE
                    val liquifyMinTy = if (isLayerLiquifying) {
                        floor(selectionState.session.srcBounds.top / TileCoord.TILE_SIZE).toInt()
                    } else Int.MAX_VALUE
                    val liquifyMaxTy = if (isLayerLiquifying) {
                        floor((selectionState.session.srcBounds.bottom - 1f) / TileCoord.TILE_SIZE).toInt()
                    } else Int.MIN_VALUE

                    reusableBatchTiles.clear()
                    for (tile in visibleTiles) {
                        if (!tile.isInitialized && tile.isOnDisk) {
                            tile.ensureResident(layer.tileMap.cacheDir)
                        }
                        if (!tile.isInitialized || !tile.hasContent || tile.sliceIndex < 0) continue
                        if (isLayerLiquifying &&
                            tile.coord.tx in liquifyMinTx..liquifyMaxTx &&
                            tile.coord.ty in liquifyMinTy..liquifyMaxTy
                        ) {
                            continue
                        }
                        reusableBatchTiles.add(tile)
                    }

                    if (reusableBatchTiles.isNotEmpty()) {
                        tileArrayBlitShader.renderTilesBatch(reusableBatchTiles, layer.opacity, TileCoord.TILE_SIZE.toFloat()); drawCallCounter++
                    }
                }

                // Render patch for this layer if active in transform or liquify session
                if (selectionState is app.goodboy13.milton.core.selection.SelectionState.ActiveTransform &&
                    selectionState.session.targetLayerIds.contains(layer.id)
                ) {
                    val patch = selectionState.session.layerPatches[layer.id]
                    if (patch != null && patch.glTextureId != 0) {
                        tileArrayBlitShader.end()
                        patchBlitShader.renderTransformPatch(
                            session = selectionState.session,
                            patch = patch,
                            layerOpacity = layer.opacity,
                            mvpMatrix = mvp
                        ); drawCallCounter++
                        tileArrayBlitShader.begin(mvp, true, textureArrayId)
                    }
                } else if (selectionState is app.goodboy13.milton.core.selection.SelectionState.ActiveLiquify &&
                    selectionState.session.targetLayerIds.contains(layer.id)
                ) {
                    val patch = selectionState.session.layerPatches[layer.id]
                    if (patch != null && patch.glTextureId != 0) {
                        tileArrayBlitShader.end()
                        patchBlitShader.renderLiquifyMesh(
                            session = selectionState.session,
                            patch = patch,
                            layerOpacity = layer.opacity,
                            mvpMatrix = mvp
                        ); drawCallCounter++
                        tileArrayBlitShader.begin(mvp, true, textureArrayId)
                    }
                }
            }
            tileArrayBlitShader.end()
        } else {
            tileBlitShader.begin(mvp, true, backgroundColorRgb, showBorders)

            for (layer in layers) {
                if (!layer.isVisible || layer.opacity <= 0.001f) continue
                val visibleTiles = layer.tileMap.getVisibleTiles(visibleBounds, reusableVisibleTiles)
                if (visibleTiles.isNotEmpty()) {
                    tileBlitShader.setOpacity(layer.opacity)
                    val isLayerLiquifying = selectionState is app.goodboy13.milton.core.selection.SelectionState.ActiveLiquify &&
                        selectionState.session.targetLayerIds.contains(layer.id)
                    val liquifyMinTx = if (isLayerLiquifying) {
                        floor(selectionState.session.srcBounds.left / TileCoord.TILE_SIZE).toInt()
                    } else Int.MAX_VALUE
                    val liquifyMaxTx = if (isLayerLiquifying) {
                        floor((selectionState.session.srcBounds.right - 1f) / TileCoord.TILE_SIZE).toInt()
                    } else Int.MIN_VALUE
                    val liquifyMinTy = if (isLayerLiquifying) {
                        floor(selectionState.session.srcBounds.top / TileCoord.TILE_SIZE).toInt()
                    } else Int.MAX_VALUE
                    val liquifyMaxTy = if (isLayerLiquifying) {
                        floor((selectionState.session.srcBounds.bottom - 1f) / TileCoord.TILE_SIZE).toInt()
                    } else Int.MIN_VALUE

                    for (tile in visibleTiles) {
                        if (!tile.isInitialized || !tile.hasContent) continue
                        if (isLayerLiquifying &&
                            tile.coord.tx in liquifyMinTx..liquifyMaxTx &&
                            tile.coord.ty in liquifyMinTy..liquifyMaxTy
                        ) {
                            continue
                        }
                        tileBlitShader.renderTile(
                            worldLeft = tile.coord.worldLeft,
                            worldTop = tile.coord.worldTop,
                            textureId = tile.textureId
                        )
                    }
                }

                // Render patch for this layer if active in transform or liquify session
                if (selectionState is app.goodboy13.milton.core.selection.SelectionState.ActiveTransform &&
                    selectionState.session.targetLayerIds.contains(layer.id)
                ) {
                    val patch = selectionState.session.layerPatches[layer.id]
                    if (patch != null && patch.glTextureId != 0) {
                        tileBlitShader.end()
                        patchBlitShader.renderTransformPatch(
                            session = selectionState.session,
                            patch = patch,
                            layerOpacity = layer.opacity,
                            mvpMatrix = mvp
                        ); drawCallCounter++
                        tileBlitShader.begin(mvp, true, backgroundColorRgb)
                    }
                } else if (selectionState is app.goodboy13.milton.core.selection.SelectionState.ActiveLiquify &&
                    selectionState.session.targetLayerIds.contains(layer.id)
                ) {
                    val patch = selectionState.session.layerPatches[layer.id]
                    if (patch != null && patch.glTextureId != 0) {
                        tileBlitShader.end()
                        patchBlitShader.renderLiquifyMesh(
                            session = selectionState.session,
                            patch = patch,
                            layerOpacity = layer.opacity,
                            mvpMatrix = mvp
                        ); drawCallCounter++
                        tileBlitShader.begin(mvp, true, backgroundColorRgb)
                    }
                }
            }
            tileBlitShader.end()
        }
    }

    /**
     * Two-pass scratch FBO composition: accumulates brush dabs into a scratch FBO ribbon,
     * then composites onto destination tile FBO in a single pass. Directly attenuates for eraser dabs.
     * Uses non-allocating pooled TileDabBuckets and bindFboDirect() to eliminate GPU stalls and GC churn.
     */
    fun stampDabsIntoTiles(
        targetTileMap: TileMap,
        dabs: List<BrushDab>,
        onTilesResolved: ((Collection<RasterTile>) -> Unit)? = null
    ) {
        if (dabs.isEmpty()) return

        tileDabsBucketMap.clear()
        for (i in activeBuckets.indices) {
            bucketPool.add(activeBuckets[i])
        }
        activeBuckets.clear()
        reusableAffectedTiles.clear()

        val tileSize = TileCoord.TILE_SIZE.toFloat()
        for (dab in dabs) {
            val reach = dab.radius + 2.0f
            val minTx = floor((dab.x - reach) / tileSize).toInt()
            val maxTx = floor((dab.x + reach) / tileSize).toInt()
            val minTy = floor((dab.y - reach) / tileSize).toInt()
            val maxTy = floor((dab.y + reach) / tileSize).toInt()
            for (ty in minTy..maxTy) {
                for (tx in minTx..maxTx) {
                    val key = packTileCoord(tx, ty)
                    var bucket = tileDabsBucketMap[key]
                    if (bucket == null) {
                        val tile = targetTileMap.getOrCreateTile(tx, ty)
                        tile.ensureResident(targetTileMap.cacheDir)
                        bucket = if (bucketPool.isNotEmpty()) bucketPool.removeAt(bucketPool.size - 1) else TileDabBucket()
                        bucket.reset(tx, ty, tile)
                        tileDabsBucketMap[key] = bucket
                        activeBuckets.add(bucket)
                        reusableAffectedTiles.add(tile)
                    }
                    if (dab.isEraser) {
                        bucket.eraserDabs.add(dab)
                    } else {
                        bucket.brushDabs.add(dab)
                    }
                }
            }
        }

        // Notify callback (e.g. undo pre-stroke snapshot) before modifying tile FBOs
        onTilesResolved?.invoke(reusableAffectedTiles)

        for (i in activeBuckets.indices) {
            val bucket = activeBuckets[i]
            val tx = bucket.tx
            val ty = bucket.ty
            val tile = bucket.tile ?: continue
            val eraserDabs = bucket.eraserDabs
            val brushDabs = bucket.brushDabs

            if (brushDabs.isNotEmpty()) {
                tile.hasContent = true
            }

            // 1. Eraser dabs directly attenuate the tile FBO
            if (eraserDabs.isNotEmpty()) {
                tile.bindFboDirect()
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

                // Pass 2: Composite the scratch stroke ribbon onto destination tile FBO in a single pass
                tile.bindFboDirect()
                strokeCompositeShader.render(strokeTextureId = scratchTextureId)
            }
        }
    }

    /**
     * Zero-allocation tile stamping directly from [NativeDabRingBuffer].
     */
    fun stampRingDabsIntoTiles(
        targetTileMap: TileMap,
        ringBuffer: NativeDabRingBuffer,
        startHead: Long,
        count: Int,
        onTilesResolved: ((List<RasterTile>) -> Unit)? = null
    ) {
        if (count <= 0) return

        tileDabsBucketMap.clear()
        for (i in activeBuckets.indices) {
            bucketPool.add(activeBuckets[i])
        }
        activeBuckets.clear()
        reusableAffectedTiles.clear()

        val tileSize = TileCoord.TILE_SIZE.toFloat()
        ringBuffer.forEachDab(startHead, count) { slot, x, y, radius, _, _, _, _, _, _, isEraser ->
            val reach = radius + 2.0f
            val minTx = floor((x - reach) / tileSize).toInt()
            val maxTx = floor((x + reach) / tileSize).toInt()
            val minTy = floor((y - reach) / tileSize).toInt()
            val maxTy = floor((y + reach) / tileSize).toInt()
            for (ty in minTy..maxTy) {
                for (tx in minTx..maxTx) {
                    val key = packTileCoord(tx, ty)
                    var bucket = tileDabsBucketMap[key]
                    if (bucket == null) {
                        val tile = targetTileMap.getOrCreateTile(tx, ty)
                        tile.ensureResident(targetTileMap.cacheDir)
                        bucket = if (bucketPool.isNotEmpty()) bucketPool.removeAt(bucketPool.size - 1) else TileDabBucket()
                        bucket.reset(tx, ty, tile)
                        tileDabsBucketMap[key] = bucket
                        activeBuckets.add(bucket)
                        reusableAffectedTiles.add(tile)
                    }
                    if (isEraser) {
                        bucket.addEraserIndex(slot)
                    } else {
                        bucket.addBrushIndex(slot)
                    }
                }
            }
        }

        onTilesResolved?.invoke(reusableAffectedTiles)

        for (i in activeBuckets.indices) {
            val bucket = activeBuckets[i]
            val tx = bucket.tx
            val ty = bucket.ty
            val tile = bucket.tile ?: continue

            if (bucket.brushCount > 0) {
                tile.hasContent = true
            }

            // 1. Eraser dabs directly attenuate the tile FBO
            if (bucket.eraserCount > 0) {
                tile.bindFboDirect()
                if (dabShader.usesFramebufferFetch) {
                    GLES30.glDisable(GLES30.GL_BLEND)
                } else {
                    GLES30.glEnable(GLES30.GL_BLEND)
                    GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
                    GLES30.glBlendFunc(GLES30.GL_ZERO, GLES30.GL_ONE_MINUS_SRC_ALPHA)
                }

                for (k in 0 until bucket.eraserCount) {
                    val slot = bucket.eraserIndices[k]
                    val localX = ringBuffer.getXBySlot(slot) - (tx * tileSize)
                    val localY = ringBuffer.getYBySlot(slot) - (ty * tileSize)
                    dabShader.renderDab(
                        centerX = localX,
                        centerY = localY,
                        radius = ringBuffer.getRadiusBySlot(slot),
                        colorRgb = 0,
                        alpha = ringBuffer.getAlphaBySlot(slot),
                        hardness = ringBuffer.getHardnessBySlot(slot),
                        brushMode = ringBuffer.getBrushModeBySlot(slot),
                        pressure = ringBuffer.getPressureBySlot(slot),
                        worldOffsetX = tx * tileSize,
                        worldOffsetY = ty * tileSize,
                        projectionMatrix = tileOrthoMatrix,
                        isEraser = true
                    )
                }
                if (dabShader.usesFramebufferFetch) {
                    GLES30.glEnable(GLES30.GL_BLEND)
                }
            }

            // 2. Brush dabs: Two-pass scratch composition
            if (bucket.brushCount > 0) {
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

                for (k in 0 until bucket.brushCount) {
                    val slot = bucket.brushIndices[k]
                    val localX = ringBuffer.getXBySlot(slot) - (tx * tileSize)
                    val localY = ringBuffer.getYBySlot(slot) - (ty * tileSize)
                    dabShader.renderDab(
                        centerX = localX,
                        centerY = localY,
                        radius = ringBuffer.getRadiusBySlot(slot),
                        colorRgb = ringBuffer.getColorRgbBySlot(slot),
                        alpha = ringBuffer.getAlphaBySlot(slot),
                        hardness = ringBuffer.getHardnessBySlot(slot),
                        brushMode = ringBuffer.getBrushModeBySlot(slot),
                        pressure = ringBuffer.getPressureBySlot(slot),
                        worldOffsetX = tx * tileSize,
                        worldOffsetY = ty * tileSize,
                        projectionMatrix = tileOrthoMatrix,
                        isEraser = false
                    )
                }

                if (dabShader.usesFramebufferFetch) {
                    GLES30.glEnable(GLES30.GL_BLEND)
                }

                // Pass 2: Composite the scratch stroke ribbon onto destination tile FBO in a single pass
                tile.bindFboDirect()
                strokeCompositeShader.render(strokeTextureId = scratchTextureId)
            }
        }
    }

    fun stampRingDabsIntoTiles(
        targetTileMap: TileMap,
        ringBuffer: NativeDabRingBuffer,
        startIndex: Int,
        count: Int,
        onTilesResolved: ((List<RasterTile>) -> Unit)? = null
    ) {
        stampRingDabsIntoTiles(targetTileMap, ringBuffer, startIndex.toLong(), count, onTilesResolved)
    }

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
                val visibleTiles = layer.tileMap.getVisibleTiles(visibleBounds, maxRestores = Int.MAX_VALUE)
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
            tileArrayBlitShader.releaseGl()
            thumbnailRenderer.releaseGl()
            patchBlitShader.releaseGl()
            isGlInitialized = false
        }
    }
}

class TileDabBucket(
    var tx: Int = 0,
    var ty: Int = 0,
    var tile: RasterTile? = null
) {
    val eraserDabs = mutableListOf<BrushDab>()
    val brushDabs = mutableListOf<BrushDab>()

    var eraserIndices = IntArray(64)
    var eraserCount = 0

    var brushIndices = IntArray(128)
    var brushCount = 0

    fun addEraserIndex(idx: Int) {
        if (eraserCount >= eraserIndices.size) {
            eraserIndices = eraserIndices.copyOf(eraserIndices.size * 2)
        }
        eraserIndices[eraserCount++] = idx
    }

    fun addBrushIndex(idx: Int) {
        if (brushCount >= brushIndices.size) {
            brushIndices = brushIndices.copyOf(brushIndices.size * 2)
        }
        brushIndices[brushCount++] = idx
    }

    fun reset(tx: Int, ty: Int, tile: RasterTile) {
        this.tx = tx
        this.ty = ty
        this.tile = tile
        eraserDabs.clear()
        brushDabs.clear()
        eraserCount = 0
        brushCount = 0
    }
}
