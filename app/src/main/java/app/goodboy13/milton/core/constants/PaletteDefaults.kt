package app.goodboy13.milton.core.constants

/**
 * Single Source of Truth for Milton color palettes and defaults.
 */
object PaletteDefaults {
    val INK_BLACK: Int = 0xFF111111.toInt()
    val CARBON_GRAPHITE: Int = 0xFF141518.toInt()
    val GRAPHITE_GREY: Int = 0xFF4A4A4A.toInt()
    val SLATE_GREY: Int = 0xFF78909C.toInt()
    val CRIMSON_RED: Int = 0xFFD32F2F.toInt()
    val COBALT_BLUE: Int = 0xFF1976D2.toInt()
    val FOREST_GREEN: Int = 0xFF388E3C.toInt()
    val AMBER_ORANGE: Int = 0xFFF57C00.toInt()
    val PAPER_WHITE: Int = 0xFFFFFFFF.toInt()
    val CANVAS_BACKGROUND_DEFAULT: Int = PAPER_WHITE

    /** Canonical default drawing color (Deep rich carbon graphite). */
    val DEFAULT_BRUSH_COLOR: Int = CARBON_GRAPHITE

    /**
     * Canonical 8-color quick palette shown on tablet quick bar.
     */
    val QUICK_PALETTE_COLORS: List<Int> = listOf(
        INK_BLACK,
        GRAPHITE_GREY,
        SLATE_GREY,
        CRIMSON_RED,
        COBALT_BLUE,
        FOREST_GREEN,
        AMBER_ORANGE,
        PAPER_WHITE
    )

    /**
     * Initial recent colors list displayed on first run.
     * Mirrors the quick palette with Carbon Graphite as active first selection.
     */
    val DEFAULT_RECENT_COLORS: List<Int> = listOf(
        CARBON_GRAPHITE,
        INK_BLACK,
        PAPER_WHITE,
        CRIMSON_RED,
        COBALT_BLUE,
        FOREST_GREEN,
        AMBER_ORANGE,
        SLATE_GREY
    )
}
