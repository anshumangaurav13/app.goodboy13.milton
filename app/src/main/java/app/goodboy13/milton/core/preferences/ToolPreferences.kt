package app.goodboy13.milton.core.preferences

import android.content.Context
import android.content.SharedPreferences
import app.goodboy13.milton.core.brush.BrushType
import app.goodboy13.milton.core.model.BezierControlPoints
import app.goodboy13.milton.core.model.PressureCurveDefaults

/**
 * Persists and restores brush tool parameters, pressure curves, and recent colors
 * across application restarts using Android SharedPreferences.
 */
object ToolPreferences {

    private const val PREFS_NAME = "milton_tool_preferences"

    private const val KEY_ACTIVE_BRUSH_TYPE = "active_brush_type"
    private const val KEY_BRUSH_COLOR = "brush_color"
    private const val KEY_RECENT_COLORS = "recent_colors"

    // Default values
    val DEFAULT_RECENT_COLORS = listOf(
        0xFF333333.toInt(), // Graphite Default
        0xFF111111.toInt(), // Ink Black
        0xFFFFFFFF.toInt(), // Paper White
        0xFFD32F2F.toInt(), // Crimson Red
        0xFF1976D2.toInt(), // Cobalt Blue
        0xFF388E3C.toInt(), // Forest Green
        0xFFF57C00.toInt(), // Amber Orange
        0xFF78909C.toInt()  // Slate Gray
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveActiveBrushType(context: Context, type: BrushType) {
        getPrefs(context).edit().putString(KEY_ACTIVE_BRUSH_TYPE, type.name).apply()
    }

    fun loadActiveBrushType(context: Context): BrushType {
        val name = getPrefs(context).getString(KEY_ACTIVE_BRUSH_TYPE, BrushType.PENCIL.name)
        return try {
            BrushType.valueOf(name ?: BrushType.PENCIL.name)
        } catch (_: Exception) {
            BrushType.PENCIL
        }
    }

    // --- Per-Tool Size ---
    fun saveToolSize(context: Context, type: BrushType, size: Float) {
        getPrefs(context).edit().putFloat("size_${type.name}", size).apply()
    }

    fun loadToolSize(context: Context, type: BrushType, defaultSize: Float = 35f): Float {
        return getPrefs(context).getFloat("size_${type.name}", defaultSize)
    }

    // --- Per-Tool Opacity ---
    fun saveToolOpacity(context: Context, type: BrushType, opacity: Float) {
        getPrefs(context).edit().putFloat("opacity_${type.name}", opacity).apply()
    }

    fun loadToolOpacity(context: Context, type: BrushType, defaultOpacity: Float = 0.75f): Float {
        return getPrefs(context).getFloat("opacity_${type.name}", defaultOpacity)
    }

    // --- Per-Tool Stabilizer ---
    fun saveToolStabilizer(context: Context, type: BrushType, stabilizer: Float) {
        getPrefs(context).edit().putFloat("stabilizer_${type.name}", stabilizer).apply()
    }

    fun loadToolStabilizer(context: Context, type: BrushType, defaultStabilizer: Float = 0.10f): Float {
        return getPrefs(context).getFloat("stabilizer_${type.name}", defaultStabilizer)
    }

    // --- Per-Tool Size Bezier Curve ---
    fun saveToolSizeBezier(context: Context, type: BrushType, curve: BezierControlPoints) {
        val p = type.name
        getPrefs(context).edit()
            .putFloat("size_bezier_cp1x_$p", curve.cp1x)
            .putFloat("size_bezier_cp1y_$p", curve.cp1y)
            .putFloat("size_bezier_cp2x_$p", curve.cp2x)
            .putFloat("size_bezier_cp2y_$p", curve.cp2y)
            .putFloat("size_bezier_min_$p", curve.minPercent)
            .putFloat("size_bezier_max_$p", curve.maxPercent)
            .apply()
    }

    fun loadToolSizeBezier(context: Context, type: BrushType, defaultCurve: BezierControlPoints): BezierControlPoints {
        val p = type.name
        val prefs = getPrefs(context)
        if (!prefs.contains("size_bezier_cp1x_$p")) return defaultCurve
        return BezierControlPoints(
            cp1x = prefs.getFloat("size_bezier_cp1x_$p", defaultCurve.cp1x),
            cp1y = prefs.getFloat("size_bezier_cp1y_$p", defaultCurve.cp1y),
            cp2x = prefs.getFloat("size_bezier_cp2x_$p", defaultCurve.cp2x),
            cp2y = prefs.getFloat("size_bezier_cp2y_$p", defaultCurve.cp2y),
            minPercent = prefs.getFloat("size_bezier_min_$p", defaultCurve.minPercent),
            maxPercent = prefs.getFloat("size_bezier_max_$p", defaultCurve.maxPercent)
        )
    }

    // --- Per-Tool Opacity Bezier Curve ---
    fun saveToolOpacityBezier(context: Context, type: BrushType, curve: BezierControlPoints) {
        val p = type.name
        getPrefs(context).edit()
            .putFloat("opacity_bezier_cp1x_$p", curve.cp1x)
            .putFloat("opacity_bezier_cp1y_$p", curve.cp1y)
            .putFloat("opacity_bezier_cp2x_$p", curve.cp2x)
            .putFloat("opacity_bezier_cp2y_$p", curve.cp2y)
            .putFloat("opacity_bezier_min_$p", curve.minPercent)
            .putFloat("opacity_bezier_max_$p", curve.maxPercent)
            .apply()
    }

    fun loadToolOpacityBezier(context: Context, type: BrushType, defaultCurve: BezierControlPoints): BezierControlPoints {
        val p = type.name
        val prefs = getPrefs(context)
        if (!prefs.contains("opacity_bezier_cp1x_$p")) return defaultCurve
        return BezierControlPoints(
            cp1x = prefs.getFloat("opacity_bezier_cp1x_$p", defaultCurve.cp1x),
            cp1y = prefs.getFloat("opacity_bezier_cp1y_$p", defaultCurve.cp1y),
            cp2x = prefs.getFloat("opacity_bezier_cp2x_$p", defaultCurve.cp2x),
            cp2y = prefs.getFloat("opacity_bezier_cp2y_$p", defaultCurve.cp2y),
            minPercent = prefs.getFloat("opacity_bezier_min_$p", defaultCurve.minPercent),
            maxPercent = prefs.getFloat("opacity_bezier_max_$p", defaultCurve.maxPercent)
        )
    }

    // --- Color ---
    fun saveBrushColor(context: Context, colorRgb: Int) {
        getPrefs(context).edit().putInt(KEY_BRUSH_COLOR, colorRgb).apply()
    }

    fun loadBrushColor(context: Context, defaultColor: Int = 0xFF333333.toInt()): Int {
        return getPrefs(context).getInt(KEY_BRUSH_COLOR, defaultColor)
    }

    // --- Recent Colors ---
    fun saveRecentColors(context: Context, colors: List<Int>) {
        val str = colors.joinToString(",") { it.toString() }
        getPrefs(context).edit().putString(KEY_RECENT_COLORS, str).apply()
    }

    fun loadRecentColors(context: Context): List<Int> {
        val raw = getPrefs(context).getString(KEY_RECENT_COLORS, null) ?: return DEFAULT_RECENT_COLORS
        return try {
            val list = raw.split(",").mapNotNull { it.trim().toIntOrNull() }
            if (list.isNotEmpty()) list else DEFAULT_RECENT_COLORS
        } catch (_: Exception) {
            DEFAULT_RECENT_COLORS
        }
    }
}
