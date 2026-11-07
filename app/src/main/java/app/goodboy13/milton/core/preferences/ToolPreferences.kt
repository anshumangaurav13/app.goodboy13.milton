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

    // --- Per-Tool Grain / Paper Texture ---
    fun saveToolGrainType(context: Context, type: BrushType, grainType: Int) {
        getPrefs(context).edit().putInt("grain_type_${type.name}", grainType).apply()
    }

    fun loadToolGrainType(context: Context, type: BrushType, defaultType: Int = BrushDefaults.getDefaultGrainType(type)): Int {
        return getPrefs(context).getInt("grain_type_${type.name}", defaultType)
    }

    fun saveToolGrainScale(context: Context, type: BrushType, grainScale: Float) {
        getPrefs(context).edit().putFloat("grain_scale_${type.name}", grainScale).apply()
    }

    fun loadToolGrainScale(context: Context, type: BrushType, defaultScale: Float = BrushDefaults.getDefaultGrainScale(type)): Float {
        return getPrefs(context).getFloat("grain_scale_${type.name}", defaultScale)
    }

    fun saveToolGrainDepth(context: Context, type: BrushType, grainDepth: Float) {
        getPrefs(context).edit().putFloat("grain_depth_${type.name}", grainDepth).apply()
    }

    fun loadToolGrainDepth(context: Context, type: BrushType, defaultDepth: Float = BrushDefaults.getDefaultGrainDepth(type)): Float {
        return getPrefs(context).getFloat("grain_depth_${type.name}", defaultDepth)
    }

    // --- Per-Tool Physical / Graphite Parameters ---
    fun saveToolParticleNoise(context: Context, type: BrushType, noise: Float) {
        getPrefs(context).edit().putFloat("particle_noise_${type.name}", noise).apply()
    }

    fun loadToolParticleNoise(context: Context, type: BrushType, defaultNoise: Float = BrushDefaults.DEFAULT_PARTICLE_NOISE): Float {
        return getPrefs(context).getFloat("particle_noise_${type.name}", defaultNoise)
    }

    fun saveToolMinDensity(context: Context, type: BrushType, density: Float) {
        getPrefs(context).edit().putFloat("min_density_${type.name}", density).apply()
    }

    fun loadToolMinDensity(context: Context, type: BrushType, defaultDensity: Float = BrushDefaults.DEFAULT_MIN_DENSITY): Float {
        return getPrefs(context).getFloat("min_density_${type.name}", defaultDensity)
    }

    fun saveToolMaxDensity(context: Context, type: BrushType, density: Float) {
        getPrefs(context).edit().putFloat("max_density_${type.name}", density).apply()
    }

    fun loadToolMaxDensity(context: Context, type: BrushType, defaultDensity: Float = BrushDefaults.DEFAULT_MAX_DENSITY): Float {
        return getPrefs(context).getFloat("max_density_${type.name}", defaultDensity)
    }

    fun saveToolActivePreset(context: Context, preset: String) {
        getPrefs(context).edit().putString("active_graphite_preset", preset).apply()
    }

    fun loadToolActivePreset(context: Context, defaultPreset: String = BrushDefaults.DEFAULT_GRAPHITE_PRESET): String {
        return getPrefs(context).getString("active_graphite_preset", defaultPreset) ?: defaultPreset
    }

    // --- Custom Presets Serialization ---
    private const val KEY_CUSTOM_PRESETS = "custom_graphite_presets"

    fun serializePresets(presets: List<app.goodboy13.milton.core.constants.GraphitePreset>): String {
        val arr = org.json.JSONArray()
        for (p in presets) {
            val obj = org.json.JSONObject().apply {
                put("name", p.name)
                put("size", p.size.toDouble())
                put("opacity", p.opacity.toDouble())
                put("hardness", p.hardness.toDouble())
                put("particleNoise", p.particleNoise.toDouble())
                put("grainDepth", p.grainDepth.toDouble())
                put("grainType", p.grainType)
                put("grainScale", p.grainScale.toDouble())
                put("minDensity", p.minDensity.toDouble())
                put("maxDensity", p.maxDensity.toDouble())
                put("tilt", p.tilt.toDouble())
                put("stabilizer", p.stabilizer.toDouble())
                put("isCustom", true)

                val sb = org.json.JSONObject().apply {
                    put("cp1x", p.sizeBezier.cp1x.toDouble())
                    put("cp1y", p.sizeBezier.cp1y.toDouble())
                    put("cp2x", p.sizeBezier.cp2x.toDouble())
                    put("cp2y", p.sizeBezier.cp2y.toDouble())
                    put("min", p.sizeBezier.minPercent.toDouble())
                    put("max", p.sizeBezier.maxPercent.toDouble())
                }
                put("sizeBezier", sb)

                val ob = org.json.JSONObject().apply {
                    put("cp1x", p.opacityBezier.cp1x.toDouble())
                    put("cp1y", p.opacityBezier.cp1y.toDouble())
                    put("cp2x", p.opacityBezier.cp2x.toDouble())
                    put("cp2y", p.opacityBezier.cp2y.toDouble())
                    put("min", p.opacityBezier.minPercent.toDouble())
                    put("max", p.opacityBezier.maxPercent.toDouble())
                }
                put("opacityBezier", ob)

                val tb = org.json.JSONObject().apply {
                    put("cp1x", p.tiltBezier.cp1x.toDouble())
                    put("cp1y", p.tiltBezier.cp1y.toDouble())
                    put("cp2x", p.tiltBezier.cp2x.toDouble())
                    put("cp2y", p.tiltBezier.cp2y.toDouble())
                    put("min", p.tiltBezier.minPercent.toDouble())
                    put("max", p.tiltBezier.maxPercent.toDouble())
                }
                put("tiltBezier", tb)
            }
            arr.put(obj)
        }
        return arr.toString()
    }

    fun deserializePresets(jsonStr: String?): List<app.goodboy13.milton.core.constants.GraphitePreset> {
        if (jsonStr.isNullOrEmpty()) return emptyList()
        val list = mutableListOf<app.goodboy13.milton.core.constants.GraphitePreset>()
        try {
            val arr = org.json.JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val sbObj = obj.optJSONObject("sizeBezier")
                val sizeBezier = if (sbObj != null) {
                    app.goodboy13.milton.core.model.BezierControlPoints(
                        cp1x = sbObj.optDouble("cp1x", 0.85).toFloat(),
                        cp1y = sbObj.optDouble("cp1y", 0.52).toFloat(),
                        cp2x = sbObj.optDouble("cp2x", 0.58).toFloat(),
                        cp2y = sbObj.optDouble("cp2y", 0.77).toFloat(),
                        minPercent = sbObj.optDouble("min", 0.0).toFloat(),
                        maxPercent = sbObj.optDouble("max", 1.0).toFloat()
                    )
                } else app.goodboy13.milton.core.model.PressureCurveDefaults.STANDARD

                val obObj = obj.optJSONObject("opacityBezier")
                val opacityBezier = if (obObj != null) {
                    app.goodboy13.milton.core.model.BezierControlPoints(
                        cp1x = obObj.optDouble("cp1x", 0.85).toFloat(),
                        cp1y = obObj.optDouble("cp1y", 0.52).toFloat(),
                        cp2x = obObj.optDouble("cp2x", 0.58).toFloat(),
                        cp2y = obObj.optDouble("cp2y", 0.77).toFloat(),
                        minPercent = obObj.optDouble("min", 0.0).toFloat(),
                        maxPercent = obObj.optDouble("max", 1.0).toFloat()
                    )
                } else app.goodboy13.milton.core.model.PressureCurveDefaults.STANDARD

                val tbObj = obj.optJSONObject("tiltBezier")
                val tiltBezier = if (tbObj != null) {
                    app.goodboy13.milton.core.model.BezierControlPoints(
                        cp1x = tbObj.optDouble("cp1x", 0.85).toFloat(),
                        cp1y = tbObj.optDouble("cp1y", 0.52).toFloat(),
                        cp2x = tbObj.optDouble("cp2x", 0.58).toFloat(),
                        cp2y = tbObj.optDouble("cp2y", 0.77).toFloat(),
                        minPercent = tbObj.optDouble("min", 0.0).toFloat(),
                        maxPercent = tbObj.optDouble("max", 1.0).toFloat()
                    )
                } else app.goodboy13.milton.core.model.PressureCurveDefaults.STANDARD.copy(minPercent = 0.0f, maxPercent = 1.0f)

                list.add(
                    app.goodboy13.milton.core.constants.GraphitePreset(
                        name = obj.getString("name"),
                        size = obj.optDouble("size", 24.0).toFloat(),
                        opacity = obj.optDouble("opacity", 0.35).toFloat(),
                        hardness = obj.optDouble("hardness", 0.50).toFloat(),
                        particleNoise = obj.optDouble("particleNoise", 0.35).toFloat(),
                        grainDepth = obj.optDouble("grainDepth", 0.60).toFloat(),
                        grainType = obj.optInt("grainType", 1),
                        grainScale = obj.optDouble("grainScale", 1.0).toFloat(),
                        minDensity = obj.optDouble("minDensity", 0.24).toFloat(),
                        maxDensity = obj.optDouble("maxDensity", 0.60).toFloat(),
                        tilt = obj.optDouble("tilt", 0.50).toFloat(),
                        stabilizer = obj.optDouble("stabilizer", 0.05).toFloat(),
                        sizeBezier = sizeBezier,
                        opacityBezier = opacityBezier,
                        tiltBezier = tiltBezier,
                        isCustom = true
                    )
                )
            }
        } catch (e: Exception) {
            // Ignored in headless tests or logged
        }
        return list
    }

    fun saveCustomPresets(context: Context, presets: List<app.goodboy13.milton.core.constants.GraphitePreset>) {
        val serialized = serializePresets(presets)
        getPrefs(context).edit().putString(KEY_CUSTOM_PRESETS, serialized).apply()
    }

    fun loadCustomPresets(context: Context): List<app.goodboy13.milton.core.constants.GraphitePreset> {
        val str = getPrefs(context).getString(KEY_CUSTOM_PRESETS, null)
        return deserializePresets(str)
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
