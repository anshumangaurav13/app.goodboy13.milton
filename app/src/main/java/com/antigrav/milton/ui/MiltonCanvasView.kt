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
import com.antigrav.milton.input.CanvasGestureDetector

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
        tileMap = com.antigrav.milton.core.tile.TileMap(
            cacheDir = context.cacheDir.resolve("milton_tile_cache"),
            maxResidentTiles = 96
        )
    )
    val brushEngine = BrushEngine()

    private var frontBufferedRenderer: GLFrontBufferedRenderer<DabPacket>? = null

    init {
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
            post { onViewportChanged?.invoke(renderer.viewport.zoom) }
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

    var onViewportChanged: ((zoom: Float) -> Unit)? = null

    val currentZoom: Float
        get() = renderer.viewport.zoom

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

    var brushColorRgb: Int
        get() = brushEngine.properties.colorRgb
        set(value) {
            brushEngine.properties.colorRgb = value
        }

    var bezierConfig: com.antigrav.milton.core.model.BezierControlPoints
        get() = brushEngine.properties.bezierConfig
        set(value) {
            brushEngine.properties.bezierConfig = value
        }

    fun undo() {
        renderer.requestUndo()
        frontBufferedRenderer?.renderMultiBufferedLayer(emptyList())
    }

    fun redo() {
        renderer.requestRedo()
        frontBufferedRenderer?.renderMultiBufferedLayer(emptyList())
    }

    fun resetCanvas() {
        renderer.viewport.reset()
        frontBufferedRenderer?.renderMultiBufferedLayer(emptyList())
        post { onViewportChanged?.invoke(renderer.viewport.zoom) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
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
        post { onViewportChanged?.invoke(renderer.viewport.zoom) }
        Log.i(TAG, "onSizeChanged: width=$w, height=$h")
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            requestUnbufferedDispatch(event)
        }

        val action = event.actionMasked
        val pointerCount = event.pointerCount
        val tool0 = event.getToolType(0)

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
                Log.i(TAG, "Stylus DOWN at screen=($sx, $sy), world=(${worldPos.x}, ${worldPos.y}), pressure=$pressure, isEraser=${brushEngine.properties.isEraser}")
                val initialDabs = brushEngine.startStroke(worldPos.x, worldPos.y, pressure)
                if (initialDabs.isNotEmpty()) {
                    renderer.queueDabs(initialDabs)
                    frontBufferedRenderer?.renderFrontBufferedLayer(DabPacket(initialDabs))
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val dabs = mutableListOf<BrushDab>()

                // Unpack sub-frame historical points for maximum curve smoothness
                val historySize = event.historySize
                for (h in 0 until historySize) {
                    val hx = event.getHistoricalX(stylusIndex, h)
                    val hy = event.getHistoricalY(stylusIndex, h)
                    val hpRaw = event.getHistoricalPressure(stylusIndex, h)
                    val hp = if (hpRaw <= 0.001f) pressure else hpRaw.coerceIn(0.01f, 1.0f)
                    val hw = renderer.viewport.screenToWorld(hx, hy)
                    dabs.addAll(brushEngine.addPoint(hw.x, hw.y, hp))
                }

                // Add current point
                dabs.addAll(brushEngine.addPoint(worldPos.x, worldPos.y, pressure))

                if (dabs.isNotEmpty()) {
                    renderer.queueDabs(dabs)
                    frontBufferedRenderer?.renderFrontBufferedLayer(DabPacket(dabs))
                }
            }

            MotionEvent.ACTION_UP -> {
                Log.i(TAG, "Stylus UP at screen=($sx, $sy)")
                val endDabs = brushEngine.endStroke()
                if (endDabs.isNotEmpty()) {
                    renderer.queueDabs(endDabs)
                }
                frontBufferedRenderer?.commit()
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
