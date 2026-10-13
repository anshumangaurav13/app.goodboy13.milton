package app.goodboy13.milton.core.liquify

import app.goodboy13.milton.core.model.Vec2
import app.goodboy13.milton.core.model.WorldRect
import app.goodboy13.milton.core.selection.LayerPatch
import app.goodboy13.milton.core.selection.TileSnapshot

/**
 * Immutable session descriptor for an active Liquify session on the canvas.
 */
data class LiquifySession(
    val polygon: List<Vec2>,
    val srcBounds: WorldRect,
    val targetLayerIds: Set<Long>,
    val originalTiles: Map<Long, List<TileSnapshot>>,
    val layerPatches: Map<Long, LayerPatch>,
    val mesh: LiquifyMesh,
    val activeMode: LiquifyMode = LiquifyMode.PUSH,
    val brushSize: Float = 80f,
    val brushStrength: Float = 0.50f,
    val showMeshGrid: Boolean = false,
    val revision: Long = 0L
)
