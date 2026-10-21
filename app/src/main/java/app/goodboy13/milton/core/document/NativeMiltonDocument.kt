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
        docPtr = if (MiltonNative.isLoaded) {
            MiltonNative.createDocument()
        } else {
            0L
        }
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

    fun setTile(layerId: Long, tx: Int, ty: Int, sliceIndex: Int, hasContent: Boolean) {
        if (!isValid) return
        MiltonNative.documentSetTile(docPtr, layerId, tx, ty, sliceIndex, hasContent)
    }

    fun generateRenderPacket(
        viewLeft: Float,
        viewTop: Float,
        viewRight: Float,
        viewBottom: Float,
        zoom: Float,
        forceOverview: Boolean,
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
