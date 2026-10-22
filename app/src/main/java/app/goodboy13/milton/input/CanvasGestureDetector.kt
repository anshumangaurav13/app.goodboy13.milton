package app.goodboy13.milton.input

import android.view.Choreographer
import android.view.MotionEvent
import android.view.VelocityTracker
import app.goodboy13.milton.core.native.MiltonNative
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
        fun onUndo()
        fun onRedo()
    }

    var isZoomLocked: Boolean = false
    var isRotationLocked: Boolean = false

    private var previousX: Float = 0f
    private var previousY: Float = 0f
    private var previousSpan: Float = 0f
    private var previousAngle: Float = 0f
    private var isGesturing: Boolean = false

    private var gestureTotalMovement: Float = 0f
    private var sessionMaxPointers: Int = 0
    private var sessionStartTime: Long = 0L
    private var sessionMaxMovement: Float = 0f
    private var velocityTracker: VelocityTracker? = null

    // Fling inertia animation
    private var flingVx: Float = 0f
    private var flingVy: Float = 0f
    private val flingVelocities = FloatArray(2)
    private val flingDeltas = FloatArray(2)
    private val gestureResults = FloatArray(8)
    private var isFlinging: Boolean = false
    private val choreographer = Choreographer.getInstance()
    private val flingCallback = object : Choreographer.FrameCallback {
        private var lastFrameTimeNs: Long = 0L

        override fun doFrame(frameTimeNs: Long) {
            if (!isFlinging) return

            if (lastFrameTimeNs > 0L) {
                val dt = ((frameTimeNs - lastFrameTimeNs) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                if (MiltonNative.isNativeAvailable()) {
                    flingVelocities[0] = flingVx
                    flingVelocities[1] = flingVy
                    val isStopped = MiltonNative.viewportStepFling(dt, flingVelocities, flingDeltas)
                    flingVx = flingVelocities[0]
                    flingVy = flingVelocities[1]
                    if (isStopped) {
                        stopFling()
                        return
                    }
                    listener.onPanZoomRotate(0f, 0f, flingDeltas[0], flingDeltas[1], 1.0f, 0f)
                } else {
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
                sessionMaxMovement = 0f
                sessionMaxPointers = 1
                sessionStartTime = System.currentTimeMillis()
                isGesturing = false
                previousX = event.x
                previousY = event.y
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                stopFling()
                val count = event.pointerCount
                if (count > sessionMaxPointers) {
                    sessionMaxPointers = count
                }
                if (count >= 2) {
                    val p0x = event.getX(0)
                    val p0y = event.getY(0)
                    val p1x = event.getX(1)
                    val p1y = event.getY(1)
                    if (MiltonNative.isNativeAvailable()) {
                        MiltonNative.viewportComputeTwoFingerGesture(
                            p0x, p0y, p1x, p1y, 0f, 0f, isZoomLocked, isRotationLocked, gestureResults
                        )
                        previousX = gestureResults[0]
                        previousY = gestureResults[1]
                        previousSpan = gestureResults[2]
                        previousAngle = gestureResults[3]
                    } else {
                        previousX = (p0x + p1x) * 0.5f
                        previousY = (p0y + p1y) * 0.5f
                        previousSpan = hypot((p1x - p0x).toDouble(), (p1y - p0y).toDouble()).toFloat()
                        previousAngle = Math.toDegrees(atan2((p1y - p0y).toDouble(), (p1x - p0x).toDouble())).toFloat()
                    }
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
                    sessionMaxMovement += dist

                    if (gestureTotalMovement > 10f && !isGesturing) {
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

                    val curX: Float
                    val curY: Float
                    val curSpan: Float
                    val curAngle: Float
                    val zoomFactor: Float
                    val appliedAngle: Float
                    val zoomDelta: Float
                    val angleDelta: Float

                    if (MiltonNative.isNativeAvailable()) {
                        MiltonNative.viewportComputeTwoFingerGesture(
                            p0x, p0y, p1x, p1y, previousSpan, previousAngle, isZoomLocked, isRotationLocked, gestureResults
                        )
                        curX = gestureResults[0]
                        curY = gestureResults[1]
                        curSpan = gestureResults[2]
                        curAngle = gestureResults[3]
                        zoomFactor = gestureResults[4]
                        appliedAngle = gestureResults[5]
                        zoomDelta = gestureResults[6]
                        angleDelta = gestureResults[7]
                    } else {
                        curX = (p0x + p1x) * 0.5f
                        curY = (p0y + p1y) * 0.5f
                        curSpan = hypot((p1x - p0x).toDouble(), (p1y - p0y).toDouble()).toFloat()
                        curAngle = Math.toDegrees(atan2((p1y - p0y).toDouble(), (p1x - p0x).toDouble())).toFloat()

                        zoomDelta = if (previousSpan > 10f) abs(curSpan - previousSpan) else 0f
                        var ad = curAngle - previousAngle
                        if (ad > 180f) ad -= 360f
                        if (ad < -180f) ad += 360f
                        angleDelta = ad

                        zoomFactor = if (!isZoomLocked && previousSpan > 10f && curSpan > 10f) {
                            curSpan / previousSpan
                        } else 1.0f

                        appliedAngle = if (!isRotationLocked) angleDelta else 0f
                    }

                    val dx = curX - previousX
                    val dy = curY - previousY
                    val moveDist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                    gestureTotalMovement += moveDist
                    sessionMaxMovement += moveDist

                    if (!isGesturing) {
                        if (gestureTotalMovement > 12f || zoomDelta > 14f || abs(angleDelta) > 2.5f) {
                            isGesturing = true
                            listener.onGestureStart()
                        }
                    }

                    if (isGesturing) {
                        listener.onPanZoomRotate(previousX, previousY, curX, curY, zoomFactor, appliedAngle)
                    }

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
                    if (MiltonNative.isNativeAvailable()) {
                        MiltonNative.viewportComputeTwoFingerGesture(
                            p0x, p0y, p1x, p1y, 0f, 0f, isZoomLocked, isRotationLocked, gestureResults
                        )
                        previousX = gestureResults[0]
                        previousY = gestureResults[1]
                        previousSpan = gestureResults[2]
                        previousAngle = gestureResults[3]
                    } else {
                        previousX = (p0x + p1x) * 0.5f
                        previousY = (p0y + p1y) * 0.5f
                        previousSpan = hypot((p1x - p0x).toDouble(), (p1y - p0y).toDouble()).toFloat()
                        previousAngle = Math.toDegrees(atan2((p1y - p0y).toDouble(), (p1x - p0x).toDouble())).toFloat()
                    }
                } else if (remainingCount == 1) {
                    val remainingIdx = if (liftedIndex == 0) 1 else 0
                    previousX = event.getX(remainingIdx)
                    previousY = event.getY(remainingIdx)
                }
            }

            MotionEvent.ACTION_UP -> {
                val duration = System.currentTimeMillis() - sessionStartTime
                val isTap = !isGesturing && duration < 320L && sessionMaxMovement < 28f

                if (isTap) {
                    if (sessionMaxPointers == 2) {
                        listener.onUndo()
                    } else if (sessionMaxPointers == 3) {
                        listener.onRedo()
                    }
                } else if (isGesturing) {
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
