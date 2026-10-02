package com.antigrav.milton

import com.antigrav.milton.core.layer.LayerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerManagerTest {

    @Test
    fun testInitialLayer() {
        val manager = LayerManager()
        assertEquals(1, manager.layers.size)
        assertEquals("Layer 1", manager.layers[0].name)
        assertEquals(manager.layers[0].id, manager.activeLayerId)
        assertEquals(1.0f, manager.layers[0].opacity, 0.001f)
        assertTrue(manager.layers[0].isVisible)
    }

    @Test
    fun testAddLayerUpToMax16() {
        val manager = LayerManager()
        for (i in 2..16) {
            val layer = manager.addLayer()
            assertNotNull("Should be able to add layer $i", layer)
            assertEquals(i, manager.layers.size)
            assertEquals(layer!!.id, manager.activeLayerId)
        }
        assertEquals(16, manager.layers.size)
        assertFalse(manager.canAddLayer())

        // 17th layer must be rejected
        val overflow = manager.addLayer()
        assertNull("17th layer must be null", overflow)
        assertEquals(16, manager.layers.size)
    }

    @Test
    fun testDeleteLayerDownToMin1() {
        val manager = LayerManager()
        val l2 = manager.addLayer("Layer 2")
        val l3 = manager.addLayer("Layer 3")
        assertEquals(3, manager.layers.size)

        // Delete l2
        assertTrue(manager.deleteLayer(l2!!.id))
        assertEquals(2, manager.layers.size)

        // Delete l3
        assertTrue(manager.deleteLayer(l3!!.id))
        assertEquals(1, manager.layers.size)

        // Cannot delete only remaining layer
        assertFalse(manager.canDeleteLayer())
        val deletedOnly = manager.deleteLayer(manager.layers[0].id)
        assertFalse(deletedOnly)
        assertEquals(1, manager.layers.size)
    }

    @Test
    fun testMoveLayersUpAndDown() {
        val manager = LayerManager()
        val l1 = manager.layers[0]
        val l2 = manager.addLayer("Layer 2")!!
        val l3 = manager.addLayer("Layer 3")!!

        // In bottom-to-top order: [l1, l2, l3]
        assertEquals(listOf(l1.id, l2.id, l3.id), manager.layers.map { it.id })

        // Move l2 down: [l2, l1, l3]
        assertTrue(manager.moveLayerDown(l2.id))
        assertEquals(listOf(l2.id, l1.id, l3.id), manager.layers.map { it.id })

        // Move l2 up: [l1, l2, l3]
        assertTrue(manager.moveLayerUp(l2.id))
        assertEquals(listOf(l1.id, l2.id, l3.id), manager.layers.map { it.id })

        // Move top layer up should fail
        assertFalse(manager.moveLayerUp(l3.id))
        // Move bottom layer down should fail
        assertFalse(manager.moveLayerDown(l1.id))
    }

    @Test
    fun testLayerOpacityAndVisibility() {
        val manager = LayerManager()
        val layer = manager.layers[0]

        manager.setLayerOpacity(layer.id, 0.45f)
        assertEquals(0.45f, manager.layers[0].opacity, 0.001f)

        // Clamp opacity [0..1]
        manager.setLayerOpacity(layer.id, 1.5f)
        assertEquals(1.0f, manager.layers[0].opacity, 0.001f)
        manager.setLayerOpacity(layer.id, -0.2f)
        assertEquals(0.0f, manager.layers[0].opacity, 0.001f)

        manager.setLayerVisibility(layer.id, false)
        assertFalse(manager.layers[0].isVisible)
        manager.setLayerVisibility(layer.id, true)
        assertTrue(manager.layers[0].isVisible)
    }

    @Test
    fun testSelectLayer() {
        val manager = LayerManager()
        val l2 = manager.addLayer("Layer 2")!!
        val l1 = manager.layers[0]

        assertEquals(l2.id, manager.activeLayerId)
        manager.selectLayer(l1.id)
        assertEquals(l1.id, manager.activeLayerId)
    }
}
