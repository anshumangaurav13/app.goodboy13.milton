package com.antigrav.milton.ui

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.graphics.lowlatency.GLFrontBufferedRenderer
import com.antigrav.milton.core.brush.BrushDab
import com.antigrav.milton.core.brush.BrushEngine
import com.antigrav.milton.core.gl.DabPacket
import com.antigrav.milton.core.gl.MiltonCanvasRenderer
import com.antigrav.milton.core.history.AddLayerCommand
import com.antigrav.milton.core.layer.Layer
import com.antigrav.milton.input.CanvasGestureDetector

data class EyedropperReticleState(
    val screenX: Float = 0f,
    val screenY: Float = 0f,
    val color: Int = 0,
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
        layerManager = com.antigrav.milton.core.layer.LayerManager(
            cacheBaseDir = context.cacheDir.resolve("milton_tile_cache"),
            maxResidentTilesPerLayer = 48
        )
    )
    val layerManager: com.antigrav.milton.core.layer.LayerManager get() = renderer.layerManager
    val brushEngine = BrushEngine()

    private var frontBufferedRenderer: GLFrontBufferedRenderer<DabPacket>? = null

    init {
        brushEngine.properties.applyPreset(com.antigrav.milton.core.brush.BrushType.PENCIL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            isAutoHandwritingEnabled = false
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
            post { onViewportChanged?.invoke(renderer.viewport.zoom, renderer.viewport.rotationDegrees) }
        }

        override fun onGestureStart() {
            // Optional: gesture start hook
        }

        override fun onGestureEnd() {
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

    var brushType: com.antigrav.milton.core.brush.BrushType
        get() = brushEngine.properties.brushType
        set(value) {
            brushEngine.properties.applyPreset(value)
            isEraserMode = (value == com.antigrav.milton.core.brush.BrushType.ERASER)
        }

    var isEraserMode: Boolean = false
        set(value) {
            field = value
            if (value) {
                if (brushEngine.properties.brushType != com.antigrav.milton.core.brush.BrushType.ERASER) {
                    brushEngine.properties.applyPreset(com.antigrav.milton.core.brush.BrushType.ERASER)
                }
            } else {
                if (brushEngine.properties.brushType == com.antigrav.milton.core.brush.BrushType.ERASER) {
                    brushEngine.properties.applyPreset(com.antigrav.milton.core.brush.BrushType.PEN)
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

    var sizeBezierConfig: com.antigrav.milton.core.model.BezierControlPoints
        get() = brushEngine.properties.sizeBezierConfig
        set(value) {
            brushEngine.properties.sizeBezierConfig = value
        }

    var opacityBezierConfig: com.antigrav.milton.core.model.BezierControlPoints
        get() = brushEngine.properties.opacityBezierConfig
        set(value) {
            brushEngine.properties.opacityBezierConfig = value
        }

    var bezierConfig: com.antigrav.milton.core.model.BezierControlPoints
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
    var onColorPicked: ((Int) -> Unit)? = null
    var onEyedropperReticleChanged: ((EyedropperReticleState) -> Unit)? = null
    var onStrokeCompleted: ((Int) -> Unit)? = null

    private var lastSampledColor: Int = 0xFF000000.toInt()
    private var lastEyedropperScreenX: Float = 0f
    private var lastEyedropperScreenY: Float = 0f
    private var isEyedropperTouching: Boolean = false

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

    fun resetCanvas() {
        renderer.viewport.reset()
        frontBufferedRenderer?.renderMultiBufferedLayer(emptyList())
        post { onViewportChanged?.invoke(renderer.viewport.zoom, renderer.viewport.rotationDegrees) }
    }

    fun requestRedraw() {
        frontBufferedRenderer?.renderMultiBufferedLayer(emptyList())
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        renderer.onColorPicked = { color ->
            post {
                lastSampledColor = color
                if (isEyedropperTouching) {
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            requestUnbufferedDispatch(event)
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
                    isEyedropperMode = false
                    onEyedropperReticleChanged?.invoke(
                        EyedropperReticleState(isVisible = false)
                    )
                    // Commit color only upon lift
                    onColorPicked?.invoke(lastSampledColor)
                }
                MotionEvent.ACTION_CANCEL -> {
                    isEyedropperTouching = false
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

        // 2. Pure touch / fingers: Never draw! Route exclusively to gesture detector
        if (stylusIndex == -1) {
            return gestureDetector.onTouchEvent(event)
        }

        // 3. Hardware Stylus: Pure drawing
        gestureDetector.stopFling()
        parent?.requestDisallowInterceptTouchEvent(true)

        val sx = event.getX(stylusIndex)
        val sy = event.getY(stylusIndex)
        val rawPressure = event.getPressure(stylusIndex)
        val pressure = if (rawPressure <= 0.001f) 0.05f else rawPressure.coerceIn(0.01f, 1.0f)
        val worldPos = renderer.viewport.screenToWorld(sx, sy)

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
