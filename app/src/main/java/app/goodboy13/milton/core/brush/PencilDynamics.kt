package app.goodboy13.milton.core.brush

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Dynamics calculations for pencil tilt and directional widening.
 *
 * Implements the cross-product alignment logic from pencil_scratchpad-2.html:
 * Strokes moving horizontally lean across the lead, expanding width.
 * Strokes moving vertically travel along the lead tip, keeping width narrow.
 */
object PencilDynamics {

    /**
     * Computes the alignment factor [0..1] between stroke movement and stylus tilt.
     *
     * @param dx Delta X of stylus motion on screen.
     * @param dy Delta Y of stylus motion on screen.
     * @param rawTiltRad Tilt angle in radians (from MotionEvent.AXIS_TILT).
     * @param orientationRad Stylus azimuth angle in radians (from MotionEvent.AXIS_ORIENTATION).
     * @param fallbackAlignment Previous alignment value retained during micro-movements.
     */
    fun computeAlignment(
        dx: Float,
        dy: Float,
        rawTiltRad: Float,
        orientationRad: Float,
        fallbackAlignment: Float = 0f
    ): Float {
        val dist = hypot(dx, dy)
        if (dist <= 0.2f) return fallbackAlignment

        val sinTilt = sin(rawTiltRad)
        val tx = sinTilt * sin(orientationRad)
        val ty = sinTilt * -cos(orientationRad)
        val tiltLen = hypot(tx, ty)

        val alignment = if (tiltLen > 1e-4f) {
            // Absolute 2D cross product: (tx * dy - ty * dx) / (tiltLength * directionLength)
            (abs(tx * dy - ty * dx) / (tiltLen * dist)).coerceIn(0f, 1f)
        } else {
            // Stylus orientation unspecified or upright: pure horizontal motion produces full width
            (abs(dx) / dist).coerceIn(0f, 1f)
        }
        return alignment
    }

    /**
     * Modulates the effective tilt for pencil widening:
     * Full tilt when moving horizontally; narrow (zero tilt effect) when moving vertically.
     * Uses quadratic falloff (alignment^2) matching pencil_scratchpad-2.html.
     */
    fun computeEffectiveTilt(rawNormalizedTilt: Float, alignment: Float): Float {
        return (rawNormalizedTilt * (alignment * alignment)).coerceIn(0f, 1f)
    }
}
