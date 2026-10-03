package app.goodboy13.milton.core.gl

import android.opengl.GLES30
import android.util.Log
import app.goodboy13.milton.core.tile.TileCoord
import java.util.ArrayDeque

/**
 * VRAM texture and Framebuffer Object (FBO) pool.
 * Pre-allocates and recycles 512x512 RGBA8 GL textures and FBOs matching
 * the resident tile budget, eliminating expensive glGenTextures, glTexImage2D,
 * and glDeleteTextures calls during tile paging and eviction.
 *
 * All methods must be called on the OpenGL render thread.
 */
object GlTexturePool {
    private const val TAG = "GlTexturePool"
    const val DEFAULT_MAX_POOL_SIZE: Int = 256

    var maxPoolSize: Int = DEFAULT_MAX_POOL_SIZE

    data class PooledEntry(val textureId: Int, val fboId: Int)

    private val pool = ArrayDeque<PooledEntry>()
    private val lock = Any()

    val pooledCount: Int
        get() = synchronized(lock) { pool.size }

    /**
     * Acquires a 512x512 RGBA8 texture and FBO pair from the pool,
     * or instantiates a new pair if the pool is empty.
     * The acquired FBO is cleared to transparent black (0, 0, 0, 0).
     */
    fun acquire(): PooledEntry {
        val entry = synchronized(lock) {
            if (pool.isNotEmpty()) pool.pop() else null
        }

        if (entry != null) {
            // Clear recycled FBO to transparent black
            val prevFbo = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, entry.fboId)
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, prevFbo[0])
            return entry
        }

        return createNewEntry()
    }

    /**
     * Releases a texture and FBO pair back into the pool.
     * If the pool is at maximum capacity, the GL resources are deleted immediately.
     */
    fun release(textureId: Int, fboId: Int) {
        if (textureId == 0 && fboId == 0) return

        val shouldPool = synchronized(lock) {
            if (pool.size < maxPoolSize) {
                pool.push(PooledEntry(textureId, fboId))
                true
            } else {
                false
            }
        }

        if (!shouldPool) {
            deleteEntry(textureId, fboId)
        }
    }

    /**
     * Deletes all pooled textures and FBOs and clears the pool.
     */
    fun clear() {
        val entriesToDelete = synchronized(lock) {
            val list = pool.toList()
            pool.clear()
            list
        }

        for (entry in entriesToDelete) {
            deleteEntry(entry.textureId, entry.fboId)
        }
    }

    private fun createNewEntry(): PooledEntry {
        val prevFbo = IntArray(1)
        val prevTex = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
        GLES30.glGetIntegerv(GLES30.GL_TEXTURE_BINDING_2D, prevTex, 0)

        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        val textureId = textures[0]

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8,
            TileCoord.TILE_SIZE, TileCoord.TILE_SIZE, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
        )

        val fbos = IntArray(1)
        GLES30.glGenFramebuffers(1, fbos, 0)
        val fboId = fbos[0]

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, textureId, 0
        )

        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "FBO initialization incomplete: status=$status")
        }

        // Clear initial tile content to transparent black
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        // Restore previous bindings
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, prevFbo[0])
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, prevTex[0])

        return PooledEntry(textureId, fboId)
    }

    private fun deleteEntry(textureId: Int, fboId: Int) {
        if (fboId != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
        }
        if (textureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
        }
    }
}
