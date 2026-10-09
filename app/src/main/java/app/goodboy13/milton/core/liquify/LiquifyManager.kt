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
 * Utilizes a high-performance native LiquifySession in Rust:
 * - Direct tile memory management without temporary patch allocations or JNI multi-megabyte churn.
 * - Unified world-space displacement field sampled from pristine pre-stroke tiles using Catmull-Rom.
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
        synchronized(this) {
            pendingWorldX = worldX
            pendingWorldY = worldY
            pendingRadius = radius
            pendingStrength = strength
            pendingPressure = pressure
            pendingEffDirX = 0f
            pendingEffDirY = 0f
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
                    registerTilesInArea(canvasView, worldX, worldY, worldX, worldY, radius, actualTargets)
                }
            }
            startContinuousTicker(canvasView)
        }
    }

    /**
     * Ensures all tiles intersecting the swept dab segment [from -> to] plus margin
     * are resident in memory and registered in the native LiquifySession.
     */
    private fun registerTilesInArea(
        canvasView: MiltonCanvasView,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        radius: Float,
        targets: Set<Long>
    ) {
        val pad = 48
        val minX = minOf(x1, x2) - radius - pad
        val maxX = maxOf(x1, x2) + radius + pad
        val minY = minOf(y1, y2) - radius - pad
        val maxY = maxOf(y1, y2) + radius + pad

        val minTx = floor(minX / TileCoord.TILE_SIZE).toInt()
        val maxTx = floor((maxX - 1f) / TileCoord.TILE_SIZE).toInt()
        val minTy = floor(minY / TileCoord.TILE_SIZE).toInt()
        val maxTy = floor((maxY - 1f) / TileCoord.TILE_SIZE).toInt()

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

        val prevX = lastWorldX
        val prevY = lastWorldY
        val dx = worldX - prevX
        val dy = worldY - prevY
        val dist = hypot(dx, dy)

        // For Push mode without continuous movement, require non-zero step
        if (mode == MiltonNative.LiquifyMode.PUSH && dist < 0.1f && !isContinuous) {
            return
        }

        // Clamp single-step delta for Push to prevent wild tearing on sudden touch jumps
        val maxStep = radius * 1.0f
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

        // Store latest parameters and accumulate movement deltas (never drop motion!)
        synchronized(this) {
            pendingWorldX = worldX
            pendingWorldY = worldY
            pendingRadius = radius
            pendingStrength = strength
            pendingPressure = pressure
            pendingEffDirX += effDirX
            pendingEffDirY += effDirY
        }

        scheduleGlDab(canvasView, prevX, prevY)
    }

    private fun scheduleGlDab(
        canvasView: MiltonCanvasView,
        fromX: Float,
        fromY: Float
    ) {
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
                val dabWorldX: Float
                val dabWorldY: Float
                val dabRadius: Float
                val dabStrength: Float
                val dabPressure: Float
                val dabEffDirX: Float
                val dabEffDirY: Float

                synchronized(this) {
                    dabWorldX = pendingWorldX
                    dabWorldY = pendingWorldY
                    dabRadius = pendingRadius
                    dabStrength = pendingStrength
                    dabPressure = pendingPressure
                    dabEffDirX = pendingEffDirX
                    dabEffDirY = pendingEffDirY
                    pendingEffDirX = 0f
                    pendingEffDirY = 0f
                }

                // 1. Ensure tiles along move segment are resident and registered in native session
                registerTilesInArea(canvasView, fromX, fromY, dabWorldX, dabWorldY, dabRadius, targets)

                val effectiveStrength = (dabStrength * dabPressure.coerceIn(0.2f, 1.0f)).coerceIn(0.01f, 1.0f)
                val targetArray = targets.toLongArray()

                // 2. Perform native deformation directly in Rust memory with zero patch allocations
                val dirtyArray = MiltonNative.liquifySessionApplyDab(
                    targetArray,
                    dabWorldX,
                    dabWorldY,
                    dabRadius,
                    effectiveStrength,
                    mode.id,
                    dabEffDirX,
                    dabEffDirY
                )

                // 3. For each dirty tile (layerId, tx, ty), upload updated pixels to Tile
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
                Log.e(TAG, "Error applying liquify dab", e)
            } finally {
                isGlTaskQueued.set(false)
                canvasView.post { canvasView.requestRedraw() }

                // Drain any additional motion deltas accumulated during execution
                if (isStrokeActive) {
                    val hasPendingMotion = synchronized(this) {
                        hypot(pendingEffDirX, pendingEffDirY) > 0.05f
                    }
                    if (hasPendingMotion) {
                        scheduleGlDab(canvasView, lastWorldX, lastWorldY)
                    }
                }
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
                if (MiltonNative.isLoaded) {
                    MiltonNative.liquifySessionEnd()
                }
                registeredTiles.clear()
                strokeOriginalTiles.clear()
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
                canvasView.post { canvasView.requestRedraw() }
            }
        }
        canvasView.requestRedraw()
    }
}
