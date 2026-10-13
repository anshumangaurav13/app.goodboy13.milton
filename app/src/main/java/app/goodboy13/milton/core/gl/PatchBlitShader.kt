package app.goodboy13.milton.core.gl

import android.opengl.GLES30
import android.opengl.Matrix
import android.util.Log
import app.goodboy13.milton.core.liquify.LiquifySession
import app.goodboy13.milton.core.selection.LayerPatch
import app.goodboy13.milton.core.selection.TransformSession
import app.goodboy13.milton.core.tile.RasterTile
import app.goodboy13.milton.core.tile.TileCoord
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * GPU shader for rendering layer-confined Free Transform quads and Liquify triangle meshes
 * directly inside the OpenGL scene at their exact layer z-depth.
 */
class PatchBlitShader {

    companion object {
        private const val TAG = "PatchBlitShader"
        const val MESH_GRID_SIZE = 128
        const val MESH_VERTS_PER_AXIS = MESH_GRID_SIZE + 1 // 129
        const val MESH_VERTEX_COUNT = MESH_VERTS_PER_AXIS * MESH_VERTS_PER_AXIS // 16,641
        const val MESH_INDEX_COUNT = MESH_GRID_SIZE * MESH_GRID_SIZE * 6 // 98,304
    }

    private var programId: Int = 0
    private var uMvpMatrixLoc: Int = -1
    private var uTextureLoc: Int = -1
    private var uOpacityLoc: Int = -1

    // Quad (Transform) resources
    private var quadVaoId: Int = 0
    private var quadPosVboId: Int = 0
    private var quadUvVboId: Int = 0
    private val quadPosBuffer: FloatBuffer = ByteBuffer.allocateDirect(6 * 2 * 4) // 6 vertices * 2 floats (x, y)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    // Mesh (Liquify) resources
    private var meshVaoId: Int = 0
    private var meshPosVboId: Int = 0
    private var meshUvVboId: Int = 0
    private var meshEboId: Int = 0
    private val meshPosBuffer: FloatBuffer = ByteBuffer.allocateDirect(MESH_VERTEX_COUNT * 2 * 4) // 16,641 * 2 floats (x, y)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

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

        programId = GlUtils.createProgram(vertexShaderCode, fragmentShaderCode)
        uMvpMatrixLoc = GLES30.glGetUniformLocation(programId, "uMvpMatrix")
        uTextureLoc = GLES30.glGetUniformLocation(programId, "uTexture")
        uOpacityLoc = GLES30.glGetUniformLocation(programId, "uOpacity")

        val vaos = IntArray(2)
        val vbos = IntArray(5)
        GLES30.glGenVertexArrays(2, vaos, 0)
        GLES30.glGenBuffers(5, vbos, 0)

        quadVaoId = vaos[0]
        quadPosVboId = vbos[0]
        quadUvVboId = vbos[1]

        meshVaoId = vaos[1]
        meshPosVboId = vbos[2]
        meshUvVboId = vbos[3]
        meshEboId = vbos[4]

        // 1. Setup Quad VAO
        GLES30.glBindVertexArray(quadVaoId)

        // Attribute 0: dynamic Position [x, y]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadPosVboId)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 6 * 2 * 4, null, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 2 * 4, 0)

        // Attribute 1: static UV [u, v] for 2 triangles: (TL, TR, BR) and (TL, BR, BL)
        val quadUvs = floatArrayOf(
            0f, 0f,
            1f, 0f,
            1f, 1f,
            0f, 0f,
            1f, 1f,
            0f, 1f
        )
        val quadUvBuf = ByteBuffer.allocateDirect(quadUvs.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(quadUvs)
        quadUvBuf.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadUvVboId)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quadUvs.size * 4, quadUvBuf, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 2 * 4, 0)

        // 2. Setup Mesh VAO
        GLES30.glBindVertexArray(meshVaoId)

        // Attribute 0: dynamic Position [x, y]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, meshPosVboId)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, MESH_VERTEX_COUNT * 2 * 4, null, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 2 * 4, 0)

        // Attribute 1: static UV [u, v] for 129x129 grid vertices
        val meshUvBuf = ByteBuffer.allocateDirect(MESH_VERTEX_COUNT * 2 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        for (r in 0 until MESH_VERTS_PER_AXIS) {
            val v = r.toFloat() / MESH_GRID_SIZE.toFloat()
            for (c in 0 until MESH_VERTS_PER_AXIS) {
                val u = c.toFloat() / MESH_GRID_SIZE.toFloat()
                meshUvBuf.put(u)
                meshUvBuf.put(v)
            }
        }
        meshUvBuf.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, meshUvVboId)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, MESH_VERTEX_COUNT * 2 * 4, meshUvBuf, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 2 * 4, 0)

        // Element Array Buffer (EBO): static triangle indices
        val meshIndexBuf = ByteBuffer.allocateDirect(MESH_INDEX_COUNT * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
        for (r in 0 until MESH_GRID_SIZE) {
            val rOffset = r * MESH_VERTS_PER_AXIS
            val nextROffset = (r + 1) * MESH_VERTS_PER_AXIS
            for (c in 0 until MESH_GRID_SIZE) {
                val iTL = (rOffset + c).toShort()
                val iTR = (rOffset + c + 1).toShort()
                val iBR = (nextROffset + c + 1).toShort()
                val iBL = (nextROffset + c).toShort()

                meshIndexBuf.put(iTL)
                meshIndexBuf.put(iTR)
                meshIndexBuf.put(iBR)
                meshIndexBuf.put(iTL)
                meshIndexBuf.put(iBR)
                meshIndexBuf.put(iBL)
            }
        }
        meshIndexBuf.position(0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, meshEboId)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, MESH_INDEX_COUNT * 2, meshIndexBuf, GLES30.GL_STATIC_DRAW)

        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)

        Log.i(TAG, "PatchBlitShader initialized successfully")
    }

    fun renderTransformPatch(
        session: TransformSession,
        patch: LayerPatch,
        layerOpacity: Float,
        mvpMatrix: FloatArray
    ) {
        if (programId == 0 || patch.glTextureId == 0) return

        val effSx = if (session.flipH) -session.scaleX else session.scaleX
        val effSy = if (session.flipV) -session.scaleY else session.scaleY
        val cosT = kotlin.math.cos(session.rotationRad)
        val sinT = kotlin.math.sin(session.rotationRad)
        val src = session.srcBounds

        val corners = floatArrayOf(
            src.left, src.top,       // 0: TL
            src.right, src.top,      // 1: TR
            src.right, src.bottom,   // 2: BR
            src.left, src.bottom     // 3: BL
        )

        // 2 triangles: (0, 1, 2) and (0, 2, 3)
        val indices = intArrayOf(0, 1, 2, 0, 2, 3)
        quadPosBuffer.position(0)
        for (idx in indices) {
            val cx = (corners[idx * 2] - session.pivotX) * effSx
            val cy = (corners[idx * 2 + 1] - session.pivotY) * effSy
            val rx = cx * cosT - cy * sinT + session.pivotX + session.translationX
            val ry = cx * sinT + cy * cosT + session.pivotY + session.translationY
            quadPosBuffer.put(rx)
            quadPosBuffer.put(ry)
        }
        quadPosBuffer.position(0)

        GLES30.glUseProgram(programId)
        GLES30.glUniformMatrix4fv(uMvpMatrixLoc, 1, false, mvpMatrix, 0)
        GLES30.glUniform1i(uTextureLoc, 0)
        GLES30.glUniform1f(uOpacityLoc, layerOpacity)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, patch.glTextureId)

        GLES30.glBindVertexArray(quadVaoId)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadPosVboId)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, 6 * 2 * 4, quadPosBuffer)

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)

        GLES30.glBindVertexArray(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    fun renderLiquifyMesh(
        session: LiquifySession,
        patch: LayerPatch,
        layerOpacity: Float,
        mvpMatrix: FloatArray
    ) {
        if (programId == 0 || patch.glTextureId == 0) return

        val mesh = session.mesh
        val worldX = mesh.worldX
        val worldY = mesh.worldY
        val count = minOf(mesh.vertexCount, MESH_VERTEX_COUNT)

        meshPosBuffer.position(0)
        for (i in 0 until count) {
            meshPosBuffer.put(worldX[i])
            meshPosBuffer.put(worldY[i])
        }
        meshPosBuffer.position(0)

        GLES30.glUseProgram(programId)
        GLES30.glUniformMatrix4fv(uMvpMatrixLoc, 1, false, mvpMatrix, 0)
        GLES30.glUniform1i(uTextureLoc, 0)
        GLES30.glUniform1f(uOpacityLoc, layerOpacity)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, patch.glTextureId)

        GLES30.glBindVertexArray(meshVaoId)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, meshPosVboId)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, count * 2 * 4, meshPosBuffer)

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        GLES30.glDrawElements(GLES30.GL_TRIANGLES, MESH_INDEX_COUNT, GLES30.GL_UNSIGNED_SHORT, 0)

        GLES30.glBindVertexArray(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    /**
     * Bakes the deformed Liquify mesh directly into the tile FBOs via hardware GPU rasterization.
     * Eliminates CPU Skia rasterization and multi-megabyte JNI memory copies, completing in < 1ms.
     */
    fun bakeLiquifyMeshIntoTiles(
        session: LiquifySession,
        patch: LayerPatch,
        tiles: List<RasterTile>
    ) {
        if (programId == 0 || patch.glTextureId == 0 || tiles.isEmpty()) return

        val mesh = session.mesh
        val worldX = mesh.worldX
        val worldY = mesh.worldY
        val count = minOf(mesh.vertexCount, MESH_VERTEX_COUNT)

        meshPosBuffer.position(0)
        for (i in 0 until count) {
            meshPosBuffer.put(worldX[i])
            meshPosBuffer.put(worldY[i])
        }
        meshPosBuffer.position(0)

        GLES30.glUseProgram(programId)
        GLES30.glUniform1i(uTextureLoc, 0)
        GLES30.glUniform1f(uOpacityLoc, 1.0f)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, patch.glTextureId)

        GLES30.glBindVertexArray(meshVaoId)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, meshPosVboId)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, count * 2 * 4, meshPosBuffer)

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        val tileMvp = FloatArray(16)
        val tileSize = TileCoord.TILE_SIZE.toFloat()

        for (tile in tiles) {
            val tx = tile.coord.tx
            val ty = tile.coord.ty

            Matrix.orthoM(
                tileMvp, 0,
                tx * tileSize, (tx + 1) * tileSize,
                (ty + 1) * tileSize, ty * tileSize,
                -1f, 1f
            )

            tile.bindFboDirect()
            GLES30.glUniformMatrix4fv(uMvpMatrixLoc, 1, false, tileMvp, 0)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, MESH_INDEX_COUNT, GLES30.GL_UNSIGNED_SHORT, 0)

            tile.hasContent = true
            tile.isDirty = true
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glBindVertexArray(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    fun releaseGl() {
        val vaos = intArrayOf(quadVaoId, meshVaoId)
        GLES30.glDeleteVertexArrays(2, vaos, 0)
        quadVaoId = 0
        meshVaoId = 0

        val vbos = intArrayOf(quadPosVboId, quadUvVboId, meshPosVboId, meshUvVboId, meshEboId)
        GLES30.glDeleteBuffers(5, vbos, 0)
        quadPosVboId = 0
        quadUvVboId = 0
        meshPosVboId = 0
        meshUvVboId = 0
        meshEboId = 0

        if (programId != 0) {
            GLES30.glDeleteProgram(programId)
            programId = 0
        }
    }
}
