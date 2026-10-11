package app.goodboy13.milton.core.viewport

import android.opengl.Matrix
import app.goodboy13.milton.core.model.Vec2
import app.goodboy13.milton.core.model.WorldRect
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Manages the 2D infinite camera transform:
 * - panX, panY (screen offset in pixels)
 * - zoom (scale factor, 1.0 = 1 world pixel per screen pixel)
 * - rotationDegrees (canvas rotation angle in degrees)
 */
class Viewport {
    @Volatile var panX: Float = 0f
    @Volatile var panY: Float = 0f
    @Volatile var zoom: Float = 0.5f
        set(value) {
            field = value.coerceIn(0.02f, 50.0f)
        }
    @Volatile var rotationDegrees: Float = 0f
    @Volatile var isFlippedHorizontally: Boolean = false

    @Volatile var screenWidth: Int = 1
    @Volatile var screenHeight: Int = 1

    fun updateScreenSize(w: Int, h: Int) {
        screenWidth = max(1, w)
        screenHeight = max(1, h)
    }

    /**
     * Converts a 2D screen coordinate (sx, sy) into world coordinate space without heap allocation.
     * Writes results into [outCoords] where index 0 is worldX and index 1 is worldY.
     */
    fun screenToWorld(screenX: Float, screenY: Float, outCoords: FloatArray) {
        val sx = if (isFlippedHorizontally) (screenWidth.toFloat() - screenX) else screenX
        val dx = (sx - panX) / zoom
        val dy = (screenY - panY) / zoom
        val rot = rotationDegrees
        if (rot == 0f) {
            outCoords[0] = dx
            outCoords[1] = dy
            return
        }
        val rad = Math.toRadians(-rot.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        outCoords[0] = dx * c - dy * s
        outCoords[1] = dx * s + dy * c
    }

    /**
     * Converts a 2D screen coordinate (sx, sy) into world coordinate space.
     * When flipped, reflects the screen X coordinate about the screen center.
     */
    fun screenToWorld(screenX: Float, screenY: Float): Vec2 {
        val sx = if (isFlippedHorizontally) (screenWidth.toFloat() - screenX) else screenX
        val dx = (sx - panX) / zoom
        val dy = (screenY - panY) / zoom
        val rot = rotationDegrees
        if (rot == 0f) {
            return Vec2(dx, dy)
        }
        val rad = Math.toRadians(-rot.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val wx = dx * c - dy * s
        val wy = dx * s + dy * c
        return Vec2(wx, wy)
    }

    /**
     * Converts a 2D world coordinate (wx, wy) into screen coordinate space without heap allocation.
     * Writes results into [outCoords] where index 0 is screenX and index 1 is screenY.
     */
    fun worldToScreen(worldX: Float, worldY: Float, outCoords: FloatArray) {
        val rot = rotationDegrees
        val z = zoom
        val px = panX
        val py = panY
        val sx = if (rot == 0f) {
            worldX * z + px
        } else {
            val rad = Math.toRadians(rot.toDouble())
            val c = cos(rad).toFloat()
            val s = sin(rad).toFloat()
            val rx = worldX * c - worldY * s
            rx * z + px
        }
        val sy = if (rot == 0f) {
            worldY * z + py
        } else {
            val rad = Math.toRadians(rot.toDouble())
            val c = cos(rad).toFloat()
            val s = sin(rad).toFloat()
            val ry = worldX * s + worldY * c
            ry * z + py
        }
        val finalSx = if (isFlippedHorizontally) (screenWidth.toFloat() - sx) else sx
        outCoords[0] = finalSx
        outCoords[1] = sy
    }

    /**
     * Converts a 2D world coordinate (wx, wy) into screen coordinate space.
     * When flipped, reflects the screen X coordinate about the screen center.
     */
    fun worldToScreen(worldX: Float, worldY: Float): Vec2 {
        val rot = rotationDegrees
        val sx = if (rot == 0f) {
            worldX * zoom + panX
        } else {
            val rad = Math.toRadians(rot.toDouble())
            val c = cos(rad).toFloat()
            val s = sin(rad).toFloat()
            val rx = worldX * c - worldY * s
            rx * zoom + panX
        }
        val sy = if (rot == 0f) {
            worldY * zoom + panY
        } else {
            val rad = Math.toRadians(rot.toDouble())
            val c = cos(rad).toFloat()
            val s = sin(rad).toFloat()
            val ry = worldX * s + worldY * c
            ry * zoom + panY
        }
        val finalSx = if (isFlippedHorizontally) (screenWidth.toFloat() - sx) else sx
        return Vec2(finalSx, sy)
    }

    /**
     * Computes the bounding box of world coordinates visible in the current viewport.
     */
    fun getVisibleWorldBounds(): WorldRect {
        val w = screenWidth.toFloat()
        val h = screenHeight.toFloat()
        val temp = FloatArray(2)

        screenToWorld(0f, 0f, temp)
        val x0 = temp[0]; val y0 = temp[1]

        screenToWorld(w, 0f, temp)
        val x1 = temp[0]; val y1 = temp[1]

        screenToWorld(w, h, temp)
        val x2 = temp[0]; val y2 = temp[1]

        screenToWorld(0f, h, temp)
        val x3 = temp[0]; val y3 = temp[1]

        val minX = min(min(x0, x1), min(x2, x3))
        val maxX = max(max(x0, x1), max(x2, x3))
        val minY = min(min(y0, y1), min(y2, y3))
        val maxY = max(max(y0, y1), max(y2, y3))

        return WorldRect(minX, minY, maxX, maxY)
    }

    /**
     * Pan, zoom, and rotate anchored at a focal screen point.
     * Accurately preserves physical finger tracking under horizontal canvas flip.
     */
    fun applyGesture(
        prevFocalX: Float,
        prevFocalY: Float,
        curFocalX: Float,
        curFocalY: Float,
        zoomFactor: Float,
        angleDelta: Float
    ) {
        val pFx = if (isFlippedHorizontally) (screenWidth.toFloat() - prevFocalX) else prevFocalX
        val cFx = if (isFlippedHorizontally) (screenWidth.toFloat() - curFocalX) else curFocalX
        val effAngle = if (isFlippedHorizontally) -angleDelta else angleDelta

        val oldZoom = zoom
        val newZoom = (oldZoom * zoomFactor).coerceIn(0.02f, 50.0f)
        val factor = newZoom / oldZoom

        var newRot = rotationDegrees + effAngle
        while (newRot > 180f) newRot -= 360f
        while (newRot < -180f) newRot += 360f

        var deltaAngle = newRot - rotationDegrees
        while (deltaAngle > 180f) deltaAngle -= 360f
        while (deltaAngle < -180f) deltaAngle -= 360f

        val deltaRad = Math.toRadians(deltaAngle.toDouble())
        val c = cos(deltaRad).toFloat()
        val s = sin(deltaRad).toFloat()

        val vx = pFx - panX
        val vy = prevFocalY - panY

        val rx = factor * (vx * c - vy * s)
        val ry = factor * (vx * s + vy * c)

        panX = cFx - rx
        panY = curFocalY - ry
        zoom = newZoom
        rotationDegrees = newRot
    }

    fun reset() {
        panX = screenWidth * 0.5f
        panY = screenHeight * 0.5f
        zoom = 0.5f
        rotationDegrees = 0f
        isFlippedHorizontally = false
    }
}
