package app.goodboy13.milton.core.document

import android.graphics.RectF
import app.goodboy13.milton.core.native.MiltonNative
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Headless, cross-platform infinite canvas document core backed by Rust (`milton_core::document`).
 *
 * Provides zero-allocation spatial tile hashing, layer management, and direct-memory
 * Flat Render Packet generation for hardware instanced presentation.
 */
class NativeMiltonDocument : Closeable {

    private val isClosed = AtomicBoolean(false)
    val docPtr: Long

    init {
        check(MiltonNative.isLoaded) { "MiltonNative core library must be loaded for NativeMiltonDocument" }
        docPtr = MiltonNative.createDocument()
        check(docPtr != 0L) { "Failed to create native MiltonDocument" }
    }

    val isValid: Boolean
        get() = docPtr != 0L && !isClosed.get()

    fun addLayer(layerId: Long, name: String, opacity: Float = 1.0f, visible: Boolean = true) {
        if (!isValid) return
        MiltonNative.documentAddLayer(docPtr, layerId, name, opacity, visible)
    }

    fun removeLayer(layerId: Long): Boolean {
        if (!isValid) return false
        return MiltonNative.documentRemoveLayer(docPtr, layerId)
    }

    fun setLayerOpacity(layerId: Long, opacity: Float) {
        if (!isValid) return
        MiltonNative.documentSetLayerOpacity(docPtr, layerId, opacity)
    }

    fun setLayerVisible(layerId: Long, visible: Boolean) {
        if (!isValid) return
        MiltonNative.documentSetLayerVisible(docPtr, layerId, visible)
    }

    fun reorderLayers(layerIds: LongArray) {
        if (!isValid) return
        MiltonNative.documentReorderLayers(docPtr, layerIds)
    }

    fun clearLayer(layerId: Long) {
        if (!isValid) return
        MiltonNative.documentClearLayer(docPtr, layerId)
    }

    fun setTile(layerId: Long, tx: Int, ty: Int, sliceIndex: Int, hasContent: Boolean) {
        if (!isValid) return
        MiltonNative.documentSetTile(docPtr, layerId, tx, ty, sliceIndex, hasContent)
    }

    fun setOverviewTile(layerId: Long, otx: Int, oty: Int, sliceIndex: Int, hasContent: Boolean) {
        if (!isValid) return
        MiltonNative.documentSetOverviewTile(docPtr, layerId, otx, oty, sliceIndex, hasContent)
    }

    fun generateRenderPacket(
        viewLeft: Float,
        viewTop: Float,
        viewRight: Float,
        viewBottom: Float,
        zoom: Float,
        forceOverview: Boolean,
        excludeLayerId: Long = 0L,
        excludeLeft: Float = 0f,
        excludeTop: Float = 0f,
        excludeRight: Float = 0f,
        excludeBottom: Float = 0f,
        outDirectBuf: ByteBuffer
    ): Int {
        if (!isValid) return 0
        return MiltonNative.documentGenerateRenderPacket(
            docPtr,
            viewLeft,
            viewTop,
            viewRight,
            viewBottom,
            zoom,
            forceOverview,
            excludeLayerId,
            excludeLeft,
            excludeTop,
            excludeRight,
            excludeBottom,
            outDirectBuf
        )
    }

    fun getBounds(): RectF? {
        if (!isValid) return null
        val bounds = FloatArray(4)
        if (MiltonNative.documentGetBounds(docPtr, bounds)) {
            return RectF(bounds[0], bounds[1], bounds[2], bounds[3])
        }
        return null
    }

    override fun close() {
        if (isClosed.compareAndSet(false, true)) {
            if (docPtr != 0L) {
                MiltonNative.destroyDocument(docPtr)
            }
        }
    }

    protected fun finalize() {
        close()
    }
}
