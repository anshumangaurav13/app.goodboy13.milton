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

            layout(location = 0) inout vec4 fragColor;

            vec3 rgb_to_oklab(vec3 c) {
                float l = 0.4122214708 * c.r + 0.5363325363 * c.g + 0.0514459929 * c.b;
                float m = 0.2119034982 * c.r + 0.6806995451 * c.g + 0.1073969566 * c.b;
                float s = 0.0883024619 * c.r + 0.2817188376 * c.g + 0.6299787005 * c.b;

                float l_ = pow(max(l, 0.0), 0.3333333333);
                float m_ = pow(max(m, 0.0), 0.3333333333);
                float s_ = pow(max(s, 0.0), 0.3333333333);

                return vec3(
                    0.2104542553 * l_ + 0.7936177850 * m_ - 0.0040720468 * s_,
                    1.9779984951 * l_ - 2.4285922050 * m_ + 0.4505937099 * s_,
                    0.0259040371 * l_ + 0.7827717662 * m_ - 0.8086757660 * s_
                );
            }

            vec3 oklab_to_rgb(vec3 c) {
                float l_ = c.x + 0.3963377774 * c.y + 0.2158037573 * c.z;
                float m_ = c.x - 0.1055613458 * c.y - 0.0638541728 * c.z;
                float s_ = c.x - 0.0894841775 * c.y - 1.2914855480 * c.z;

                float l = l_ * l_ * l_;
                float m = m_ * m_ * m_;
                float s = s_ * s_ * s_;

                return clamp(vec3(
                    +4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
                    -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
                    -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
                ), 0.0, 1.0);
            }

            vec3 to_pigment(vec3 c) {
                float g_boost = 0.45 * max(0.0, c.b - max(c.r, c.g)) * (1.0 - c.r);
                float b_boost = 0.35 * max(0.0, c.r - max(c.g, c.b)) * (1.0 - c.g);
                return vec3(c.r, min(1.0, c.g + g_boost), min(1.0, c.b + b_boost));
            }

            vec3 mix_pigment_oklab(vec3 c1, vec3 c2, float t) {
                vec3 p1 = to_pigment(c1);
                vec3 p2 = to_pigment(c2);

                vec3 sub = pow(max(p1, vec3(0.001)), vec3(1.0 - t)) * pow(max(p2, vec3(0.001)), vec3(t));

                vec3 ok1 = rgb_to_oklab(c1);
                vec3 ok2 = rgb_to_oklab(c2);
                vec3 ok_sub = rgb_to_oklab(sub);

                float L_target = mix(ok1.x, ok2.x, t);
                float L_mix = mix(L_target, ok_sub.x, 0.40);

                return oklab_to_rgb(vec3(L_mix, ok_sub.y, ok_sub.z));
            }

            void main() {
                vec4 tex = texture(uTexture, vTexCoord);
                float alpha = tex.a * uOpacity;
                if (alpha <= 0.001) {
                    discard;
                }

                vec3 srcColor = tex.rgb / max(tex.a, 0.001);
                vec4 dst = fragColor;

                if (dst.a <= 0.001) {
                    fragColor = vec4(srcColor * alpha, alpha);
                } else {
                    vec3 dstColor = clamp(dst.rgb / dst.a, 0.0, 1.0);
                    vec3 mixed;
                    if (distance(dstColor, srcColor) < 0.015) {
                        mixed = srcColor;
                    } else {
                        mixed = mix_pigment_oklab(dstColor, srcColor, alpha);
                    }
                    float outAlpha = dst.a + alpha * (1.0 - dst.a);
                    fragColor = vec4(mixed * outAlpha, outAlpha);
                }
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

    fun begin(mvpMatrix: FloatArray, flipY: Boolean = true) {
        if (programId == 0) return
        GLES30.glUseProgram(programId)
        GLES30.glUniformMatrix4fv(uMvpMatrixLoc, 1, false, mvpMatrix, 0)
        GLES30.glUniform1i(uTextureLoc, 0)
        GLES30.glUniform1f(uTileSizeLoc, TileCoord.TILE_SIZE.toFloat())
        GLES30.glUniform1i(uFlipYLoc, if (flipY) 1 else 0)
        GLES30.glUniform1f(uOpacityLoc, 1.0f)
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
