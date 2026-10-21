package app.goodboy13.milton.core.layer

import android.graphics.Bitmap
import app.goodboy13.milton.core.document.NativeMiltonDocument
import app.goodboy13.milton.core.model.WorldRect
import app.goodboy13.milton.core.tile.RasterTile
import app.goodboy13.milton.core.tile.TileMap
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Manages the stack of layers (up to 16) on the infinite canvas.
 * Layers are ordered bottom-to-top: index 0 is rendered first, last index is rendered on top.
 */
class LayerManager(
    val cacheBaseDir: File? = null,
    val maxResidentTilesPerLayer: Int = 256,
    val maxLayers: Int = MAX_LAYERS
) {
    companion object {
        const val MAX_LAYERS = 16
        const val MIN_LAYERS = 1
    }

    val nativeDoc: NativeMiltonDocument = NativeMiltonDocument()

    private var nextLayerId: Long = 1L
    private var layerNameCounter: Int = 1

    private val _layers = CopyOnWriteArrayList<Layer>()
    val layers: List<Layer> get() = _layers

    val hasPendingRestores: Boolean
        get() = _layers.any { it.tileMap.hasPendingRestores }

    @Volatile
    var structuralRevision: Long = 0L
        private set

    @Volatile
    var activeLayerId: Long = 1L
        private set

    val activeLayer: Layer
        get() = _layers.find { it.id == activeLayerId } ?: _layers.last()

    var onLayersChangedListener: (() -> Unit)? = null

    init {
        cacheBaseDir?.mkdirs()
        val firstLayer = createLayerInstance("Layer 1")
        _layers.add(firstLayer)
        activeLayerId = firstLayer.id
    }

    private fun bindLayerToNative(layer: Layer) {
        nativeDoc.addLayer(layer.id, layer.name, layer.opacity, layer.isVisible)
        layer.tileMap.onTileStateChanged = { tx, ty, sliceIndex, hasContent ->
            nativeDoc.setTile(layer.id, tx, ty, sliceIndex, hasContent)
        }
        layer.tileMap.onOverviewTileStateChanged = { otx, oty, sliceIndex, hasContent ->
            nativeDoc.setOverviewTile(layer.id, otx, oty, sliceIndex, hasContent)
        }
    }

    private fun createLayerInstance(name: String): Layer {
        val id = nextLayerId++
        val layerCacheDir = cacheBaseDir?.resolve("layer_$id")
        val tileMap = TileMap(cacheDir = layerCacheDir, maxResidentTiles = maxResidentTilesPerLayer)
        val layer = Layer(
            id = id,
            initialName = name,
            initialOpacity = 1.0f,
            initialIsVisible = true,
            tileMap = tileMap
        )
        bindLayerToNative(layer)
        return layer
    }

    fun canAddLayer(): Boolean = _layers.size < maxLayers

    fun canDeleteLayer(): Boolean = _layers.size > MIN_LAYERS

    /**
     * Adds a new layer directly above the active layer (or top of the stack).
     * Returns the newly created layer, or null if maximum layers reached.
     */
    fun addLayer(name: String? = null, insertAboveActive: Boolean = true): Layer? {
        if (!canAddLayer()) return null

        layerNameCounter++
        val layerName = name ?: "Layer $layerNameCounter"
        val newLayer = createLayerInstance(layerName)

        val activeIdx = _layers.indexOfFirst { it.id == activeLayerId }
        val insertIdx = if (insertAboveActive && activeIdx >= 0) {
            (activeIdx + 1).coerceAtMost(_layers.size)
        } else {
            _layers.size
        }

        _layers.add(insertIdx, newLayer)
        activeLayerId = newLayer.id
        structuralRevision++
        notifyChanged()
        return newLayer
    }

    /**
     * Deletes a layer by id. Requires at least 1 layer to remain.
     */
    fun deleteLayer(layerId: Long): Boolean {
        if (!canDeleteLayer()) return false

        val layerToDelete = _layers.find { it.id == layerId } ?: return false
        val index = _layers.indexOf(layerToDelete)

        _layers.remove(layerToDelete)
        nativeDoc.removeLayer(layerId)
        layerToDelete.tileMap.releaseAll()

        if (activeLayerId == layerId) {
            val newActiveIndex = index.coerceAtMost(_layers.size - 1)
            activeLayerId = _layers[newActiveIndex].id
        }

        structuralRevision++
        notifyChanged()
        return true
    }

    fun removeLayer(layerId: Long): Layer? {
        val layerToDelete = _layers.find { it.id == layerId } ?: return null
        val index = _layers.indexOf(layerToDelete)
        _layers.remove(layerToDelete)
        nativeDoc.removeLayer(layerId)
        if (activeLayerId == layerId && _layers.isNotEmpty()) {
            val newActiveIndex = index.coerceAtMost(_layers.size - 1)
            activeLayerId = _layers[newActiveIndex].id
        }
        notifyChanged()
        return layerToDelete
    }

    fun restoreLayer(
        id: Long,
        name: String,
        opacity: Float,
        isVisible: Boolean,
        storageIndex: Int,
        thumbnail: Bitmap?
    ): Layer {
        val layerCacheDir = cacheBaseDir?.resolve("layer_$id")
        val tileMap = TileMap(cacheDir = layerCacheDir, maxResidentTiles = maxResidentTilesPerLayer)
        val layer = Layer(
            id = id,
            initialName = name,
            initialOpacity = opacity,
            initialIsVisible = isVisible,
            tileMap = tileMap
        )
        layer.thumbnailBitmap = thumbnail
        bindLayerToNative(layer)
        val insertIdx = storageIndex.coerceIn(0, _layers.size)
        _layers.add(insertIdx, layer)
        if (id >= nextLayerId) {
            nextLayerId = id + 1
        }
        notifyChanged()
        return layer
    }

    fun notifyThumbnailsChanged() {
        structuralRevision++
    }

    fun selectLayer(layerId: Long) {
        if (_layers.any { it.id == layerId }) {
            activeLayerId = layerId
            notifyChanged()
        }
    }

    fun setLayerVisibility(layerId: Long, isVisible: Boolean) {
        val layer = _layers.find { it.id == layerId } ?: return
        layer.isVisible = isVisible
        nativeDoc.setLayerVisible(layerId, isVisible)
        notifyChanged()
    }

    fun setLayerOpacity(layerId: Long, opacity: Float) {
        val layer = _layers.find { it.id == layerId } ?: return
        val clamped = opacity.coerceIn(0f, 1f)
        layer.opacity = clamped
        nativeDoc.setLayerOpacity(layerId, clamped)
        notifyChanged()
    }

    fun renameLayer(layerId: Long, newName: String) {
        val layer = _layers.find { it.id == layerId } ?: return
        layer.name = newName.trim().ifEmpty { "Layer" }
        notifyChanged()
    }

    fun updateLayerThumbnail(layerId: Long, bitmap: Bitmap?) {
        val layer = _layers.find { it.id == layerId } ?: return
        layer.thumbnailBitmap = bitmap
    }

    /**
     * Reorders a layer from fromIndex to toIndex in bottom-to-top storage order.
     */
    fun moveLayer(fromIndex: Int, toIndex: Int): Boolean {
        if (fromIndex !in _layers.indices || toIndex !in _layers.indices || fromIndex == toIndex) {
            return false
        }
        val layer = _layers.removeAt(fromIndex)
        _layers.add(toIndex, layer)
        nativeDoc.reorderLayers(_layers.map { it.id }.toLongArray())
        structuralRevision++
        notifyChanged()
        return true
    }

    /**
     * Atomically sets the exact order of layers to match the specified layer IDs.
     */
    fun reorderLayersById(orderedIds: List<Long>): Boolean {
        if (orderedIds.size != _layers.size) return false
        val idMap = _layers.associateBy { it.id }
        val newLayers = orderedIds.mapNotNull { idMap[it] }
        if (newLayers.size != _layers.size) return false
        _layers.clear()
        _layers.addAll(newLayers)
        nativeDoc.reorderLayers(_layers.map { it.id }.toLongArray())
        structuralRevision++
        notifyChanged()
        return true
    }

    /**
     * Move layer up in visual stacking order (closer to top of screen / higher index).
     */
    fun moveLayerUp(layerId: Long): Boolean {
        val index = _layers.indexOfFirst { it.id == layerId }
        if (index < 0 || index >= _layers.size - 1) return false
        return moveLayer(index, index + 1)
    }

    /**
     * Move layer down in visual stacking order (closer to bottom / lower index).
     */
    fun moveLayerDown(layerId: Long): Boolean {
        val index = _layers.indexOfFirst { it.id == layerId }
        if (index <= 0) return false
        return moveLayer(index, index - 1)
    }

    fun getLayer(layerId: Long): Layer? {
        return _layers.find { it.id == layerId }
    }

    fun trimAllToBudget(visibleBounds: WorldRect) {
        val visibleTiles = mutableListOf<RasterTile>()
        for (layer in _layers) {
            layer.tileMap.getVisibleTiles(visibleBounds, visibleTiles)
            layer.tileMap.trimToBudget(visibleTiles)
        }
    }

    fun resetToSingleLayer(renderer: app.goodboy13.milton.core.gl.MiltonCanvasRenderer? = null) {
        for (layer in _layers) {
            layer.tileMap.clearTiles(renderer)
        }
        _layers.clear()
        layerNameCounter = 1
        val firstLayer = createLayerInstance("Layer 1")
        _layers.add(firstLayer)
        activeLayerId = firstLayer.id
        structuralRevision++
        notifyChanged()
    }

    fun restoreLayersFromMetadata(
        layersMetadata: List<app.goodboy13.milton.core.storage.LayerDescriptor>,
        targetActiveId: Long,
        renderer: app.goodboy13.milton.core.gl.MiltonCanvasRenderer? = null,
        onLayerRestored: (Layer, app.goodboy13.milton.core.storage.LayerDescriptor) -> Unit
    ) {
        for (layer in _layers) {
            layer.tileMap.clearTiles(renderer)
        }
        _layers.clear()

        var maxId = 0L
        for (desc in layersMetadata) {
            val layerCacheDir = cacheBaseDir?.resolve("layer_${desc.id}")
            val tileMap = TileMap(cacheDir = layerCacheDir, maxResidentTiles = maxResidentTilesPerLayer)
            val layer = Layer(
                id = desc.id,
                initialName = desc.name,
                initialOpacity = desc.opacity,
                initialIsVisible = desc.isVisible,
                tileMap = tileMap
            )
            _layers.add(layer)
            onLayerRestored(layer, desc)
            if (desc.id > maxId) maxId = desc.id
        }

        if (_layers.isEmpty()) {
            layerNameCounter = 1
            val firstLayer = createLayerInstance("Layer 1")
            _layers.add(firstLayer)
            activeLayerId = firstLayer.id
        } else {
            nextLayerId = maxOf(nextLayerId, maxId + 1)
            activeLayerId = if (_layers.any { it.id == targetActiveId }) targetActiveId else _layers.first().id
        }
        structuralRevision++
        notifyChanged()
    }

    fun releaseAll() {
        for (layer in _layers) {
            layer.tileMap.releaseAll()
        }
        _layers.clear()
        nativeDoc.close()
        cacheBaseDir?.deleteRecursively()
    }

    private fun notifyChanged() {
        structuralRevision++
        onLayersChangedListener?.invoke()
    }
}
