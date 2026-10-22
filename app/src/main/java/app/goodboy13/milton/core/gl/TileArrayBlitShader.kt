package app.goodboy13.milton.core.gl

import android.opengl.GLES30
import android.util.Log
import app.goodboy13.milton.core.tile.RasterTile
import app.goodboy13.milton.core.tile.TileCoord
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Batched GPU presentation shader for blitting multiple 512x512 RasterTiles in a single draw call
 * using OpenGL ES 3.0 GL_TEXTURE_2D_ARRAY and instanced rendering (glDrawArraysInstanced).
 *
 * Replaces 40+ individual per-tile texture binds and draw calls with 1 single instanced draw call
 * per visible layer, eliminating mobile TBDR state-thrashing and reducing GPU CPU-side overhead.
 */
class TileArrayBlitShader {

    companion object {
        private const val TAG = "TileArrayBlitShader"
        private const val FLOATS_PER_INSTANCE = 5 // (x, y, size, sliceIndex, opacity)
        private const val BYTES_PER_INSTANCE = FLOATS_PER_INSTANCE * 4
        private const val INITIAL_INSTANCE_CAPACITY = 512
    }

    private var programId: Int = 0
    private var vaoId: Int = 0
    private var quadVboId: Int = 0
    private var instanceVboId: Int = 0

    private var uMvpMatrixLoc: Int = -1
    private var uFlipYLoc: Int = -1
    private var uTextureArrayLoc: Int = -1
    private var uShowBordersLoc: Int = -1

    private var instanceCapacity: Int = INITIAL_INSTANCE_CAPACITY
    private var instanceByteBuffer: ByteBuffer = ByteBuffer.allocateDirect(INITIAL_INSTANCE_CAPACITY * BYTES_PER_INSTANCE)
        .order(ByteOrder.nativeOrder())
    private var instanceFloatBuffer: FloatBuffer = instanceByteBuffer.asFloatBuffer()

    var usesFramebufferFetch: Boolean = false
        private set

    val isReady: Boolean
        get() = programId != 0 && vaoId != 0 && instanceVboId != 0

    fun initGl() {
        val vertexShaderCode = """
            #version 300 es
            layout(location = 0) in vec2 aPosition;   // Unit quad [0..1, 0..1]
            layout(location = 1) in vec2 aTilePos;     // World coordinates (left, top)
            layout(location = 2) in float aTileSize;   // World tile dimension (512.0)
            layout(location = 3) in float aSliceIndex; // Array texture layer index
            layout(location = 4) in float aOpacity;    // Layer opacity

            uniform mat4 uMvpMatrix;
            uniform int uFlipY;

            out vec3 vTexCoord; // (u, v, sliceIndex)
            out vec2 vUnitPos;
            out float vOpacity;

            void main() {
                float ty = (uFlipY == 1) ? (1.0 - aPosition.y) : aPosition.y;
                vTexCoord = vec3(aPosition.x, ty, aSliceIndex);
                vUnitPos = aPosition;
                vec2 worldPos = aTilePos + aPosition * aTileSize;
                vOpacity = aOpacity;
                gl_Position = uMvpMatrix * vec4(worldPos, 0.0, 1.0);
            }
        """.trimIndent()

        val fragmentShaderCode = """
            #version 300 es
            precision mediump float;
            precision mediump sampler2DArray;

            in vec3 vTexCoord;
            in vec2 vUnitPos;
            in float vOpacity;

            uniform sampler2DArray uTextureArray;
            uniform int uShowBorders;
            out vec4 fragColor;

            void main() {
                if (uShowBorders == 1) {
                    if (vUnitPos.x < 0.008 || vUnitPos.x > 0.992 || vUnitPos.y < 0.008 || vUnitPos.y > 0.992) {
                        fragColor = vec4(0.0, 0.9, 1.0, 1.0);
                        return;
                    }
                }

                vec4 tex = texture(uTextureArray, vTexCoord);
                if (tex.a <= 0.001) {
                    discard;
                }
                // Tile textures store premultiplied colors.
                // Multiplying by layer opacity scales premultiplied RGB and alpha equally.
                // Hardware blending (GL_ONE, GL_ONE_MINUS_SRC_ALPHA) performs exact Porter-Duff Over.
                fragColor = tex * vOpacity;
            }
        """.trimIndent()

        programId = GlUtils.createProgram(vertexShaderCode, fragmentShaderCode)
        usesFramebufferFetch = false
        Log.i(TAG, "Initialized with hardware premultiplied blend and GL_TEXTURE_2D_ARRAY")

        uMvpMatrixLoc = GLES30.glGetUniformLocation(programId, "uMvpMatrix")
        uFlipYLoc = GLES30.glGetUniformLocation(programId, "uFlipY")
        uTextureArrayLoc = GLES30.glGetUniformLocation(programId, "uTextureArray")
        uShowBordersLoc = GLES30.glGetUniformLocation(programId, "uShowBorders")

        // Setup VAO and VBOs
        val vaos = IntArray(1)
        GLES30.glGenVertexArrays(1, vaos, 0)
        vaoId = vaos[0]
        GLES30.glBindVertexArray(vaoId)

        // 1. Static Unit Quad VBO (Attribute 0: aPosition)
        val vbos = IntArray(2)
        GLES30.glGenBuffers(2, vbos, 0)
        quadVboId = vbos[0]
        instanceVboId = vbos[1]

        val unitQuad = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            0f, 1f,
            1f, 0f,
            1f, 1f
        )
        val quadBuf = ByteBuffer.allocateDirect(unitQuad.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(unitQuad)
        quadBuf.position(0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVboId)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, unitQuad.size * 4, quadBuf, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 2 * 4, 0)
        GLES30.glVertexAttribDivisor(0, 0) // Per-vertex

        // 2. Dynamic Instance VBO (Attributes 1, 2, 3, 4)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVboId)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            instanceCapacity * BYTES_PER_INSTANCE,
            null,
            GLES30.GL_DYNAMIC_DRAW
        )

        // Location 1: aTilePos (vec2, offset 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, BYTES_PER_INSTANCE, 0)
        GLES30.glVertexAttribDivisor(1, 1) // Per-instance

        // Location 2: aTileSize (float, offset 8)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 1, GLES30.GL_FLOAT, false, BYTES_PER_INSTANCE, 2 * 4)
        GLES30.glVertexAttribDivisor(2, 1)

        // Location 3: aSliceIndex (float, offset 12)
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 1, GLES30.GL_FLOAT, false, BYTES_PER_INSTANCE, 3 * 4)
        GLES30.glVertexAttribDivisor(3, 1)

        // Location 4: aOpacity (float, offset 16)
        GLES30.glEnableVertexAttribArray(4)
        GLES30.glVertexAttribPointer(4, 1, GLES30.GL_FLOAT, false, BYTES_PER_INSTANCE, 4 * 4)
        GLES30.glVertexAttribDivisor(4, 1)

        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    fun begin(mvpMatrix: FloatArray, flipY: Boolean, textureArrayId: Int, showBorders: Boolean = false) {
        if (!isReady) return

        GLES30.glUseProgram(programId)
        GLES30.glUniformMatrix4fv(uMvpMatrixLoc, 1, false, mvpMatrix, 0)
        GLES30.glUniform1i(uFlipYLoc, if (flipY) 1 else 0)
        GLES30.glUniform1i(uShowBordersLoc, if (showBorders) 1 else 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, textureArrayId)
        GLES30.glUniform1i(uTextureArrayLoc, 0)

        GLES30.glBindVertexArray(vaoId)

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
        GLES30.glBlendFuncSeparate(
            GLES30.GL_ONE,
            GLES30.GL_ONE_MINUS_SRC_ALPHA,
            GLES30.GL_ONE,
            GLES30.GL_ONE_MINUS_SRC_ALPHA
        )
    }

    fun renderTilesBatch(
        tiles: List<RasterTile>,
        opacity: Float,
        worldTileSize: Float = TileCoord.TILE_SIZE.toFloat()
    ) {
        val count = tiles.size
        if (count <= 0 || !isReady) return

        ensureCapacity(count)
        instanceFloatBuffer.position(0)

        for (i in 0 until count) {
            val tile = tiles[i]
            val left = tile.coord.tx * worldTileSize
            val top = tile.coord.ty * worldTileSize
            instanceFloatBuffer.put(left)
            instanceFloatBuffer.put(top)
            instanceFloatBuffer.put(worldTileSize)
            instanceFloatBuffer.put(tile.sliceIndex.toFloat())
            instanceFloatBuffer.put(opacity)
        }
        instanceFloatBuffer.position(0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVboId)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, count * BYTES_PER_INSTANCE, instanceByteBuffer)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        // Single draw call for all tiles in this batch
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLES, 0, 6, count)
    }

    /**
     * Instanced draw call directly using native Rust-generated Flat Render Packet buffer.
     * Bypasses all JVM looping, FloatBuffer puts, and object iterations.
     */
    fun renderPacketDirect(directBuf: ByteBuffer, count: Int) {
        if (count <= 0 || !isReady) return
        ensureCapacity(count)
        directBuf.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVboId)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, count * BYTES_PER_INSTANCE, directBuf)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLES, 0, 6, count)
    }

    fun end() {
        if (!isReady) return
        GLES30.glBindVertexArray(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, 0)
        if (usesFramebufferFetch) {
            GLES30.glEnable(GLES30.GL_BLEND)
        }
    }

    private fun ensureCapacity(requiredCount: Int) {
        if (requiredCount <= instanceCapacity) return

        var newCapacity = instanceCapacity * 2
        while (newCapacity < requiredCount) {
            newCapacity *= 2
        }

        instanceCapacity = newCapacity
        instanceByteBuffer = ByteBuffer.allocateDirect(instanceCapacity * BYTES_PER_INSTANCE)
            .order(ByteOrder.nativeOrder())
        instanceFloatBuffer = instanceByteBuffer.asFloatBuffer()

        if (instanceVboId != 0) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVboId)
            GLES30.glBufferData(
                GLES30.GL_ARRAY_BUFFER,
                instanceCapacity * BYTES_PER_INSTANCE,
                null,
                GLES30.GL_DYNAMIC_DRAW
            )
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        }
    }

    fun releaseGl() {
        if (vaoId != 0) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(vaoId), 0)
            vaoId = 0
        }
        if (quadVboId != 0) {
            GLES30.glDeleteBuffers(1, intArrayOf(quadVboId), 0)
            quadVboId = 0
        }
        if (instanceVboId != 0) {
            GLES30.glDeleteBuffers(1, intArrayOf(instanceVboId), 0)
            instanceVboId = 0
        }
        if (programId != 0) {
            GLES30.glDeleteProgram(programId)
            programId = 0
        }
    }
}
