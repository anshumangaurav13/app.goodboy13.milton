package app.goodboy13.milton.core.liquify

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log
import app.goodboy13.milton.core.model.Vec2
import app.goodboy13.milton.core.native.MiltonNative
import app.goodboy13.milton.core.selection.SelectionState
import app.goodboy13.milton.core.selection.SelectionTransformManager
import app.goodboy13.milton.core.selection.TransformSession
import app.goodboy13.milton.core.tile.TileCoord
import app.goodboy13.milton.ui.MiltonCanvasView
import kotlin.math.floor

/**
 * Coordinates Liquify deformation sessions: transforms stylus events into mesh displacement,
 * manages tool parameter updates, and bakes deformed patches back into Milton's sparse TileMap.
 */
class LiquifyManager(
    private val selectionManager: SelectionTransformManager
) {
    companion object {
        private const val TAG = "LiquifyManager"
    }

    private var lastStylusPos: Vec2? = null

    fun startLiquifyFromTransform(transformSession: TransformSession) {
        val mesh = LiquifyMesh(transformSession.srcBounds, transformSession.polygon)
        val liquifySession = LiquifySession(
            polygon = transformSession.polygon,
            srcBounds = transformSession.srcBounds,
            targetLayerIds = transformSession.targetLayerIds,
            originalTiles = transformSession.originalTiles,
            layerPatches = transformSession.layerPatches,
            mesh = mesh
        )
        selectionManager.setLiquifyState(liquifySession)
    }

    fun onStylusDown(worldPos: Vec2) {
        lastStylusPos = worldPos
    }

    fun onStylusMove(worldPos: Vec2, pressure: Float, canvasView: MiltonCanvasView) {
        val last = lastStylusPos ?: run {
            lastStylusPos = worldPos
            return
        }

        val current = selectionManager.state.value
        if (current !is SelectionState.ActiveLiquify) return

        val session = current.session
        session.mesh.applyStroke(
            x1 = last.x,
            y1 = last.y,
            x2 = worldPos.x,
            y2 = worldPos.y,
            radius = session.brushSize,
            strength = session.brushStrength * pressure,
            mode = session.activeMode
        )

        lastStylusPos = worldPos
        selectionManager.updateLiquifyRevision(session)
        canvasView.requestRedraw()
    }

    fun onStylusUp() {
        lastStylusPos = null
    }

    fun setMode(mode: LiquifyMode) {
        val current = selectionManager.state.value
        if (current is SelectionState.ActiveLiquify) {
            selectionManager.updateLiquifySession(current.session.copy(activeMode = mode))
        }
    }

    fun setBrushSize(size: Float) {
        val current = selectionManager.state.value
        if (current is SelectionState.ActiveLiquify) {
            selectionManager.updateLiquifySession(current.session.copy(brushSize = size.coerceIn(10f, 500f)))
        }
    }

    fun setBrushStrength(strength: Float) {
        val current = selectionManager.state.value
        if (current is SelectionState.ActiveLiquify) {
            selectionManager.updateLiquifySession(current.session.copy(brushStrength = strength.coerceIn(0.05f, 1.0f)))
        }
    }

    fun toggleMeshGrid(show: Boolean) {
        val current = selectionManager.state.value
        if (current is SelectionState.ActiveLiquify) {
            selectionManager.updateLiquifySession(current.session.copy(showMeshGrid = show))
        }
    }

    fun reset() {
        val current = selectionManager.state.value
        if (current is SelectionState.ActiveLiquify) {
            current.session.mesh.reset()
            selectionManager.updateLiquifyRevision(current.session)
        }
    }

    fun cancel(canvasView: MiltonCanvasView) {
        val current = selectionManager.state.value
        if (current !is SelectionState.ActiveLiquify) return

        canvasView.renderer.runOnGlThread {
            try {
                for ((layerId, snapshots) in current.session.originalTiles) {
                    val layer = canvasView.layerManager.layers.find { it.id == layerId } ?: continue
                    for (snap in snapshots) {
                        val tile = layer.tileMap.getOrCreateTile(snap.tx, snap.ty)
                        tile.writePixels(snap.rawBytes)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error restoring tiles on cancel liquify", e)
            } finally {
                if (MiltonNative.isLoaded) {
                    MiltonNative.selectionSessionEnd()
                }
                selectionManager.clearState()
                canvasView.post { canvasView.requestRedraw() }
            }
        }
    }

    fun commit(canvasView: MiltonCanvasView) {
        val current = selectionManager.state.value
        if (current !is SelectionState.ActiveLiquify) return

        val session = current.session
        canvasView.renderer.runOnGlThread {
            try {
                val affectedLayers = mutableListOf<Pair<Long, app.goodboy13.milton.core.tile.TileMap>>()
                for (layerId in session.targetLayerIds) {
                    val layer = canvasView.layerManager.layers.find { it.id == layerId } ?: continue
                    val patch = session.layerPatches[layerId] ?: continue
                    affectedLayers.add(layerId to layer.tileMap)

                    // 1. Register pre-modified tiles for Undo
                    val origSnapshots = session.originalTiles[layerId] ?: emptyList()
                    for (snap in origSnapshots) {
                        canvasView.renderer.undoManager.registerPreModifiedTile(
                            layerId,
                            TileCoord(snap.tx, snap.ty),
                            snap.rawBytes
                        )
                    }

                    // 2. Bake warped mesh into an offscreen destination bitmap
                    val bakedBitmap = Bitmap.createBitmap(
                        patch.previewBitmap.width,
                        patch.previewBitmap.height,
                        Bitmap.Config.ARGB_8888
                    )
                    val canvas = Canvas(bakedBitmap)
                    val paint = Paint().apply { isFilterBitmap = true }

                    val localWarpedVerts = FloatArray(session.mesh.vertexCount * 2)
                    val scaleX = patch.previewBitmap.width.toFloat() / session.mesh.width
                    val scaleY = patch.previewBitmap.height.toFloat() / session.mesh.height

                    var vi = 0
                    for (i in 0 until session.mesh.vertexCount) {
                        val lx = (session.mesh.worldX[i] - session.mesh.srcBounds.left) * scaleX
                        val ly = (session.mesh.worldY[i] - session.mesh.srcBounds.top) * scaleY
                        localWarpedVerts[vi] = lx
                        localWarpedVerts[vi + 1] = ly
                        vi += 2
                    }

                    canvas.drawBitmapMesh(
                        patch.previewBitmap,
                        session.mesh.gridCols,
                        session.mesh.gridRows,
                        localWarpedVerts,
                        0,
                        null,
                        0,
                        paint
                    )

                    // 3. Blit baked bitmap back into intersecting tiles
                    val minTx = floor(session.srcBounds.left / TileCoord.TILE_SIZE).toInt()
                    val maxTx = floor((session.srcBounds.right - 1f) / TileCoord.TILE_SIZE).toInt()
                    val minTy = floor(session.srcBounds.top / TileCoord.TILE_SIZE).toInt()
                    val maxTy = floor((session.srcBounds.bottom - 1f) / TileCoord.TILE_SIZE).toInt()

                    val bakedPixels = IntArray(bakedBitmap.width * bakedBitmap.height)
                    bakedBitmap.getPixels(bakedPixels, 0, bakedBitmap.width, 0, 0, bakedBitmap.width, bakedBitmap.height)

                    for (ty in minTy..maxTy) {
                        for (tx in minTx..maxTx) {
                            val tile = layer.tileMap.getOrCreateTile(tx, ty)
                            tile.ensureResident(layer.tileMap.cacheDir)
                            val curBytes = tile.readPixels()

                            val tileWorldLeft = tx * TileCoord.TILE_SIZE.toFloat()
                            val tileWorldTop = ty * TileCoord.TILE_SIZE.toFloat()
                            var tileModified = false

                            for (py in 0 until TileCoord.TILE_SIZE) {
                                val wy = tileWorldTop + py
                                if (wy < session.srcBounds.top || wy >= session.srcBounds.bottom) continue
                                val patchY = ((wy - session.srcBounds.top) * scaleY).toInt().coerceIn(0, bakedBitmap.height - 1)

                                for (px in 0 until TileCoord.TILE_SIZE) {
                                    val wx = tileWorldLeft + px
                                    if (wx < session.srcBounds.left || wx >= session.srcBounds.right) continue
                                    val patchX = ((wx - session.srcBounds.left) * scaleX).toInt().coerceIn(0, bakedBitmap.width - 1)

                                    val bakedColor = bakedPixels[patchY * bakedBitmap.width + patchX]
                                    val bakedAlpha = (bakedColor ushr 24) and 0xFF

                                    if (bakedAlpha > 0) {
                                        val byteIdx = (py * TileCoord.TILE_SIZE + px) * 4
                                        val bR = (bakedColor ushr 16) and 0xFF
                                        val bG = (bakedColor ushr 8) and 0xFF
                                        val bB = bakedColor and 0xFF

                                        curBytes[byteIdx] = bR.toByte()
                                        curBytes[byteIdx + 1] = bG.toByte()
                                        curBytes[byteIdx + 2] = bB.toByte()
                                        curBytes[byteIdx + 3] = bakedAlpha.toByte()
                                        tileModified = true
                                    }
                                }
                            }

                            if (tileModified) {
                                tile.writePixels(curBytes)
                            }
                        }
                    }
                }

                if (affectedLayers.isNotEmpty()) {
                    canvasView.renderer.undoManager.commitMultiLayerStroke(affectedLayers)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error committing liquify to tiles", e)
            } finally {
                if (MiltonNative.isLoaded) {
                    MiltonNative.selectionSessionEnd()
                }
                selectionManager.clearState()
                canvasView.post { canvasView.requestRedraw() }
            }
        }
    }
}
