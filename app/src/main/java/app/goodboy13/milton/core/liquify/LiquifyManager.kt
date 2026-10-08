package app.goodboy13.milton.core.liquify

import android.util.Log
import app.goodboy13.milton.core.native.MiltonNative
import app.goodboy13.milton.core.tile.TileCoord
import app.goodboy13.milton.core.tile.TileMap
import app.goodboy13.milton.ui.MiltonCanvasView
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Coordinates multi-layer continuous raster deformation for the Liquify tool.
 * Warps tile regions seamlessly across 512x512 tile boundaries using libmilton_core.so.
 */
class LiquifyManager {
    companion object {
        private const val TAG = "LiquifyManager"
    }

    var mode: MiltonNative.LiquifyMode = MiltonNative.LiquifyMode.PUSH

    private var isStrokeActive: Boolean = false
    private var lastWorldX: Float = 0f
    private var lastWorldY: Float = 0f
    private var targetLayerIds: Set<Long> = emptySet()

    // Key: (layerId to TileCoord) -> Original tile raw bytes before this continuous stroke started
    private val strokeOriginalTiles = mutableMapOf<Pair<Long, TileCoord>, ByteArray>()

    // Key: (layerId to TileCoord) -> Working CPU tile raw bytes during this continuous stroke
    private val strokeWorkingTiles = mutableMapOf<Pair<Long, TileCoord>, ByteArray>()

    val isStrokeInProgress: Boolean
        get() = isStrokeActive

    fun startStroke(
        worldX: Float,
        worldY: Float,
        targets: Set<Long>
    ) {
        isStrokeActive = true
        lastWorldX = worldX
        lastWorldY = worldY
        targetLayerIds = targets
        strokeOriginalTiles.clear()
        strokeWorkingTiles.clear()
    }

    fun applyDab(
        canvasView: MiltonCanvasView,
        worldX: Float,
        worldY: Float,
        radius: Float,
        strength: Float,
        pressure: Float
    ) {
        if (!isStrokeActive || !MiltonNative.isLoaded) return

        val dx = worldX - lastWorldX
        val dy = worldY - lastWorldY
        val dist = hypot(dx, dy)

        // For Push mode, require slight displacement
        if (mode == MiltonNative.LiquifyMode.PUSH && dist < 0.5f) {
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

        val pad = 2
        val pLeft = floor(worldX - radius - pad).toInt()
        val pTop = floor(worldY - radius - pad).toInt()
        val pRight = ceil(worldX + radius + pad).toInt()
        val pBottom = ceil(worldY + radius + pad).toInt()

        val pW = (pRight - pLeft).coerceAtLeast(1)
        val pH = (pBottom - pTop).coerceAtLeast(1)

        val minTx = floor(pLeft.toFloat() / TileCoord.TILE_SIZE).toInt()
        val maxTx = floor(pRight.toFloat() / TileCoord.TILE_SIZE).toInt()
        val minTy = floor(pTop.toFloat() / TileCoord.TILE_SIZE).toInt()
        val maxTy = floor(pBottom.toFloat() / TileCoord.TILE_SIZE).toInt()

        val targets = if (targetLayerIds.isEmpty()) {
            setOf(canvasView.layerManager.activeLayerId)
        } else {
            targetLayerIds
        }

        canvasView.renderer.runOnGlThread {
            try {
                for (layerId in targets) {
                    val layer = canvasView.layerManager.layers.find { it.id == layerId } ?: continue

                    // 1. Ensure tiles exist and cache CPU buffer ONCE per tile on first touch in this stroke
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
                                canvasView.renderer.undoManager.registerPreModifiedTile(
                                    layerId,
                                    coord,
                                    originalBytes.clone()
                                )
                            }
                        }
                    }

                    // 2. Extract dab region into contiguous RGBA buffer directly from CPU working buffers (ZERO glReadPixels!)
                    val patchRgba = ByteArray(pW * pH * 4)
                    val origRgba = if (mode == MiltonNative.LiquifyMode.RECONSTRUCT) {
                        ByteArray(pW * pH * 4)
                    } else null

                    for (ty in minTy..maxTy) {
                        for (tx in minTx..maxTx) {
                            val coord = TileCoord(tx, ty)
                            val key = layerId to coord
                            val tileBytes = strokeWorkingTiles[key] ?: continue
                            MiltonNative.extractTileRegion(
                                tileBytes,
                                tx * TileCoord.TILE_SIZE,
                                ty * TileCoord.TILE_SIZE,
                                patchRgba,
                                pW, pH,
                                pLeft, pTop
                            )
                            if (origRgba != null) {
                                val origTileBytes = strokeOriginalTiles[key] ?: tileBytes
                                MiltonNative.extractTileRegion(
                                    origTileBytes,
                                    tx * TileCoord.TILE_SIZE,
                                    ty * TileCoord.TILE_SIZE,
                                    origRgba,
                                    pW, pH,
                                    pLeft, pTop
                                )
                            }
                        }
                    }

                    // 3. Warp pixels via Rust SIMD kernel
                    val effectiveStrength = (strength * pressure.coerceIn(0.1f, 1.0f)).coerceIn(0.01f, 1.0f)
                    MiltonNative.liquifyPatch(
                        patchRgba,
                        origRgba,
                        pW, pH,
                        pLeft.toFloat(), pTop.toFloat(),
                        worldX, worldY,
                        radius,
                        effectiveStrength,
                        mode.id,
                        effDirX, effDirY
                    )

                    // 4. Blit warped region back into intersecting tiles in CPU buffer and upload to GL texture
                    for (ty in minTy..maxTy) {
                        for (tx in minTx..maxTx) {
                            val coord = TileCoord(tx, ty)
                            val key = layerId to coord
                            val tileBytes = strokeWorkingTiles[key] ?: continue
                            MiltonNative.blitPatchToTileOverwrite(
                                tileBytes,
                                tx * TileCoord.TILE_SIZE,
                                ty * TileCoord.TILE_SIZE,
                                patchRgba,
                                pW, pH,
                                pLeft, pTop
                            )
                            val tile = layer.tileMap.getExistingTile(tx, ty) ?: continue
                            tile.writePixels(tileBytes)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error applying liquify dab", e)
            } finally {
                canvasView.post { canvasView.requestRedraw() }
            }
        }
        // Immediately request redraw on the front-buffered renderer so the GL frame scheduler triggers!
        canvasView.requestRedraw()
    }

    fun finishStroke(canvasView: MiltonCanvasView) {
        if (!isStrokeActive) return
        isStrokeActive = false

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

        canvasView.renderer.runOnGlThread {
            try {
                // Restore original tiles before stroke
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
                canvasView.post { canvasView.requestRedraw() }
            }
        }
        canvasView.requestRedraw()
    }
}
