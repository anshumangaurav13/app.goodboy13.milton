package app.goodboy13.milton.core.gl

import android.opengl.GLES30
import android.util.Log
import app.goodboy13.milton.core.constants.CanvasConstants
import app.goodboy13.milton.core.tile.TileCoord
import java.util.ArrayDeque

/**
 * VRAM texture and Framebuffer Object (FBO) pool.
 * Pre-allocates and manages a shared 512x512 RGBA8 GL_TEXTURE_2D_ARRAY matching
 * the resident tile budget, enabling single-draw-call canvas presentation.
 *
 * Each resident tile occupies a slice index (0 until maxPoolSize) in the texture array,
 * with an individual pre-bound FBO attached via glFramebufferTextureLayer.
 *
 * All methods must be called on the OpenGL render thread.
 */
object GlTexturePool {
    private const val TAG = "GlTexturePool"
    const val DEFAULT_MAX_POOL_SIZE: Int = CanvasConstants.DEFAULT_GL_TEXTURE_POOL_SIZE

    var maxPoolSize: Int = DEFAULT_MAX_POOL_SIZE

    data class PooledEntry(
        val textureId: Int,
        val fboId: Int,
        val sliceIndex: Int = -1
    )

    private val lock = Any()

    var isGlInitialized: Boolean = false
        private set

    var usesTextureArray: Boolean = false
        private set

    var textureArrayId: Int = 0
        private set

    private var sliceFbos = IntArray(0)
    private val freeSlices = ArrayDeque<Int>()

    // Fallback 2D texture pool
    private val fallbackPool = ArrayDeque<PooledEntry>()

    val pooledCount: Int
        get() = synchronized(lock) {
            if (usesTextureArray) freeSlices.size else fallbackPool.size
        }

    /**
     * Initializes the OpenGL ES 3.0 2D texture array and slice FBOs on the GL thread.
     */
    fun ensureGlInitialized() {
        if (isGlInitialized) return

        val maxLayers = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_ARRAY_TEXTURE_LAYERS, maxLayers, 0)
        val supportedLayers = maxLayers[0].coerceAtLeast(1)
        val poolSize = minOf(maxPoolSize, supportedLayers)

        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        val texId = textures[0]

        if (texId != 0) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, texId)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

            // Allocate immutable 3D storage for the 2D texture array (512 x 512 x poolSize)
            GLES30.glTexStorage3D(
                GLES30.GL_TEXTURE_2D_ARRAY, 1, GLES30.GL_RGBA8,
                TileCoord.TILE_SIZE, TileCoord.TILE_SIZE, poolSize
            )

            val err = GLES30.glGetError()
            if (err == GLES30.GL_NO_ERROR) {
                textureArrayId = texId
                sliceFbos = IntArray(poolSize)
                GLES30.glGenFramebuffers(poolSize, sliceFbos, 0)

                synchronized(lock) {
                    freeSlices.clear()
                    for (i in 0 until poolSize) {
                        val fbo = sliceFbos[i]
                        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
                        GLES30.glFramebufferTextureLayer(
                            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                            textureArrayId, 0, i
                        )
                        GLES30.glClearColor(0f, 0f, 0f, 0f)
                        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                        freeSlices.push(i)
                    }
                }
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, 0)

                usesTextureArray = true
                Log.i(TAG, "Initialized GL_TEXTURE_2D_ARRAY with $poolSize slices of 512x512")
            } else {
                Log.w(TAG, "glTexStorage3D failed with error $err, falling back to 2D textures")
                GLES30.glDeleteTextures(1, textures, 0)
                usesTextureArray = false
            }
        } else {
            usesTextureArray = false
        }

        isGlInitialized = true
    }

    /**
     * Acquires a 512x512 RGBA8 texture and FBO pair from the pool.
     */
    fun acquire(): PooledEntry {
        if (!isGlInitialized) {
            ensureGlInitialized()
        }

        if (usesTextureArray) {
            val slice = synchronized(lock) {
                if (freeSlices.isNotEmpty()) freeSlices.pop() else null
            }

            if (slice != null) {
                val fbo = sliceFbos[slice]
                // Clear slice to transparent black
                val prevFbo = IntArray(1)
                GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
                GLES30.glClearColor(0f, 0f, 0f, 0f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, prevFbo[0])

                return PooledEntry(textureArrayId, fbo, slice)
            }
            // Slices exhausted, fall through to fallback 2D texture entry
        }

        return acquireFallbackEntry()
    }

    /**
     * Releases a texture and FBO entry back into the pool.
     */
    fun release(entry: PooledEntry) {
        release(entry.textureId, entry.fboId, entry.sliceIndex)
    }

    fun release(textureId: Int, fboId: Int, sliceIndex: Int = -1) {
        if (textureId == 0 && fboId == 0) return

        if (sliceIndex >= 0 && usesTextureArray && textureId == textureArrayId) {
            synchronized(lock) {
                freeSlices.push(sliceIndex)
            }
            return
        }

        // Fallback individual texture release
        val shouldPool = synchronized(lock) {
            if (fallbackPool.size < maxPoolSize) {
                fallbackPool.push(PooledEntry(textureId, fboId, -1))
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
        synchronized(lock) {
            if (usesTextureArray) {
                if (sliceFbos.isNotEmpty()) {
                    GLES30.glDeleteFramebuffers(sliceFbos.size, sliceFbos, 0)
                    sliceFbos = IntArray(0)
                }
                if (textureArrayId != 0) {
                    GLES30.glDeleteTextures(1, intArrayOf(textureArrayId), 0)
                    textureArrayId = 0
                }
                freeSlices.clear()
                usesTextureArray = false
            }

            val fallbackList = fallbackPool.toList()
            fallbackPool.clear()
            for (entry in fallbackList) {
                deleteEntry(entry.textureId, entry.fboId)
            }
            isGlInitialized = false
        }
    }

    private fun acquireFallbackEntry(): PooledEntry {
        val entry = synchronized(lock) {
            if (fallbackPool.isNotEmpty()) fallbackPool.pop() else null
        }

        if (entry != null) {
            val prevFbo = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, entry.fboId)
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, prevFbo[0])
            return entry
        }

        return createNewFallbackEntry()
    }

    private fun createNewFallbackEntry(): PooledEntry {
        val prevFbo = IntArray(1)
        val prevTex = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
        GLES30.glGetIntegerv(GLES30.GL_TEXTURE_BINDING_2D, prevTex, 0)

        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        val textureId = textures[0]

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
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
            Log.e(TAG, "Fallback FBO initialization incomplete: status=$status")
        }

        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, prevFbo[0])
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, prevTex[0])

        return PooledEntry(textureId, fboId, -1)
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
