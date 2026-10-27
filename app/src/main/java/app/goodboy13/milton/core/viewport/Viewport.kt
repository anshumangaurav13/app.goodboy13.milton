package app.goodboy13.milton.core.viewport

import app.goodboy13.milton.core.model.Vec2
import app.goodboy13.milton.core.model.WorldRect
import app.goodboy13.milton.core.native.MiltonNative
import kotlin.math.max

/**
 * Manages the 2D infinite camera transform:
 * - panX, panY (screen offset in pixels)
 * - zoom (scale factor, 1.0 = 1 world pixel per screen pixel)
 * - rotationDegrees (canvas rotation angle in degrees)
 *
 * Fully backed by native Rust `milton_core::viewport::Viewport` for maximum precision and performance.
 */
class Viewport(
    initialWidth: Int = 1,
    initialHeight: Int = 1
) {
    var nativePtr: Long = 0L
        private set

    @Volatile var panX: Float = 0f
        set(value) {
            field = value
            if (nativePtr != 0L) {
                MiltonNative.viewportSetPan(nativePtr, field, panY)
            }
        }

    @Volatile var panY: Float = 0f
        set(value) {
            field = value
            if (nativePtr != 0L) {
                MiltonNative.viewportSetPan(nativePtr, panX, field)
            }
        }

    @Volatile var zoom: Float = 0.5f
        set(value) {
            field = value.coerceIn(0.02f, 50.0f)
            if (nativePtr != 0L) {
                MiltonNative.viewportSetZoom(nativePtr, field)
            }
        }

    @Volatile var rotationDegrees: Float = 0f
        set(value) {
            field = value
            if (nativePtr != 0L) {
                MiltonNative.viewportSetRotation(nativePtr, field)
            }
        }

    @Volatile var isFlippedHorizontally: Boolean = false
        set(value) {
            field = value
            if (nativePtr != 0L) {
                MiltonNative.viewportSetFlipped(nativePtr, field)
            }
        }

    @Volatile var screenWidth: Int = max(1, initialWidth)
    @Volatile var screenHeight: Int = max(1, initialHeight)

    private val tempBounds = FloatArray(4)
    private val panBuf = FloatArray(2)

    init {
        check(MiltonNative.isLoaded) { "MiltonNative core library must be loaded for Viewport" }
        nativePtr = MiltonNative.viewportCreate(screenWidth.toFloat(), screenHeight.toFloat())
        check(nativePtr != 0L) { "Failed to create native Viewport instance" }
        MiltonNative.viewportSetPan(nativePtr, panX, panY)
        MiltonNative.viewportSetZoom(nativePtr, zoom)
        MiltonNative.viewportSetRotation(nativePtr, rotationDegrees)
        MiltonNative.viewportSetFlipped(nativePtr, isFlippedHorizontally)
    }

    protected fun finalize() {
        if (nativePtr != 0L) {
            MiltonNative.viewportDestroy(nativePtr)
            nativePtr = 0L
        }
    }

    fun updateScreenSize(w: Int, h: Int) {
        screenWidth = max(1, w)
        screenHeight = max(1, h)
        if (nativePtr != 0L) {
            MiltonNative.viewportUpdateScreenSize(nativePtr, screenWidth.toFloat(), screenHeight.toFloat())
        }
    }

    /**
     * Updates screen size while preserving whichever world coordinate was located at the visible center
     * of the canvas before the resize/orientation change.
     */
    fun preserveCenterOnResize(newWidth: Int, newHeight: Int, oldWidth: Int, oldHeight: Int) {
        if (oldWidth <= 0 || oldHeight <= 0) {
            updateScreenSize(newWidth, newHeight)
            return
        }
        val centerWorld = screenToWorld(oldWidth * 0.5f, oldHeight * 0.5f)
        updateScreenSize(newWidth, newHeight)

        val newCenterX = newWidth * 0.5f
        val newCenterY = newHeight * 0.5f
        val rad = Math.toRadians(rotationDegrees.toDouble())
        val cosR = kotlin.math.cos(rad).toFloat()
        val sinR = kotlin.math.sin(rad).toFloat()
        val rx = (centerWorld.x * cosR - centerWorld.y * sinR) * zoom
        val ry = (centerWorld.x * sinR + centerWorld.y * cosR) * zoom
        panX = newCenterX - rx
        panY = newCenterY - ry
    }

    /**
     * Converts a 2D screen coordinate (sx, sy) into world coordinate space without heap allocation.
     * Writes results into [outCoords] where index 0 is worldX and index 1 is worldY.
     */
    fun screenToWorld(screenX: Float, screenY: Float, outCoords: FloatArray) {
        if (nativePtr != 0L) {
            MiltonNative.viewportScreenToWorld(nativePtr, screenX, screenY, outCoords)
        }
    }

    /**
     * Converts a 2D screen coordinate (sx, sy) into world coordinate space.
     */
    fun screenToWorld(screenX: Float, screenY: Float): Vec2 {
        val temp = FloatArray(2)
        screenToWorld(screenX, screenY, temp)
        return Vec2(temp[0], temp[1])
    }

    /**
     * Converts a 2D world coordinate (wx, wy) into screen coordinate space without heap allocation.
     * Writes results into [outCoords] where index 0 is screenX and index 1 is screenY.
     */
    fun worldToScreen(worldX: Float, worldY: Float, outCoords: FloatArray) {
        if (nativePtr != 0L) {
            MiltonNative.viewportWorldToScreen(nativePtr, worldX, worldY, outCoords)
        }
    }

    /**
     * Converts a 2D world coordinate (wx, wy) into screen coordinate space.
     */
    fun worldToScreen(worldX: Float, worldY: Float): Vec2 {
        val temp = FloatArray(2)
        worldToScreen(worldX, worldY, temp)
        return Vec2(temp[0], temp[1])
    }

    /**
     * Computes the bounding box of world coordinates visible in the current viewport.
     */
    fun getVisibleWorldBounds(): WorldRect {
        if (nativePtr != 0L) {
            synchronized(tempBounds) {
                MiltonNative.viewportGetVisibleBounds(nativePtr, tempBounds)
                return WorldRect(tempBounds[0], tempBounds[1], tempBounds[2], tempBounds[3])
            }
        }
        return WorldRect(0f, 0f, 0f, 0f)
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
        if (nativePtr != 0L) {
            MiltonNative.viewportApplyGesture(
                nativePtr,
                prevFocalX, prevFocalY,
                curFocalX, curFocalY,
                zoomFactor, angleDelta
            )
            synchronized(panBuf) {
                MiltonNative.viewportGetPan(nativePtr, panBuf)
                panX = panBuf[0]
                panY = panBuf[1]
            }
            zoom = MiltonNative.viewportGetZoom(nativePtr)
            rotationDegrees = MiltonNative.viewportGetRotation(nativePtr)
        }
    }

    fun reset() {
        if (nativePtr != 0L) {
            MiltonNative.viewportReset(nativePtr)
            synchronized(panBuf) {
                MiltonNative.viewportGetPan(nativePtr, panBuf)
                panX = panBuf[0]
                panY = panBuf[1]
            }
            zoom = MiltonNative.viewportGetZoom(nativePtr)
            rotationDegrees = MiltonNative.viewportGetRotation(nativePtr)
            isFlippedHorizontally = MiltonNative.viewportIsFlipped(nativePtr)
        }
    }
}
