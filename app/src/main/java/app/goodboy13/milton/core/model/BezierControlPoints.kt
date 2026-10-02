package app.goodboy13.milton.core.model

data class BezierControlPoints(
    val cp1x: Float = 0.85f,
    val cp1y: Float = 0.52f,
    val cp2x: Float = 0.58f,
    val cp2y: Float = 0.77f,
    val minPercent: Float = 0.0f,
    val maxPercent: Float = 1.0f
) {
    /**
     * Maps raw pressure [0.0, 1.0] through the cubic Bezier curve defined by (0,0), cp1, cp2, (1,1)
     * into a normalized [0.0, 1.0] range without applying minPercent / maxPercent scaling.
     */
    fun solveNormalizedY(rawPressure: Float): Float {
        val clamped = rawPressure.coerceIn(0.0f, 1.0f)
        if (clamped <= 0.0f) return 0.0f
        if (clamped >= 1.0f) return 1.0f

        var t = clamped
        for (i in 0 until 8) {
            val oneMinusT = 1.0f - t
            val currentX = 3.0f * oneMinusT * oneMinusT * t * cp1x +
                    3.0f * oneMinusT * t * t * cp2x +
                    t * t * t
            val error = currentX - clamped
            if (kotlin.math.abs(error) < 1e-4f) {
                break
            }
            val dxdt = 3.0f * oneMinusT * oneMinusT * cp1x +
                    6.0f * oneMinusT * t * (cp2x - cp1x) +
                    3.0f * t * t * (1.0f - cp2x)
            if (kotlin.math.abs(dxdt) < 1e-5f) {
                break
            }
            t -= error / dxdt
            t = t.coerceIn(0.0f, 1.0f)
        }

        val oneMinusT = 1.0f - t
        val normalizedY = 3.0f * oneMinusT * oneMinusT * t * cp1y +
                3.0f * oneMinusT * t * t * cp2y +
                t * t * t
        return normalizedY.coerceIn(0.0f, 1.0f)
    }

    /**
     * Maps raw pressure [0.0, 1.0] through the cubic Bezier curve defined by (0,0), cp1, cp2, (1,1),
     * scaled between [minPercent, maxPercent].
     */
    fun solveY(rawPressure: Float): Float {
        val minP = minPercent.coerceIn(0.0f, 1.0f)
        val maxP = maxPercent.coerceIn(minP, 1.0f)
        val clampedY = solveNormalizedY(rawPressure)
        return (minP + (maxP - minP) * clampedY).coerceIn(0.0f, 1.0f)
    }
}

object PressureCurveDefaults {
    val STANDARD = BezierControlPoints(cp1x = 0.85f, cp1y = 0.52f, cp2x = 0.58f, cp2y = 0.77f)
    val SOFT = BezierControlPoints(cp1x = 0.20f, cp1y = 0.40f, cp2x = 0.25f, cp2y = 1.00f)
    val FIRM = BezierControlPoints(cp1x = 0.45f, cp1y = 0.05f, cp2x = 0.70f, cp2y = 0.65f)
    val LINEAR = BezierControlPoints(cp1x = 0.00f, cp1y = 0.00f, cp2x = 1.00f, cp2y = 1.00f)
}
