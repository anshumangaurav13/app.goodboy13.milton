package app.goodboy13.milton.core.selection

import android.graphics.Bitmap
import android.util.Log
import app.goodboy13.milton.core.model.Vec2
import app.goodboy13.milton.core.model.WorldRect
import app.goodboy13.milton.core.native.MiltonNative
import app.goodboy13.milton.core.tile.RasterTile
import app.goodboy13.milton.core.tile.TileCoord
import app.goodboy13.milton.ui.MiltonCanvasView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import kotlin.math.floor
import kotlin.math.roundToInt

data class TileSnapshot(
    val tx: Int,
    val ty: Int,
    val rawBytes: ByteArray
)

data class LayerPatch(
    val width: Int,
    val height: Int,
    val rgbaBytes: ByteArray,
    val previewBitmap: Bitmap
)

data class TransformSession(
    val polygon: List<Vec2>,
    val srcBounds: WorldRect,
    val targetLayerIds: Set<Long>,
    val originalTiles: Map<Long, List<TileSnapshot>>,
    val layerPatches: Map<Long, LayerPatch>,
    val translationX: Float = 0f,
    val translationY: Float = 0f,
    val scaleX: Float = 1.0f,
    val scaleY: Float = 1.0f,
    val rotationRad: Float = 0f,
    val pivotX: Float = srcBounds.centerX,
    val pivotY: Float = srcBounds.centerY,
    val flipH: Boolean = false,
    val flipV: Boolean = false
) {
    fun calculateDestBounds(): WorldRect {
        val effSx = if (flipH) -scaleX else scaleX
        val effSy = if (flipV) -scaleY else scaleY
        val cosT = kotlin.math.cos(rotationRad)
        val sinT = kotlin.math.sin(rotationRad)

        val corners = listOf(
            Vec2(srcBounds.left, srcBounds.top),
            Vec2(srcBounds.right, srcBounds.top),
            Vec2(srcBounds.right, srcBounds.bottom),
            Vec2(srcBounds.left, srcBounds.bottom)
        )

        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE

        for (c in corners) {
            val cx = (c.x - pivotX) * effSx
            val cy = (c.y - pivotY) * effSy
            val rx = cx * cosT - cy * sinT + pivotX + translationX
            val ry = cx * sinT + cy * cosT + pivotY + translationY
            minX = minOf(minX, rx)
            maxX = maxOf(maxX, rx)
            minY = minOf(minY, ry)
            maxY = maxOf(maxY, ry)
        }
        return WorldRect(minX, minY, maxX, maxY)
    }
}

sealed interface SelectionState {
    object Idle : SelectionState
    data class DrawingLasso(val points: List<Vec2>) : SelectionState
    data class ActiveTransform(val session: TransformSession, val revision: Long = 0L) : SelectionState
}

class SelectionTransformManager {
    companion object {
        private const val TAG = "SelectionTransform"
    }

    private val _state = MutableStateFlow<SelectionState>(SelectionState.Idle)
    val state: StateFlow<SelectionState> = _state.asStateFlow()

    private val lassoPoints = mutableListOf<Vec2>()

    fun startLasso(point: Vec2) {
        lassoPoints.clear()
        lassoPoints.add(point)
        _state.value = SelectionState.DrawingLasso(lassoPoints.toList())
    }

    fun addLassoPoint(point: Vec2) {
        val last = lassoPoints.lastOrNull()
        if (last == null || kotlin.math.hypot(point.x - last.x, point.y - last.y) >= 4f) {
            lassoPoints.add(point)
            _state.value = SelectionState.DrawingLasso(lassoPoints.toList())
        }
    }

    fun finishLasso(canvasView: MiltonCanvasView, targetLayerIds: Set<Long>) {
        if (lassoPoints.size < 3) {
            cancelLasso()
            return
        }

        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (pt in lassoPoints) {
            minX = minOf(minX, pt.x)
            maxX = maxOf(maxX, pt.x)
            minY = minOf(minY, pt.y)
            maxY = maxOf(maxY, pt.y)
        }

        val w = maxX - minX
        val h = maxY - minY
        if (w < 8f || h < 8f) {
            // Accidental small tap
            cancelLasso()
            return
        }

        val polygon = lassoPoints.toList()
        val bLeft = kotlin.math.floor(minX)
        val bTop = kotlin.math.floor(minY)
        val bRight = kotlin.math.ceil(maxX)
        val bBottom = kotlin.math.ceil(maxY)
        val srcBounds = WorldRect(bLeft, bTop, bRight, bBottom)

        // Capture patches and clear cutouts on GL thread
        canvasView.renderer.runOnGlThread {
            try {
                val targets = if (targetLayerIds.isEmpty()) {
                    setOf(canvasView.layerManager.activeLayerId)
                } else {
                    targetLayerIds
                }

                val allOriginalTiles = mutableMapOf<Long, List<TileSnapshot>>()
                val allPatches = mutableMapOf<Long, LayerPatch>()

                val srcW = srcBounds.width.roundToInt().coerceAtLeast(1)
                val srcH = srcBounds.height.roundToInt().coerceAtLeast(1)

                val pointsX = FloatArray(polygon.size) { polygon[it].x }
                val pointsY = FloatArray(polygon.size) { polygon[it].y }

                val minTx = floor(srcBounds.left / TileCoord.TILE_SIZE).toInt()
                val maxTx = floor(srcBounds.right / TileCoord.TILE_SIZE).toInt()
                val minTy = floor(srcBounds.top / TileCoord.TILE_SIZE).toInt()
                val maxTy = floor(srcBounds.bottom / TileCoord.TILE_SIZE).toInt()

                for (layerId in targets) {
                    val layer = canvasView.layerManager.layers.find { it.id == layerId } ?: continue
                    val snapshots = mutableListOf<TileSnapshot>()
                    val patchRgba = ByteArray(srcW * srcH * 4)

                    for (ty in minTy..maxTy) {
                        for (tx in minTx..maxTx) {
                            val tile = layer.tileMap.getOrCreateTile(tx, ty)
                            val originalBytes = tile.readPixels()
                            snapshots.add(TileSnapshot(tx, ty, originalBytes.clone()))

                            val mask = MiltonNative.rasterizePolygonMask(
                                pointsX, pointsY,
                                (tx * TileCoord.TILE_SIZE).toFloat(),
                                (ty * TileCoord.TILE_SIZE).toFloat()
                            )

                            if (mask != null && MiltonNative.isLoaded) {
                                val workingTile = originalBytes.clone()
                                val ok = MiltonNative.extractAndClearTileSelection(
                                    workingTile,
                                    tx * TileCoord.TILE_SIZE,
                                    ty * TileCoord.TILE_SIZE,
                                    mask,
                                    patchRgba,
                                    srcW,
                                    srcH,
                                    srcBounds.left.roundToInt(),
                                    srcBounds.top.roundToInt()
                                )
                                if (ok) {
                                    tile.writePixels(workingTile)
                                }
                            }
                        }
                    }

                    // Create bitmap preview
                    val previewBitmap = Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888)
                    previewBitmap.copyPixelsFromBuffer(ByteBuffer.wrap(patchRgba))

                    allOriginalTiles[layerId] = snapshots
                    allPatches[layerId] = LayerPatch(srcW, srcH, patchRgba, previewBitmap)
                }

                val session = TransformSession(
                    polygon = polygon,
                    srcBounds = srcBounds,
                    targetLayerIds = targets,
                    originalTiles = allOriginalTiles,
                    layerPatches = allPatches
                )

                _state.value = SelectionState.ActiveTransform(session)
                canvasView.post { canvasView.requestRedraw() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start transform session", e)
                _state.value = SelectionState.Idle
            }
        }
        canvasView.requestRedraw()
    }

    fun cancelLasso() {
        lassoPoints.clear()
        _state.value = SelectionState.Idle
    }

    fun cancelTransform(canvasView: MiltonCanvasView) {
        val current = _state.value
        if (current !is SelectionState.ActiveTransform) {
            _state.value = SelectionState.Idle
            return
        }

        canvasView.renderer.runOnGlThread {
            try {
                // Restore all original tiles
                for ((layerId, snapshots) in current.session.originalTiles) {
                    val layer = canvasView.layerManager.layers.find { it.id == layerId } ?: continue
                    for (snap in snapshots) {
                        val tile = layer.tileMap.getOrCreateTile(snap.tx, snap.ty)
                        tile.writePixels(snap.rawBytes)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error restoring tiles on cancel", e)
            } finally {
                _state.value = SelectionState.Idle
                canvasView.post { canvasView.requestRedraw() }
            }
        }
        canvasView.requestRedraw()
    }

    fun commitTransform(canvasView: MiltonCanvasView) {
        val current = _state.value
        if (current !is SelectionState.ActiveTransform) {
            _state.value = SelectionState.Idle
            return
        }

        val session = current.session
        canvasView.renderer.runOnGlThread {
            try {
                val affectedLayers = mutableListOf<Pair<Long, app.goodboy13.milton.core.tile.TileMap>>()
                for (layerId in session.targetLayerIds) {
                    val layer = canvasView.layerManager.layers.find { it.id == layerId } ?: continue
                    val patch = session.layerPatches[layerId] ?: continue

                    // Transform via Rust native engine
                    val transformed = MiltonNative.transformPatch(
                        patch.rgbaBytes,
                        patch.width,
                        patch.height,
                        session.scaleX,
                        session.scaleY,
                        session.rotationRad,
                        session.pivotX - session.srcBounds.left,
                        session.pivotY - session.srcBounds.top,
                        session.flipH,
                        session.flipV
                    ) ?: continue

                    val destLeft = session.pivotX + transformed.offsetX + session.translationX
                    val destTop = session.pivotY + transformed.offsetY + session.translationY
                    val destRight = destLeft + transformed.width
                    val destBottom = destTop + transformed.height

                    val minTx = floor(destLeft / TileCoord.TILE_SIZE).toInt()
                    val maxTx = floor(destRight / TileCoord.TILE_SIZE).toInt()
                    val minTy = floor(destTop / TileCoord.TILE_SIZE).toInt()
                    val maxTy = floor(destBottom / TileCoord.TILE_SIZE).toInt()

                    // Register original source tiles into UndoManager
                    val origSnapshots = session.originalTiles[layerId] ?: emptyList()
                    for (snap in origSnapshots) {
                        canvasView.renderer.undoManager.registerPreModifiedTile(
                            layerId,
                            TileCoord(snap.tx, snap.ty),
                            snap.rawBytes
                        )
                    }

                    for (ty in minTy..maxTy) {
                        for (tx in minTx..maxTx) {
                            val tile = layer.tileMap.getOrCreateTile(tx, ty)
                            val curBytes = tile.readPixels()
                            // Register pre-blit destination tile state if not already registered
                            canvasView.renderer.undoManager.registerPreModifiedTile(
                                layerId,
                                TileCoord(tx, ty),
                                if (tile.isInitialized && tile.hasContent) curBytes.clone() else null
                            )

                            MiltonNative.blitPatchToTile(
                                curBytes,
                                tx * TileCoord.TILE_SIZE,
                                ty * TileCoord.TILE_SIZE,
                                transformed.rgbaBytes,
                                transformed.width,
                                transformed.height,
                                destLeft.roundToInt(),
                                destTop.roundToInt()
                            )
                            tile.writePixels(curBytes)
                        }
                    }

                    affectedLayers.add(layerId to layer.tileMap)
                }

                if (affectedLayers.isNotEmpty()) {
                    canvasView.renderer.undoManager.commitMultiLayerStroke(affectedLayers)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error committing transform", e)
            } finally {
                _state.value = SelectionState.Idle
                canvasView.post { canvasView.requestRedraw() }
            }
        }
        canvasView.requestRedraw()
    }

    fun updateTransform(
        translationX: Float? = null,
        translationY: Float? = null,
        scaleX: Float? = null,
        scaleY: Float? = null,
        rotationRad: Float? = null,
        flipH: Boolean? = null,
        flipV: Boolean? = null
    ) {
        val current = _state.value as? SelectionState.ActiveTransform ?: return
        val s = current.session
        val newSession = s.copy(
            translationX = translationX ?: s.translationX,
            translationY = translationY ?: s.translationY,
            scaleX = scaleX ?: s.scaleX,
            scaleY = scaleY ?: s.scaleY,
            rotationRad = rotationRad ?: s.rotationRad,
            flipH = flipH ?: s.flipH,
            flipV = flipV ?: s.flipV
        )
        _state.value = SelectionState.ActiveTransform(newSession, current.revision + 1)
    }

    fun resetSessionTransform() {
        val current = _state.value as? SelectionState.ActiveTransform ?: return
        val s = current.session
        val newSession = s.copy(
            translationX = 0f,
            translationY = 0f,
            scaleX = 1f,
            scaleY = 1f,
            rotationRad = 0f,
            flipH = false,
            flipV = false
        )
        _state.value = SelectionState.ActiveTransform(newSession, current.revision + 1)
    }
}
