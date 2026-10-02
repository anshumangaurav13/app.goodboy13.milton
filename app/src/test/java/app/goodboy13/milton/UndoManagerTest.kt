package app.goodboy13.milton

import app.goodboy13.milton.core.history.UndoManager
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

    @Test
    fun testEmptyTileCapturesNullPreStrokeDelta() {
        val undoManager = UndoManager()
        val tile = app.goodboy13.milton.core.tile.RasterTile(app.goodboy13.milton.core.tile.TileCoord(0, 0))
        org.junit.Assert.assertFalse(tile.hasContent)

        undoManager.capturePreStrokeTiles(listOf(tile))
        val tileMap = app.goodboy13.milton.core.tile.TileMap()
        undoManager.commitStroke(tileMap)

        // Empty tile with no content changes must not push a command to the undo stack
        org.junit.Assert.assertFalse(undoManager.canUndo)
    }
}
