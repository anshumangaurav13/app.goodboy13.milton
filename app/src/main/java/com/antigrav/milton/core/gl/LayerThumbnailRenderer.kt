package com.antigrav.milton.core.gl

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.Matrix
import com.antigrav.milton.core.layer.Layer
import com.antigrav.milton.core.model.WorldRect
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Offscreen thumbnail generator for individual layers.
 * Extracts a lightweight (72x48) Bitmap preview of the visible layer contents.
 */
class LayerThumbnailRenderer(
    val thumbWidth: Int = 72,
    val thumbHeight: Int = 48
) {
    private var fboId: Int = 0
    private var textureId: Int = 0
    private val pixelBuffer: ByteBuffer = ByteBuffer.allocateDirect(thumbWidth * thumbHeight * 4)
        .order(ByteOrder.nativeOrder())
    private val orthoMatrix = FloatArray(16)

    fun initGl() {
        if (fboId != 0) return

        val fbos = IntArray(1)
        GLES30.glGenFramebuffers(1, fbos, 0)
        fboId = fbos[0]

        val texs = IntArray(1)
        GLES30.glGenTextures(1, texs, 0)
        textureId = texs[0]

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA,
            thumbWidth, thumbHeight, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            textureId,
            0
        )

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    /**
     * Renders visible content of [layer] in [visibleBounds] into a Bitmap.
     * Must be called on GL thread.
     */
    fun captureLayerThumbnail(
        layer: Layer,
        visibleBounds: WorldRect,
        tileBlitShader: TileBlitShader,
        restoreFboId: Int = 0,
        restoreWidth: Int = 0,
        restoreHeight: Int = 0
    ): Bitmap? {
        if (fboId == 0) initGl()

        val visibleTiles = layer.tileMap.getVisibleTiles(visibleBounds)
            .filter { it.isInitialized && it.hasContent }
        if (visibleTiles.isEmpty()) {
            return null
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
        GLES30.glViewport(0, 0, thumbWidth, thumbHeight)

        // Clear to transparent
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        // Maps world visible bounds to NDC with inverted Y so glReadPixels row 0 aligns with Bitmap row 0
        Matrix.orthoM(
            orthoMatrix, 0,
            visibleBounds.left, visibleBounds.right,
            visibleBounds.top, visibleBounds.bottom,
            -1f, 1f
        )

        tileBlitShader.begin(orthoMatrix, flipY = true)
        tileBlitShader.setOpacity(1.0f)
        for (tile in visibleTiles) {
            tileBlitShader.renderTile(
                worldLeft = tile.coord.worldLeft,
                worldTop = tile.coord.worldTop,
                textureId = tile.textureId
            )
        }
        tileBlitShader.end()

        // Read pixels
        pixelBuffer.position(0)
        GLES30.glReadPixels(
            0, 0, thumbWidth, thumbHeight,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixelBuffer
        )

        // Restore target surface FBO and viewport
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, restoreFboId)
        if (restoreWidth > 0 && restoreHeight > 0) {
            GLES30.glViewport(0, 0, restoreWidth, restoreHeight)
        }

        val bitmap = Bitmap.createBitmap(thumbWidth, thumbHeight, Bitmap.Config.ARGB_8888)
        pixelBuffer.position(0)
        bitmap.copyPixelsFromBuffer(pixelBuffer)
        return bitmap
    }

    fun releaseGl() {
        if (fboId != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
            fboId = 0
        }
        if (textureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
    }
}
