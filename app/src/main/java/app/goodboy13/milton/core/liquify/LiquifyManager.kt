package app.goodboy13.milton.core.liquify

import android.util.Log
import app.goodboy13.milton.core.model.Vec2
import app.goodboy13.milton.core.native.MiltonNative
import app.goodboy13.milton.core.selection.SelectionState
import app.goodboy13.milton.core.selection.SelectionTransformManager
import app.goodboy13.milton.core.selection.TransformSession
import app.goodboy13.milton.core.tile.RasterTile
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

    var currentBrushSize: Float = 300f
        private set
    var currentBrushStrength: Float = 0.50f
        private set
    var currentMode: LiquifyMode = LiquifyMode.PUSH
        private set
    var showMeshGrid: Boolean = false
        private set

    private var lastStylusPos: Vec2? = null

    fun startLiquifyFromTransform(transformSession: TransformSession) {
        val mesh = LiquifyMesh(transformSession.srcBounds, transformSession.polygon)
        val liquifySession = LiquifySession(
            polygon = transformSession.polygon,
            srcBounds = transformSession.srcBounds,
            targetLayerIds = transformSession.targetLayerIds,
            originalTiles = transformSession.originalTiles,
            layerPatches = transformSession.layerPatches,
            mesh = mesh,
            activeMode = currentMode,
            brushSize = currentBrushSize,
            brushStrength = currentBrushStrength,
            showMeshGrid = showMeshGrid
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
        currentMode = mode
        val current = selectionManager.state.value
        if (current is SelectionState.ActiveLiquify) {
            selectionManager.updateLiquifySession(current.session.copy(activeMode = mode))
        }
    }

    fun setBrushSize(size: Float) {
        currentBrushSize = size.coerceIn(10f, 800f)
        val current = selectionManager.state.value
        if (current is SelectionState.ActiveLiquify) {
            selectionManager.updateLiquifySession(current.session.copy(brushSize = currentBrushSize))
        }
    }

    fun setBrushStrength(strength: Float) {
        currentBrushStrength = strength.coerceIn(0.05f, 1.0f)
        val current = selectionManager.state.value
        if (current is SelectionState.ActiveLiquify) {
            selectionManager.updateLiquifySession(current.session.copy(brushStrength = currentBrushStrength))
        }
    }

    fun toggleMeshGrid(show: Boolean) {
        showMeshGrid = show
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

        // Dismiss overlay immediately on UI thread for instant responsiveness
        selectionManager.clearState()

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
                SelectionTransformManager.deletePatchTextures(current.session.layerPatches.values)
                MiltonNative.selectionSessionEnd()
                canvasView.post { canvasView.requestRedraw() }
            }
        }
        canvasView.requestRedraw()
    }

    fun commit(canvasView: MiltonCanvasView) {
        val current = selectionManager.state.value
        if (current !is SelectionState.ActiveLiquify) return

        val session = current.session
        // Dismiss overlay immediately on UI thread
        selectionManager.clearState()

        canvasView.renderer.runOnGlThread {
            try {
                val affectedLayers = mutableListOf<Pair<Long, app.goodboy13.milton.core.tile.TileMap>>()
                val patchBlitShader = canvasView.renderer.sceneRenderer.patchBlitShader

                val minTx = floor(session.srcBounds.left / TileCoord.TILE_SIZE).toInt()
                val maxTx = floor((session.srcBounds.right - 1f) / TileCoord.TILE_SIZE).toInt()
                val minTy = floor(session.srcBounds.top / TileCoord.TILE_SIZE).toInt()
                val maxTy = floor((session.srcBounds.bottom - 1f) / TileCoord.TILE_SIZE).toInt()

                for (layerId in session.targetLayerIds) {
                    val layer = canvasView.layerManager.layers.find { it.id == layerId } ?: continue
                    val patch = session.layerPatches[layerId] ?: continue
                    if (patch.glTextureId == 0) continue

                    affectedLayers.add(layerId to layer.tileMap)

                    // 1. Register pre-cut original source tiles for Undo
                    val origSnapshots = session.originalTiles[layerId] ?: emptyList()
                    val origCoordSet = mutableSetOf<TileCoord>()
                    for (snap in origSnapshots) {
                        val coord = TileCoord(snap.tx, snap.ty)
                        origCoordSet.add(coord)
                        canvasView.renderer.undoManager.registerPreModifiedTile(
                            layerId,
                            coord,
                            snap.rawBytes
                        )
                    }

                    // 2. Collect all candidate tiles within bounds
                    val candidateTiles = mutableListOf<RasterTile>()
                    val newlyCreatedTiles = mutableListOf<RasterTile>()

                    for (ty in minTy..maxTy) {
                        for (tx in minTx..maxTx) {
                            val coord = TileCoord(tx, ty)
                            val existing = layer.tileMap.getExistingTile(tx, ty)
                            if (existing != null) {
                                existing.ensureResident(layer.tileMap.cacheDir)
                                candidateTiles.add(existing)
                                if (!origCoordSet.contains(coord)) {
                                    val preBytes = if (existing.hasContent) existing.readPixels() else null
                                    canvasView.renderer.undoManager.registerPreModifiedTile(layerId, coord, preBytes)
                                }
                            } else {
                                val newTile = layer.tileMap.getOrCreateTile(tx, ty)
                                newTile.ensureResident(layer.tileMap.cacheDir)
                                candidateTiles.add(newTile)
                                newlyCreatedTiles.add(newTile)
                            }
                        }
                    }

                    // 3. Fast hardware GPU rasterization directly into tile FBOs (< 1ms)
                    patchBlitShader.bakeLiquifyMeshIntoTiles(
                        session = session,
                        patch = patch,
                        tiles = candidateTiles
                    )

                    // 4. Register newly created tiles that actually received pixels
                    for (newTile in newlyCreatedTiles) {
                        val afterPixels = newTile.readPixels()
                        var hasAlpha = false
                        for (i in 3 until afterPixels.size step 4) {
                            if (afterPixels[i] != 0.toByte()) {
                                hasAlpha = true
                                break
                            }
                        }
                        if (hasAlpha) {
                            newTile.hasContent = true
                            canvasView.renderer.undoManager.registerPreModifiedTile(layerId, newTile.coord, null)
                        } else {
                            newTile.hasContent = false
                        }
                    }
                }

                if (affectedLayers.isNotEmpty()) {
                    canvasView.renderer.undoManager.commitMultiLayerStroke(affectedLayers)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error committing liquify to tiles", e)
            } finally {
                SelectionTransformManager.deletePatchTextures(session.layerPatches.values)
                MiltonNative.selectionSessionEnd()
                canvasView.post { canvasView.requestRedraw() }
            }
        }
        canvasView.requestRedraw()
    }
}
