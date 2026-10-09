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
    val previewBitmap: Bitmap,
    val rgbaBytes: ByteArray? = null
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

                val pointsX = FloatArray(polygon.size) { polygon[it].x }
                val pointsY = FloatArray(polygon.size) { polygon[it].y }

                if (MiltonNative.isLoaded) {
                    MiltonNative.selectionSessionBegin(pointsX, pointsY)
                }

                val minTx = floor(srcBounds.left / TileCoord.TILE_SIZE).toInt()
                val maxTx = floor((srcBounds.right - 1f) / TileCoord.TILE_SIZE).toInt()
                val minTy = floor(srcBounds.top / TileCoord.TILE_SIZE).toInt()
                val maxTy = floor((srcBounds.bottom - 1f) / TileCoord.TILE_SIZE).toInt()

                for (layerId in targets) {
                    val layer = canvasView.layerManager.layers.find { it.id == layerId } ?: continue
                    val snapshots = mutableListOf<TileSnapshot>()

                    // Sparse query: only examine existing tiles with content intersecting bounds!
                    val candidateTiles = layer.tileMap.getAllTiles().filter { tile ->
                        tile.hasContent &&
                        tile.coord.tx in minTx..maxTx &&
                        tile.coord.ty in minTy..maxTy
                    }

                    for (tile in candidateTiles) {
                        tile.ensureResident(layer.tileMap.cacheDir)
                        val originalBytes = tile.readPixels()
                        val workingTile = originalBytes.clone()

                        val cut = if (MiltonNative.isLoaded) {
                            MiltonNative.selectionSessionCutTile(
                                layerId,
                                tile.coord.tx,
                                tile.coord.ty,
                                workingTile
                            )
                        } else {
                            false
                        }

                        if (cut) {
                            tile.writePixels(workingTile)
                            snapshots.add(TileSnapshot(tile.coord.tx, tile.coord.ty, originalBytes))
                        }
                    }

                    // Create lightweight downsampled preview bitmap (capped at max 1024)
                    val packed = if (MiltonNative.isLoaded) {
                        MiltonNative.selectionSessionGetPreview(layerId, 1024)
                    } else null

                    if (packed != null && packed.size >= 8) {
                        val buf = ByteBuffer.wrap(packed).order(java.nio.ByteOrder.BIG_ENDIAN)
                        val previewW = buf.int
                        val previewH = buf.int
                        val expectedLen = previewW * previewH * 4
                        if (packed.size >= 8 + expectedLen) {
                            val previewBitmap = Bitmap.createBitmap(previewW, previewH, Bitmap.Config.ARGB_8888)
                            buf.position(8)
                            previewBitmap.copyPixelsFromBuffer(buf)
                            allPatches[layerId] = LayerPatch(previewW, previewH, previewBitmap)
                        }
                    }

                    if (snapshots.isNotEmpty()) {
                        allOriginalTiles[layerId] = snapshots
                    }
                }

                if (allOriginalTiles.isEmpty()) {
                    if (MiltonNative.isLoaded) {
                        MiltonNative.selectionSessionEnd()
                    }
                    _state.value = SelectionState.Idle
                    return@runOnGlThread
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
                if (MiltonNative.isLoaded) {
                    MiltonNative.selectionSessionEnd()
                }
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
                if (MiltonNative.isLoaded) {
                    MiltonNative.selectionSessionEnd()
                }
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

                    // Register original source tiles into UndoManager
                    val origSnapshots = session.originalTiles[layerId] ?: emptyList()
                    for (snap in origSnapshots) {
                        canvasView.renderer.undoManager.registerPreModifiedTile(
                            layerId,
                            TileCoord(snap.tx, snap.ty),
                            snap.rawBytes
                        )
                    }

                    if (MiltonNative.isLoaded) {
                        val affectedCoords = MiltonNative.selectionSessionGetAffectedDestTiles(
                            layerId,
                            session.scaleX,
                            session.scaleY,
                            session.rotationRad,
                            session.translationX,
                            session.translationY,
                            session.pivotX,
                            session.pivotY,
                            session.flipH,
                            session.flipV
                        ) ?: IntArray(0)

                        for (i in 0 until affectedCoords.size step 2) {
                            val tx = affectedCoords[i]
                            val ty = affectedCoords[i + 1]
                            val tile = layer.tileMap.getOrCreateTile(tx, ty)
                            tile.ensureResident(layer.tileMap.cacheDir)
                            val curBytes = tile.readPixels()

                            // Register pre-blit destination tile state if not already registered
                            canvasView.renderer.undoManager.registerPreModifiedTile(
                                layerId,
                                TileCoord(tx, ty),
                                if (tile.isInitialized && tile.hasContent) curBytes.clone() else null
                            )

                            val modified = MiltonNative.selectionSessionBlitToTile(
                                layerId,
                                tx,
                                ty,
                                curBytes,
                                session.scaleX,
                                session.scaleY,
                                session.rotationRad,
                                session.translationX,
                                session.translationY,
                                session.pivotX,
                                session.pivotY,
                                session.flipH,
                                session.flipV
                            )

                            if (modified) {
                                tile.writePixels(curBytes)
                            }
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
                if (MiltonNative.isLoaded) {
                    MiltonNative.selectionSessionEnd()
                }
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
