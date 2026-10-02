package com.antigrav.milton

import com.antigrav.milton.core.brush.BrushType
import com.antigrav.milton.core.layer.Layer
import com.antigrav.milton.ui.CanvasViewModel
import com.antigrav.milton.ui.EyedropperReticleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasViewModelTest {

    @Test
    fun testInitialUiStateDefaults() {
        val vm = CanvasViewModel()
        val state = vm.uiState.value

        assertEquals(BrushType.PENCIL, state.tool.brushType)
        assertEquals(0.5f, state.viewport.zoomLevel, 0.001f)
        assertEquals(0f, state.viewport.rotationDegrees, 0.001f)
        assertFalse(state.viewport.isCanvasFlipped)
        assertFalse(state.viewport.canUndo)
        assertFalse(state.viewport.canRedo)
        assertFalse(state.viewport.isZenMode)
        assertEquals("Untitled Artwork", state.document.documentTitle)
        assertFalse(vm.showNewProjectDialog.value)
    }

    @Test
    fun testViewportUpdates() {
        val vm = CanvasViewModel()
        vm.onViewportChanged(1.25f, 45f)
        assertEquals(1.25f, vm.uiState.value.viewport.zoomLevel, 0.001f)
        assertEquals(45f, vm.uiState.value.viewport.rotationDegrees, 0.001f)

        vm.setZenMode(true)
        assertTrue(vm.uiState.value.viewport.isZenMode)

        vm.setUndoRedoState(canUndo = true, canRedo = true)
        assertTrue(vm.uiState.value.viewport.canUndo)
        assertTrue(vm.uiState.value.viewport.canRedo)
    }

    @Test
    fun testRecentColors() {
        val vm = CanvasViewModel()
        val newColor = 0xFF123456.toInt()
        vm.addRecentColor(newColor)

        assertEquals(newColor, vm.uiState.value.tool.recentColors.first())

        // Adding same color again should deduplicate it to top
        vm.addRecentColor(newColor)
        assertEquals(newColor, vm.uiState.value.tool.recentColors.first())
        assertEquals(1, vm.uiState.value.tool.recentColors.count { it == newColor })
    }

    @Test
    fun testEyedropperReticleUpdates() {
        val vm = CanvasViewModel()
        val reticle = EyedropperReticleState(isVisible = true, screenX = 150f, screenY = 250f, color = 0xFFAABBCC.toInt())
        vm.onEyedropperReticleChanged(reticle)

        assertTrue(vm.uiState.value.tool.eyedropperReticleState.isVisible)
        assertEquals(150f, vm.uiState.value.tool.eyedropperReticleState.screenX, 0.001f)
        assertEquals(250f, vm.uiState.value.tool.eyedropperReticleState.screenY, 0.001f)
        assertEquals(0xFFAABBCC.toInt(), vm.uiState.value.tool.eyedropperReticleState.color)
    }

    @Test
    fun testDialogState() {
        val vm = CanvasViewModel()
        assertFalse(vm.showNewProjectDialog.value)
        vm.setShowNewProjectDialog(true)
        assertTrue(vm.showNewProjectDialog.value)
        vm.setShowNewProjectDialog(false)
        assertFalse(vm.showNewProjectDialog.value)
    }

    @Test
    fun testLayerStateUpdates() {
        val vm = CanvasViewModel()
        val testLayers = listOf(
            Layer(id = 1L, initialName = "Background", tileMap = com.antigrav.milton.core.tile.TileMap()),
            Layer(id = 2L, initialName = "Ink", tileMap = com.antigrav.milton.core.tile.TileMap())
        )
        vm.updateLayers(testLayers, 2L)

        assertEquals(2, vm.uiState.value.layers.layers.size)
        assertEquals(2L, vm.uiState.value.layers.activeLayerId)
    }
}
