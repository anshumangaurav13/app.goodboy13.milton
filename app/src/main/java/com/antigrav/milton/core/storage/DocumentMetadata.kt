package com.antigrav.milton.core.storage

import org.json.JSONArray
import org.json.JSONObject

/**
 * Metadata descriptor representing a saved Milton project document.
 */
data class DocumentMetadata(
    var title: String = "Untitled Artwork",
    val version: Int = 1,
    var createdAt: Long = System.currentTimeMillis(),
    var modifiedAt: Long = System.currentTimeMillis(),
    var backgroundColorRgb: Int = 0xFFF8F8F7.toInt(),
    var viewportPanX: Float = 0f,
    var viewportPanY: Float = 0f,
    var viewportZoom: Float = 0.5f,
    var viewportRotation: Float = 0f,
    var isFlippedHorizontally: Boolean = false,
    var activeLayerId: Long = 1L,
    val layers: MutableList<LayerDescriptor> = mutableListOf()
) {
    fun toJson(): String {
        val root = JSONObject()
        root.put("title", title)
        root.put("version", version)
        root.put("createdAt", createdAt)
        root.put("modifiedAt", modifiedAt)
        root.put("backgroundColorRgb", backgroundColorRgb)
        root.put("viewportPanX", viewportPanX.toDouble())
        root.put("viewportPanY", viewportPanY.toDouble())
        root.put("viewportZoom", viewportZoom.toDouble())
        root.put("viewportRotation", viewportRotation.toDouble())
        root.put("isFlippedHorizontally", isFlippedHorizontally)
        root.put("activeLayerId", activeLayerId)

        val layersArray = JSONArray()
        for (l in layers) {
            val lObj = JSONObject()
            lObj.put("id", l.id)
            lObj.put("name", l.name)
            lObj.put("opacity", l.opacity.toDouble())
            lObj.put("isVisible", l.isVisible)
            val tilesArray = JSONArray()
            for ((tx, ty) in l.tiles) {
                val tObj = JSONObject()
                tObj.put("tx", tx)
                tObj.put("ty", ty)
                tilesArray.put(tObj)
            }
            lObj.put("tiles", tilesArray)
            layersArray.put(lObj)
        }
        root.put("layers", layersArray)
        return root.toString(2)
    }

    companion object {
        fun fromJson(jsonStr: String): DocumentMetadata {
            val root = JSONObject(jsonStr)
            val meta = DocumentMetadata(
                title = root.optString("title", "Untitled Artwork"),
                version = root.optInt("version", 1),
                createdAt = root.optLong("createdAt", System.currentTimeMillis()),
                modifiedAt = root.optLong("modifiedAt", System.currentTimeMillis()),
                backgroundColorRgb = root.optInt("backgroundColorRgb", 0xFFF8F8F7.toInt()),
                viewportPanX = root.optDouble("viewportPanX", 0.0).toFloat(),
                viewportPanY = root.optDouble("viewportPanY", 0.0).toFloat(),
                viewportZoom = root.optDouble("viewportZoom", 0.5).toFloat(),
                viewportRotation = root.optDouble("viewportRotation", 0.0).toFloat(),
                isFlippedHorizontally = root.optBoolean("isFlippedHorizontally", false),
                activeLayerId = root.optLong("activeLayerId", 1L)
            )

            val layersArray = root.optJSONArray("layers")
            if (layersArray != null) {
                for (i in 0 until layersArray.length()) {
                    val lObj = layersArray.getJSONObject(i)
                    val id = lObj.getLong("id")
                    val name = lObj.getString("name")
                    val opacity = lObj.getDouble("opacity").toFloat()
                    val isVis = lObj.getBoolean("isVisible")
                    val tilesList = mutableListOf<Pair<Int, Int>>()
                    val tArr = lObj.optJSONArray("tiles")
                    if (tArr != null) {
                        for (j in 0 until tArr.length()) {
                            val tObj = tArr.getJSONObject(j)
                            tilesList.add(tObj.getInt("tx") to tObj.getInt("ty"))
                        }
                    }
                    meta.layers.add(LayerDescriptor(id, name, opacity, isVis, tilesList))
                }
            }
            return meta
        }
    }
}

data class LayerDescriptor(
    val id: Long,
    val name: String,
    val opacity: Float,
    val isVisible: Boolean,
    val tiles: List<Pair<Int, Int>>
)
