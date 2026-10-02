package com.antigrav.milton

import com.antigrav.milton.core.memory.DirectBufferPool
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer

class DirectBufferPoolTest {

    @Before
    fun setUp() {
        DirectBufferPool.clear()
        DirectBufferPool.maxPoolSize = DirectBufferPool.DEFAULT_MAX_POOL_SIZE
    }

    @After
    fun tearDown() {
        DirectBufferPool.clear()
    }

    @Test
    fun testAcquireReturnsDirectBufferOfExactSize() {
        val buf = DirectBufferPool.acquire()
        try {
            assertTrue("Buffer should be direct off-heap native memory", buf.isDirect)
            assertEquals("Capacity must match 512x512x4 (1 MB)", DirectBufferPool.BUFFER_SIZE, buf.capacity())
            assertEquals("Buffer position must be reset to 0", 0, buf.position())
            assertEquals("Buffer limit must equal capacity", DirectBufferPool.BUFFER_SIZE, buf.limit())
        } finally {
            DirectBufferPool.release(buf)
        }
    }

    @Test
    fun testBufferRecycling() {
        assertEquals("Initial pool count should be 0", 0, DirectBufferPool.pooledCount)

        val buf1 = DirectBufferPool.acquire()
        buf1.put(42.toByte())
        assertEquals(1, buf1.position())

        DirectBufferPool.release(buf1)
        assertEquals("Pool should contain 1 recycled buffer", 1, DirectBufferPool.pooledCount)

        val buf2 = DirectBufferPool.acquire()
        assertSame("Acquired buffer should be the recycled instance", buf1, buf2)
        assertEquals("Recycled buffer position must be cleared to 0", 0, buf2.position())
        assertEquals("Pool count should be 0 after acquire", 0, DirectBufferPool.pooledCount)

        DirectBufferPool.release(buf2)
    }

    @Test
    fun testUseBufferRecyclesAutomatically() {
        assertEquals(0, DirectBufferPool.pooledCount)

        var capturedBuffer: ByteBuffer? = null
        val result = DirectBufferPool.useBuffer { buf ->
            capturedBuffer = buf
            buf.put(123.toByte())
            "success"
        }

        assertEquals("success", result)
        assertEquals("Buffer must be automatically released back to pool", 1, DirectBufferPool.pooledCount)

        val reacquired = DirectBufferPool.acquire()
        assertSame(capturedBuffer, reacquired)
        assertEquals(0, reacquired.position())
        DirectBufferPool.release(reacquired)
    }

    @Test
    fun testUseBufferRecyclesOnException() {
        assertEquals(0, DirectBufferPool.pooledCount)

        try {
            DirectBufferPool.useBuffer { buf ->
                buf.put(99.toByte())
                throw IllegalStateException("Simulated crash inside block")
            }
            fail("Exception should have been thrown")
        } catch (e: IllegalStateException) {
            assertEquals("Simulated crash inside block", e.message)
        }

        assertEquals("Buffer must be released even when block throws", 1, DirectBufferPool.pooledCount)
    }

    @Test
    fun testMaxPoolCapacityEnforced() {
        DirectBufferPool.maxPoolSize = 2

        val b1 = DirectBufferPool.acquire()
        val b2 = DirectBufferPool.acquire()
        val b3 = DirectBufferPool.acquire()

        DirectBufferPool.release(b1)
        DirectBufferPool.release(b2)
        DirectBufferPool.release(b3) // Should be discarded because pool is full

        assertEquals(2, DirectBufferPool.pooledCount)
    }

    @Test
    fun testRejectsInvalidBuffers() {
        val heapBuf = ByteBuffer.allocate(DirectBufferPool.BUFFER_SIZE)
        DirectBufferPool.release(heapBuf)
        assertEquals("Heap buffer should not be pooled", 0, DirectBufferPool.pooledCount)

        val wrongSizeDirect = ByteBuffer.allocateDirect(1024)
        DirectBufferPool.release(wrongSizeDirect)
        assertEquals("Wrong sized direct buffer should not be pooled", 0, DirectBufferPool.pooledCount)
    }

    @Test
    fun testClearEmptiesPool() {
        val b1 = DirectBufferPool.acquire()
        DirectBufferPool.release(b1)
        assertEquals(1, DirectBufferPool.pooledCount)

        DirectBufferPool.clear()
        assertEquals(0, DirectBufferPool.pooledCount)
    }
}
