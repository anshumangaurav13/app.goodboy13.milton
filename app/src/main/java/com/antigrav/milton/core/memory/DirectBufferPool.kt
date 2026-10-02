package com.antigrav.milton.core.memory

import com.antigrav.milton.core.tile.TileCoord
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque

/**
 * High-performance off-heap direct ByteBuffer pool.
 * Recycles 1MB (512x512x4) buffers across tile readPixels, writePixels,
 * and export operations to eliminate GC heap churn and native allocation spikes.
 */
object DirectBufferPool {
    const val BUFFER_SIZE: Int = TileCoord.TILE_SIZE * TileCoord.TILE_SIZE * 4 // 1,048,576 bytes (1 MB)
    const val DEFAULT_MAX_POOL_SIZE: Int = 6

    var maxPoolSize: Int = DEFAULT_MAX_POOL_SIZE

    private val pool = ArrayDeque<ByteBuffer>()
    private val lock = Any()

    val pooledCount: Int
        get() = synchronized(lock) { pool.size }

    /**
     * Acquires a cleared, native-ordered direct ByteBuffer of size [BUFFER_SIZE].
     */
    fun acquire(): ByteBuffer = synchronized(lock) {
        if (pool.isNotEmpty()) {
            val buf = pool.pop()
            buf.clear()
            buf
        } else {
            ByteBuffer.allocateDirect(BUFFER_SIZE).order(ByteOrder.nativeOrder())
        }
    }

    /**
     * Releases a direct ByteBuffer back into the pool if it meets size requirements
     * and the pool is below capacity.
     */
    fun release(buffer: ByteBuffer) = synchronized(lock) {
        if (buffer.isDirect && buffer.capacity() == BUFFER_SIZE && pool.size < maxPoolSize) {
            buffer.clear()
            pool.push(buffer)
        }
    }

    /**
     * Executes [block] with a pooled direct buffer, ensuring it is automatically
     * recycled upon completion.
     */
    inline fun <R> useBuffer(block: (ByteBuffer) -> R): R {
        val buffer = acquire()
        try {
            return block(buffer)
        } finally {
            release(buffer)
        }
    }

    /**
     * Clears all pooled buffers.
     */
    fun clear() = synchronized(lock) {
        pool.clear()
    }
}
