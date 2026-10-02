package com.antigrav.milton.core.gl

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Shader for blitting 512x512 RasterTiles into the screen viewport backbuffer.
 */
class TileBlitShader {

    private var programId: Int = 0
    private var uMvpMatrixLoc: Int = -1
    private var uTextureLoc: Int = -1

    private val vertexBuffer: FloatBuffer

    init {
        // Dynamic buffer for tile quad: [posX, posY, texU, texV]
        val initialData = FloatArray(6 * 4)
        vertexBuffer = ByteBuffer.allocateDirect(initialData.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
    }

    fun initGl() {
        val vertexShaderCode = """
            #version 300 es
            layout(location = 0) in vec2 aPosition;
            layout(location = 1) in vec2 aTexCoord;
            uniform mat4 uMvpMatrix;
            out vec2 vTexCoord;
            void main() {
                vTexCoord = aTexCoord;
                gl_Position = uMvpMatrix * vec4(aPosition, 0.0, 1.0);
            }
        """.trimIndent()

        val fragmentShaderCode = """
            #version 300 es
            precision mediump float;
            in vec2 vTexCoord;
            uniform sampler2D uTexture;
            out vec4 fragColor;
            void main() {
                vec4 tex = texture(uTexture, vTexCoord);
                if (tex.a <= 0.001) {
                    discard;
                }
                fragColor = tex;
            }
        """.trimIndent()

        programId = GlUtils.createProgram(vertexShaderCode, fragmentShaderCode)
        uMvpMatrixLoc = GLES30.glGetUniformLocation(programId, "uMvpMatrix")
        uTextureLoc = GLES30.glGetUniformLocation(programId, "uTexture")
    }

    fun begin(mvpMatrix: FloatArray) {
        if (programId == 0) return
        GLES30.glUseProgram(programId)
        GLES30.glUniformMatrix4fv(uMvpMatrixLoc, 1, false, mvpMatrix, 0)
        GLES30.glUniform1i(uTextureLoc, 0)
    }

    fun renderTile(worldLeft: Float, worldTop: Float, worldRight: Float, worldBottom: Float, textureId: Int) {
        if (programId == 0 || textureId == 0) return

        val quad = floatArrayOf(
            worldLeft,  worldTop,    0f, 0f,
            worldRight, worldTop,    1f, 0f,
            worldRight, worldBottom, 1f, 1f,
            worldLeft,  worldTop,    0f, 0f,
            worldRight, worldBottom, 1f, 1f,
            worldLeft,  worldBottom, 0f, 1f
        )

        vertexBuffer.position(0)
        vertexBuffer.put(quad)
        vertexBuffer.position(0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)

        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 4 * 4, vertexBuffer.position(0))

        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 4 * 4, vertexBuffer.position(2))

        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)

        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    fun releaseGl() {
        if (programId != 0) {
            GLES30.glDeleteProgram(programId)
            programId = 0
        }
    }
}
