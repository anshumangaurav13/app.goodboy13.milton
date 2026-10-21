package app.goodboy13.milton.core.gl

import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * OpenGL ES 3.0 GPU Pigment Advection Smudge Shader.
 * Samples existing tile pigments from a source scratch snapshot, advects them along the
 * stroke motion vector, and blends them onto the destination tile FBO in linear photometric space.
 * Operates 100% on GPU with zero CPU glReadPixels pipeline stalls.
 */
class SmudgeShader {

    companion object {
        private const val TAG = "SmudgeShader"
    }

    private var programId: Int = 0
    private var vaoId: Int = 0
    private var vboId: Int = 0

    private var uProjectionLoc: Int = -1
    private var uCenterLoc: Int = -1
    private var uRadiusLoc: Int = -1
    private var uMotionDirLoc: Int = -1
    private var uStrengthLoc: Int = -1
    private var uHardnessLoc: Int = -1
    private var uSourceTextureLoc: Int = -1

    var isReady: Boolean = false
        private set

    fun initGl() {
        if (isReady) return

        val vertexShaderCode = """
            #version 300 es
            layout(location = 0) in vec2 aPosition; // Unit quad [-1, 1]

            uniform mat4 uProjection;
            uniform vec2 uCenter;
            uniform float uRadius;

            out vec2 vLocalCoord;
            out vec2 vTileCoord;

            void main() {
                vLocalCoord = aPosition;
                vec2 pos = uCenter + aPosition * uRadius;
                vTileCoord = pos;
                gl_Position = uProjection * vec4(pos, 0.0, 1.0);
            }
        """.trimIndent()

        val fragmentShaderCode = """
            #version 300 es
            precision highp float;

            in vec2 vLocalCoord;
            in vec2 vTileCoord;

            uniform sampler2D uSourceTexture;
            uniform vec2 uMotionDir; // in tile pixels
            uniform float uHardness;
            uniform float uStrength; // 0.0 .. 1.0

            layout(location = 0) out vec4 fragColor;

            void main() {
                float dist = length(vLocalCoord);
                if (dist > 1.0) {
                    discard;
                }

                // Smooth Gaussian-like edge falloff
                float weight = smoothstep(1.0, uHardness, dist) * uStrength;
                if (weight <= 0.001) {
                    discard;
                }

                // UV coordinate on the 512x512 tile [0, 1]
                vec2 currentUv = vTileCoord / 512.0;

                // Pull pigment backward along the stroke motion vector (advection)
                vec2 pullUv = currentUv - (uMotionDir / 512.0) * weight;

                vec4 pulled = texture(uSourceTexture, clamp(pullUv, 0.0, 1.0));
                vec4 current = texture(uSourceTexture, clamp(currentUv, 0.0, 1.0));

                if (pulled.a <= 0.001 && current.a <= 0.001) {
                    discard;
                }

                // Convert to linear photometric color space to prevent dark muddy fringes
                vec3 pulledUnpremul = pulled.rgb / max(pulled.a, 0.001);
                vec3 currentUnpremul = current.rgb / max(current.a, 0.001);

                vec3 pulledLinear = pow(clamp(pulledUnpremul, 0.0, 1.0), vec3(2.2));
                vec3 currentLinear = pow(clamp(currentUnpremul, 0.0, 1.0), vec3(2.2));

                float blendFactor = clamp(weight * 0.85, 0.0, 0.98);
                vec3 mixedLinear = mix(currentLinear, pulledLinear, blendFactor);
                vec3 outSrgb = pow(clamp(mixedLinear, 0.0, 1.0), vec3(1.0 / 2.2));

                float outA = clamp(mix(current.a, pulled.a, blendFactor), 0.0, 1.0);
                fragColor = vec4(outSrgb * outA, outA);
            }
        """.trimIndent()

        programId = GlUtils.createProgram(vertexShaderCode, fragmentShaderCode)

        uProjectionLoc = GLES30.glGetUniformLocation(programId, "uProjection")
        uCenterLoc = GLES30.glGetUniformLocation(programId, "uCenter")
        uRadiusLoc = GLES30.glGetUniformLocation(programId, "uRadius")
        uMotionDirLoc = GLES30.glGetUniformLocation(programId, "uMotionDir")
        uStrengthLoc = GLES30.glGetUniformLocation(programId, "uStrength")
        uHardnessLoc = GLES30.glGetUniformLocation(programId, "uHardness")
        uSourceTextureLoc = GLES30.glGetUniformLocation(programId, "uSourceTexture")

        // Setup unit quad [-1, 1]
        val quadVertices = floatArrayOf(
            -1f, -1f,
             1f, -1f,
            -1f,  1f,
            -1f,  1f,
             1f, -1f,
             1f,  1f
        )
        val vaos = IntArray(1)
        val vbos = IntArray(1)
        GLES30.glGenVertexArrays(1, vaos, 0)
        GLES30.glGenBuffers(1, vbos, 0)
        vaoId = vaos[0]
        vboId = vbos[0]

        GLES30.glBindVertexArray(vaoId)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)

        val buf: FloatBuffer = ByteBuffer.allocateDirect(quadVertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(quadVertices)
        buf.position(0)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quadVertices.size * 4, buf, GLES30.GL_STATIC_DRAW)

        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 2 * 4, 0)

        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        isReady = true
        Log.i(TAG, "SmudgeShader compiled and linked successfully")
    }

    fun begin(projectionMatrix: FloatArray, sourceTextureId: Int) {
        if (!isReady) return
        GLES30.glUseProgram(programId)
        GLES30.glUniformMatrix4fv(uProjectionLoc, 1, false, projectionMatrix, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sourceTextureId)
        GLES30.glUniform1i(uSourceTextureLoc, 0)

        GLES30.glBindVertexArray(vaoId)
    }

    fun renderDab(
        centerX: Float,
        centerY: Float,
        radius: Float,
        dirX: Float,
        dirY: Float,
        strength: Float,
        hardness: Float
    ) {
        if (!isReady) return
        GLES30.glUniform2f(uCenterLoc, centerX, centerY)
        GLES30.glUniform1f(uRadiusLoc, radius)
        GLES30.glUniform2f(uMotionDirLoc, dirX, dirY)
        GLES30.glUniform1f(uStrengthLoc, strength)
        GLES30.glUniform1f(uHardnessLoc, hardness)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
    }

    fun end() {
        if (!isReady) return
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
        isReady = false
    }
}
