package com.antigrav.milton.core.gl

import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * High-performance GPU shader that composites an accumulated stroke ribbon from a 512x512
 * scratch FBO onto a destination tile FBO in a single pass.
 *
 * This guarantees:
 * 1. ZERO scallop rings across the stroke (the stroke ribbon is fully assembled before compositing).
 * 2. Clean, single-pass Porter-Duff Over digital blending onto the destination tile.
 */
class StrokeCompositeShader {

    private var programId: Int = 0
    private var vaoId: Int = 0
    private var vboId: Int = 0

    private var uStrokeTextureLoc: Int = -1

    var usesFramebufferFetch: Boolean = false
        private set

    fun initGl() {
        val vertexShaderCode = """
            #version 300 es
            layout(location = 0) in vec2 aPosition; // Unit quad [-1..1, -1..1]
            out vec2 vTexCoord;
            void main() {
                vTexCoord = (aPosition + vec2(1.0)) * 0.5;
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """.trimIndent()

        val fragmentShaderCodeFetch = """
            #version 300 es
            #extension GL_EXT_shader_framebuffer_fetch : require
            precision highp float;
            in vec2 vTexCoord;

            uniform sampler2D uStrokeTexture;

            layout(location = 0) inout vec4 fragColor;

            void main() {
                vec4 stroke = texture(uStrokeTexture, vTexCoord);
                if (stroke.a <= 0.001) discard;

                vec4 dst = fragColor;

                // If destination tile pixel is transparent, directly stamp the stroke
                if (dst.a <= 0.001) {
                    fragColor = stroke;
                    return;
                }

                float strokeAlpha = stroke.a;
                vec3 strokeRgb = stroke.rgb / max(strokeAlpha, 0.001);
                float dstAlpha = dst.a;

                float outAlpha = dstAlpha + strokeAlpha * (1.0 - dstAlpha);

                // Single-pass Porter-Duff Over optical blending evaluated once across the stroke ribbon
                vec3 outPremul = strokeRgb * strokeAlpha + dst.rgb * (1.0 - strokeAlpha);
                fragColor = vec4(clamp(outPremul, 0.0, 1.0), clamp(outAlpha, 0.0, 1.0));
            }
        """.trimIndent()

        val fragmentShaderCodeFallback = """
            #version 300 es
            precision highp float;
            in vec2 vTexCoord;
            uniform sampler2D uStrokeTexture;
            out vec4 fragColor;
            void main() {
                vec4 stroke = texture(uStrokeTexture, vTexCoord);
                if (stroke.a <= 0.001) discard;
                fragColor = stroke;
            }
        """.trimIndent()

        programId = GlUtils.tryCreateProgram(vertexShaderCode, fragmentShaderCodeFetch)
        if (programId != 0) {
            usesFramebufferFetch = true
            Log.i("StrokeCompositeShader", "Initialized with GL_EXT_shader_framebuffer_fetch")
        } else {
            programId = GlUtils.createProgram(vertexShaderCode, fragmentShaderCodeFallback)
            usesFramebufferFetch = false
            Log.i("StrokeCompositeShader", "Initialized with standard fallback shader")
        }

        uStrokeTextureLoc = GLES30.glGetUniformLocation(programId, "uStrokeTexture")

        // Setup static Unit Quad covering NDC [-1..1, -1..1]
        val vaos = IntArray(1)
        GLES30.glGenVertexArrays(1, vaos, 0)
        vaoId = vaos[0]
        GLES30.glBindVertexArray(vaoId)

        val vbos = IntArray(1)
        GLES30.glGenBuffers(1, vbos, 0)
        vboId = vbos[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)

        val unitQuad = floatArrayOf(
            -1f, -1f,
             1f, -1f,
             1f,  1f,
            -1f, -1f,
             1f,  1f,
            -1f,  1f
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
    }

    fun render(strokeTextureId: Int) {
        if (programId == 0) return

        GLES30.glUseProgram(programId)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, strokeTextureId)
        GLES30.glUniform1i(uStrokeTextureLoc, 0)

        if (usesFramebufferFetch) {
            GLES30.glDisable(GLES30.GL_BLEND)
        } else {
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        }

        GLES30.glBindVertexArray(vaoId)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
        GLES30.glBindVertexArray(0)

        if (usesFramebufferFetch) {
            GLES30.glEnable(GLES30.GL_BLEND)
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
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
