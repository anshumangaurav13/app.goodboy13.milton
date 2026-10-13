package app.goodboy13.milton.core.liquify

/**
 * Supported deformation tools in the Liquify engine.
 * Restore is intentionally omitted as Milton provides full stack Undo/Redo.
 */
enum class LiquifyMode(val displayName: String) {
    PUSH("Push"),
    BLOAT("Bloat"),
    PINCH("Pinch"),
    TWIRL_CW("Twirl CW"),
    TWIRL_CCW("Twirl CCW"),
    SMOOTH("Smooth")
}
