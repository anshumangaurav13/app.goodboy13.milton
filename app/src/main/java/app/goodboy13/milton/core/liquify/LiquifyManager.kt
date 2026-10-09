package app.goodboy13.milton.core.liquify

import android.os.Handler
import android.os.Looper
import android.util.Log
import app.goodboy13.milton.core.native.MiltonNative
import app.goodboy13.milton.core.tile.TileCoord
import app.goodboy13.milton.core.tile.TileMap
import app.goodboy13.milton.ui.MiltonCanvasView
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.floor

/**
 * Coordinates multi-layer continuous raster deformation for the Liquify tool.
 * Warps tile regions seamlessly across 512x512 tile boundaries using libmilton_core.so.
 *
 * Utilizes a high-performance native LiquifySession in Rust:
 * - Direct tile memory management without temporary patch allocations or JNI multi-megabyte churn.
 * - Continuous path interpolation eliminating discrete stepping/jagged potholes along strokes.
 * - Real-time bilinear resampling during active drag (120 FPS fluid response), followed by
 *   pristine Catmull-Rom bicubic spline on pen-up commit.
 * - Zero iterative blur accumulation, zero edge clamping/tube artifacts across tile borders.
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

    // Key: (layerId to TileCoord) -> Original tile raw bytes before this continuous stroke started (for Undo/Cancel)
    private val strokeOriginalTiles = mutableMapOf<Pair<Long, TileCoord>, ByteArray?>()

    // Set of tiles already registered in the native LiquifySession
    private val registeredTiles = mutableSetOf<Pair<Long, TileCoord>>()

    // Pre-allocated reusable buffer for reading 512x512 tile pixels from native session (zero GC churn)
    private val reusableTileBuffer = ByteArray(TileCoord.TILE_SIZE * TileCoord.TILE_SIZE * 4)
    // Pre-allocated blank tile buffer for registering empty canvas tiles without GPU readbacks or allocations
    private val emptyTileBuffer = ByteArray(TileCoord.TILE_SIZE * TileCoord.TILE_SIZE * 4)

    // Continuous point path accumulator [x0, y0, x1, y1, ...]
    private val pendingPoints = ArrayList<Float>()
    private val isGlTaskQueued = AtomicBoolean(false)

    // Continuous deformation while stylus is held pressed
    private val mainHandler = Handler(Looper.getMainLooper())
    private var continuousTicker: Runnable? = null

    val isStrokeInProgress: Boolean
        get() = isStrokeActive

    fun startStroke(
        worldX: Float,
        worldY: Float,
        radius: Float = 50f,
        strength: Float = 0.5f,
        pressure: Float = 1.0f,
        targets: Set<Long>,
        canvasView: MiltonCanvasView? = null
    ) {
        isStrokeActive = true
        lastWorldX = worldX
        lastWorldY = worldY
        lastRadius = radius
        lastStrength = strength
        lastPressure = pressure
        targetLayerIds = targets
        strokeOriginalTiles.clear()
        registeredTiles.clear()

        synchronized(pendingPoints) {
            pendingPoints.clear()
            pendingPoints.add(worldX)
            pendingPoints.add(worldY)
        }
        isGlTaskQueued.set(false)

        if (canvasView != null) {
            val actualTargets = if (targets.isEmpty()) {
                setOf(canvasView.layerManager.activeLayerId)
            } else {
                targets
            }
            // Pre-register tiles directly under the initial touch on the GL thread
            canvasView.renderer.runOnGlThread {
                if (MiltonNative.isLoaded) {
                    MiltonNative.liquifySessionBegin()
                    registerTilesForPoints(canvasView, floatArrayOf(worldX, worldY), radius, actualTargets)
                }
            }
            startContinuousTicker(canvasView)
        }
    }

    /**
     * Appends a sub-frame point to the active stroke path.
     */
    fun addPoint(
        worldX: Float,
        worldY: Float,
        radius: Float,
        strength: Float,
        pressure: Float
    ) {
        if (!isStrokeActive || !MiltonNative.isLoaded) return
        lastWorldX = worldX
        lastWorldY = worldY
        lastRadius = radius
        lastStrength = strength
        lastPressure = pressure

        synchronized(pendingPoints) {
            pendingPoints.add(worldX)
            pendingPoints.add(worldY)
        }
    }

    /**
     * Triggers execution of all accumulated points on the GL thread and requests an immediate frame redraw.
     */
    fun flushDab(canvasView: MiltonCanvasView) {
        if (!isStrokeActive || !MiltonNative.isLoaded) return

        if (isGlTaskQueued.compareAndSet(false, true)) {
            val targets = if (targetLayerIds.isEmpty()) {
                setOf(canvasView.layerManager.activeLayerId)
            } else {
                targetLayerIds
            }

            canvasView.renderer.runOnGlThread {
                processGlDab(canvasView, targets)
            }
        }
        canvasView.requestRedraw()
    }

    /**
     * Backward-compatible helper for single dab submission.
     */
    fun applyDab(
        canvasView: MiltonCanvasView,
        worldX: Float,
        worldY: Float,
        radius: Float,
        strength: Float,
        pressure: Float,
        isContinuous: Boolean = false
    ) {
        addPoint(worldX, worldY, radius, strength, pressure)
        flushDab(canvasView)
    }

    private fun processGlDab(
        canvasView: MiltonCanvasView,
        targets: Set<Long>
    ) {
        try {
            val pts: FloatArray
            synchronized(pendingPoints) {
                if (pendingPoints.size < 2) {
                    return
                }
                pts = pendingPoints.toFloatArray()
                val lastX = pts[pts.size - 2]
                val lastY = pts[pts.size - 1]
                pendingPoints.clear()
                // Preserve the end point as the anchor for the next frame so cross-frame strokes never break
                pendingPoints.add(lastX)
                pendingPoints.add(lastY)
            }

            val radius = lastRadius
            val effectiveStrength = (lastStrength * lastPressure.coerceIn(0.2f, 1.0f)).coerceIn(0.01f, 1.0f)
            val targetArray = targets.toLongArray()

            // 1. Ensure all intersecting tiles are resident and registered in native session
            registerTilesForPoints(canvasView, pts, radius, targets)

            // 2. Perform native deformation along the continuous path in Rust
            val dirtyArray = MiltonNative.liquifySessionApplyPath(
                targetArray,
                pts,
                radius,
                effectiveStrength,
                mode.id
            )

            // 3. For each dirty tile (layerId, tx, ty), upload updated pixels to Tile texture
            if (dirtyArray != null && dirtyArray.isNotEmpty()) {
                val count = dirtyArray.size / 3
                for (i in 0 until count) {
                    val layerId = dirtyArray[i * 3]
                    val tx = dirtyArray[i * 3 + 1].toInt()
                    val ty = dirtyArray[i * 3 + 2].toInt()

                    val layer = canvasView.layerManager.layers.find { it.id == layerId } ?: continue
                    if (MiltonNative.liquifySessionGetTilePixels(layerId, tx, ty, reusableTileBuffer)) {
                        val tile = layer.tileMap.getOrCreateTile(tx, ty)
                        tile.writePixels(reusableTileBuffer)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error applying liquify path", e)
        } finally {
            isGlTaskQueued.set(false)
            val hasMore: Boolean
            synchronized(pendingPoints) {
                hasMore = pendingPoints.size > 2
            }
            if (hasMore && isStrokeActive) {
                flushDab(canvasView)
            }
        }
    }

    private fun registerTilesForPoints(
        canvasView: MiltonCanvasView,
        pts: FloatArray,
        radius: Float,
        targets: Set<Long>
    ) {
        if (pts.size < 2) return
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE

        var i = 0
        while (i < pts.size) {
            val x = pts[i]
            val y = pts[i + 1]
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            i += 2
        }

        val pad = 48f
        val boundMinX = minX - radius - pad
        val boundMaxX = maxX + radius + pad
        val boundMinY = minY - radius - pad
        val boundMaxY = maxY + radius + pad

        val minTx = floor(boundMinX / TileCoord.TILE_SIZE).toInt()
        val maxTx = floor((boundMaxX - 1f) / TileCoord.TILE_SIZE).toInt()
        val minTy = floor(boundMinY / TileCoord.TILE_SIZE).toInt()
        val maxTy = floor((boundMaxY - 1f) / TileCoord.TILE_SIZE).toInt()

        for (layerId in targets) {
            val layer = canvasView.layerManager.layers.find { it.id == layerId } ?: continue

            for (ty in minTy..maxTy) {
                for (tx in minTx..maxTx) {
                    val coord = TileCoord(tx, ty)
                    val key = layerId to coord
                    if (registeredTiles.add(key)) {
                        val existingTile = layer.tileMap.getExistingTile(tx, ty)
                        val hasData = existingTile != null && ((existingTile.isInitialized && existingTile.hasContent) || existingTile.isOnDisk)

                        if (hasData) {
                            val tile = existingTile!!
                            tile.ensureResident(layer.tileMap.cacheDir)
                            val originalBytes = tile.readPixels()
                            strokeOriginalTiles[key] = originalBytes.clone()
                            canvasView.renderer.undoManager.registerPreModifiedTile(
                                layerId,
                                coord,
                                originalBytes.clone()
                            )
                            MiltonNative.liquifySessionRegisterTile(
                                layerId,
                                tx,
                                ty,
                                originalBytes
                            )
                        } else {
                            strokeOriginalTiles[key] = null
                            canvasView.renderer.undoManager.registerPreModifiedTile(
                                layerId,
                                coord,
                                null
                            )
                            MiltonNative.liquifySessionRegisterTile(
                                layerId,
                                tx,
                                ty,
                                emptyTileBuffer
                            )
                        }
                    }
                }
            }
        }
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
                // 1. Process any remaining pending path points
                processGlDab(canvasView, targets)

                // 2. High-quality Catmull-Rom resampling pass for pristine finish
                val dirtyArray = MiltonNative.liquifySessionResampleFinal(targets.toLongArray())
                if (dirtyArray != null && dirtyArray.isNotEmpty()) {
                    val count = dirtyArray.size / 3
                    for (i in 0 until count) {
                        val layerId = dirtyArray[i * 3]
                        val tx = dirtyArray[i * 3 + 1].toInt()
                        val ty = dirtyArray[i * 3 + 2].toInt()

                        val layer = canvasView.layerManager.layers.find { it.id == layerId } ?: continue
                        if (MiltonNative.liquifySessionGetTilePixels(layerId, tx, ty, reusableTileBuffer)) {
                            val tile = layer.tileMap.getOrCreateTile(tx, ty)
                            tile.writePixels(reusableTileBuffer)
                        }
                    }
                }

                // 3. Commit undo
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
                if (MiltonNative.isLoaded) {
                    MiltonNative.liquifySessionEnd()
                }
                registeredTiles.clear()
                strokeOriginalTiles.clear()
                synchronized(pendingPoints) { pendingPoints.clear() }
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
                    if (raw != null) {
                        tile.writePixels(raw)
                    } else {
                        tile.clear()
                        tile.hasContent = false
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error canceling liquify stroke", e)
            } finally {
                if (MiltonNative.isLoaded) {
                    MiltonNative.liquifySessionEnd()
                }
                registeredTiles.clear()
                strokeOriginalTiles.clear()
                synchronized(pendingPoints) { pendingPoints.clear() }
                canvasView.post { canvasView.requestRedraw() }
            }
        }
        canvasView.requestRedraw()
    }
}
