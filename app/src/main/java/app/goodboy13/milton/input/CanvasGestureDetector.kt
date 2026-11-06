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
/**
 * Formal states for multi-touch gesture detection and velocity fling inertia.
 */
sealed interface CanvasGestureState {
    object Idle : CanvasGestureState

    data class TapEvaluating(
        val startTimeMs: Long,
        var initialX: Float,
        var initialY: Float,
        var maxPointers: Int = 1,
        var maxMovement: Float = 0f
    ) : CanvasGestureState

    data class Panning(
        var previousX: Float,
        var previousY: Float,
        var totalMovement: Float
    ) : CanvasGestureState

    data class PinchZoomRotating(
        var previousX: Float,
        var previousY: Float,
        var previousSpan: Float,
        var previousAngle: Float,
        var totalMovement: Float
    ) : CanvasGestureState

    data class Flinging(
        var vx: Float,
        var vy: Float
    ) : CanvasGestureState
}

/**
 * Handles multi-touch gestures: single-finger pan, 2-finger pinch-zoom, and 2-finger rotation with fling inertia.
 * Governed by an explicit [CanvasGestureState] finite state machine.
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

    var state: CanvasGestureState = CanvasGestureState.Idle
        private set

    val isGesturing: Boolean
        get() = state is CanvasGestureState.Panning || state is CanvasGestureState.PinchZoomRotating

    val isFlinging: Boolean
        get() = state is CanvasGestureState.Flinging

    private var previousX: Float = 0f
    private var previousY: Float = 0f
    private var previousSpan: Float = 0f
    private var previousAngle: Float = 0f

    private var velocityTracker: VelocityTracker? = null

    // Fling inertia animation
    private val flingVelocities = FloatArray(2)
    private val flingDeltas = FloatArray(2)
    private val gestureResults = FloatArray(8)
    private val choreographer = Choreographer.getInstance()
    private val flingCallback = object : Choreographer.FrameCallback {
        private var lastFrameTimeNs: Long = 0L

        override fun doFrame(frameTimeNs: Long) {
            val flingState = state as? CanvasGestureState.Flinging ?: return

            if (lastFrameTimeNs > 0L) {
                val dt = ((frameTimeNs - lastFrameTimeNs) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                if (MiltonNative.isNativeAvailable()) {
                    flingVelocities[0] = flingState.vx
                    flingVelocities[1] = flingState.vy
                    val isStopped = MiltonNative.viewportStepFling(dt, flingVelocities, flingDeltas)
                    flingState.vx = flingVelocities[0]
                    flingState.vy = flingVelocities[1]
                    if (isStopped) {
                        stopFling()
                        return
                    }
                    listener.onPanZoomRotate(0f, 0f, flingDeltas[0], flingDeltas[1], 1.0f, 0f)
                } else {
                    val decay = Math.pow(0.0001, dt.toDouble()).toFloat()
                    flingState.vx *= decay
                    flingState.vy *= decay

                    if (abs(flingState.vx) < 35f && abs(flingState.vy) < 35f) {
                        stopFling()
                        return
                    }

                    val dx = flingState.vx * dt
                    val dy = flingState.vy * dt
                    listener.onPanZoomRotate(0f, 0f, dx, dy, 1.0f, 0f)
                }
            }
            lastFrameTimeNs = frameTimeNs
            if (state is CanvasGestureState.Flinging) {
                choreographer.postFrameCallback(this)
            }
        }

        fun reset() {
            lastFrameTimeNs = 0L
        }
    }

    fun stopFling() {
        if (state is CanvasGestureState.Flinging) {
            state = CanvasGestureState.Idle
            choreographer.removeFrameCallback(flingCallback)
            flingCallback.reset()
            listener.onGestureEnd()
        }
    }

    fun startFling(vx: Float, vy: Float) {
        val speed = hypot(vx.toDouble(), vy.toDouble()).toFloat()
        if (speed < 400f) {
            if (isGesturing) {
                listener.onGestureEnd()
            }
            state = CanvasGestureState.Idle
            return
        }

        stopFling()
        val clampedVx = (vx * 0.45f).coerceIn(-2500f, 2500f)
        val clampedVy = (vy * 0.45f).coerceIn(-2500f, 2500f)
        state = CanvasGestureState.Flinging(clampedVx, clampedVy)
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
                previousX = event.x
                previousY = event.y
                state = CanvasGestureState.TapEvaluating(
                    startTimeMs = System.currentTimeMillis(),
                    initialX = event.x,
                    initialY = event.y,
                    maxPointers = 1,
                    maxMovement = 0f
                )
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                stopFling()
                val count = event.pointerCount
                val tapEval = state as? CanvasGestureState.TapEvaluating
                if (tapEval != null && count > tapEval.maxPointers) {
                    tapEval.maxPointers = count
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

                    when (val s = state) {
                        is CanvasGestureState.TapEvaluating -> {
                            s.maxMovement += dist
                            if (s.maxMovement > 10f) {
                                state = CanvasGestureState.Panning(
                                    previousX = previousX,
                                    previousY = previousY,
                                    totalMovement = s.maxMovement
                                )
                                listener.onGestureStart()
                                listener.onPanZoomRotate(previousX, previousY, curX, curY, 1.0f, 0f)
                            }
                        }
                        is CanvasGestureState.Panning -> {
                            s.totalMovement += dist
                            s.previousX = curX
                            s.previousY = curY
                            listener.onPanZoomRotate(previousX, previousY, curX, curY, 1.0f, 0f)
                        }
                        else -> {
                            state = CanvasGestureState.Panning(previousX, previousY, dist)
                            listener.onGestureStart()
                            listener.onPanZoomRotate(previousX, previousY, curX, curY, 1.0f, 0f)
                        }
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

                    when (val s = state) {
                        is CanvasGestureState.TapEvaluating -> {
                            s.maxMovement += moveDist
                            if (s.maxMovement > 12f || zoomDelta > 14f || abs(angleDelta) > 2.5f) {
                                state = CanvasGestureState.PinchZoomRotating(
                                    previousX = curX,
                                    previousY = curY,
                                    previousSpan = curSpan,
                                    previousAngle = curAngle,
                                    totalMovement = s.maxMovement
                                )
                                listener.onGestureStart()
                                listener.onPanZoomRotate(previousX, previousY, curX, curY, zoomFactor, appliedAngle)
                            }
                        }
                        is CanvasGestureState.PinchZoomRotating -> {
                            s.totalMovement += moveDist
                            s.previousX = curX
                            s.previousY = curY
                            s.previousSpan = curSpan
                            s.previousAngle = curAngle
                            listener.onPanZoomRotate(previousX, previousY, curX, curY, zoomFactor, appliedAngle)
                        }
                        else -> {
                            state = CanvasGestureState.PinchZoomRotating(
                                previousX = curX,
                                previousY = curY,
                                previousSpan = curSpan,
                                previousAngle = curAngle,
                                totalMovement = moveDist
                            )
                            listener.onGestureStart()
                            listener.onPanZoomRotate(previousX, previousY, curX, curY, zoomFactor, appliedAngle)
                        }
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
                val tapEval = state as? CanvasGestureState.TapEvaluating
                val wasGesturing = isGesturing

                if (tapEval != null) {
                    val duration = System.currentTimeMillis() - tapEval.startTimeMs
                    val isTap = duration < 320L && tapEval.maxMovement < 28f
                    if (isTap) {
                        if (tapEval.maxPointers == 2) {
                            listener.onUndo()
                        } else if (tapEval.maxPointers == 3) {
                            listener.onRedo()
                        }
                    }
                    state = CanvasGestureState.Idle
                } else if (wasGesturing) {
                    velocityTracker?.computeCurrentVelocity(1000)
                    val vx = velocityTracker?.xVelocity ?: 0f
                    val vy = velocityTracker?.yVelocity ?: 0f
                    startFling(vx, vy)
                } else {
                    state = CanvasGestureState.Idle
                }

                velocityTracker?.recycle()
                velocityTracker = null
            }

            MotionEvent.ACTION_CANCEL -> {
                stopFling()
                if (isGesturing) {
                    listener.onGestureEnd()
                }
                state = CanvasGestureState.Idle
                velocityTracker?.recycle()
                velocityTracker = null
            }
        }
        return true
    }
}
