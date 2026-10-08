package app.goodboy13.milton

import android.view.KeyEvent
import app.goodboy13.milton.input.KeyboardShortcutActions
import app.goodboy13.milton.input.KeyboardShortcutHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardShortcutHandlerTest {

    private class TestActions : KeyboardShortcutActions {
        var spaceHeld = false
        var ctrlHeld = false
        var altHeld = false
        var rHeld = false
        var shiftHeld = false

        var canvasFlippedCount = 0
        var resetViewCount = 0
        var toggleZoomCount = 0
        var resetRotationCount = 0

        var paintbrushSelectedCount = 0
        var cyclePenPencilCount = 0
        var cycleEraserCount = 0
        var toggleEyedropperCount = 0
        var selectLassoCount = 0

        var brushSizeStepUpCount = 0
        var brushSizeStepDownCount = 0
        var lastOpacity = -1f
        var swapRecentColorCount = 0
        var resetDefaultColorCount = 0

        var undoCount = 0
        var redoCount = 0
        var saveProjectCount = 0

        var addNewLayerCount = 0
        var clearActiveLayerCount = 0
        var selectLayerAboveCount = 0
        var selectLayerBelowCount = 0

        var toggleZenModeCount = 0

        override fun onModifierChanged(
            isSpaceHeld: Boolean,
            isCtrlHeld: Boolean,
            isAltHeld: Boolean,
            isRHeld: Boolean,
            isShiftHeld: Boolean
        ) {
            spaceHeld = isSpaceHeld
            ctrlHeld = isCtrlHeld
            altHeld = isAltHeld
            rHeld = isRHeld
            shiftHeld = isShiftHeld
        }

        override fun onToggleCanvasFlip() { canvasFlippedCount++ }
        override fun onResetView() { resetViewCount++ }
        override fun onToggleZoom50or100() { toggleZoomCount++ }
        override fun onResetRotation() { resetRotationCount++ }

        override fun onSelectPaintbrush() { paintbrushSelectedCount++ }
        override fun onCyclePenPencil() { cyclePenPencilCount++ }
        override fun onCycleEraser() { cycleEraserCount++ }
        override fun onToggleEyedropper() { toggleEyedropperCount++ }
        override fun onSelectLasso() { selectLassoCount++ }

        override fun onStepBrushSize(increase: Boolean) {
            if (increase) brushSizeStepUpCount++ else brushSizeStepDownCount++
        }
        override fun onSetBrushOpacity(opacity: Float) { lastOpacity = opacity }
        override fun onSwapRecentColor() { swapRecentColorCount++ }
        override fun onResetDefaultColor() { resetDefaultColorCount++ }

        override fun onUndo() { undoCount++ }
        override fun onRedo() { redoCount++ }
        override fun onSaveProject() { saveProjectCount++ }

        override fun onAddNewLayer() { addNewLayerCount++ }
        override fun onClearActiveLayer() { clearActiveLayerCount++ }
        override fun onSelectLayerAbove() { selectLayerAboveCount++ }
        override fun onSelectLayerBelow() { selectLayerBelowCount++ }

        override fun onToggleZenMode() { toggleZenModeCount++ }
    }

    private fun createKeyEvent(action: Int, keyCode: Int, metaState: Int = 0, repeat: Int = 0): KeyEvent {
        return KeyEvent(0L, 0L, action, keyCode, repeat, metaState)
    }

    @Test
    fun testSpringLoadedModifiersState() {
        val actions = TestActions()
        val handler = KeyboardShortcutHandler(actions)

        // Space down & up
        handler.handleKeyDown(KeyEvent.KEYCODE_SPACE, createKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE))
        assertTrue(handler.isSpaceHeld)
        assertTrue(actions.spaceHeld)

        handler.handleKeyUp(KeyEvent.KEYCODE_SPACE, createKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE))
        assertFalse(handler.isSpaceHeld)
        assertFalse(actions.spaceHeld)

        // Ctrl & Alt down
        handler.handleKeyDown(KeyEvent.KEYCODE_CTRL_LEFT, createKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT))
        handler.handleKeyDown(KeyEvent.KEYCODE_ALT_LEFT, createKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT))
        assertTrue(handler.isCtrlHeld)
        assertTrue(handler.isAltHeld)

        handler.resetModifiers()
        assertFalse(handler.isCtrlHeld)
        assertFalse(handler.isAltHeld)
        assertFalse(actions.ctrlHeld)
        assertFalse(actions.altHeld)
    }

    @Test
    fun testToolSwitchingAndCycling() {
        val actions = TestActions()
        val handler = KeyboardShortcutHandler(actions)

        handler.handleKeyDown(KeyEvent.KEYCODE_B, createKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_B))
        assertEquals(1, actions.paintbrushSelectedCount)

        handler.handleKeyDown(KeyEvent.KEYCODE_P, createKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_P))
        assertEquals(1, actions.cyclePenPencilCount)

        handler.handleKeyDown(KeyEvent.KEYCODE_E, createKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_E))
        assertEquals(1, actions.cycleEraserCount)

        handler.handleKeyDown(KeyEvent.KEYCODE_I, createKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_I))
        assertEquals(1, actions.toggleEyedropperCount)

        handler.handleKeyDown(KeyEvent.KEYCODE_L, createKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_L))
        assertEquals(1, actions.selectLassoCount)
    }

    @Test
    fun testBrushParametersAndColor() {
        val actions = TestActions()
        val handler = KeyboardShortcutHandler(actions)

        handler.handleKeyDown(KeyEvent.KEYCODE_LEFT_BRACKET, createKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_LEFT_BRACKET))
        assertEquals(1, actions.brushSizeStepDownCount)

        handler.handleKeyDown(KeyEvent.KEYCODE_RIGHT_BRACKET, createKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_RIGHT_BRACKET))
        assertEquals(1, actions.brushSizeStepUpCount)

        handler.handleKeyDown(KeyEvent.KEYCODE_5, createKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_5))
        assertEquals(0.50f, actions.lastOpacity, 0.001f)

        handler.handleKeyDown(KeyEvent.KEYCODE_0, createKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_0))
        assertEquals(1.00f, actions.lastOpacity, 0.001f)

        handler.handleKeyDown(KeyEvent.KEYCODE_X, createKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_X))
        assertEquals(1, actions.swapRecentColorCount)

        handler.handleKeyDown(KeyEvent.KEYCODE_D, createKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_D))
        assertEquals(1, actions.resetDefaultColorCount)
    }

    @Test
    fun testDocumentAndHistory() {
        val actions = TestActions()
        val handler = KeyboardShortcutHandler(actions)

        // Ctrl + Z
        handler.handleKeyDown(KeyEvent.KEYCODE_CTRL_LEFT, createKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT))
        handler.handleKeyDown(KeyEvent.KEYCODE_Z, createKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, metaState = KeyEvent.META_CTRL_ON))
        assertEquals(1, actions.undoCount)

        // Ctrl + Shift + Z
        handler.handleKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT, createKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT))
        handler.handleKeyDown(
            KeyEvent.KEYCODE_Z,
            createKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, metaState = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
        )
        assertEquals(1, actions.redoCount)

        // Ctrl + S
        handler.handleKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT, createKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT))
        handler.handleKeyDown(KeyEvent.KEYCODE_S, createKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_S, metaState = KeyEvent.META_CTRL_ON))
        assertEquals(1, actions.saveProjectCount)
    }

    @Test
    fun testViewportResetsAndToggles() {
        val actions = TestActions()
        val handler = KeyboardShortcutHandler(actions)

        // M
        handler.handleKeyDown(KeyEvent.KEYCODE_M, createKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_M))
        assertEquals(1, actions.canvasFlippedCount)

        // Ctrl + 0
        handler.handleKeyDown(KeyEvent.KEYCODE_CTRL_LEFT, createKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT))
        handler.handleKeyDown(KeyEvent.KEYCODE_0, createKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_0, metaState = KeyEvent.META_CTRL_ON))
        assertEquals(1, actions.resetViewCount)

        // Ctrl + 1
        handler.handleKeyDown(KeyEvent.KEYCODE_1, createKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1, metaState = KeyEvent.META_CTRL_ON))
        assertEquals(1, actions.toggleZoomCount)

        // Shift + R
        handler.handleKeyUp(KeyEvent.KEYCODE_CTRL_LEFT, createKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT))
        handler.handleKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT, createKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT))
        handler.handleKeyDown(KeyEvent.KEYCODE_R, createKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_R, metaState = KeyEvent.META_SHIFT_ON))
        assertEquals(1, actions.resetRotationCount)
    }

    @Test
    fun testLayerControlsAndZenMode() {
        val actions = TestActions()
        val handler = KeyboardShortcutHandler(actions)

        // Delete
        handler.handleKeyDown(KeyEvent.KEYCODE_FORWARD_DEL, createKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_FORWARD_DEL))
        assertEquals(1, actions.clearActiveLayerCount)

        // Page Up / Page Down
        handler.handleKeyDown(KeyEvent.KEYCODE_PAGE_UP, createKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PAGE_UP))
        assertEquals(1, actions.selectLayerAboveCount)

        handler.handleKeyDown(KeyEvent.KEYCODE_PAGE_DOWN, createKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PAGE_DOWN))
        assertEquals(1, actions.selectLayerBelowCount)

        // Tab
        handler.handleKeyDown(KeyEvent.KEYCODE_TAB, createKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB))
        assertEquals(1, actions.toggleZenModeCount)
    }
}
