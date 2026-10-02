package com.antigrav.milton.input

import android.view.Choreographer
import android.view.MotionEvent
import android.view.VelocityTracker
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Handles multi-touch gestures: single-finger pan, 2-finger pinch-zoom, and 2-finger rotation with fling inertia.
 */
class CanvasGestureDetector(
    private val listener: GestureListener
) {
    interface GestureListener {
        fun onPanZoomRotate(
            prevFocalX: Float,
            prevFocalY: Float,
            curFocalX: Float,
            curFocalY: Float,
            zoomFactor: Float,
            angleDelta: Float
        )
        fun onGestureStart()
        fun onGestureEnd()
        fun onFling(vx: Float, vy: Float)
    }

    var isZoomLocked: Boolean = false
    var isRotationLocked: Boolean = false

    private var previousX: Float = 0f
    private var previousY: Float = 0f
    private var previousSpan: Float = 0f
    private var previousAngle: Float = 0f
    private var isGesturing: Boolean = false

    private var gestureTotalMovement: Float = 0f
    private var velocityTracker: VelocityTracker? = null

    // Fling inertia animation
    private var flingVx: Float = 0f
    private var flingVy: Float = 0f
    private var isFlinging: Boolean = false
    private val choreographer = Choreographer.getInstance()
    private val flingCallback = object : Choreographer.FrameCallback {
        private var lastFrameTimeNs: Long = 0L

        override fun doFrame(frameTimeNs: Long) {
            if (!isFlinging) return

            if (lastFrameTimeNs > 0L) {
                val dt = ((frameTimeNs - lastFrameTimeNs) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                val decay = Math.pow(0.0001, dt.toDouble()).toFloat()
                flingVx *= decay
                flingVy *= decay

                if (abs(flingVx) < 35f && abs(flingVy) < 35f) {
                    stopFling()
                    return
                }

                val dx = flingVx * dt
                val dy = flingVy * dt
                listener.onPanZoomRotate(0f, 0f, dx, dy, 1.0f, 0f)
            }
            lastFrameTimeNs = frameTimeNs
            if (isFlinging) {
                choreographer.postFrameCallback(this)
            }
        }

        fun reset() {
            lastFrameTimeNs = 0L
        }
    }

    fun stopFling() {
        if (isFlinging) {
            isFlinging = false
            choreographer.removeFrameCallback(flingCallback)
            flingCallback.reset()
            listener.onGestureEnd()
        }
    }

    fun startFling(vx: Float, vy: Float) {
        val speed = hypot(vx.toDouble(), vy.toDouble()).toFloat()
        if (speed < 400f) return

        stopFling()
        flingVx = (vx * 0.45f).coerceIn(-2500f, 2500f)
        flingVy = (vy * 0.45f).coerceIn(-2500f, 2500f)
        isFlinging = true
        flingCallback.reset()
        listener.onGestureStart()
        choreographer.postFrameCallback(flingCallback)
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain()
        }
        velocityTracker?.addMovement(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopFling()
                gestureTotalMovement = 0f
                isGesturing = false
                previousX = event.x
                previousY = event.y
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                stopFling()
                val count = event.pointerCount
                if (count >= 2) {
                    if (!isGesturing) {
                        isGesturing = true
                        listener.onGestureStart()
                    }
                    val p0x = event.getX(0)
                    val p0y = event.getY(0)
                    val p1x = event.getX(1)
                    val p1y = event.getY(1)
                    previousX = (p0x + p1x) * 0.5f
                    previousY = (p0y + p1y) * 0.5f
                    previousSpan = hypot((p1x - p0x).toDouble(), (p1y - p0y).toDouble()).toFloat()
                    previousAngle = Math.toDegrees(atan2((p1y - p0y).toDouble(), (p1x - p0x).toDouble())).toFloat()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val count = event.pointerCount
                if (count == 1) {
                    val curX = event.x
                    val curY = event.y
                    val dx = curX - previousX
                    val dy = curY - previousY
                    val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                    gestureTotalMovement += dist

                    if (gestureTotalMovement > 8f && !isGesturing) {
                        isGesturing = true
                        listener.onGestureStart()
                    }
                    if (isGesturing) {
                        listener.onPanZoomRotate(previousX, previousY, curX, curY, 1.0f, 0f)
                    }
                    previousX = curX
                    previousY = curY
                } else if (count >= 2) {
                    val p0x = event.getX(0)
                    val p0y = event.getY(0)
                    val p1x = event.getX(1)
                    val p1y = event.getY(1)
                    val curX = (p0x + p1x) * 0.5f
                    val curY = (p0y + p1y) * 0.5f
                    val curSpan = hypot((p1x - p0x).toDouble(), (p1y - p0y).toDouble()).toFloat()
                    val curAngle = Math.toDegrees(atan2((p1y - p0y).toDouble(), (p1x - p0x).toDouble())).toFloat()

                    val dx = curX - previousX
                    val dy = curY - previousY
                    gestureTotalMovement += hypot(dx.toDouble(), dy.toDouble()).toFloat()

                    val zoomFactor = if (!isZoomLocked && previousSpan > 10f && curSpan > 10f) {
                        curSpan / previousSpan
                    } else 1.0f

                    var angleDelta = if (!isRotationLocked) curAngle - previousAngle else 0f
                    if (angleDelta > 180f) angleDelta -= 360f
                    if (angleDelta < -180f) angleDelta += 360f

                    if (!isGesturing) {
                        isGesturing = true
                        listener.onGestureStart()
                    }

                    listener.onPanZoomRotate(previousX, previousY, curX, curY, zoomFactor, angleDelta)

                    previousX = curX
                    previousY = curY
                    previousSpan = curSpan
                    previousAngle = curAngle
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val liftedIndex = event.actionIndex
                val remainingCount = event.pointerCount - 1
                if (remainingCount >= 2) {
                    var idx0 = 0
                    if (idx0 == liftedIndex) idx0++
                    var idx1 = idx0 + 1
                    if (idx1 == liftedIndex) idx1++

                    val p0x = event.getX(idx0)
                    val p0y = event.getY(idx0)
                    val p1x = event.getX(idx1)
                    val p1y = event.getY(idx1)
                    previousX = (p0x + p1x) * 0.5f
                    previousY = (p0y + p1y) * 0.5f
                    previousSpan = hypot((p1x - p0x).toDouble(), (p1y - p0y).toDouble()).toFloat()
                    previousAngle = Math.toDegrees(atan2((p1y - p0y).toDouble(), (p1x - p0x).toDouble())).toFloat()
                } else if (remainingCount == 1) {
                    val remainingIdx = if (liftedIndex == 0) 1 else 0
                    previousX = event.getX(remainingIdx)
                    previousY = event.getY(remainingIdx)
                }
            }

            MotionEvent.ACTION_UP -> {
                if (isGesturing) {
                    velocityTracker?.computeCurrentVelocity(1000)
                    val vx = velocityTracker?.xVelocity ?: 0f
                    val vy = velocityTracker?.yVelocity ?: 0f
                    startFling(vx, vy)
                    listener.onGestureEnd()
                }
                velocityTracker?.recycle()
                velocityTracker = null
                isGesturing = false
            }

            MotionEvent.ACTION_CANCEL -> {
                stopFling()
                if (isGesturing) {
                    listener.onGestureEnd()
                }
                velocityTracker?.recycle()
                velocityTracker = null
                isGesturing = false
            }
        }
        return true
    }
}
