package app.goodboy13.milton.core.preferences

import android.content.Context
import android.content.SharedPreferences
import app.goodboy13.milton.core.brush.BrushType
import app.goodboy13.milton.core.constants.BrushDefaults
import app.goodboy13.milton.core.constants.PaletteDefaults
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

    // Default values from single source of truth
    val DEFAULT_RECENT_COLORS = PaletteDefaults.DEFAULT_RECENT_COLORS

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

    fun loadToolSize(context: Context, type: BrushType, defaultSize: Float = BrushDefaults.getDefaultSize(type)): Float {
        return getPrefs(context).getFloat("size_${type.name}", defaultSize)
    }

    // --- Per-Tool Opacity ---
    fun saveToolOpacity(context: Context, type: BrushType, opacity: Float) {
        getPrefs(context).edit().putFloat("opacity_${type.name}", opacity).apply()
    }

    fun loadToolOpacity(context: Context, type: BrushType, defaultOpacity: Float = BrushDefaults.getDefaultOpacity(type)): Float {
        return getPrefs(context).getFloat("opacity_${type.name}", defaultOpacity)
    }

    // --- Per-Tool Stabilizer ---
    fun saveToolStabilizer(context: Context, type: BrushType, stabilizer: Float) {
        getPrefs(context).edit().putFloat("stabilizer_${type.name}", stabilizer).apply()
    }

    fun loadToolStabilizer(context: Context, type: BrushType, defaultStabilizer: Float = BrushDefaults.getDefaultStabilizer(type)): Float {
        return getPrefs(context).getFloat("stabilizer_${type.name}", defaultStabilizer)
    }

    // --- Per-Tool Hardness (Graphite Grade) ---
    fun saveToolHardness(context: Context, type: BrushType, hardness: Float) {
        getPrefs(context).edit().putFloat("hardness_${type.name}", hardness).apply()
    }

    fun loadToolHardness(context: Context, type: BrushType, defaultHardness: Float = BrushDefaults.getDefaultHardness(type)): Float {
        return getPrefs(context).getFloat("hardness_${type.name}", defaultHardness)
    }

    // --- Per-Tool Tilt Sensitivity / Effect ---
    fun saveToolTilt(context: Context, type: BrushType, tilt: Float) {
        getPrefs(context).edit().putFloat("tilt_${type.name}", tilt).apply()
    }

    fun loadToolTilt(context: Context, type: BrushType, defaultTilt: Float = BrushDefaults.getDefaultTilt(type)): Float {
        return getPrefs(context).getFloat("tilt_${type.name}", defaultTilt)
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

    fun loadToolSizeBezier(context: Context, type: BrushType, defaultCurve: BezierControlPoints = BrushDefaults.getDefaultSizeBezier(type)): BezierControlPoints {
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

    fun loadToolOpacityBezier(context: Context, type: BrushType, defaultCurve: BezierControlPoints = BrushDefaults.getDefaultOpacityBezier(type)): BezierControlPoints {
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

    // --- Per-Tool Tilt Bezier Curve ---
    fun saveToolTiltBezier(context: Context, type: BrushType, curve: BezierControlPoints) {
        val p = type.name
        getPrefs(context).edit()
            .putFloat("tilt_bezier_cp1x_$p", curve.cp1x)
            .putFloat("tilt_bezier_cp1y_$p", curve.cp1y)
            .putFloat("tilt_bezier_cp2x_$p", curve.cp2x)
            .putFloat("tilt_bezier_cp2y_$p", curve.cp2y)
            .putFloat("tilt_bezier_min_$p", curve.minPercent)
            .putFloat("tilt_bezier_max_$p", curve.maxPercent)
            .apply()
    }

    fun loadToolTiltBezier(context: Context, type: BrushType, defaultCurve: BezierControlPoints = BrushDefaults.getDefaultTiltBezier(type)): BezierControlPoints {
        val p = type.name
        val prefs = getPrefs(context)
        if (!prefs.contains("tilt_bezier_cp1x_$p")) return defaultCurve
        return BezierControlPoints(
            cp1x = prefs.getFloat("tilt_bezier_cp1x_$p", defaultCurve.cp1x),
            cp1y = prefs.getFloat("tilt_bezier_cp1y_$p", defaultCurve.cp1y),
            cp2x = prefs.getFloat("tilt_bezier_cp2x_$p", defaultCurve.cp2x),
            cp2y = prefs.getFloat("tilt_bezier_cp2y_$p", defaultCurve.cp2y),
            minPercent = prefs.getFloat("tilt_bezier_min_$p", defaultCurve.minPercent),
            maxPercent = prefs.getFloat("tilt_bezier_max_$p", defaultCurve.maxPercent)
        )
    }

    // --- Color ---
    fun saveBrushColor(context: Context, colorRgb: Int) {
        getPrefs(context).edit().putInt(KEY_BRUSH_COLOR, colorRgb).apply()
    }

    fun loadBrushColor(context: Context, defaultColor: Int = PaletteDefaults.DEFAULT_BRUSH_COLOR): Int {
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
