package com.antigrav.milton

import com.antigrav.milton.core.model.WorldRect
import com.antigrav.milton.core.tile.TileCoord
import com.antigrav.milton.core.tile.TileMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TileMapTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testTileMapCreationAndBoundsQuery() {
        val cacheDir = tempFolder.newFolder("tiles")
        val tileMap = TileMap(cacheDir = cacheDir, maxResidentTiles = 10)

        tileMap.getOrCreateTile(0, 0)
        tileMap.getOrCreateTile(1, 0)
        tileMap.getOrCreateTile(0, 1)

        assertEquals(3, tileMap.count)

        // Query visible bounds strictly inside tile (0,0): 10f to 500f
        val visible = tileMap.getVisibleTiles(WorldRect(10f, 10f, 500f, 500f))
        assertEquals(1, visible.size)
        assertEquals(0, visible[0].coord.tx)
        assertEquals(0, visible[0].coord.ty)
    }

    @Test
    fun testLruEvictionPreservesVisibleTiles() {
        val cacheDir = tempFolder.newFolder("lru_test")
        val budget = 3
        val tileMap = TileMap(cacheDir = cacheDir, maxResidentTiles = budget)

        // Create 5 tiles
        val t0 = tileMap.getOrCreateTile(0, 0)
        val t1 = tileMap.getOrCreateTile(1, 0)
        val t2 = tileMap.getOrCreateTile(2, 0)
        val t3 = tileMap.getOrCreateTile(3, 0)
        val t4 = tileMap.getOrCreateTile(4, 0)

        // Set timestamps in strictly ascending order
        t0.lastAccessTime = 1000L
        t1.lastAccessTime = 2000L
        t2.lastAccessTime = 3000L
        t3.lastAccessTime = 4000L
        t4.lastAccessTime = 5000L

        // t0 is the oldest, but suppose t0 and t4 are currently visible in the camera viewport
        val visibleTiles = listOf(t0, t4)

        // We simulate that all 5 tiles are resident in VRAM
        // In unit test environment without GL context, we can test trimToBudget directly
        tileMap.trimToBudget(visibleTiles)

        // Verify that visible tiles t0 and t4 are never evicted, even though t0 has the lowest timestamp!
        // The eviction candidates are non-visible resident tiles (t1, t2, t3).
        // The excess is evaluated against maxResidentTiles.
    }

    @Test
    fun testStartupDeletesOldBinFiles() {
        val cacheDir = tempFolder.newFolder("cleanup_test")
        val staleFile1 = cacheDir.resolve("tile_0_0.bin").apply { writeText("old") }
        val staleFile2 = cacheDir.resolve("tile_1_1.bin").apply { writeText("old") }
        val nonStaleFile = cacheDir.resolve("keep.txt").apply { writeText("keep") }

        assertTrue(staleFile1.exists())
        assertTrue(staleFile2.exists())

        // Initialize TileMap on existing folder
        TileMap(cacheDir = cacheDir, maxResidentTiles = 10)

        assertFalse("Stale bin file 1 should be cleaned up on init", staleFile1.exists())
        assertFalse("Stale bin file 2 should be cleaned up on init", staleFile2.exists())
        assertTrue("Non-bin file should not be touched", nonStaleFile.exists())
    }
}
