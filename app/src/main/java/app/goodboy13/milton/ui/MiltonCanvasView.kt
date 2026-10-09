package app.goodboy13.milton.ui

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.graphics.lowlatency.GLFrontBufferedRenderer
import app.goodboy13.milton.core.brush.BrushDab
import app.goodboy13.milton.core.brush.BrushEngine
import app.goodboy13.milton.core.gl.DabPacket
import app.goodboy13.milton.core.gl.MiltonCanvasRenderer
import app.goodboy13.milton.core.history.AddLayerCommand
import app.goodboy13.milton.core.layer.Layer
import app.goodboy13.milton.core.selection.SelectionState
import app.goodboy13.milton.core.selection.SelectionTransformManager
import app.goodboy13.milton.input.CanvasGestureDetector

data class EyedropperReticleState(
    val screenX: Float = 0f,
    val screenY: Float = 0f,
    val color: Int = 0,
    val isVisible: Boolean = false
)

data class LiquifyReticleState(
    val screenX: Float = 0f,
    val screenY: Float = 0f,
    val radiusScreen: Float = 0f,
    val isVisible: Boolean = false
)

/**
 * Pure Infinite Canvas View:
 * - Stylus-only drawing (rejects fingers/palm for drawing).
 * - Multi-touch finger navigation (pan, zoom, rotate).
 * - Ultra-low latency front buffering.
 */
class MiltonCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "MiltonCanvasView"
    }

    val renderer = MiltonCanvasRenderer(
        layerManager = app.goodboy13.milton.core.layer.LayerManager(
            cacheBaseDir = context.cacheDir.resolve("milton_tile_cache"),
            maxResidentTilesPerLayer = 256
        )
    )
    val layerManager: app.goodboy13.milton.core.layer.LayerManager get() = renderer.layerManager
    val brushEngine = BrushEngine()
    val selectionManager = SelectionTransformManager()
    val liquifyManager = app.goodboy13.milton.core.liquify.LiquifyManager()
    var selectedTransformLayerIds: Set<Long> = emptySet()

    private var frontBufferedRenderer: GLFrontBufferedRenderer<DabPacket>? = null
    private var lastViewportPostTime: Long = 0L

    init {
        brushEngine.properties.applyPreset(app.goodboy13.milton.core.brush.BrushType.PENCIL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            isAutoHandwritingEnabled = false
        }
        renderer.onRequestRedraw = {
            post { requestRedraw() }
        }
    }

    private val gestureDetector = CanvasGestureDetector(object : CanvasGestureDetector.GestureListener {
        override fun onPanZoomRotate(
            prevFocalX: Float,
            prevFocalY: Float,
            curFocalX: Float,
            curFocalY: Float,
            zoomFactor: Float,
            angleDelta: Float
        ) {
            renderer.viewport.applyGesture(
                prevFocalX, prevFocalY,
                curFocalX, curFocalY,
                zoomFactor, angleDelta
            )
            frontBufferedRenderer?.renderMultiBufferedLayer(emptyList())
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastViewportPostTime > 64L) {
                lastViewportPostTime = now
                post { onViewportChanged?.invoke(renderer.viewport.zoom, renderer.viewport.rotationDegrees) }
            }
        }

        override fun onGestureStart() {
            // Optional: gesture start hook
        }

        override fun onGestureEnd() {
            lastViewportPostTime = android.os.SystemClock.uptimeMillis()
            post { onViewportChanged?.invoke(renderer.viewport.zoom, renderer.viewport.rotationDegrees) }
            renderer.requestTrimBudget()
            frontBufferedRenderer?.renderMultiBufferedLayer(emptyList())
        }

        override fun onFling(vx: Float, vy: Float) {
            // Fling updates handled via onPanZoomRotate callbacks
        }

        override fun onUndo() {
            undo()
        }

        override fun onRedo() {
            redo()
        }
    })

    var onViewportChanged: ((zoom: Float, rotationDegrees: Float) -> Unit)? = null

    var isZoomLocked: Boolean
        get() = gestureDetector.isZoomLocked
        set(value) { gestureDetector.isZoomLocked = value }

    var isRotationLocked: Boolean
        get() = gestureDetector.isRotationLocked
        set(value) { gestureDetector.isRotationLocked = value }

    var isCanvasFlipped: Boolean
        get() = renderer.viewport.isFlippedHorizontally
        set(value) {
            if (renderer.viewport.isFlippedHorizontally != value) {
                renderer.viewport.isFlippedHorizontally = value
                requestRedraw()
            }
        }

    fun toggleCanvasFlip(): Boolean {
        renderer.viewport.isFlippedHorizontally = !renderer.viewport.isFlippedHorizontally
        requestRedraw()
        return renderer.viewport.isFlippedHorizontally
    }

    var isThumbnailCaptureEnabled: Boolean
        get() = renderer.isThumbnailCaptureEnabled
        set(value) { renderer.isThumbnailCaptureEnabled = value }

    val currentZoom: Float
        get() = renderer.viewport.zoom

    val currentRotation: Float
        get() = renderer.viewport.rotationDegrees

    var brushType: app.goodboy13.milton.core.brush.BrushType
        get() = brushEngine.properties.brushType
        set(value) {
            brushEngine.properties.applyPreset(value)
            isEraserMode = (value == app.goodboy13.milton.core.brush.BrushType.ERASER)
        }

    var liquifyMode: app.goodboy13.milton.core.native.MiltonNative.LiquifyMode
        get() = liquifyManager.mode
        set(value) {
            liquifyManager.mode = value
            brushEngine.properties.liquifyMode = value
        }

    var isEraserMode: Boolean = false
        set(value) {
            field = value
            if (value) {
                if (brushEngine.properties.brushType != app.goodboy13.milton.core.brush.BrushType.ERASER) {
                    brushEngine.properties.applyPreset(app.goodboy13.milton.core.brush.BrushType.ERASER)
                }
            } else {
                if (brushEngine.properties.brushType == app.goodboy13.milton.core.brush.BrushType.ERASER) {
                    brushEngine.properties.applyPreset(app.goodboy13.milton.core.brush.BrushType.PEN)
                }
            }
        }

    var brushSize: Float
        get() = brushEngine.properties.size
        set(value) {
            brushEngine.properties.size = value
            brushEngine.properties.minRadius = (value * 0.10f).coerceIn(1.2f, 20f)
        }

    var brushOpacity: Float
        get() = brushEngine.properties.opacity
        set(value) {
            brushEngine.properties.opacity = value
        }

    var brushStabilizer: Float
        get() = brushEngine.properties.stabilizer
        set(value) {
            brushEngine.properties.stabilizer = value
        }

    var brushColorRgb: Int
        get() = brushEngine.properties.colorRgb
        set(value) {
            brushEngine.properties.colorRgb = value
        }

    var sizeBezierConfig: app.goodboy13.milton.core.model.BezierControlPoints
        get() = brushEngine.properties.sizeBezierConfig
        set(value) {
            brushEngine.properties.sizeBezierConfig = value
        }

    var opacityBezierConfig: app.goodboy13.milton.core.model.BezierControlPoints
        get() = brushEngine.properties.opacityBezierConfig
        set(value) {
            brushEngine.properties.opacityBezierConfig = value
        }

    var bezierConfig: app.goodboy13.milton.core.model.BezierControlPoints
        get() = brushEngine.properties.sizeBezierConfig
        set(value) {
            brushEngine.properties.sizeBezierConfig = value
        }

    var backgroundColorRgb: Int
        get() = renderer.backgroundColorRgb
        set(value) {
            renderer.backgroundColorRgb = value
            requestRedraw()
        }

    var isEyedropperMode: Boolean = false
        set(value) {
            field = value
            if (!value) {
                isEyedropperHovering = false
                isEyedropperTouching = false
                onEyedropperReticleChanged?.invoke(EyedropperReticleState(isVisible = false))
            }
        }

    var onColorPicked: ((Int) -> Unit)? = null
    var onEyedropperReticleChanged: ((EyedropperReticleState) -> Unit)? = null
    var onLiquifyReticleChanged: ((LiquifyReticleState) -> Unit)? = null
    var onStrokeCompleted: ((Int) -> Unit)? = null

    private var lastSampledColor: Int = 0xFF000000.toInt()
    private var lastEyedropperScreenX: Float = 0f
    private var lastEyedropperScreenY: Float = 0f
    private var isEyedropperTouching: Boolean = false
    private var isEyedropperHovering: Boolean = false
    private var isLiquifyHovering: Boolean = false

    fun undo() {
        renderer.requestUndo()
        frontBufferedRenderer?.renderMultiBufferedLayer(emptyList())
    }

    fun redo() {
        renderer.requestRedo()
        frontBufferedRenderer?.renderMultiBufferedLayer(emptyList())
    }

    fun addLayer(name: String? = null, insertAboveActive: Boolean = true): Layer? {
        val activeBefore = layerManager.activeLayerId
        val newLayer = layerManager.addLayer(name, insertAboveActive) ?: return null
        val storageIndex = layerManager.layers.indexOf(newLayer)
        renderer.undoManager.pushCustomCommand(
            AddLayerCommand(
                layerId = newLayer.id,
                layerName = newLayer.name,
                layerOpacity = newLayer.opacity,
                layerIsVisible = newLayer.isVisible,
                storageIndex = storageIndex,
                activeLayerIdBefore = activeBefore
            )
        )
        return newLayer
    }

    fun deleteLayer(layerId: Long) {
        renderer.deleteLayer(this, layerId)
    }

    fun clearLayer(layerId: Long) {
        renderer.clearLayer(this, layerId)
    }

    enum class SpringLoadedMode {
        NONE,
        PAN,
        SCRUBBY_ZOOM,
        ROTATE,
        BRUSH_RESIZE,
        EYEDROPPER
    }

    var isSpaceHeld: Boolean = false
    var isCtrlHeld: Boolean = false
    var isAltHeld: Boolean = false
    var isRHeld: Boolean = false
    var isShiftHeld: Boolean = false

    fun setModifierState(space: Boolean, ctrl: Boolean, alt: Boolean, r: Boolean, shift: Boolean) {
        val altWasHeld = isAltHeld
        isSpaceHeld = space
        isCtrlHeld = ctrl
        isAltHeld = alt
        isRHeld = r
        isShiftHeld = shift

        // If any modifier is pressed, cancel any in-progress lasso or liquify stroke immediately
        if (space || ctrl || alt || r) {
            if (selectionManager.state.value is SelectionState.DrawingLasso) {
                selectionManager.cancelLasso()
            }
            if (liquifyManager.isStrokeInProgress) {
                liquifyManager.cancelStroke(this)
            }
        }

        // If all modifiers are released, finalize any active spring-loaded interaction
        if (!space && !ctrl && !alt && !r && activeSpringMode != SpringLoadedMode.NONE) {
            if (activeSpringMode == SpringLoadedMode.PAN || activeSpringMode == SpringLoadedMode.SCRUBBY_ZOOM || activeSpringMode == SpringLoadedMode.ROTATE) {
                renderer.requestTrimBudget()
                frontBufferedRenderer?.renderMultiBufferedLayer(emptyList())
                post { onViewportChanged?.invoke(renderer.viewport.zoom, renderer.viewport.rotationDegrees) }
            } else if (activeSpringMode == SpringLoadedMode.EYEDROPPER) {
                isEyedropperTouching = false
                isEyedropperHovering = false
                onEyedropperReticleChanged?.invoke(EyedropperReticleState(isVisible = false))
                onColorPicked?.invoke(lastSampledColor)
            }
            activeSpringMode = SpringLoadedMode.NONE
        }

        if (!alt && altWasHeld) {
            isEyedropperHovering = false
            if (activeSpringMode == SpringLoadedMode.EYEDROPPER) {
                activeSpringMode = SpringLoadedMode.NONE
            }
            if (!isEyedropperMode) {
                onEyedropperReticleChanged?.invoke(EyedropperReticleState(isVisible = false))
            }
        }
    }

    var onBrushSizeChangedInteractively: ((Float) -> Unit)? = null

    private var activeSpringMode: SpringLoadedMode = SpringLoadedMode.NONE
    private var springStartScreenX: Float = 0f
    private var springStartScreenY: Float = 0f
    private var springLastScreenX: Float = 0f
    private var springLastScreenY: Float = 0f
    private var springStartBrushSize: Float = 35f
    private var springLastAngle: Float = 0f

    fun resetCanvas() {
        renderer.viewport.reset()
        frontBufferedRenderer?.renderMultiBufferedLayer(emptyList())
        post { onViewportChanged?.invoke(renderer.viewport.zoom, renderer.viewport.rotationDegrees) }
    }

    fun resetRotation() {
        renderer.viewport.rotationDegrees = 0f
        requestRedraw()
        post { onViewportChanged?.invoke(renderer.viewport.zoom, 0f) }
    }

    fun toggleZoom50or100() {
        val targetZoom = if (kotlin.math.abs(renderer.viewport.zoom - 1.0f) < 0.08f) 0.5f else 1.0f
        val cx = renderer.viewport.screenWidth * 0.5f
        val cy = renderer.viewport.screenHeight * 0.5f
        val factor = targetZoom / renderer.viewport.zoom
        renderer.viewport.applyGesture(cx, cy, cx, cy, factor, 0f)
        requestRedraw()
        post { onViewportChanged?.invoke(renderer.viewport.zoom, renderer.viewport.rotationDegrees) }
    }

    fun stepBrushSize(increase: Boolean) {
        val current = brushSize
        val step = when {
            current < 10f -> 1f
            current < 30f -> 2f
            current < 70f -> 5f
            current < 150f -> 10f
            current < 300f -> 25f
            else -> 50f
        }
        val newSize = if (increase) (current + step).coerceAtMost(1000f) else (current - step).coerceAtLeast(1f)
        brushSize = newSize
        onBrushSizeChangedInteractively?.invoke(newSize)
    }

    fun loadToolPreferences(context: Context) {
        brushEngine.properties.loadPreferences(context)
        brushColorRgb = app.goodboy13.milton.core.preferences.ToolPreferences.loadBrushColor(context, brushColorRgb)
    }

    fun saveToolPreferences(context: Context) {
        brushEngine.properties.savePreferences(context)
        app.goodboy13.milton.core.preferences.ToolPreferences.saveBrushColor(context, brushColorRgb)
    }

    fun requestRedraw() {
        frontBufferedRenderer?.renderMultiBufferedLayer(emptyList())
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        renderer.onColorPicked = { color ->
            post {
                lastSampledColor = color
                if (isEyedropperTouching || isEyedropperHovering) {
                    onEyedropperReticleChanged?.invoke(
                        EyedropperReticleState(
                            screenX = lastEyedropperScreenX,
                            screenY = lastEyedropperScreenY,
                            color = color,
                            isVisible = true
                        )
                    )
                }
            }
        }
        frontBufferedRenderer = GLFrontBufferedRenderer(this, renderer)
        Log.i(TAG, "GLFrontBufferedRenderer attached to window")
    }

    override fun onDetachedFromWindow() {
        frontBufferedRenderer?.release(true) {
            renderer.cleanup()
        }
        frontBufferedRenderer = null
        super.onDetachedFromWindow()
        Log.i(TAG, "GLFrontBufferedRenderer released")
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        renderer.viewport.updateScreenSize(w, h)
        if (oldw == 0 && oldh == 0) {
            renderer.viewport.reset()
        }
        frontBufferedRenderer?.renderMultiBufferedLayer(emptyList())
        post { onViewportChanged?.invoke(renderer.viewport.zoom, renderer.viewport.rotationDegrees) }
        Log.i(TAG, "onSizeChanged: width=$w, height=$h")
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (handleEyedropperHover(event)) return true
        if (handleLiquifyHover(event)) return true
        return super.onHoverEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE ||
            event.actionMasked == MotionEvent.ACTION_HOVER_ENTER ||
            event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) {
            if (handleEyedropperHover(event)) return true
            if (handleLiquifyHover(event)) return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun handleEyedropperHover(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val isEyedropperActive = isEyedropperMode || isAltHeld

        if (!isEyedropperActive) {
            if (isEyedropperHovering) {
                isEyedropperHovering = false
                onEyedropperReticleChanged?.invoke(EyedropperReticleState(isVisible = false))
            }
            return false
        }

        when (action) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                isEyedropperHovering = true
                lastEyedropperScreenX = event.x
                lastEyedropperScreenY = event.y
                val worldPos = renderer.viewport.screenToWorld(event.x, event.y)
                renderer.requestColorPick(worldPos.x, worldPos.y)
                requestRedraw()
                onEyedropperReticleChanged?.invoke(
                    EyedropperReticleState(
                        screenX = event.x,
                        screenY = event.y,
                        color = lastSampledColor,
                        isVisible = true
                    )
                )
                return true
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                isEyedropperHovering = false
                if (!isEyedropperTouching) {
                    onEyedropperReticleChanged?.invoke(
                        EyedropperReticleState(isVisible = false)
                    )
                }
                return true
            }
        }
        return false
    }

    private fun handleLiquifyHover(event: MotionEvent): Boolean {
        if (brushType != app.goodboy13.milton.core.brush.BrushType.LIQUIFY) {
            if (isLiquifyHovering) {
                isLiquifyHovering = false
                onLiquifyReticleChanged?.invoke(LiquifyReticleState(isVisible = false))
            }
            return false
        }

        val radius = (brushSize * 0.5f).coerceAtLeast(4f)
        val radiusScreen = radius * renderer.viewport.zoom
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                isLiquifyHovering = true
                onLiquifyReticleChanged?.invoke(
                    LiquifyReticleState(
                        screenX = event.x,
                        screenY = event.y,
                        radiusScreen = radiusScreen,
                        isVisible = true
                    )
                )
                return true
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                isLiquifyHovering = false
                onLiquifyReticleChanged?.invoke(LiquifyReticleState(isVisible = false))
                return true
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            requestUnbufferedDispatch(event)
            isEyedropperHovering = false
        }

        val action = event.actionMasked

        // 0. Eyedropper sampling mode
        if (isEyedropperMode) {
            parent?.requestDisallowInterceptTouchEvent(true)
            val worldPos = renderer.viewport.screenToWorld(event.x, event.y)
            lastEyedropperScreenX = event.x
            lastEyedropperScreenY = event.y

            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    isEyedropperTouching = true
                    isEyedropperHovering = false
                    renderer.requestColorPick(worldPos.x, worldPos.y)
                    requestRedraw()
                    onEyedropperReticleChanged?.invoke(
                        EyedropperReticleState(
                            screenX = event.x,
                            screenY = event.y,
                            color = lastSampledColor,
                            isVisible = true
                        )
                    )
                }
                MotionEvent.ACTION_MOVE -> {
                    isEyedropperTouching = true
                    isEyedropperHovering = false
                    renderer.requestColorPick(worldPos.x, worldPos.y)
                    requestRedraw()
                    onEyedropperReticleChanged?.invoke(
                        EyedropperReticleState(
                            screenX = event.x,
                            screenY = event.y,
                            color = lastSampledColor,
                            isVisible = true
                        )
                    )
                }
                MotionEvent.ACTION_UP -> {
                    isEyedropperTouching = false
                    isEyedropperHovering = false
                    isEyedropperMode = false
                    onEyedropperReticleChanged?.invoke(
                        EyedropperReticleState(isVisible = false)
                    )
                    // Commit color only upon lift
                    onColorPicked?.invoke(lastSampledColor)
                }
                MotionEvent.ACTION_CANCEL -> {
                    isEyedropperTouching = false
                    isEyedropperHovering = false
                    isEyedropperMode = false
                    onEyedropperReticleChanged?.invoke(
                        EyedropperReticleState(isVisible = false)
                    )
                }
            }
            return true
        }

        val pointerCount = event.pointerCount

        // 1. Identify if any pointer is a hardware stylus
        var stylusIndex = -1
        for (i in 0 until pointerCount) {
            val tool = event.getToolType(i)
            if (tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER) {
                stylusIndex = i
                break
            }
        }

        // 2. Spring-loaded viewport modifiers (Held Key + Drag)
        val desiredSpringMode = when {
            isCtrlHeld && isAltHeld -> SpringLoadedMode.BRUSH_RESIZE
            isCtrlHeld && isSpaceHeld -> SpringLoadedMode.SCRUBBY_ZOOM
            isSpaceHeld -> SpringLoadedMode.PAN
            isRHeld -> SpringLoadedMode.ROTATE
            isAltHeld -> SpringLoadedMode.EYEDROPPER
            else -> SpringLoadedMode.NONE
        }

        val px = if (stylusIndex != -1) event.getX(stylusIndex) else event.x
        val py = if (stylusIndex != -1) event.getY(stylusIndex) else event.y

        // Dynamically activate spring mode if modifier is held (works on ACTION_DOWN, ACTION_POINTER_DOWN, or drag)
        if (desiredSpringMode != SpringLoadedMode.NONE && activeSpringMode == SpringLoadedMode.NONE) {
            activeSpringMode = desiredSpringMode
            springStartScreenX = px
            springStartScreenY = py
            springLastScreenX = px
            springLastScreenY = py
            springStartBrushSize = brushSize
            val cx = renderer.viewport.screenWidth * 0.5f
            val cy = renderer.viewport.screenHeight * 0.5f
            springLastAngle = Math.toDegrees(kotlin.math.atan2((py - cy).toDouble(), (px - cx).toDouble())).toFloat()
        } else if (desiredSpringMode == SpringLoadedMode.NONE && activeSpringMode != SpringLoadedMode.NONE) {
            // Modifier was released mid-stroke
            if (activeSpringMode == SpringLoadedMode.PAN || activeSpringMode == SpringLoadedMode.SCRUBBY_ZOOM || activeSpringMode == SpringLoadedMode.ROTATE) {
                renderer.requestTrimBudget()
                frontBufferedRenderer?.renderMultiBufferedLayer(emptyList())
                post { onViewportChanged?.invoke(renderer.viewport.zoom, renderer.viewport.rotationDegrees) }
            } else if (activeSpringMode == SpringLoadedMode.EYEDROPPER) {
                isEyedropperTouching = false
                onEyedropperReticleChanged?.invoke(EyedropperReticleState(isVisible = false))
                onColorPicked?.invoke(lastSampledColor)
            }
            activeSpringMode = SpringLoadedMode.NONE
        }

        if (activeSpringMode != SpringLoadedMode.NONE || desiredSpringMode != SpringLoadedMode.NONE) {
            parent?.requestDisallowInterceptTouchEvent(true)
            val currentMode = if (activeSpringMode != SpringLoadedMode.NONE) activeSpringMode else desiredSpringMode

            val isGestureEnding = (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) ||
                    (action == MotionEvent.ACTION_POINTER_UP && (stylusIndex != -1 && event.actionIndex == stylusIndex))

            when (currentMode) {
                SpringLoadedMode.PAN -> {
                    when {
                        action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN -> {
                            springLastScreenX = px
                            springLastScreenY = py
                        }
                        action == MotionEvent.ACTION_MOVE -> {
                            renderer.viewport.applyGesture(springLastScreenX, springLastScreenY, px, py, 1.0f, 0f)
                            frontBufferedRenderer?.renderMultiBufferedLayer(emptyList())
                            val now = android.os.SystemClock.uptimeMillis()
                            if (now - lastViewportPostTime > 32L) {
                                lastViewportPostTime = now
                                post { onViewportChanged?.invoke(renderer.viewport.zoom, renderer.viewport.rotationDegrees) }
                            }
                            springLastScreenX = px
                            springLastScreenY = py
                        }
                        isGestureEnding -> {
                            renderer.requestTrimBudget()
                            frontBufferedRenderer?.renderMultiBufferedLayer(emptyList())
                            post { onViewportChanged?.invoke(renderer.viewport.zoom, renderer.viewport.rotationDegrees) }
                            activeSpringMode = SpringLoadedMode.NONE
                        }
                    }
                }
                SpringLoadedMode.SCRUBBY_ZOOM -> {
                    when {
                        action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN -> {
                            springStartScreenX = px
                            springStartScreenY = py
                            springLastScreenX = px
                            springLastScreenY = py
                        }
                        action == MotionEvent.ACTION_MOVE -> {
                            val dx = px - springLastScreenX
                            val dy = py - springLastScreenY
                            val delta = (dx - dy) * 0.008f
                            val zoomFactor = (1.0f + delta).coerceIn(0.5f, 2.0f)
                            renderer.viewport.applyGesture(springStartScreenX, springStartScreenY, springStartScreenX, springStartScreenY, zoomFactor, 0f)
                            frontBufferedRenderer?.renderMultiBufferedLayer(emptyList())
                            val now = android.os.SystemClock.uptimeMillis()
                            if (now - lastViewportPostTime > 32L) {
                                lastViewportPostTime = now
                                post { onViewportChanged?.invoke(renderer.viewport.zoom, renderer.viewport.rotationDegrees) }
                            }
                            springLastScreenX = px
                            springLastScreenY = py
                        }
                        isGestureEnding -> {
                            renderer.requestTrimBudget()
                            frontBufferedRenderer?.renderMultiBufferedLayer(emptyList())
                            post { onViewportChanged?.invoke(renderer.viewport.zoom, renderer.viewport.rotationDegrees) }
                            activeSpringMode = SpringLoadedMode.NONE
                        }
                    }
                }
                SpringLoadedMode.ROTATE -> {
                    val cx = renderer.viewport.screenWidth * 0.5f
                    val cy = renderer.viewport.screenHeight * 0.5f
                    when {
                        action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN -> {
                            springLastAngle = Math.toDegrees(kotlin.math.atan2((py - cy).toDouble(), (px - cx).toDouble())).toFloat()
                        }
                        action == MotionEvent.ACTION_MOVE -> {
                            val curAngle = Math.toDegrees(kotlin.math.atan2((py - cy).toDouble(), (px - cx).toDouble())).toFloat()
                            var deltaAngle = curAngle - springLastAngle
                            while (deltaAngle > 180f) deltaAngle -= 360f
                            while (deltaAngle < -180f) deltaAngle += 360f
                            renderer.viewport.applyGesture(cx, cy, cx, cy, 1.0f, deltaAngle)
                            frontBufferedRenderer?.renderMultiBufferedLayer(emptyList())
                            val now = android.os.SystemClock.uptimeMillis()
                            if (now - lastViewportPostTime > 32L) {
                                lastViewportPostTime = now
                                post { onViewportChanged?.invoke(renderer.viewport.zoom, renderer.viewport.rotationDegrees) }
                            }
                            springLastAngle = curAngle
                        }
                        isGestureEnding -> {
                            renderer.requestTrimBudget()
                            frontBufferedRenderer?.renderMultiBufferedLayer(emptyList())
                            post { onViewportChanged?.invoke(renderer.viewport.zoom, renderer.viewport.rotationDegrees) }
                            activeSpringMode = SpringLoadedMode.NONE
                        }
                    }
                }
                SpringLoadedMode.BRUSH_RESIZE -> {
                    when {
                        action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN -> {
                            springStartScreenX = px
                            springStartBrushSize = brushSize
                        }
                        action == MotionEvent.ACTION_MOVE -> {
                            val dx = px - springStartScreenX
                            val factor = (0.15f + 0.003f * springStartBrushSize).coerceIn(0.15f, 3.0f)
                            val newSize = (springStartBrushSize + dx * factor).coerceIn(1.0f, 1000f)
                            brushSize = newSize
                            onBrushSizeChangedInteractively?.invoke(newSize)
                        }
                        isGestureEnding -> {
                            onBrushSizeChangedInteractively?.invoke(brushSize)
                            activeSpringMode = SpringLoadedMode.NONE
                        }
                    }
                }
                SpringLoadedMode.EYEDROPPER -> {
                    val worldPos = renderer.viewport.screenToWorld(px, py)
                    lastEyedropperScreenX = px
                    lastEyedropperScreenY = py
                    when {
                        action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_MOVE -> {
                            isEyedropperTouching = true
                            renderer.requestColorPick(worldPos.x, worldPos.y)
                            requestRedraw()
                            onEyedropperReticleChanged?.invoke(
                                EyedropperReticleState(
                                    screenX = px,
                                    screenY = py,
                                    color = lastSampledColor,
                                    isVisible = true
                                )
                            )
                        }
                        isGestureEnding -> {
                            isEyedropperTouching = false
                            onEyedropperReticleChanged?.invoke(
                                EyedropperReticleState(isVisible = false)
                            )
                            onColorPicked?.invoke(lastSampledColor)
                            activeSpringMode = SpringLoadedMode.NONE
                        }
                    }
                }
                SpringLoadedMode.NONE -> {}
            }
            return true
        }

        // 3. Pure touch / fingers: Never draw! Route exclusively to gesture detector
        if (stylusIndex == -1) {
            return gestureDetector.onTouchEvent(event)
        }

        // 4. Hardware Stylus: Pure drawing
        gestureDetector.stopFling()
        parent?.requestDisallowInterceptTouchEvent(true)

        val sx = event.getX(stylusIndex)
        val sy = event.getY(stylusIndex)
        val rawPressure = event.getPressure(stylusIndex)
        val pressure = if (rawPressure <= 0.001f) 0.05f else rawPressure.coerceIn(0.01f, 1.0f)
        val worldPos = renderer.viewport.screenToWorld(sx, sy)

        // 4a. Lasso Selection Mode (Stylus only!)
        if (brushType == app.goodboy13.milton.core.brush.BrushType.LASSO) {
            if (selectionManager.state.value is SelectionState.ActiveTransform) {
                return false
            }

            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    selectionManager.startLasso(worldPos)
                }
                MotionEvent.ACTION_MOVE -> {
                    selectionManager.addLassoPoint(worldPos)
                }
                MotionEvent.ACTION_UP -> {
                    val targets = if (selectedTransformLayerIds.isNotEmpty()) {
                        selectedTransformLayerIds
                    } else {
                        setOf(layerManager.activeLayerId)
                    }
                    selectionManager.finishLasso(this, targets)
                }
                MotionEvent.ACTION_CANCEL -> {
                    selectionManager.cancelLasso()
                }
            }
            return true
        }

        // 4b. Liquify Mode (Stylus only!)
        if (brushType == app.goodboy13.milton.core.brush.BrushType.LIQUIFY) {
            val radius = (brushSize * 0.5f).coerceAtLeast(4f)
            val radiusScreen = radius * renderer.viewport.zoom
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    val targets = if (selectedTransformLayerIds.isNotEmpty()) {
                        selectedTransformLayerIds
                    } else {
                        setOf(layerManager.activeLayerId)
                    }
                    if (liquifyManager.isStrokeInProgress) {
                        liquifyManager.finishStroke(this)
                    }
                    liquifyManager.startStroke(
                        worldX = worldPos.x,
                        worldY = worldPos.y,
                        radius = radius,
                        strength = brushOpacity,
                        pressure = pressure,
                        targets = targets,
                        canvasView = this
                    )
                    if (liquifyManager.mode != app.goodboy13.milton.core.native.MiltonNative.LiquifyMode.PUSH) {
                        liquifyManager.applyDab(
                            canvasView = this,
                            worldX = worldPos.x,
                            worldY = worldPos.y,
                            radius = radius,
                            strength = brushOpacity,
                            pressure = pressure,
                            isContinuous = true
                        )
                    }
                    onLiquifyReticleChanged?.invoke(
                        LiquifyReticleState(sx, sy, radiusScreen, isVisible = true)
                    )
                }
                MotionEvent.ACTION_MOVE -> {
                    val historySize = event.historySize
                    for (h in 0 until historySize) {
                        val hx = event.getHistoricalX(stylusIndex, h)
                        val hy = event.getHistoricalY(stylusIndex, h)
                        val hpRaw = event.getHistoricalPressure(stylusIndex, h)
                        val hp = if (hpRaw <= 0.001f) pressure else hpRaw.coerceIn(0.01f, 1.0f)
                        val hw = renderer.viewport.screenToWorld(hx, hy)
                        liquifyManager.addPoint(hw.x, hw.y, radius, brushOpacity, hp)
                    }
                    liquifyManager.addPoint(worldPos.x, worldPos.y, radius, brushOpacity, pressure)
                    liquifyManager.flushDab(this)
                    onLiquifyReticleChanged?.invoke(
                        LiquifyReticleState(sx, sy, radiusScreen, isVisible = true)
                    )
                }
                MotionEvent.ACTION_UP -> {
                    liquifyManager.finishStroke(this)
                    onLiquifyReticleChanged?.invoke(LiquifyReticleState(isVisible = false))
                }
                MotionEvent.ACTION_CANCEL -> {
                    liquifyManager.cancelStroke(this)
                    onLiquifyReticleChanged?.invoke(LiquifyReticleState(isVisible = false))
                }
            }
            return true
        }

        val stylusTool = event.getToolType(stylusIndex)
        val buttonState = event.buttonState
        val isHardwareEraser = (stylusTool == MotionEvent.TOOL_TYPE_ERASER) ||
                ((buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0) ||
                ((buttonState and MotionEvent.BUTTON_STYLUS_SECONDARY) != 0)

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                brushEngine.properties.isEraser = isEraserMode || isHardwareEraser
                val isEraser = brushEngine.properties.isEraser
                Log.i(TAG, "Stylus DOWN at screen=($sx, $sy), world=(${worldPos.x}, ${worldPos.y}), pressure=$pressure, isEraser=$isEraser")
                val initialDabs = brushEngine.startStroke(worldPos.x, worldPos.y, pressure, event.eventTime)
                if (initialDabs.isNotEmpty()) {
                    renderer.queueDabs(initialDabs)
                    if (isEraser) {
                        frontBufferedRenderer?.renderMultiBufferedLayer(emptyList())
                    } else {
                        frontBufferedRenderer?.renderFrontBufferedLayer(DabPacket(initialDabs))
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val isEraser = brushEngine.properties.isEraser
                val dabs = mutableListOf<BrushDab>()

                // Unpack sub-frame historical points for maximum curve smoothness
                val historySize = event.historySize
                for (h in 0 until historySize) {
                    val hx = event.getHistoricalX(stylusIndex, h)
                    val hy = event.getHistoricalY(stylusIndex, h)
                    val hpRaw = event.getHistoricalPressure(stylusIndex, h)
                    val hp = if (hpRaw <= 0.001f) pressure else hpRaw.coerceIn(0.01f, 1.0f)
                    val ht = event.getHistoricalEventTime(h)
                    val hw = renderer.viewport.screenToWorld(hx, hy)
                    dabs.addAll(brushEngine.addPoint(hw.x, hw.y, hp, ht))
                }

                // Add current point
                dabs.addAll(brushEngine.addPoint(worldPos.x, worldPos.y, pressure, event.eventTime))

                if (dabs.isNotEmpty()) {
                    renderer.queueDabs(dabs)
                    if (isEraser) {
                        frontBufferedRenderer?.renderMultiBufferedLayer(emptyList())
                    } else {
                        frontBufferedRenderer?.renderFrontBufferedLayer(DabPacket(dabs))
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                Log.i(TAG, "Stylus UP at screen=($sx, $sy)")
                val isEraser = brushEngine.properties.isEraser
                val endDabs = brushEngine.endStroke()
                if (endDabs.isNotEmpty()) {
                    renderer.queueDabs(endDabs)
                }
                renderer.markStrokeFinished()
                frontBufferedRenderer?.commit()
                if (!isEraser) {
                    val strokeColor = brushEngine.properties.colorRgb
                    post { onStrokeCompleted?.invoke(strokeColor) }
                }
                brushEngine.properties.isEraser = isEraserMode
            }

            MotionEvent.ACTION_CANCEL -> {
                Log.i(TAG, "Stylus CANCEL")
                brushEngine.endStroke()
                renderer.clearPendingDabs()
                frontBufferedRenderer?.cancel()
                brushEngine.properties.isEraser = isEraserMode
            }
        }

        return true
    }
}
