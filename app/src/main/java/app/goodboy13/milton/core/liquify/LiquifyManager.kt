package app.goodboy13.milton.core.liquify

import android.os.Handler
import android.os.Looper
import android.util.Log
import app.goodboy13.milton.core.native.MiltonNative
import app.goodboy13.milton.core.tile.TileCoord
import app.goodboy13.milton.core.tile.TileMap
import app.goodboy13.milton.ui.MiltonCanvasView
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Coordinates multi-layer continuous raster deformation for the Liquify tool.
 * Warps tile regions seamlessly across 512x512 tile boundaries using libmilton_core.so.
 *
 * Employs a 2D continuous displacement field sampled directly from pristine pre-stroke
 * tiles using Catmull-Rom bicubic interpolation, guaranteeing ZERO iterative blur accumulation.
 */
class LiquifyManager {
    companion object {
        private const val TAG = "LiquifyManager"
    }

    var mode: MiltonNative.LiquifyMode = MiltonNative.LiquifyMode.PUSH

    private var isStrokeActive: Boolean = false
    private var lastWorldX: Float = 0f
    private var lastWorldY: Float = 0f
    private var lastRadius: Float = 0f
    private var lastStrength: Float = 0f
    private var lastPressure: Float = 0f
    private var targetLayerIds: Set<Long> = emptySet()

    // Key: (layerId to TileCoord) -> Original tile raw bytes before this continuous stroke started
    private val strokeOriginalTiles = mutableMapOf<Pair<Long, TileCoord>, ByteArray>()

    // Key: (layerId to TileCoord) -> Working CPU tile raw bytes during this continuous stroke
    private val strokeWorkingTiles = mutableMapOf<Pair<Long, TileCoord>, ByteArray>()

    // Key: (layerId to TileCoord) -> Cumulative 2D displacement field (dx, dy) floats during this continuous stroke
    private val strokeDisplacementTiles = mutableMapOf<Pair<Long, TileCoord>, FloatArray>()

    // Coalescing to prevent queuing unbounded heavy tasks during rapid 120Hz/240Hz stylus drag
    private val isGlTaskQueued = AtomicBoolean(false)
    @Volatile private var pendingWorldX: Float = 0f
    @Volatile private var pendingWorldY: Float = 0f
    @Volatile private var pendingRadius: Float = 0f
    @Volatile private var pendingStrength: Float = 0f
    @Volatile private var pendingPressure: Float = 0f
    @Volatile private var pendingEffDirX: Float = 0f
    @Volatile private var pendingEffDirY: Float = 0f

    // Continuous deformation while stylus is held pressed
    private val mainHandler = Handler(Looper.getMainLooper())
    private var continuousTicker: Runnable? = null

    val isStrokeInProgress: Boolean
        get() = isStrokeActive

    fun startStroke(
        worldX: Float,
        worldY: Float,
        targets: Set<Long>,
        canvasView: MiltonCanvasView? = null
    ) {
        isStrokeActive = true
        lastWorldX = worldX
        lastWorldY = worldY
        targetLayerIds = targets
        strokeOriginalTiles.clear()
        strokeWorkingTiles.clear()
        strokeDisplacementTiles.clear()
        isGlTaskQueued.set(false)

        if (canvasView != null) {
            startContinuousTicker(canvasView)
        }
    }

    fun applyDab(
        canvasView: MiltonCanvasView,
        worldX: Float,
        worldY: Float,
        radius: Float,
        strength: Float,
        pressure: Float,
        isContinuous: Boolean = false
    ) {
        if (!isStrokeActive || !MiltonNative.isLoaded) return

        val dx = worldX - lastWorldX
        val dy = worldY - lastWorldY
        val dist = hypot(dx, dy)

        // For Push mode without continuous movement, require non-zero step
        if (mode == MiltonNative.LiquifyMode.PUSH && dist < 0.2f && !isContinuous) {
            return
        }

        // Clamp displacement step for Push to avoid unnatural tearing on fast flick
        val maxStep = radius * 0.6f
        val (effDirX, effDirY) = if (dist > maxStep && dist > 0.001f) {
            val scale = maxStep / dist
            (dx * scale) to (dy * scale)
        } else {
            dx to dy
        }

        lastWorldX = worldX
        lastWorldY = worldY
        lastRadius = radius
        lastStrength = strength
        lastPressure = pressure

        // Store latest parameters for the GL worker
        pendingWorldX = worldX
        pendingWorldY = worldY
        pendingRadius = radius
        pendingStrength = strength
        pendingPressure = pressure
        pendingEffDirX = effDirX
        pendingEffDirY = effDirY

        // If a GL task is already queued or executing, coalesce by updating parameters
        if (!isGlTaskQueued.compareAndSet(false, true)) {
            canvasView.requestRedraw()
            return
        }

        val targets = if (targetLayerIds.isEmpty()) {
            setOf(canvasView.layerManager.activeLayerId)
        } else {
            targetLayerIds
        }

        canvasView.renderer.runOnGlThread {
            try {
                val dabWorldX = pendingWorldX
                val dabWorldY = pendingWorldY
                val dabRadius = pendingRadius
                val dabStrength = pendingStrength
                val dabPressure = pendingPressure
                val dabEffDirX = pendingEffDirX
                val dabEffDirY = pendingEffDirY

                // Generous padding to accommodate displacement without border clamping
                val pad = (dabRadius * 0.4f).toInt().coerceAtLeast(32)
                val pLeft = floor(dabWorldX - dabRadius - pad).toInt()
                val pTop = floor(dabWorldY - dabRadius - pad).toInt()
                val pRight = ceil(dabWorldX + dabRadius + pad).toInt()
                val pBottom = ceil(dabWorldY + dabRadius + pad).toInt()

                val pW = (pRight - pLeft).coerceAtLeast(1)
                val pH = (pBottom - pTop).coerceAtLeast(1)

                val minTx = floor(pLeft.toFloat() / TileCoord.TILE_SIZE).toInt()
                val maxTx = floor(pRight.toFloat() / TileCoord.TILE_SIZE).toInt()
                val minTy = floor(pTop.toFloat() / TileCoord.TILE_SIZE).toInt()
                val maxTy = floor(pBottom.toFloat() / TileCoord.TILE_SIZE).toInt()

                val effectiveStrength = (dabStrength * dabPressure.coerceIn(0.1f, 1.0f)).coerceIn(0.01f, 1.0f)

                for (layerId in targets) {
                    val layer = canvasView.layerManager.layers.find { it.id == layerId } ?: continue

                    // 1. Ensure tiles exist and cache original, working, and displacement CPU buffers ONCE per tile
                    for (ty in minTy..maxTy) {
                        for (tx in minTx..maxTx) {
                            val coord = TileCoord(tx, ty)
                            val key = layerId to coord
                            if (!strokeWorkingTiles.containsKey(key)) {
                                val tile = layer.tileMap.getOrCreateTile(tx, ty)
                                tile.ensureResident(layer.tileMap.cacheDir)
                                val originalBytes = tile.readPixels()
                                strokeOriginalTiles[key] = originalBytes.clone()
                                strokeWorkingTiles[key] = originalBytes.clone()
                                strokeDisplacementTiles[key] = FloatArray(TileCoord.TILE_SIZE * TileCoord.TILE_SIZE * 2)
                                canvasView.renderer.undoManager.registerPreModifiedTile(
                                    layerId,
                                    coord,
                                    originalBytes.clone()
                                )
                            }
                        }
                    }

                    // 2. Extract pristine original RGBA, working RGBA, and cumulative displacement field for this patch
                    val patchRgba = ByteArray(pW * pH * 4)
                    val origRgba = ByteArray(pW * pH * 4)
                    val dispPatch = FloatArray(pW * pH * 2)

                    for (ty in minTy..maxTy) {
                        for (tx in minTx..maxTx) {
                            val coord = TileCoord(tx, ty)
                            val key = layerId to coord

                            val tileWorkingBytes = strokeWorkingTiles[key]
                            if (tileWorkingBytes != null) {
                                MiltonNative.extractTileRegion(
                                    tileWorkingBytes,
                                    tx * TileCoord.TILE_SIZE,
                                    ty * TileCoord.TILE_SIZE,
                                    patchRgba,
                                    pW, pH,
                                    pLeft, pTop
                                )
                            }

                            val tileOrigBytes = strokeOriginalTiles[key]
                            if (tileOrigBytes != null) {
                                MiltonNative.extractTileRegion(
                                    tileOrigBytes,
                                    tx * TileCoord.TILE_SIZE,
                                    ty * TileCoord.TILE_SIZE,
                                    origRgba,
                                    pW, pH,
                                    pLeft, pTop
                                )
                            }

                            val tileDispFloats = strokeDisplacementTiles[key]
                            if (tileDispFloats != null) {
                                MiltonNative.extractTileDisplacement(
                                    tileDispFloats,
                                    tx * TileCoord.TILE_SIZE,
                                    ty * TileCoord.TILE_SIZE,
                                    dispPatch,
                                    pW, pH,
                                    pLeft, pTop
                                )
                            }
                        }
                    }

                    // 3. Warp pixels via Rust SIMD kernel with accumulated displacement & Catmull-Rom sampling
                    MiltonNative.liquifyPatch(
                        patchRgba,
                        origRgba,
                        dispPatch,
                        pW, pH,
                        pLeft.toFloat(), pTop.toFloat(),
                        dabWorldX, dabWorldY,
                        dabRadius,
                        effectiveStrength,
                        mode.id,
                        dabEffDirX, dabEffDirY
                    )

                    // 4. Blit updated displacement and warped RGBA back into intersecting tiles
                    for (ty in minTy..maxTy) {
                        for (tx in minTx..maxTx) {
                            val coord = TileCoord(tx, ty)
                            val key = layerId to coord

                            val tileDispFloats = strokeDisplacementTiles[key]
                            if (tileDispFloats != null) {
                                MiltonNative.blitTileDisplacementOverwrite(
                                    tileDispFloats,
                                    tx * TileCoord.TILE_SIZE,
                                    ty * TileCoord.TILE_SIZE,
                                    dispPatch,
                                    pW, pH,
                                    pLeft, pTop
                                )
                            }

                            val tileBytes = strokeWorkingTiles[key]
                            if (tileBytes != null) {
                                MiltonNative.blitPatchToTileOverwrite(
                                    tileBytes,
                                    tx * TileCoord.TILE_SIZE,
                                    ty * TileCoord.TILE_SIZE,
                                    patchRgba,
                                    pW, pH,
                                    pLeft, pTop
                                )
                                val tile = layer.tileMap.getExistingTile(tx, ty)
                                tile?.writePixels(tileBytes)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error applying liquify dab", e)
            } finally {
                isGlTaskQueued.set(false)
                canvasView.post { canvasView.requestRedraw() }
            }
        }
        canvasView.requestRedraw()
    }

    private fun startContinuousTicker(canvasView: MiltonCanvasView) {
        stopContinuousTicker()
        if (mode == MiltonNative.LiquifyMode.PUSH) return

        val ticker = object : Runnable {
            override fun run() {
                if (!isStrokeActive) return
                applyDab(
                    canvasView = canvasView,
                    worldX = lastWorldX,
                    worldY = lastWorldY,
                    radius = lastRadius,
                    strength = lastStrength,
                    pressure = lastPressure,
                    isContinuous = true
                )
                mainHandler.postDelayed(this, 16)
            }
        }
        continuousTicker = ticker
        mainHandler.postDelayed(ticker, 16)
    }

    private fun stopContinuousTicker() {
        continuousTicker?.let { mainHandler.removeCallbacks(it) }
        continuousTicker = null
    }

    fun finishStroke(canvasView: MiltonCanvasView) {
        if (!isStrokeActive) return
        isStrokeActive = false
        stopContinuousTicker()

        val targets = if (targetLayerIds.isEmpty()) {
            setOf(canvasView.layerManager.activeLayerId)
        } else {
            targetLayerIds
        }

        canvasView.renderer.runOnGlThread {
            try {
                val affected = mutableListOf<Pair<Long, TileMap>>()
                for (layerId in targets) {
                    val layer = canvasView.layerManager.layers.find { it.id == layerId } ?: continue
                    affected.add(layerId to layer.tileMap)
                }
                if (affected.isNotEmpty()) {
                    canvasView.renderer.undoManager.commitMultiLayerStroke(affected)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error finalizing liquify stroke", e)
            } finally {
                strokeOriginalTiles.clear()
                strokeWorkingTiles.clear()
                strokeDisplacementTiles.clear()
                canvasView.post {
                    canvasView.requestRedraw()
                    canvasView.onStrokeCompleted?.invoke(0)
                }
            }
        }
        canvasView.requestRedraw()
    }

    fun cancelStroke(canvasView: MiltonCanvasView) {
        if (!isStrokeActive) return
        isStrokeActive = false
        stopContinuousTicker()

        canvasView.renderer.runOnGlThread {
            try {
                for ((key, raw) in strokeOriginalTiles) {
                    val (layerId, coord) = key
                    val layer = canvasView.layerManager.layers.find { it.id == layerId } ?: continue
                    val tile = layer.tileMap.getExistingTile(coord.tx, coord.ty) ?: continue
                    tile.writePixels(raw)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error canceling liquify stroke", e)
            } finally {
                strokeOriginalTiles.clear()
                strokeWorkingTiles.clear()
                strokeDisplacementTiles.clear()
                canvasView.post { canvasView.requestRedraw() }
            }
        }
        canvasView.requestRedraw()
    }
}
