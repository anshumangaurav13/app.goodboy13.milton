package app.goodboy13.milton.core.brush

import app.goodboy13.milton.core.constants.BrushDefaults
import app.goodboy13.milton.core.constants.PaletteDefaults
import app.goodboy13.milton.core.model.BezierControlPoints
import app.goodboy13.milton.core.model.PressureCurveDefaults

/**
 * A single stamped raster dab in world coordinates.
 */
data class BrushDab(
    val x: Float,
    val y: Float,
    val radius: Float,
    val alpha: Float,
    val colorRgb: Int,
    val isEraser: Boolean = false,
    val hardness: Float = 0.85f,
    val brushMode: Int = 0, // 0 = Pen/Eraser, 1 = Pencil, 2 = Paintbrush
    val pressure: Float = 0.5f,
    val tilt: Float = 0.0f,
    val strokeId: Long = 0L
)

enum class BrushType(val displayName: String) {
    PEN("Pen"),
    PENCIL("Pencil"),
    PAINTBRUSH("Paintbrush"),
    SMUDGE("Smudge"),
    ERASER("Eraser"),
    LASSO("Lasso"),
    LIQUIFY("Liquify")
}

data class BrushProperties(
    var brushType: BrushType = BrushType.PENCIL,
    var size: Float = BrushDefaults.getDefaultSize(BrushType.PENCIL),
    var colorRgb: Int = PaletteDefaults.DEFAULT_BRUSH_COLOR,
    var opacity: Float = BrushDefaults.getDefaultOpacity(BrushType.PENCIL),
    var spacing: Float = BrushDefaults.getDefaultSpacing(BrushType.PENCIL),
    var hardness: Float = BrushDefaults.getDefaultHardness(BrushType.PENCIL),
    var tilt: Float = BrushDefaults.getDefaultTilt(BrushType.PENCIL),
    var minRadius: Float = (BrushDefaults.getDefaultSize(BrushType.PENCIL) * 0.12f).coerceAtLeast(1.2f),
    var isEraser: Boolean = BrushDefaults.getDefaultIsEraser(BrushType.PENCIL),
    var brushMode: Int = BrushDefaults.getDefaultBrushMode(BrushType.PENCIL),
    var stabilizer: Float = BrushDefaults.getDefaultStabilizer(BrushType.PENCIL),
    var sizeBezierConfig: BezierControlPoints = BrushDefaults.getDefaultSizeBezier(BrushType.PENCIL),
    var opacityBezierConfig: BezierControlPoints = BrushDefaults.getDefaultOpacityBezier(BrushType.PENCIL),
    var tiltBezierConfig: BezierControlPoints = BrushDefaults.getDefaultTiltBezier(BrushType.PENCIL)
) {
    // Legacy alias
    var bezierConfig: BezierControlPoints
        get() = sizeBezierConfig
        set(value) { sizeBezierConfig = value }

    private val savedSizes = BrushType.values().associateWith { BrushDefaults.getDefaultSize(it) }.toMutableMap()
    private val savedOpacities = BrushType.values().associateWith { BrushDefaults.getDefaultOpacity(it) }.toMutableMap()
    private val savedStabilizers = BrushType.values().associateWith { BrushDefaults.getDefaultStabilizer(it) }.toMutableMap()
    private val savedTilts = BrushType.values().associateWith { BrushDefaults.getDefaultTilt(it) }.toMutableMap()
    private val savedHardnesses = BrushType.values().associateWith { BrushDefaults.getDefaultHardness(it) }.toMutableMap()
    private val savedSizeBeziers = BrushType.values().associateWith { BrushDefaults.getDefaultSizeBezier(it) }.toMutableMap()
    private val savedOpacityBeziers = BrushType.values().associateWith { BrushDefaults.getDefaultOpacityBezier(it) }.toMutableMap()
    private val savedTiltBeziers = BrushType.values().associateWith { BrushDefaults.getDefaultTiltBezier(it) }.toMutableMap()

    fun applyPreset(type: BrushType, saveCurrentFirst: Boolean = true) {
        if (saveCurrentFirst) {
            savedSizes[brushType] = size
            savedOpacities[brushType] = opacity
            savedStabilizers[brushType] = stabilizer
            savedTilts[brushType] = tilt
            savedHardnesses[brushType] = hardness
            savedSizeBeziers[brushType] = sizeBezierConfig
            savedOpacityBeziers[brushType] = opacityBezierConfig
            savedTiltBeziers[brushType] = tiltBezierConfig
        }

        brushType = type
        size = savedSizes[type] ?: BrushDefaults.getDefaultSize(type)
        opacity = savedOpacities[type] ?: BrushDefaults.getDefaultOpacity(type)
        stabilizer = savedStabilizers[type] ?: BrushDefaults.getDefaultStabilizer(type)
        tilt = savedTilts[type] ?: BrushDefaults.getDefaultTilt(type)
        hardness = savedHardnesses[type] ?: BrushDefaults.getDefaultHardness(type)
        sizeBezierConfig = savedSizeBeziers[type] ?: BrushDefaults.getDefaultSizeBezier(type)
        opacityBezierConfig = savedOpacityBeziers[type] ?: BrushDefaults.getDefaultOpacityBezier(type)
        tiltBezierConfig = savedTiltBeziers[type] ?: BrushDefaults.getDefaultTiltBezier(type)
        minRadius = (size * 0.12f).coerceAtLeast(1.2f)

        val preset = BrushDefaults.getPreset(type)
        spacing = preset.spacing
        brushMode = preset.brushMode
        isEraser = preset.isEraser
        if (type == BrushType.PEN) {
            hardness = preset.hardness
        }
    }

    fun loadPreferences(context: android.content.Context) {
        for (t in BrushType.values()) {
            savedSizes[t] = app.goodboy13.milton.core.preferences.ToolPreferences.loadToolSize(context, t, savedSizes[t] ?: BrushDefaults.getDefaultSize(t))
            savedOpacities[t] = app.goodboy13.milton.core.preferences.ToolPreferences.loadToolOpacity(context, t, savedOpacities[t] ?: BrushDefaults.getDefaultOpacity(t))
            savedStabilizers[t] = app.goodboy13.milton.core.preferences.ToolPreferences.loadToolStabilizer(context, t, savedStabilizers[t] ?: BrushDefaults.getDefaultStabilizer(t))
            savedTilts[t] = app.goodboy13.milton.core.preferences.ToolPreferences.loadToolTilt(context, t, savedTilts[t] ?: BrushDefaults.getDefaultTilt(t))
            savedHardnesses[t] = app.goodboy13.milton.core.preferences.ToolPreferences.loadToolHardness(context, t, savedHardnesses[t] ?: BrushDefaults.getDefaultHardness(t))
            savedSizeBeziers[t] = app.goodboy13.milton.core.preferences.ToolPreferences.loadToolSizeBezier(context, t, savedSizeBeziers[t] ?: BrushDefaults.getDefaultSizeBezier(t))
            savedOpacityBeziers[t] = app.goodboy13.milton.core.preferences.ToolPreferences.loadToolOpacityBezier(context, t, savedOpacityBeziers[t] ?: BrushDefaults.getDefaultOpacityBezier(t))
            savedTiltBeziers[t] = app.goodboy13.milton.core.preferences.ToolPreferences.loadToolTiltBezier(context, t, savedTiltBeziers[t] ?: BrushDefaults.getDefaultTiltBezier(t))
        }
        val active = app.goodboy13.milton.core.preferences.ToolPreferences.loadActiveBrushType(context)
        applyPreset(active, saveCurrentFirst = false)
    }

    fun savePreferences(context: android.content.Context) {
        savedSizes[brushType] = size
        savedOpacities[brushType] = opacity
        savedStabilizers[brushType] = stabilizer
        savedTilts[brushType] = tilt
        savedHardnesses[brushType] = hardness
        savedSizeBeziers[brushType] = sizeBezierConfig
        savedOpacityBeziers[brushType] = opacityBezierConfig
        savedTiltBeziers[brushType] = tiltBezierConfig
        app.goodboy13.milton.core.preferences.ToolPreferences.saveActiveBrushType(context, brushType)
        for (t in BrushType.values()) {
            savedSizes[t]?.let { app.goodboy13.milton.core.preferences.ToolPreferences.saveToolSize(context, t, it) }
            savedOpacities[t]?.let { app.goodboy13.milton.core.preferences.ToolPreferences.saveToolOpacity(context, t, it) }
            savedStabilizers[t]?.let { app.goodboy13.milton.core.preferences.ToolPreferences.saveToolStabilizer(context, t, it) }
            savedTilts[t]?.let { app.goodboy13.milton.core.preferences.ToolPreferences.saveToolTilt(context, t, it) }
            savedHardnesses[t]?.let { app.goodboy13.milton.core.preferences.ToolPreferences.saveToolHardness(context, t, it) }
            savedSizeBeziers[t]?.let { app.goodboy13.milton.core.preferences.ToolPreferences.saveToolSizeBezier(context, t, it) }
            savedOpacityBeziers[t]?.let { app.goodboy13.milton.core.preferences.ToolPreferences.saveToolOpacityBezier(context, t, it) }
            savedTiltBeziers[t]?.let { app.goodboy13.milton.core.preferences.ToolPreferences.saveToolTiltBezier(context, t, it) }
        }
    }
}
