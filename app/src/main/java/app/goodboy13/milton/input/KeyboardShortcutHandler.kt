package app.goodboy13.milton.input

import android.view.KeyEvent

/**
 * Interface defining callbacks for all recognized canvas keyboard shortcuts.
 */
interface KeyboardShortcutActions {
    // Spring-loaded modifiers
    fun onModifierChanged(isSpaceHeld: Boolean, isCtrlHeld: Boolean, isAltHeld: Boolean, isRHeld: Boolean, isShiftHeld: Boolean)

    // Viewport Resets & Quick Toggles
    fun onToggleCanvasFlip()
    fun onResetView()
    fun onToggleZoom50or100()
    fun onResetRotation()

    // Tool Switching & Cycling
    fun onSelectPaintbrush()
    fun onCyclePenPencil()
    fun onCycleEraser()
    fun onToggleEyedropper()
    fun onSelectLasso()

    // Brush Parameters & Color
    fun onStepBrushSize(increase: Boolean)
    fun onSetBrushOpacity(opacity: Float)
    fun onSwapRecentColor()
    fun onResetDefaultColor()

    // Document & History
    fun onUndo()
    fun onRedo()
    fun onSaveProject()

    // Layer Controls
    fun onAddNewLayer()
    fun onClearActiveLayer()
    fun onSelectLayerAbove()
    fun onSelectLayerBelow()

    // Interface
    fun onToggleZenMode()
}

/**
 * Dispatches physical keyboard events into Milton actions and tracks spring-loaded modifier states.
 */
class KeyboardShortcutHandler(
    private val actions: KeyboardShortcutActions
) {
    var isSpaceHeld: Boolean = false
        private set
    var isCtrlHeld: Boolean = false
        private set
    var isAltHeld: Boolean = false
        private set
    var isRHeld: Boolean = false
        private set
    var isShiftHeld: Boolean = false
        private set

    fun resetModifiers() {
        if (isSpaceHeld || isCtrlHeld || isAltHeld || isRHeld || isShiftHeld) {
            isSpaceHeld = false
            isCtrlHeld = false
            isAltHeld = false
            isRHeld = false
            isShiftHeld = false
            actions.onModifierChanged(false, false, false, false, false)
        }
    }

    private fun updateModifiers() {
        actions.onModifierChanged(isSpaceHeld, isCtrlHeld, isAltHeld, isRHeld, isShiftHeld)
    }

    fun handleKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // 1. Modifiers state tracking
        when (keyCode) {
            KeyEvent.KEYCODE_SPACE -> {
                if (!isSpaceHeld) {
                    isSpaceHeld = true
                    updateModifiers()
                }
                return true
            }
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> {
                if (!isCtrlHeld) {
                    isCtrlHeld = true
                    updateModifiers()
                }
                return true
            }
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> {
                if (!isAltHeld) {
                    isAltHeld = true
                    updateModifiers()
                }
                return true
            }
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> {
                if (!isShiftHeld) {
                    isShiftHeld = true
                    updateModifiers()
                }
                return true
            }
            KeyEvent.KEYCODE_R -> {
                // Shift + R is Reset Rotation
                if (event.isShiftPressed || isShiftHeld) {
                    if (event.repeatCount == 0) {
                        actions.onResetRotation()
                    }
                    return true
                }
                // R alone is spring-loaded rotation modifier
                if (!isRHeld) {
                    isRHeld = true
                    updateModifiers()
                }
                return true
            }
        }

        val ctrl = event.isCtrlPressed || isCtrlHeld
        val shift = event.isShiftPressed || isShiftHeld
        val alt = event.isAltPressed || isAltHeld

        // Allow auto-repeat for continuous actions: brush size stepping and undo/redo
        val isRepeatable = (keyCode == KeyEvent.KEYCODE_LEFT_BRACKET && !ctrl) ||
                (keyCode == KeyEvent.KEYCODE_RIGHT_BRACKET && !ctrl) ||
                (ctrl && keyCode == KeyEvent.KEYCODE_Z) ||
                (ctrl && keyCode == KeyEvent.KEYCODE_Y)

        if (event.repeatCount > 0 && !isRepeatable) {
            return false
        }

        // 2. Document & History
        if (ctrl && !shift && !alt && keyCode == KeyEvent.KEYCODE_Z) {
            actions.onUndo()
            return true
        }
        if ((ctrl && shift && keyCode == KeyEvent.KEYCODE_Z) || (ctrl && !shift && !alt && keyCode == KeyEvent.KEYCODE_Y)) {
            actions.onRedo()
            return true
        }
        if (ctrl && !shift && !alt && keyCode == KeyEvent.KEYCODE_S) {
            actions.onSaveProject()
            return true
        }

        // 3. Layer Controls
        if (ctrl && shift && !alt && keyCode == KeyEvent.KEYCODE_N) {
            actions.onAddNewLayer()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_FORWARD_DEL || (ctrl && keyCode == KeyEvent.KEYCODE_DEL)) {
            actions.onClearActiveLayer()
            return true
        }
        if ((ctrl && keyCode == KeyEvent.KEYCODE_RIGHT_BRACKET) || keyCode == KeyEvent.KEYCODE_PAGE_UP) {
            actions.onSelectLayerAbove()
            return true
        }
        if ((ctrl && keyCode == KeyEvent.KEYCODE_LEFT_BRACKET) || keyCode == KeyEvent.KEYCODE_PAGE_DOWN) {
            actions.onSelectLayerBelow()
            return true
        }

        // 4. Viewport Resets & Quick Toggles
        if (ctrl && !shift && !alt && keyCode == KeyEvent.KEYCODE_0) {
            actions.onResetView()
            return true
        }
        if (ctrl && !shift && !alt && keyCode == KeyEvent.KEYCODE_1) {
            actions.onToggleZoom50or100()
            return true
        }
        if (!ctrl && !shift && !alt && keyCode == KeyEvent.KEYCODE_M) {
            actions.onToggleCanvasFlip()
            return true
        }

        // 5. Tool Switching & Cycling
        if (!ctrl && !shift && !alt && keyCode == KeyEvent.KEYCODE_B) {
            actions.onSelectPaintbrush()
            return true
        }
        if (!ctrl && !shift && !alt && keyCode == KeyEvent.KEYCODE_P) {
            actions.onCyclePenPencil()
            return true
        }
        if (!ctrl && !shift && !alt && keyCode == KeyEvent.KEYCODE_E) {
            actions.onCycleEraser()
            return true
        }
        if (!ctrl && !shift && !alt && keyCode == KeyEvent.KEYCODE_I) {
            actions.onToggleEyedropper()
            return true
        }
        if (!ctrl && !shift && !alt && keyCode == KeyEvent.KEYCODE_L) {
            actions.onSelectLasso()
            return true
        }

        // 6. Brush Parameters & Color
        if (!ctrl && !shift && !alt && keyCode == KeyEvent.KEYCODE_LEFT_BRACKET) {
            actions.onStepBrushSize(false)
            return true
        }
        if (!ctrl && !shift && !alt && keyCode == KeyEvent.KEYCODE_RIGHT_BRACKET) {
            actions.onStepBrushSize(true)
            return true
        }
        if (!ctrl && !shift && !alt && keyCode == KeyEvent.KEYCODE_X) {
            actions.onSwapRecentColor()
            return true
        }
        if (!ctrl && !shift && !alt && keyCode == KeyEvent.KEYCODE_D) {
            actions.onResetDefaultColor()
            return true
        }

        // Number keys 1-0: Brush Opacity in 10% steps
        if (!ctrl && !shift && !alt) {
            when (keyCode) {
                KeyEvent.KEYCODE_1 -> { actions.onSetBrushOpacity(0.10f); return true }
                KeyEvent.KEYCODE_2 -> { actions.onSetBrushOpacity(0.20f); return true }
                KeyEvent.KEYCODE_3 -> { actions.onSetBrushOpacity(0.30f); return true }
                KeyEvent.KEYCODE_4 -> { actions.onSetBrushOpacity(0.40f); return true }
                KeyEvent.KEYCODE_5 -> { actions.onSetBrushOpacity(0.50f); return true }
                KeyEvent.KEYCODE_6 -> { actions.onSetBrushOpacity(0.60f); return true }
                KeyEvent.KEYCODE_7 -> { actions.onSetBrushOpacity(0.70f); return true }
                KeyEvent.KEYCODE_8 -> { actions.onSetBrushOpacity(0.80f); return true }
                KeyEvent.KEYCODE_9 -> { actions.onSetBrushOpacity(0.90f); return true }
                KeyEvent.KEYCODE_0 -> { actions.onSetBrushOpacity(1.00f); return true }
            }
        }

        // 7. Interface
        if (!ctrl && !shift && !alt && keyCode == KeyEvent.KEYCODE_TAB) {
            actions.onToggleZenMode()
            return true
        }

        return false
    }

    fun handleKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        var changed = false
        when (keyCode) {
            KeyEvent.KEYCODE_SPACE -> {
                if (isSpaceHeld) {
                    isSpaceHeld = false
                    changed = true
                }
            }
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> {
                if (isCtrlHeld) {
                    isCtrlHeld = false
                    changed = true
                }
            }
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> {
                if (isAltHeld) {
                    isAltHeld = false
                    changed = true
                }
            }
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> {
                if (isShiftHeld) {
                    isShiftHeld = false
                    changed = true
                }
            }
            KeyEvent.KEYCODE_R -> {
                if (isRHeld) {
                    isRHeld = false
                    changed = true
                }
            }
        }
        if (changed) {
            updateModifiers()
            return true
        }
        return false
    }
}
