package com.antigrav.milton

import com.antigrav.milton.core.history.UndoManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class UndoManagerTest {

    @Test
    fun testCompressionDecompressionRoundTrip() {
        val size = 512 * 512 * 4
        val testData = ByteArray(size)
        // Fill partially to simulate sparse line drawing on canvas
        val random = Random(42)
        for (i in 0 until 5000) {
            val idx = random.nextInt(size - 4)
            testData[idx] = 255.toByte()
            testData[idx + 1] = 100.toByte()
            testData[idx + 2] = 50.toByte()
            testData[idx + 3] = 255.toByte()
        }

        val compressed = UndoManager.compress(testData)
        // Ensure significant compression ratio for sparse tile data
        assertTrue("Compressed size ${compressed.size} should be < 50KB", compressed.size < 50_000)

        val decompressed = UndoManager.decompress(compressed)
        assertArrayEquals(testData, decompressed)
    }

    @Test
    fun testDecompressAllZeros() {
        val size = 512 * 512 * 4
        val blankTile = ByteArray(size)
        val compressed = UndoManager.compress(blankTile)
        val decompressed = UndoManager.decompress(compressed)
        assertArrayEquals(blankTile, decompressed)
    }
}
