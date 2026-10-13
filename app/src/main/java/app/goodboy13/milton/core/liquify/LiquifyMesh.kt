package app.goodboy13.milton.core.liquify

import app.goodboy13.milton.core.model.Vec2
import app.goodboy13.milton.core.model.WorldRect
import app.goodboy13.milton.core.viewport.Viewport
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * High-performance 2D deformation mesh for Liquify operations.
 * Constrained strictly to the bounding box and boundary polygon of a Lasso selection.
 */
class LiquifyMesh(
    val srcBounds: WorldRect,
    val polygon: List<Vec2>,
    val gridCols: Int = 128,
    val gridRows: Int = 128
) {
    val vertexCount = (gridCols + 1) * (gridRows + 1)
    val width = srcBounds.width
    val height = srcBounds.height

    // Original local coordinates relative to srcBounds top-left [0..width, 0..height]
    val initLocalX = FloatArray(vertexCount)
    val initLocalY = FloatArray(vertexCount)

    // Current deformed world positions
    val worldX = FloatArray(vertexCount)
    val worldY = FloatArray(vertexCount)

    // Polygon boundary weights [0.0..1.0] to anchor vertices outside/at the lasso edge
    val maskWeights = FloatArray(vertexCount)

    init {
        val dx = width / gridCols.toFloat()
        val dy = height / gridRows.toFloat()
        val featherRadius = (maxOf(width, height) * 0.05f).coerceIn(32f, 96f)

        var idx = 0
        for (r in 0..gridRows) {
            val ly = (r * dy).coerceAtMost(height)
            val wy = srcBounds.top + ly
            for (c in 0..gridCols) {
                val lx = (c * dx).coerceAtMost(width)
                val wx = srcBounds.left + lx

                initLocalX[idx] = lx
                initLocalY[idx] = ly
                worldX[idx] = wx
                worldY[idx] = wy

                val pt = Vec2(wx, wy)
                if (isPointInPolygon(pt, polygon)) {
                    val edgeDist = minDistanceToPolygon(pt, polygon)
                    val t = (edgeDist / featherRadius).coerceIn(0f, 1f)
                    // Smooth cubic Hermite C^1 curve: 3t^2 - 2t^3
                    maskWeights[idx] = t * t * (3f - 2f * t)
                } else {
                    maskWeights[idx] = 0f
                }
                idx++
            }
        }
    }

    fun reset() {
        for (i in 0 until vertexCount) {
            worldX[i] = srcBounds.left + initLocalX[i]
            worldY[i] = srcBounds.top + initLocalY[i]
        }
    }

    /**
     * Applies a continuous stylus drag segment from (x1, y1) to (x2, y2).
     */
    fun applyStroke(
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        radius: Float,
        strength: Float,
        mode: LiquifyMode
    ) {
        if (radius <= 0.001f || strength <= 0.001f) return

        val r2 = radius * radius
        val segDx = x2 - x1
        val segDy = y2 - y1
        val segLenSq = segDx * segDx + segDy * segDy

        val midX = (x1 + x2) * 0.5f
        val midY = (y1 + y2) * 0.5f

        // Bounding box filter for quick rejection
        val minX = min(x1, x2) - radius
        val maxX = max(x1, x2) + radius
        val minY = min(y1, y2) - radius
        val maxY = max(y1, y2) + radius

        val effStrength = strength.coerceIn(0.01f, 1.0f)

        for (i in 0 until vertexCount) {
            val w = maskWeights[i]
            if (w <= 0.001f) continue // Vertices outside the lasso selection NEVER move

            val vx = worldX[i]
            val vy = worldY[i]

            if (vx < minX || vx > maxX || vy < minY || vy > maxY) continue

            // Distance from vertex to line segment (x1, y1) -> (x2, y2)
            val distSq = if (segLenSq < 0.001f) {
                val ddx = vx - x2
                val ddy = vy - y2
                ddx * ddx + ddy * ddy
            } else {
                val t = (((vx - x1) * segDx + (vy - y1) * segDy) / segLenSq).coerceIn(0f, 1f)
                val projX = x1 + t * segDx
                val projY = y1 + t * segDy
                val ddx = vx - projX
                val ddy = vy - projY
                ddx * ddx + ddy * ddy
            }

            if (distSq >= r2) continue

            val dist = kotlin.math.sqrt(distSq)
            val f = 1f - dist / radius
            // Smooth cubic Hermite falloff
            val falloff = f * f * (3f - 2f * f)
            val weight = falloff * w * effStrength

            when (mode) {
                LiquifyMode.PUSH -> {
                    worldX[i] += segDx * weight
                    worldY[i] += segDy * weight
                }
                LiquifyMode.BLOAT -> {
                    val ddx = vx - midX
                    val ddy = vy - midY
                    val factor = 0.35f * weight
                    worldX[i] += ddx * factor
                    worldY[i] += ddy * factor
                }
                LiquifyMode.PINCH -> {
                    val ddx = vx - midX
                    val ddy = vy - midY
                    val factor = 0.35f * weight
                    worldX[i] -= ddx * factor
                    worldY[i] -= ddy * factor
                }
                LiquifyMode.TWIRL_CW -> {
                    val ddx = vx - midX
                    val ddy = vy - midY
                    val angle = 0.60f * weight
                    val cosA = cos(angle)
                    val sinA = sin(angle)
                    worldX[i] = midX + (ddx * cosA - ddy * sinA)
                    worldY[i] = midY + (ddx * sinA + ddy * cosA)
                }
                LiquifyMode.TWIRL_CCW -> {
                    val ddx = vx - midX
                    val ddy = vy - midY
                    val angle = -0.60f * weight
                    val cosA = cos(angle)
                    val sinA = sin(angle)
                    worldX[i] = midX + (ddx * cosA - ddy * sinA)
                    worldY[i] = midY + (ddx * sinA + ddy * cosA)
                }
                LiquifyMode.SMOOTH -> {
                    // Relax toward neighbors
                    val r = i / (gridCols + 1)
                    val c = i % (gridCols + 1)
                    var neighborCount = 0
                    var avgX = 0f
                    var avgY = 0f

                    if (c > 0) {
                        val leftIdx = i - 1
                        avgX += worldX[leftIdx]
                        avgY += worldY[leftIdx]
                        neighborCount++
                    }
                    if (c < gridCols) {
                        val rightIdx = i + 1
                        avgX += worldX[rightIdx]
                        avgY += worldY[rightIdx]
                        neighborCount++
                    }
                    if (r > 0) {
                        val upIdx = i - (gridCols + 1)
                        avgX += worldX[upIdx]
                        avgY += worldY[upIdx]
                        neighborCount++
                    }
                    if (r < gridRows) {
                        val downIdx = i + (gridCols + 1)
                        avgX += worldX[downIdx]
                        avgY += worldY[downIdx]
                        neighborCount++
                    }

                    if (neighborCount > 0) {
                        avgX /= neighborCount.toFloat()
                        avgY /= neighborCount.toFloat()
                        worldX[i] += (avgX - vx) * 0.5f * weight
                        worldY[i] += (avgY - vy) * 0.5f * weight
                    }
                }
            }
        }
    }

    /**
     * Projects world vertex coordinates to screen space for Canvas.drawBitmapMesh.
     */
    fun computeScreenVerts(viewport: Viewport, outArray: FloatArray) {
        var vi = 0
        for (i in 0 until vertexCount) {
            val sc = viewport.worldToScreen(worldX[i], worldY[i])
            outArray[vi] = sc.x
            outArray[vi + 1] = sc.y
            vi += 2
        }
    }

    /**
     * Converts world vertex coordinates to local patch space [0..width, 0..height] for baking.
     */
    fun computeLocalWarpedVerts(outArray: FloatArray) {
        var vi = 0
        for (i in 0 until vertexCount) {
            outArray[vi] = worldX[i] - srcBounds.left
            outArray[vi + 1] = worldY[i] - srcBounds.top
            vi += 2
        }
    }

    companion object {
        fun isPointInPolygon(p: Vec2, poly: List<Vec2>): Boolean {
            if (poly.size < 3) return false
            var inside = false
            var j = poly.size - 1
            for (i in poly.indices) {
                val pi = poly[i]
                val pj = poly[j]
                if (((pi.y > p.y) != (pj.y > p.y)) &&
                    (p.x < (pj.x - pi.x) * (p.y - pi.y) / (pj.y - pi.y) + pi.x)
                ) {
                    inside = !inside
                }
                j = i
            }
            return inside
        }

        fun minDistanceToPolygon(p: Vec2, poly: List<Vec2>): Float {
            if (poly.size < 2) return 0f
            var minDistSq = Float.MAX_VALUE
            for (i in poly.indices) {
                val a = poly[i]
                val b = poly[(i + 1) % poly.size]
                val abx = b.x - a.x
                val aby = b.y - a.y
                val lenSq = abx * abx + aby * aby
                val distSq = if (lenSq < 0.001f) {
                    val dx = p.x - a.x
                    val dy = p.y - a.y
                    dx * dx + dy * dy
                } else {
                    val t = (((p.x - a.x) * abx + (p.y - a.y) * aby) / lenSq).coerceIn(0f, 1f)
                    val projX = a.x + t * abx
                    val projY = a.y + t * aby
                    val dx = p.x - projX
                    val dy = p.y - projY
                    dx * dx + dy * dy
                }
                minDistSq = min(minDistSq, distSq)
            }
            return kotlin.math.sqrt(minDistSq)
        }
    }
}
