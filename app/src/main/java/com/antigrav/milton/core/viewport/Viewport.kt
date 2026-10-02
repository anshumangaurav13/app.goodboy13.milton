package com.antigrav.milton.core.viewport

import android.opengl.Matrix
import com.antigrav.milton.core.model.Vec2
import com.antigrav.milton.core.model.WorldRect
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
    var panX: Float = 0f
    var panY: Float = 0f
    var zoom: Float = 0.5f
        set(value) {
            field = value.coerceIn(0.02f, 50.0f)
        }
    var rotationDegrees: Float = 0f

    var screenWidth: Int = 1
    var screenHeight: Int = 1

    fun updateScreenSize(w: Int, h: Int) {
        screenWidth = max(1, w)
        screenHeight = max(1, h)
    }

    /**
     * Converts a 2D screen coordinate (sx, sy) into world coordinate space.
     */
    fun screenToWorld(screenX: Float, screenY: Float): Vec2 {
        val dx = (screenX - panX) / zoom
        val dy = (screenY - panY) / zoom
        if (rotationDegrees == 0f) {
            return Vec2(dx, dy)
        }
        val rad = Math.toRadians(-rotationDegrees.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val wx = dx * c - dy * s
        val wy = dx * s + dy * c
        return Vec2(wx, wy)
    }

    /**
     * Converts a 2D world coordinate (wx, wy) into screen coordinate space.
     */
    fun worldToScreen(worldX: Float, worldY: Float): Vec2 {
        if (rotationDegrees == 0f) {
            return Vec2(worldX * zoom + panX, worldY * zoom + panY)
        }
        val rad = Math.toRadians(rotationDegrees.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val rx = worldX * c - worldY * s
        val ry = worldX * s + worldY * c
        return Vec2(rx * zoom + panX, ry * zoom + panY)
    }

    /**
     * Computes the bounding box of world coordinates visible in the current viewport.
     */
    fun getVisibleWorldBounds(): WorldRect {
        val w = screenWidth.toFloat()
        val h = screenHeight.toFloat()
        val p0 = screenToWorld(0f, 0f)
        val p1 = screenToWorld(w, 0f)
        val p2 = screenToWorld(w, h)
        val p3 = screenToWorld(0f, h)

        val minX = min(min(p0.x, p1.x), min(p2.x, p3.x))
        val maxX = max(max(p0.x, p1.x), max(p2.x, p3.x))
        val minY = min(min(p0.y, p1.y), min(p2.y, p3.y))
        val maxY = max(max(p0.y, p1.y), max(p2.y, p3.y))

        return WorldRect(minX, minY, maxX, maxY)
    }

    /**
     * Pan, zoom, and rotate anchored at a focal screen point.
     */
    fun applyGesture(
        prevFocalX: Float,
        prevFocalY: Float,
        curFocalX: Float,
        curFocalY: Float,
        zoomFactor: Float,
        angleDelta: Float
    ) {
        val oldZoom = zoom
        val newZoom = (oldZoom * zoomFactor).coerceIn(0.02f, 50.0f)
        val factor = newZoom / oldZoom

        var newRot = rotationDegrees + angleDelta
        while (newRot > 180f) newRot -= 360f
        while (newRot < -180f) newRot += 360f

        var deltaAngle = newRot - rotationDegrees
        while (deltaAngle > 180f) deltaAngle -= 360f
        while (deltaAngle < -180f) deltaAngle += 360f

        val deltaRad = Math.toRadians(deltaAngle.toDouble())
        val c = cos(deltaRad).toFloat()
        val s = sin(deltaRad).toFloat()

        val vx = prevFocalX - panX
        val vy = prevFocalY - panY

        val rx = factor * (vx * c - vy * s)
        val ry = factor * (vx * s + vy * c)

        panX = curFocalX - rx
        panY = curFocalY - ry
        zoom = newZoom
        rotationDegrees = newRot
    }

    fun reset() {
        panX = screenWidth * 0.5f
        panY = screenHeight * 0.5f
        zoom = 0.5f
        rotationDegrees = 0f
    }
}
