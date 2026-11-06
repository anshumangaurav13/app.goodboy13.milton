package app.goodboy13.milton.core.tile

import android.opengl.GLES30
import android.util.Log
import app.goodboy13.milton.core.gl.GlTexturePool
import app.goodboy13.milton.core.history.UndoManager
import app.goodboy13.milton.core.memory.DirectBufferPool
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Formal residence states for a 512x512 RasterTile chunk on the infinite canvas.
 */
sealed interface TileResidenceState {
    /** Tile exists in coordinate map but occupies neither VRAM nor disk storage. */
    object VirtualEmpty : TileResidenceState

    /** Tile is resident in VRAM backed by OpenGL texture/FBO. */
    data class Resident(
        val textureId: Int,
        val fboId: Int,
        val sliceIndex: Int,
        val hasContent: Boolean = false
    ) : TileResidenceState

    /** Tile has content evicted to disk cache to maintain VRAM memory budget. */
    object SwappedToDisk : TileResidenceState
}

/**
 * A single 512x512 raster chunk of the infinite canvas.
 * Backed by an OpenGL ES 3.0 RGBA texture and Framebuffer Object (FBO).
 * Lifecycle is governed by an explicit [TileResidenceState] state machine.
 */
class RasterTile(
    val coord: TileCoord,
    var onStateChanged: ((RasterTile) -> Unit)? = null
) {
    var residenceState: TileResidenceState = TileResidenceState.VirtualEmpty
        private set

    val textureId: Int get() = when (val s = residenceState) {
        is TileResidenceState.Resident -> s.textureId
        else -> 0
    }

    val fboId: Int get() = when (val s = residenceState) {
        is TileResidenceState.Resident -> s.fboId
        else -> 0
    }

    val sliceIndex: Int get() = when (val s = residenceState) {
        is TileResidenceState.Resident -> s.sliceIndex
        else -> -1
    }

    val isInitialized: Boolean get() = residenceState is TileResidenceState.Resident
    val isResident: Boolean get() = isInitialized
    var isDirty: Boolean = false

    var lastAccessTime: Long = System.nanoTime()

    var hasContent: Boolean
        get() = when (val s = residenceState) {
            is TileResidenceState.Resident -> s.hasContent
            is TileResidenceState.SwappedToDisk -> true
            else -> false
        }
        set(value) {
            when (val s = residenceState) {
                is TileResidenceState.Resident -> {
                    if (s.hasContent != value) {
                        residenceState = s.copy(hasContent = value)
                        onStateChanged?.invoke(this)
                    }
                }
                is TileResidenceState.SwappedToDisk -> {
                    if (!value) {
                        residenceState = TileResidenceState.VirtualEmpty
                        onStateChanged?.invoke(this)
                    }
                }
                is TileResidenceState.VirtualEmpty -> {}
            }
        }

    var isOnDisk: Boolean
        get() = residenceState is TileResidenceState.SwappedToDisk
        set(value) {
            if (value) {
                residenceState = TileResidenceState.SwappedToDisk
            } else if (residenceState is TileResidenceState.SwappedToDisk) {
                residenceState = TileResidenceState.VirtualEmpty
            }
        }

    private val prevFbo = IntArray(1)
    private val prevViewport = IntArray(4)

    fun initGl() {
        if (isInitialized) return

        val entry = GlTexturePool.acquire()
        residenceState = TileResidenceState.Resident(
            textureId = entry.textureId,
            fboId = entry.fboId,
            sliceIndex = entry.sliceIndex,
            hasContent = false
        )

        isDirty = true
        onStateChanged?.invoke(this)
    }

    /**
     * Binds this tile's FBO directly without querying glGetIntegerv for existing FBO/viewport state.
     * Prevents synchronous CPU-GPU pipeline stalls during batch tile stamping.
     */
    fun bindFboDirect() {
        if (!isInitialized) initGl()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
        GLES30.glViewport(0, 0, TileCoord.TILE_SIZE, TileCoord.TILE_SIZE)
    }

    fun bindFbo() {
        if (!isInitialized) initGl()
        GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, prevViewport, 0)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
        GLES30.glViewport(0, 0, TileCoord.TILE_SIZE, TileCoord.TILE_SIZE)
    }

    fun unbindFbo() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, prevFbo[0])
        GLES30.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3])
    }

    fun clear() {
        if (!isInitialized) return
        bindFbo()
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        unbindFbo()
        isDirty = true
        hasContent = false
    }

    /**
     * Clones this tile's texture directly into a target FBO on the GPU using glBlitFramebuffer.
     * Operates completely in VRAM with zero CPU stalls or pipeline readback overhead.
     */
    fun cloneToFbo(targetFboId: Int) {
        if (!isInitialized) return
        val prevReadFbo = IntArray(1)
        val prevDrawFbo = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_READ_FRAMEBUFFER_BINDING, prevReadFbo, 0)
        GLES30.glGetIntegerv(GLES30.GL_DRAW_FRAMEBUFFER_BINDING, prevDrawFbo, 0)

        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, fboId)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, targetFboId)
        GLES30.glBlitFramebuffer(
            0, 0, TileCoord.TILE_SIZE, TileCoord.TILE_SIZE,
            0, 0, TileCoord.TILE_SIZE, TileCoord.TILE_SIZE,
            GLES30.GL_COLOR_BUFFER_BIT,
            GLES30.GL_NEAREST
        )

        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, prevReadFbo[0])
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, prevDrawFbo[0])
    }

    /**
     * Reads the current RGBA pixel buffer of this tile from its FBO.
     */
    fun readPixels(): ByteArray {
        if (!isInitialized) initGl()
        bindFbo()
        val rawBytes = ByteArray(TileCoord.TILE_SIZE * TileCoord.TILE_SIZE * 4)
        DirectBufferPool.useBuffer { byteBuf ->
            GLES30.glReadPixels(
                0, 0,
                TileCoord.TILE_SIZE, TileCoord.TILE_SIZE,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, byteBuf
            )
            byteBuf.rewind()
            byteBuf.get(rawBytes)
        }
        unbindFbo()
        return rawBytes
    }

    /**
     * Reads this tile's current RGBA pixel buffer directly into an off-heap direct ByteBuffer.
     * Uses bindFboDirect to avoid synchronous glGetIntegerv state queries.
     */
    fun readPixelsDirect(outDirectBuf: ByteBuffer) {
        if (!isInitialized) initGl()
        bindFboDirect()
        outDirectBuf.clear()
        GLES30.glReadPixels(
            0, 0,
            TileCoord.TILE_SIZE, TileCoord.TILE_SIZE,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, outDirectBuf
        )
        outDirectBuf.rewind()
    }

    /**
     * Writes RGBA pixels directly from an off-heap direct ByteBuffer into this tile's texture.
     */
    fun writePixelsDirect(directBuf: ByteBuffer) {
        if (!isInitialized) initGl()
        directBuf.position(0)
        if (sliceIndex >= 0) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, textureId)
            GLES30.glTexSubImage3D(
                GLES30.GL_TEXTURE_2D_ARRAY, 0,
                0, 0, sliceIndex,
                TileCoord.TILE_SIZE, TileCoord.TILE_SIZE, 1,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, directBuf
            )
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, 0)
        } else {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
            GLES30.glTexSubImage2D(
                GLES30.GL_TEXTURE_2D, 0,
                0, 0,
                TileCoord.TILE_SIZE, TileCoord.TILE_SIZE,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, directBuf
            )
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        }
        isDirty = true
        hasContent = true
    }

    /**
     * Writes an RGBA pixel buffer back into this tile's texture.
     */
    fun writePixels(rawBytes: ByteArray) {
        val expectedSize = TileCoord.TILE_SIZE * TileCoord.TILE_SIZE * 4
        if (rawBytes.size < expectedSize) {
            Log.e("RasterTile", "Refusing to write truncated pixel buffer: ${rawBytes.size} < $expectedSize")
            return
        }
        if (!isInitialized) initGl()
        DirectBufferPool.useBuffer { byteBuf ->
            byteBuf.put(rawBytes, 0, expectedSize)
            byteBuf.position(0)
            if (sliceIndex >= 0) {
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, textureId)
                GLES30.glTexSubImage3D(
                    GLES30.GL_TEXTURE_2D_ARRAY, 0,
                    0, 0, sliceIndex,
                    TileCoord.TILE_SIZE, TileCoord.TILE_SIZE, 1,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, byteBuf
                )
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, 0)
            } else {
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
                GLES30.glTexSubImage2D(
                    GLES30.GL_TEXTURE_2D, 0,
                    0, 0,
                    TileCoord.TILE_SIZE, TileCoord.TILE_SIZE,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, byteBuf
                )
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            }
        }
        isDirty = true
        hasContent = true
    }

    fun ensureResident(cacheDir: File?) {
        lastAccessTime = System.nanoTime()
        if (isOnDisk) {
            restoreFromDisk(cacheDir)
        } else if (!isInitialized) {
            initGl()
        }
    }

    fun evictToDisk(cacheDir: File?) {
        if (!isInitialized) return
        val currentHasContent = hasContent
        if (!currentHasContent || cacheDir == null) {
            releaseGl()
            residenceState = TileResidenceState.VirtualEmpty
            return
        }

        try {
            var ok = false
            bindFbo()
            DirectBufferPool.useBuffer { byteBuf ->
                GLES30.glReadPixels(
                    0, 0,
                    TileCoord.TILE_SIZE, TileCoord.TILE_SIZE,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, byteBuf
                )
                byteBuf.rewind()
                ok = app.goodboy13.milton.core.native.MiltonNative.swapWriteTileDirect(
                    cacheDir.absolutePath, 0L, coord.tx, coord.ty, byteBuf
                )
            }
            unbindFbo()
            if (ok) {
                residenceState = TileResidenceState.SwappedToDisk
            } else {
                residenceState = TileResidenceState.VirtualEmpty
            }
        } catch (e: Exception) {
            Log.e("RasterTile", "Failed to evict tile $coord to disk", e)
            residenceState = TileResidenceState.VirtualEmpty
        } finally {
            releaseGlInternal()
        }
    }

    fun restoreFromDisk(cacheDir: File?) {
        if (!isOnDisk || cacheDir == null) {
            if (!isInitialized) initGl()
            return
        }

        try {
            var ok = false
            DirectBufferPool.useBuffer { byteBuf ->
                ok = app.goodboy13.milton.core.native.MiltonNative.swapReadTileDirect(
                    cacheDir.absolutePath, 0L, coord.tx, coord.ty, byteBuf
                )
                if (ok) {
                    initGl()
                    byteBuf.position(0)
                    if (sliceIndex >= 0) {
                        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, textureId)
                        GLES30.glTexSubImage3D(
                            GLES30.GL_TEXTURE_2D_ARRAY, 0,
                            0, 0, sliceIndex,
                            TileCoord.TILE_SIZE, TileCoord.TILE_SIZE, 1,
                            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, byteBuf
                        )
                        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, 0)
                    } else {
                        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
                        GLES30.glTexSubImage2D(
                            GLES30.GL_TEXTURE_2D, 0,
                            0, 0,
                            TileCoord.TILE_SIZE, TileCoord.TILE_SIZE,
                            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, byteBuf
                        )
                        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
                    }
                    isDirty = true
                    hasContent = true
                }
            }
        } catch (e: Exception) {
            Log.e("RasterTile", "Failed to restore tile $coord from disk", e)
            initGl()
        }
    }

    fun deleteDiskSwap(cacheDir: File?) {
        if (cacheDir != null) {
            app.goodboy13.milton.core.native.MiltonNative.swapDeleteTile(
                cacheDir.absolutePath, 0L, coord.tx, coord.ty
            )
        }
        if (residenceState is TileResidenceState.SwappedToDisk) {
            residenceState = TileResidenceState.VirtualEmpty
        }
    }

    fun releaseGl() {
        releaseGlInternal()
        if (residenceState !is TileResidenceState.SwappedToDisk) {
            residenceState = TileResidenceState.VirtualEmpty
        }
        onStateChanged?.invoke(this)
    }

    private fun releaseGlInternal() {
        val curTex = textureId
        val curFbo = fboId
        val curSlice = sliceIndex

        if (curTex != 0 && curFbo != 0) {
            GlTexturePool.release(curTex, curFbo, curSlice)
        } else if (curFbo != 0) {
            val fbos = intArrayOf(curFbo)
            GLES30.glDeleteFramebuffers(1, fbos, 0)
        } else if (curTex != 0) {
            val textures = intArrayOf(curTex)
            GLES30.glDeleteTextures(1, textures, 0)
        }
    }
}
