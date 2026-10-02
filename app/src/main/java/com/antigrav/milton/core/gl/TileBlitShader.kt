package com.antigrav.milton.core.gl

import android.opengl.GLES30
import android.util.Log
import com.antigrav.milton.core.tile.TileCoord
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * High-performance GPU shader for blitting 512x512 RasterTiles into the screen viewport.
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

    fun initGl() {
        val vertexShaderCode = """
            #version 300 es
            layout(location = 0) in vec2 aPosition; // Unit quad [0..1, 0..1]
            uniform mat4 uMvpMatrix;
            uniform vec2 uTilePos;
            uniform float uTileSize;
            out vec2 vTexCoord;
            void main() {
                vTexCoord = vec2(aPosition.x, 1.0 - aPosition.y);
                vec2 worldPos = uTilePos + aPosition * uTileSize;
                gl_Position = uMvpMatrix * vec4(worldPos, 0.0, 1.0);
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
        uTilePosLoc = GLES30.glGetUniformLocation(programId, "uTilePos")
        uTileSizeLoc = GLES30.glGetUniformLocation(programId, "uTileSize")
        uTextureLoc = GLES30.glGetUniformLocation(programId, "uTexture")

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

    fun begin(mvpMatrix: FloatArray) {
        if (programId == 0) return
        GLES30.glUseProgram(programId)
        GLES30.glUniformMatrix4fv(uMvpMatrixLoc, 1, false, mvpMatrix, 0)
        GLES30.glUniform1i(uTextureLoc, 0)
        GLES30.glUniform1f(uTileSizeLoc, TileCoord.TILE_SIZE.toFloat())
        GLES30.glBindVertexArray(vaoId)
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
