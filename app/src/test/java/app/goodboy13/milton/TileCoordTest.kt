package app.goodboy13.milton

import app.goodboy13.milton.core.model.WorldRect
import app.goodboy13.milton.core.tile.TileCoord
import org.junit.Assert.assertEquals
import org.junit.Test

class TileCoordTest {

    @Test
    fun testWorldToTileCoordPositive() {
        val c1 = TileCoord.fromWorld(0f, 0f)
        assertEquals(0, c1.tx)
        assertEquals(0, c1.ty)

        val c2 = TileCoord.fromWorld(511f, 511f)
        assertEquals(0, c2.tx)
        assertEquals(0, c2.ty)

        val c3 = TileCoord.fromWorld(512f, 1024f)
        assertEquals(1, c3.tx)
        assertEquals(2, c3.ty)
    }

    @Test
    fun testWorldToTileCoordNegative() {
        val c1 = TileCoord.fromWorld(-1f, -1f)
        assertEquals(-1, c1.tx)
        assertEquals(-1, c1.ty)

        val c2 = TileCoord.fromWorld(-512f, -512f)
        assertEquals(-1, c2.tx)
        assertEquals(-1, c2.ty)

        val c3 = TileCoord.fromWorld(-513f, -513f)
        assertEquals(-2, c3.tx)
        assertEquals(-2, c3.ty)
    }

    @Test
    fun testTileRangeBounds() {
        val bounds = WorldRect(-100f, -100f, 600f, 600f)
        val range = TileCoord.getTileRangeForBounds(bounds)
        assertEquals(-1, range.minTx)
        assertEquals(1, range.maxTx)
        assertEquals(-1, range.minTy)
        assertEquals(1, range.maxTy)
    }
}
