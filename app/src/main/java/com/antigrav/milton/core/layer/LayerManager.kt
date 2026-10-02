package com.antigrav.milton.core.layer

import com.antigrav.milton.core.model.WorldRect
import com.antigrav.milton.core.tile.TileMap
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Manages the stack of layers (up to 16) on the infinite canvas.
 * Layers are ordered bottom-to-top: index 0 is rendered first, last index is rendered on top.
 */
class LayerManager(
    val cacheBaseDir: File? = null,
    val maxResidentTilesPerLayer: Int = 48,
    val maxLayers: Int = MAX_LAYERS
) {
    companion object {
        const val MAX_LAYERS = 16
        const val MIN_LAYERS = 1
    }

    private var nextLayerId: Long = 1L
    private var layerNameCounter: Int = 1

    private val _layers = CopyOnWriteArrayList<Layer>()
    val layers: List<Layer> get() = _layers

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

    private fun createLayerInstance(name: String): Layer {
        val id = nextLayerId++
        val layerCacheDir = cacheBaseDir?.resolve("layer_$id")
        val tileMap = TileMap(cacheDir = layerCacheDir, maxResidentTiles = maxResidentTilesPerLayer)
        return Layer(
            id = id,
            name = name,
            opacity = 1.0f,
            isVisible = true,
            tileMap = tileMap
        )
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
        layerToDelete.tileMap.releaseAll()

        if (activeLayerId == layerId) {
            val newActiveIndex = index.coerceAtMost(_layers.size - 1)
            activeLayerId = _layers[newActiveIndex].id
        }

        notifyChanged()
        return true
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
        notifyChanged()
    }

    fun setLayerOpacity(layerId: Long, opacity: Float) {
        val layer = _layers.find { it.id == layerId } ?: return
        layer.opacity = opacity.coerceIn(0f, 1f)
        notifyChanged()
    }

    fun renameLayer(layerId: Long, newName: String) {
        val layer = _layers.find { it.id == layerId } ?: return
        layer.name = newName.trim().ifEmpty { "Layer" }
        notifyChanged()
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
        for (layer in _layers) {
            val visibleTiles = layer.tileMap.getVisibleTiles(visibleBounds)
            layer.tileMap.trimToBudget(visibleTiles)
        }
    }

    fun releaseAll() {
        for (layer in _layers) {
            layer.tileMap.releaseAll()
        }
        _layers.clear()
        cacheBaseDir?.deleteRecursively()
    }

    private fun notifyChanged() {
        onLayersChangedListener?.invoke()
    }
}
