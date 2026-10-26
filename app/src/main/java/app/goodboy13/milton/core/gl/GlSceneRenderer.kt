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
import app.goodboy13.milton.core.native.MiltonNative
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


    fun resetSmudgeCarrier() {
        // Maintained for backward compatibility; GPU SmudgeShader advects tile pixels directly
    }

    private var scratchFboId: Int = 0
    private var scratchTextureId: Int = 0
    private val pickPixelBuf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())

    private val tileOrthoMatrix = FloatArray(16)
    private val finalMvpMatrix = FloatArray(16)
    private val identityMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }

    // Reusable buffers on GL render thread to eliminate frame-rate GC allocations
    private val reusableVisibleTiles = mutableListOf<RasterTile>()
    var lastDrawCalls: Int = 0
        private set
    private val reusableBatchTiles = ArrayList<RasterTile>()
    private val reusableFallbackTiles = ArrayList<RasterTile>()
    private val reusableAffectedTiles = mutableListOf<RasterTile>()
    private val packetDirectBuffer = ByteBuffer.allocateDirect(2048 * 20).order(ByteOrder.nativeOrder())
    private var nativeBucketHeaders = IntArray(256 * 6)
    private var nativeDabIndices = IntArray(16384)
    private val compatRingBuffer = NativeDabRingBuffer(capacity = 8192)

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

    fun setGrainParams(type: Int, scale: Float, depth: Float) {
        dabShader.setGrainParams(type, scale, depth)
    }

    fun computeFinalMvpMatrix(viewport: Viewport, bufferInfo: BufferInfo, transform: FloatArray): FloatArray {
        if (viewport.nativePtr != 0L) {
            MiltonNative.viewportComputeFinalMvp(
                viewport.nativePtr,
                bufferInfo.width.toFloat(),
                bufferInfo.height.toFloat(),
                transform,
                finalMvpMatrix
            )
        }
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
        dabShader.beginBatch(mvp)
        for (dab in dabs) {
            if (dab.isEraser || dab.brushMode == 3) continue // Eraser and Smudge are never drawn into front buffer overlay

            dabShader.renderDabFast(
                centerX = dab.x,
                centerY = dab.y,
                radius = dab.radius,
                colorRgb = dab.colorRgb,
                alpha = dab.alpha * activeLayerOpacity,
                hardness = dab.hardness,
                brushMode = dab.brushMode,
                pressure = dab.pressure,
                isEraser = false
            )
        }
        dabShader.endBatch()
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
        dabShader.beginBatch(mvp)
        ringBuffer.forEachDab(startHead, count) { _, x, y, radius, alpha, colorRgb, hardness, brushMode, pressure, _, isEraser ->
            if (!isEraser && brushMode != 3) {
                dabShader.renderDabFast(
                    centerX = x,
                    centerY = y,
                    radius = radius,
                    colorRgb = colorRgb,
                    alpha = alpha * activeLayerOpacity,
                    hardness = hardness,
                    brushMode = brushMode,
                    pressure = pressure,
                    isEraser = false
                )
            }
        }
        dabShader.endBatch()
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
        selectionState: app.goodboy13.milton.core.selection.SelectionState? = null,
        nativeDoc: app.goodboy13.milton.core.document.NativeMiltonDocument? = null
    ) {
        var drawCallCounter = 0
        val showBorders = false
        val forceOverview = false
        val allowArrayBlit = true

        val mvp = computeFinalMvpMatrix(viewport, bufferInfo, transform)
        val textureArrayId = GlTexturePool.textureArrayId
        val useArrayBlit = allowArrayBlit && GlTexturePool.usesTextureArray && tileArrayBlitShader.isReady && textureArrayId != 0

        if (useArrayBlit) {
            tileArrayBlitShader.begin(mvp, true, textureArrayId, showBorders)

            if (nativeDoc != null && nativeDoc.isValid && selectionState == null) {
                for (layer in layers) {
                    if (layer.isVisible && layer.opacity > 0.001f && layer.tileMap.onDiskCount > 0) {
                        layer.tileMap.getVisibleTiles(visibleBounds, reusableVisibleTiles, maxRestores = 4)
                    }
                }

                val count = nativeDoc.generateRenderPacket(
                    viewLeft = visibleBounds.left,
                    viewTop = visibleBounds.top,
                    viewRight = visibleBounds.right,
                    viewBottom = visibleBounds.bottom,
                    zoom = viewport.zoom,
                    forceOverview = forceOverview,
                    outDirectBuf = packetDirectBuffer
                )
                if (count > 0) {
                    tileArrayBlitShader.renderPacketDirect(packetDirectBuffer, count)
                    drawCallCounter++
                }
                tileArrayBlitShader.end()
                lastDrawCalls = drawCallCounter
                return
            }

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
                    reusableFallbackTiles.clear()
                    for (tile in visibleTiles) {
                        if (!tile.isInitialized && tile.isOnDisk) {
                            tile.ensureResident(layer.tileMap.cacheDir)
                        }
                        if (!tile.isInitialized || !tile.hasContent) continue
                        if (isLayerLiquifying &&
                            tile.coord.tx in liquifyMinTx..liquifyMaxTx &&
                            tile.coord.ty in liquifyMinTy..liquifyMaxTy
                        ) {
                            continue
                        }
                        if (tile.sliceIndex >= 0) {
                            reusableBatchTiles.add(tile)
                        } else {
                            reusableFallbackTiles.add(tile)
                        }
                    }

                    if (reusableBatchTiles.isNotEmpty()) {
                        tileArrayBlitShader.renderTilesBatch(reusableBatchTiles, layer.opacity, TileCoord.TILE_SIZE.toFloat()); drawCallCounter++
                    }
                    if (reusableFallbackTiles.isNotEmpty()) {
                        tileArrayBlitShader.end()
                        tileBlitShader.begin(mvp, true, backgroundColorRgb = 0, showBorders = showBorders)
                        tileBlitShader.setOpacity(layer.opacity)
                        for (tile in reusableFallbackTiles) {
                            tileBlitShader.renderTile(
                                worldLeft = tile.coord.worldLeft,
                                worldTop = tile.coord.worldTop,
                                textureId = tile.textureId
                            ); drawCallCounter++
                        }
                        tileBlitShader.end()
                        tileArrayBlitShader.begin(mvp, true, textureArrayId, showBorders)
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

    fun stampDabsIntoTiles(
        targetTileMap: TileMap,
        dabs: List<BrushDab>,
        onTilesResolved: ((Collection<RasterTile>) -> Unit)? = null
    ) {
        if (dabs.isEmpty()) return
        compatRingBuffer.reset()
        for (dab in dabs) {
            compatRingBuffer.pushDab(dab)
        }
        val onResolvedAdapter: ((List<RasterTile>) -> Unit)? = onTilesResolved?.let { cb ->
            { list -> cb(list) }
        }
        stampRingDabsIntoTiles(
            targetTileMap = targetTileMap,
            ringBuffer = compatRingBuffer,
            startHead = 0L,
            count = dabs.size,
            onTilesResolved = onResolvedAdapter
        )
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

        if (MiltonNative.isLoaded) {
            val requiredHeaders = 256 * 6
            if (nativeBucketHeaders.size < requiredHeaders) {
                nativeBucketHeaders = IntArray(requiredHeaders)
            }
            if (nativeDabIndices.size < count * 4) {
                nativeDabIndices = IntArray(count * 4 + 1024)
            }

            val tileSize = TileCoord.TILE_SIZE.toFloat()
            val bucketCount = MiltonNative.brushBinRingDabs(
                ringBuffer.byteBuffer,
                ringBuffer.capacity,
                startHead,
                count,
                tileSize,
                nativeBucketHeaders,
                nativeDabIndices
            )

            if (bucketCount > 0) {
                reusableAffectedTiles.clear()
                for (b in 0 until bucketCount) {
                    val hOffset = b * 6
                    val tx = nativeBucketHeaders[hOffset + 0]
                    val ty = nativeBucketHeaders[hOffset + 1]
                    val tile = targetTileMap.getOrCreateTile(tx, ty)
                    tile.ensureResident(targetTileMap.cacheDir)
                    reusableAffectedTiles.add(tile)
                }

                onTilesResolved?.invoke(reusableAffectedTiles)

                for (b in 0 until bucketCount) {
                    val hOffset = b * 6
                    val tx = nativeBucketHeaders[hOffset + 0]
                    val ty = nativeBucketHeaders[hOffset + 1]
                    val eraserCount = nativeBucketHeaders[hOffset + 2]
                    val brushCount = nativeBucketHeaders[hOffset + 3]
                    val eraserStart = nativeBucketHeaders[hOffset + 4]
                    val brushStart = nativeBucketHeaders[hOffset + 5]
                    val tile = reusableAffectedTiles[b]

                    if (brushCount > 0) {
                        tile.hasContent = true
                    }

                    // 1. Eraser dabs
                    if (eraserCount > 0) {
                        tile.bindFboDirect()
                        if (dabShader.usesFramebufferFetch) {
                            GLES30.glDisable(GLES30.GL_BLEND)
                        } else {
                            GLES30.glEnable(GLES30.GL_BLEND)
                            GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
                            GLES30.glBlendFunc(GLES30.GL_ZERO, GLES30.GL_ONE_MINUS_SRC_ALPHA)
                        }

                        dabShader.beginBatch(tileOrthoMatrix)
                        for (k in 0 until eraserCount) {
                            val slot = nativeDabIndices[eraserStart + k]
                            val localX = ringBuffer.getXBySlot(slot) - (tx * tileSize)
                            val localY = ringBuffer.getYBySlot(slot) - (ty * tileSize)
                            dabShader.renderDabFast(
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
                                isEraser = true
                            )
                        }
                        dabShader.endBatch()
                        if (dabShader.usesFramebufferFetch) {
                            GLES30.glEnable(GLES30.GL_BLEND)
                        }
                    }

                    // 2. Brush dabs
                    if (brushCount > 0) {
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

                        dabShader.beginBatch(tileOrthoMatrix)
                        for (k in 0 until brushCount) {
                            val slot = nativeDabIndices[brushStart + k]
                            val localX = ringBuffer.getXBySlot(slot) - (tx * tileSize)
                            val localY = ringBuffer.getYBySlot(slot) - (ty * tileSize)
                            dabShader.renderDabFast(
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
                                isEraser = false
                            )
                        }
                        dabShader.endBatch()

                        if (dabShader.usesFramebufferFetch) {
                            GLES30.glEnable(GLES30.GL_BLEND)
                        }

                        tile.bindFboDirect()
                        strokeCompositeShader.render(strokeTextureId = scratchTextureId)
                    }
                }
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

            val exportMvp = FloatArray(16)
            if (viewport.nativePtr != 0L) {
                MiltonNative.viewportComputeFinalMvp(
                    viewport.nativePtr,
                    screenW,
                    screenH,
                    identityMatrix,
                    exportMvp
                )
            }

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
