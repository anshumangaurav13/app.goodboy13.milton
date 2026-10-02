package com.antigrav.milton.core.gl

import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * High-performance GPU shader for stamping circular anti-aliased brush dabs.
 * Uses a static VAO + VBO unit quad with uniform scaling and translation.
 */
class DabShader {

    private var programId: Int = 0
    private var vaoId: Int = 0
    private var vboId: Int = 0

    private var uProjectionLoc: Int = -1
    private var uCenterLoc: Int = -1
    private var uRadiusLoc: Int = -1
    private var uColorLoc: Int = -1
    private var uHardnessLoc: Int = -1

    fun initGl() {
        val vertexShaderCode = """
            #version 300 es
            layout(location = 0) in vec2 aPosition; // Unit quad [-1..1, -1..1]
            uniform mat4 uProjection;
            uniform vec2 uCenter;
            uniform float uRadius;
            out vec2 vLocalCoord;
            void main() {
                vLocalCoord = aPosition;
                vec2 worldPos = uCenter + aPosition * uRadius;
                gl_Position = uProjection * vec4(worldPos, 0.0, 1.0);
            }
        """.trimIndent()

        val fragmentShaderCode = """
            #version 300 es
            precision mediump float;
            in vec2 vLocalCoord;
            uniform vec4 uColor;
            uniform float uHardness;
            out vec4 fragColor;
            void main() {
                float dist = length(vLocalCoord);
                if (dist > 1.0) {
                    discard;
                }
                float alpha = smoothstep(1.0, uHardness, dist) * uColor.a;
                // Premultiplied alpha output
                fragColor = vec4(uColor.rgb * alpha, alpha);
            }
        """.trimIndent()

        programId = GlUtils.createProgram(vertexShaderCode, fragmentShaderCode)
        uProjectionLoc = GLES30.glGetUniformLocation(programId, "uProjection")
        uCenterLoc = GLES30.glGetUniformLocation(programId, "uCenter")
        uRadiusLoc = GLES30.glGetUniformLocation(programId, "uRadius")
        uColorLoc = GLES30.glGetUniformLocation(programId, "uColor")
        uHardnessLoc = GLES30.glGetUniformLocation(programId, "uHardness")

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

        val err = GLES30.glGetError()
        if (err != GLES30.GL_NO_ERROR) {
            Log.e("DabShader", "GL error during DabShader.initGl(): $err")
        }
    }

    fun renderDab(
        centerX: Float,
        centerY: Float,
        radius: Float,
        colorRgb: Int,
        alpha: Float,
        hardness: Float,
        projectionMatrix: FloatArray
    ) {
        if (programId == 0) return

        GLES30.glUseProgram(programId)

        GLES30.glUniformMatrix4fv(uProjectionLoc, 1, false, projectionMatrix, 0)
        GLES30.glUniform2f(uCenterLoc, centerX, centerY)
        GLES30.glUniform1f(uRadiusLoc, radius)

        val red = ((colorRgb shr 16) and 0xFF) / 255.0f
        val green = ((colorRgb shr 8) and 0xFF) / 255.0f
        val blue = (colorRgb and 0xFF) / 255.0f
        GLES30.glUniform4f(uColorLoc, red, green, blue, alpha)
        GLES30.glUniform1f(uHardnessLoc, hardness.coerceIn(0.01f, 0.99f))

        GLES30.glBindVertexArray(vaoId)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
        GLES30.glBindVertexArray(0)
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
