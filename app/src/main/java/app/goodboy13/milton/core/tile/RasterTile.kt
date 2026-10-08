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
 * A single 512x512 raster chunk of the infinite canvas.
 * Backed by an OpenGL ES 3.0 RGBA texture and Framebuffer Object (FBO).
 */
class RasterTile(val coord: TileCoord) {
    var textureId: Int = 0
        private set
    var fboId: Int = 0
        private set

    var isInitialized: Boolean = false
        private set
    var isDirty: Boolean = false

    var lastAccessTime: Long = System.nanoTime()
    var hasContent: Boolean = false
    var isOnDisk: Boolean = false

    private val prevFbo = IntArray(1)
    private val prevViewport = IntArray(4)

    fun initGl() {
        if (isInitialized) return

        val entry = GlTexturePool.acquire()
        textureId = entry.textureId
        fboId = entry.fboId

        isInitialized = true
        isDirty = true
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
     * Writes an RGBA pixel buffer back into this tile's texture.
     */
    fun writePixels(rawBytes: ByteArray) {
        if (!isInitialized) initGl()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        DirectBufferPool.useBuffer { byteBuf ->
            byteBuf.put(rawBytes)
            byteBuf.position(0)
            GLES30.glTexSubImage2D(
                GLES30.GL_TEXTURE_2D, 0,
                0, 0,
                TileCoord.TILE_SIZE, TileCoord.TILE_SIZE,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, byteBuf
            )
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
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
        if (!hasContent || cacheDir == null) {
            releaseGl()
            isOnDisk = false
            return
        }

        try {
            val raw = readPixels()
            if (app.goodboy13.milton.core.native.MiltonNative.isLoaded) {
                val ok = app.goodboy13.milton.core.native.MiltonNative.swapWriteTile(
                    cacheDir.absolutePath, 0L, coord.tx, coord.ty, raw
                )
                if (ok) {
                    isOnDisk = true
                    return
                }
            }
            val swapFile = getSwapFile(cacheDir)
            swapFile.parentFile?.mkdirs()
            swapFile.writeBytes(raw)
            isOnDisk = true
        } catch (e: Exception) {
            Log.e("RasterTile", "Failed to evict tile $coord to disk", e)
        } finally {
            releaseGl()
        }
    }

    fun restoreFromDisk(cacheDir: File?) {
        if (!isOnDisk || cacheDir == null) {
            if (!isInitialized) initGl()
            return
        }

        try {
            if (app.goodboy13.milton.core.native.MiltonNative.isLoaded) {
                val raw = ByteArray(TileCoord.TILE_SIZE * TileCoord.TILE_SIZE * 4)
                val ok = app.goodboy13.milton.core.native.MiltonNative.swapReadTile(
                    cacheDir.absolutePath, 0L, coord.tx, coord.ty, raw
                )
                if (ok) {
                    initGl()
                    writePixels(raw)
                    hasContent = true
                    return
                }
            }
            val swapFile = getSwapFile(cacheDir)
            val raw = readSwapBytes(swapFile)
            if (raw != null) {
                initGl()
                writePixels(raw)
                hasContent = true
            } else {
                initGl()
            }
        } catch (e: Exception) {
            Log.e("RasterTile", "Failed to restore tile $coord from disk", e)
            initGl()
        } finally {
            isOnDisk = false
        }
    }

    fun deleteDiskSwap(cacheDir: File?) {
        if (cacheDir != null) {
            if (app.goodboy13.milton.core.native.MiltonNative.isLoaded) {
                app.goodboy13.milton.core.native.MiltonNative.swapDeleteTile(
                    cacheDir.absolutePath, 0L, coord.tx, coord.ty
                )
            }
            val swapFile = getSwapFile(cacheDir)
            if (swapFile.exists()) {
                swapFile.delete()
            }
        }
        isOnDisk = false
    }

    private fun getSwapFile(cacheDir: File): File {
        return File(cacheDir, "tile_${coord.tx}_${coord.ty}.bin")
    }

    companion object {
        /**
         * Reads tile swap bytes from disk. Supports both lightning-fast raw 1MB streams
         * and legacy Deflater-compressed files with automatic decompression.
         */
        fun readSwapBytes(swapFile: File): ByteArray? {
            if (!swapFile.exists()) return null
            val data = swapFile.readBytes()
            return if (data.size == TileCoord.TILE_SIZE * TileCoord.TILE_SIZE * 4) {
                data
            } else {
                UndoManager.decompress(data)
            }
        }
    }

    fun releaseGl() {
        if (textureId != 0 && fboId != 0) {
            GlTexturePool.release(textureId, fboId)
            textureId = 0
            fboId = 0
        } else if (fboId != 0) {
            val fbos = intArrayOf(fboId)
            GLES30.glDeleteFramebuffers(1, fbos, 0)
            fboId = 0
        } else if (textureId != 0) {
            val textures = intArrayOf(textureId)
            GLES30.glDeleteTextures(1, textures, 0)
            textureId = 0
        }
        isInitialized = false
    }
}
