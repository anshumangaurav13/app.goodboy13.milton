package com.antigrav.milton.core.gl

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Shader for stamping circular anti-aliased brush dabs into Tile FBOs.
 */
class DabShader {

    private var programId: Int = 0
    private var uProjectionLoc: Int = -1
    private var uColorLoc: Int = -1
    private var uHardnessLoc: Int = -1

    private val vertexBuffer: FloatBuffer

    init {
        // Quad with local coords: [posX, posY, localU, localV]
        // 6 vertices (2 triangles)
        val vertices = floatArrayOf(
            -1f, -1f, -1f, -1f,
             1f, -1f,  1f, -1f,
             1f,  1f,  1f,  1f,
            -1f, -1f, -1f, -1f,
             1f,  1f,  1f,  1f,
            -1f,  1f, -1f,  1f
        )
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
        vertexBuffer.position(0)
    }

    fun initGl() {
        val vertexShaderCode = """
            #version 300 es
            layout(location = 0) in vec2 aPosition;
            layout(location = 1) in vec2 aLocalCoord;
            uniform mat4 uProjection;
            out vec2 vLocalCoord;
            void main() {
                vLocalCoord = aLocalCoord;
                gl_Position = uProjection * vec4(aPosition, 0.0, 1.0);
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
        uColorLoc = GLES30.glGetUniformLocation(programId, "uColor")
        uHardnessLoc = GLES30.glGetUniformLocation(programId, "uHardness")
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

        // Setup Dab Quad in local tile/target coordinates
        val r = radius
        val left = centerX - r
        val right = centerX + r
        val top = centerY - r
        val bottom = centerY + r

        val quadVertices = floatArrayOf(
            left,  top,    -1f, -1f,
            right, top,     1f, -1f,
            right, bottom,  1f,  1f,
            left,  top,    -1f, -1f,
            right, bottom,  1f,  1f,
            left,  bottom, -1f,  1f
        )
        vertexBuffer.position(0)
        vertexBuffer.put(quadVertices)
        vertexBuffer.position(0)

        GLES30.glUniformMatrix4fv(uProjectionLoc, 1, false, projectionMatrix, 0)

        val red = ((colorRgb shr 16) and 0xFF) / 255.0f
        val green = ((colorRgb shr 8) and 0xFF) / 255.0f
        val blue = (colorRgb and 0xFF) / 255.0f
        GLES30.glUniform4f(uColorLoc, red, green, blue, alpha)
        GLES30.glUniform1f(uHardnessLoc, hardness.coerceIn(0.01f, 0.99f))

        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 4 * 4, vertexBuffer.position(0))

        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 4 * 4, vertexBuffer.position(2))

        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)

        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
    }

    fun releaseGl() {
        if (programId != 0) {
            GLES30.glDeleteProgram(programId)
            programId = 0
        }
    }
}
