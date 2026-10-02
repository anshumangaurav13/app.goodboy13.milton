package com.antigrav.milton.core.tile

import android.opengl.GLES30
import android.util.Log
import com.antigrav.milton.core.history.UndoManager
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

        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        textureId = textures[0]

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8,
            TileCoord.TILE_SIZE, TileCoord.TILE_SIZE, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
        )

        val fbos = IntArray(1)
        GLES30.glGenFramebuffers(1, fbos, 0)
        fboId = fbos[0]

        GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, textureId, 0
        )

        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            throw RuntimeException("RasterTile FBO initialization incomplete: $status")
        }

        // Clear initial tile content to transparent black
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        // Restore previous FBO
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, prevFbo[0])
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

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
        val byteBuf = ByteBuffer.allocateDirect(TileCoord.TILE_SIZE * TileCoord.TILE_SIZE * 4)
            .order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(
            0, 0,
            TileCoord.TILE_SIZE, TileCoord.TILE_SIZE,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, byteBuf
        )
        unbindFbo()
        val rawBytes = ByteArray(TileCoord.TILE_SIZE * TileCoord.TILE_SIZE * 4)
        byteBuf.rewind()
        byteBuf.get(rawBytes)
        return rawBytes
    }

    /**
     * Writes an RGBA pixel buffer back into this tile's texture.
     */
    fun writePixels(rawBytes: ByteArray) {
        if (!isInitialized) initGl()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        val byteBuf = ByteBuffer.allocateDirect(rawBytes.size)
            .order(ByteOrder.nativeOrder())
            .put(rawBytes)
        byteBuf.position(0)
        GLES30.glTexSubImage2D(
            GLES30.GL_TEXTURE_2D, 0,
            0, 0,
            TileCoord.TILE_SIZE, TileCoord.TILE_SIZE,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, byteBuf
        )
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
            val compressed = UndoManager.compress(raw)
            val swapFile = getSwapFile(cacheDir)
            swapFile.parentFile?.mkdirs()
            swapFile.writeBytes(compressed)
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
            val swapFile = getSwapFile(cacheDir)
            if (swapFile.exists()) {
                val compressed = swapFile.readBytes()
                val raw = UndoManager.decompress(compressed)
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

    fun releaseGl() {
        if (fboId != 0) {
            val fbos = intArrayOf(fboId)
            GLES30.glDeleteFramebuffers(1, fbos, 0)
            fboId = 0
        }
        if (textureId != 0) {
            val textures = intArrayOf(textureId)
            GLES30.glDeleteTextures(1, textures, 0)
            textureId = 0
        }
        isInitialized = false
    }
}
