package com.antigrav.milton.core.gl

import android.opengl.GLES30
import android.util.Log
import com.antigrav.milton.core.tile.TileCoord
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * High-performance GPU shader for blitting 512x512 RasterTiles into the screen viewport.
 * Supports programmable GL_EXT_shader_framebuffer_fetch with realistic subtractive
 * Oklab pigment mixing across layers (layer compositing).
 * Uses a static VAO + VBO unit quad [0..1, 0..1] with uniform positioning.
 */
class TileBlitShader {

    private var programId: Int = 0
    private var vaoId: Int = 0
    private var vboId: Int = 0

    private var uMvpMatrixLoc: Int = -1
    private var uTilePosLoc: Int = -1
    private var uTileSizeLoc: Int = -1
    private var uTextureLoc: Int = -1
    private var uFlipYLoc: Int = -1
    private var uOpacityLoc: Int = -1
    private var uBackgroundColorLoc: Int = -1

    var usesFramebufferFetch: Boolean = false
        private set

    fun initGl() {
        val vertexShaderCode = """
            #version 300 es
            layout(location = 0) in vec2 aPosition; // Unit quad [0..1, 0..1]
            uniform mat4 uMvpMatrix;
            uniform vec2 uTilePos;
            uniform float uTileSize;
            uniform int uFlipY;
            out vec2 vTexCoord;
            void main() {
                float ty = (uFlipY == 1) ? (1.0 - aPosition.y) : aPosition.y;
                vTexCoord = vec2(aPosition.x, ty);
                vec2 worldPos = uTilePos + aPosition * uTileSize;
                gl_Position = uMvpMatrix * vec4(worldPos, 0.0, 1.0);
            }
        """.trimIndent()

        val fragmentShaderCodeFetch = """
            #version 300 es
            #extension GL_EXT_shader_framebuffer_fetch : require
            precision highp float;
            in vec2 vTexCoord;
            uniform sampler2D uTexture;
            uniform float uOpacity;
            uniform vec3 uBackgroundColor;

            layout(location = 0) inout vec4 fragColor;

            vec3 mix_pigment_fast(vec3 c1, vec3 c2, float t) {
                vec3 diff = c1 - c2;
                if (dot(diff, diff) < 0.0004) {
                    return c2;
                }

                vec3 c1cmy = 1.0 - c1;
                vec3 c2cmy = 1.0 - c2;

                vec3 mix_cmy = mix(c1cmy, c2cmy, clamp(t, 0.0, 1.0));

                float cy_overlap = min(mix_cmy.x, mix_cmy.z);
                float shared_green_absorption = min(c1cmy.y, c2cmy.y);
                float can_form_green = max(0.0, 1.0 - 2.0 * shared_green_absorption);
                mix_cmy.y *= max(0.0, 1.0 - 1.6 * cy_overlap * can_form_green);

                return clamp(1.0 - mix_cmy, 0.0, 1.0);
            }

            void main() {
                vec4 tex = texture(uTexture, vTexCoord);
                float alpha = tex.a * uOpacity;
                if (alpha <= 0.001) {
                    discard;
                }

                vec3 srcColor = tex.rgb / max(tex.a, 0.001);
                vec4 dst = fragColor;

                vec3 dstColor = dst.rgb;
                // Check if the destination is canvas paper
                vec3 paperDiff = abs(dstColor - uBackgroundColor);
                bool isPaper = all(lessThan(paperDiff, vec3(0.02)));

                vec3 mixed;
                if (isPaper) {
                    // Painting over canvas paper: natural optical transmission over paper
                    mixed = mix(dstColor, srcColor, alpha);
                } else {
                    // Subtractive pigment glaze over previous layer
                    mixed = mix_pigment_fast(dstColor, srcColor, alpha);
                }
                fragColor = vec4(mixed, 1.0);
            }
        """.trimIndent()

        val fragmentShaderCodeFallback = """
            #version 300 es
            precision mediump float;
            in vec2 vTexCoord;
            uniform sampler2D uTexture;
            uniform float uOpacity;
            out vec4 fragColor;
            void main() {
                vec4 tex = texture(uTexture, vTexCoord);
                if (tex.a <= 0.001) {
                    discard;
                }
                fragColor = tex * uOpacity;
            }
        """.trimIndent()

        programId = GlUtils.tryCreateProgram(vertexShaderCode, fragmentShaderCodeFetch)
        if (programId != 0) {
            usesFramebufferFetch = true
            Log.i("TileBlitShader", "Initialized with GL_EXT_shader_framebuffer_fetch Oklab pigment mixing")
        } else {
            programId = GlUtils.createProgram(vertexShaderCode, fragmentShaderCodeFallback)
            usesFramebufferFetch = false
            Log.i("TileBlitShader", "Initialized with standard fallback shader")
        }

        uMvpMatrixLoc = GLES30.glGetUniformLocation(programId, "uMvpMatrix")
        uTilePosLoc = GLES30.glGetUniformLocation(programId, "uTilePos")
        uTileSizeLoc = GLES30.glGetUniformLocation(programId, "uTileSize")
        uTextureLoc = GLES30.glGetUniformLocation(programId, "uTexture")
        uFlipYLoc = GLES30.glGetUniformLocation(programId, "uFlipY")
        uOpacityLoc = GLES30.glGetUniformLocation(programId, "uOpacity")
        uBackgroundColorLoc = GLES30.glGetUniformLocation(programId, "uBackgroundColor")

        // Setup static Unit Quad in VBO + VAO
        val vaos = IntArray(1)
        GLES30.glGenVertexArrays(1, vaos, 0)
        vaoId = vaos[0]
        GLES30.glBindVertexArray(vaoId)

        val vbos = IntArray(1)
        GLES30.glGenBuffers(1, vbos, 0)
        vboId = vbos[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)

        val unitQuad = floatArrayOf(
            0f, 0f,
            1f, 0f,
            1f, 1f,
            0f, 0f,
            1f, 1f,
            0f, 1f
        )
        val buf = ByteBuffer.allocateDirect(unitQuad.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(unitQuad)
        buf.position(0)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, unitQuad.size * 4, buf, GLES30.GL_STATIC_DRAW)

        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 2 * 4, 0)

        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        val err = GLES30.glGetError()
        if (err != GLES30.GL_NO_ERROR) {
            Log.e("TileBlitShader", "GL error during TileBlitShader.initGl(): $err")
        }
    }

    fun begin(mvpMatrix: FloatArray, flipY: Boolean = true, backgroundColorRgb: Int = 0xFFFFFFFF.toInt()) {
        if (programId == 0) return
        GLES30.glUseProgram(programId)
        GLES30.glUniformMatrix4fv(uMvpMatrixLoc, 1, false, mvpMatrix, 0)
        GLES30.glUniform1i(uTextureLoc, 0)
        GLES30.glUniform1f(uTileSizeLoc, TileCoord.TILE_SIZE.toFloat())
        GLES30.glUniform1i(uFlipYLoc, if (flipY) 1 else 0)
        GLES30.glUniform1f(uOpacityLoc, 1.0f)

        if (uBackgroundColorLoc != -1) {
            val r = ((backgroundColorRgb shr 16) and 0xFF) / 255.0f
            val g = ((backgroundColorRgb shr 8) and 0xFF) / 255.0f
            val b = (backgroundColorRgb and 0xFF) / 255.0f
            GLES30.glUniform3f(uBackgroundColorLoc, r, g, b)
        }

        GLES30.glBindVertexArray(vaoId)

        if (usesFramebufferFetch) {
            GLES30.glDisable(GLES30.GL_BLEND)
        } else {
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        }
    }

    fun setOpacity(opacity: Float) {
        if (programId != 0 && uOpacityLoc != -1) {
            GLES30.glUniform1f(uOpacityLoc, opacity.coerceIn(0f, 1f))
        }
    }

    fun renderTile(worldLeft: Float, worldTop: Float, textureId: Int) {
        if (programId == 0 || textureId == 0) return

        GLES30.glUniform2f(uTilePosLoc, worldLeft, worldTop)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
    }

    fun end() {
        GLES30.glBindVertexArray(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        if (usesFramebufferFetch) {
            GLES30.glEnable(GLES30.GL_BLEND)
        }
    }

    fun releaseGl() {
        if (vaoId != 0) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(vaoId), 0)
            vaoId = 0
        }
        if (vboId != 0) {
            GLES30.glDeleteBuffers(1, intArrayOf(vboId), 0)
            vboId = 0
        }
        if (programId != 0) {
            GLES30.glDeleteProgram(programId)
            programId = 0
        }
    }
}
