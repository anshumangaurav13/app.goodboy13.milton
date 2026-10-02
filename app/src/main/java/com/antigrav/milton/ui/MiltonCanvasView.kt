package com.antigrav.milton.ui

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
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

    val renderer = MiltonCanvasRenderer()
    val brushEngine = BrushEngine()

    private var frontBufferedRenderer: GLFrontBufferedRenderer<DabPacket>? = null

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
            frontBufferedRenderer?.commit()
        }

        override fun onGestureStart() {
            // Optional: gesture start hook
        }

        override fun onGestureEnd() {
            frontBufferedRenderer?.commit()
        }

        override fun onFling(vx: Float, vy: Float) {
            // Fling updates handled via onPanZoomRotate callbacks
        }
    })

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        frontBufferedRenderer = GLFrontBufferedRenderer(this, renderer)
        Log.d(TAG, "GLFrontBufferedRenderer attached to window")
    }

    override fun onDetachedFromWindow() {
        frontBufferedRenderer?.release(true) {
            renderer.cleanup()
        }
        frontBufferedRenderer = null
        super.onDetachedFromWindow()
        Log.d(TAG, "GLFrontBufferedRenderer released")
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        renderer.viewport.updateScreenSize(w, h)
        if (oldw == 0 && oldh == 0) {
            renderer.viewport.reset()
        }
        frontBufferedRenderer?.commit()
        Log.d(TAG, "onSizeChanged: width=$w, height=$h")
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            requestUnbufferedDispatch(event)
        }

        // 1. Identify if any pointer is a hardware stylus
        var stylusIndex = -1
        for (i in 0 until event.pointerCount) {
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
        val pressure = event.getPressure(stylusIndex).coerceIn(0.01f, 1.0f)
        val worldPos = renderer.viewport.screenToWorld(sx, sy)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val initialDabs = brushEngine.startStroke(worldPos.x, worldPos.y, pressure)
                if (initialDabs.isNotEmpty()) {
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
                    val hp = event.getHistoricalPressure(stylusIndex, h).coerceIn(0.01f, 1.0f)
                    val hw = renderer.viewport.screenToWorld(hx, hy)
                    dabs.addAll(brushEngine.addPoint(hw.x, hw.y, hp))
                }

                // Add current point
                dabs.addAll(brushEngine.addPoint(worldPos.x, worldPos.y, pressure))

                if (dabs.isNotEmpty()) {
                    frontBufferedRenderer?.renderFrontBufferedLayer(DabPacket(dabs))
                }
            }

            MotionEvent.ACTION_UP -> {
                val endDabs = brushEngine.endStroke()
                if (endDabs.isNotEmpty()) {
                    renderer.queueDabs(endDabs)
                }
                frontBufferedRenderer?.commit()
            }

            MotionEvent.ACTION_CANCEL -> {
                brushEngine.endStroke()
                frontBufferedRenderer?.commit()
            }
        }

        return true
    }
}
