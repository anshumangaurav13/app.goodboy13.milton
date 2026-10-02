package com.antigrav.milton.core.gl

import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * High-performance GPU shader for stamping circular anti-aliased brush dabs.
 * Supports programmable GL_EXT_shader_framebuffer_fetch with intra-stroke
 * accumulation to fuse overlapping dabs into a continuous stroke ribbon.
 * Gracefully falls back to standard blending if framebuffer fetch is unavailable.
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
    private var uBrushModeLoc: Int = -1
    private var uPressureLoc: Int = -1
    private var uWorldOffsetLoc: Int = -1
    private var uIsEraserLoc: Int = -1

    var usesFramebufferFetch: Boolean = false
        private set

    fun initGl() {
        val vertexShaderCode = """
            #version 300 es
            layout(location = 0) in vec2 aPosition; // Unit quad [-1..1, -1..1]
            uniform mat4 uProjection;
            uniform vec2 uCenter;
            uniform float uRadius;
            uniform vec2 uWorldOffset;
            out vec2 vLocalCoord;
            out vec2 vWorldPos;
            void main() {
                vLocalCoord = aPosition;
                vec2 worldPos = uCenter + aPosition * uRadius;
                vWorldPos = uWorldOffset + worldPos;
                gl_Position = uProjection * vec4(worldPos, 0.0, 1.0);
            }
        """.trimIndent()

        // 1. High-fidelity fragment shader with GL_EXT_shader_framebuffer_fetch
        val fragmentShaderCodeFetch = """
            #version 300 es
            #extension GL_EXT_shader_framebuffer_fetch : require
            precision highp float;
            in vec2 vLocalCoord;
            in vec2 vWorldPos;

            uniform vec4 uColor;
            uniform float uHardness;
            uniform int uBrushMode; // 0 = Pen, 1 = Pencil, 2 = Paintbrush
            uniform float uPressure;
            uniform int uIsEraser;

            layout(location = 0) inout vec4 fragColor;

            float hash(vec2 p) {
                vec3 p3 = fract(vec3(p.xyx) * 0.1031);
                p3 += dot(p3, p3.yzx + 33.33);
                return fract((p3.x + p3.y) * p3.z);
            }

            float paperTooth(vec2 p) {
                vec2 i = floor(p);
                vec2 f = fract(p);
                f = f * f * (3.0 - 2.0 * f);
                float a = hash(i);
                float b = hash(i + vec2(1.0, 0.0));
                float c = hash(i + vec2(0.0, 1.0));
                float d = hash(i + vec2(1.0, 1.0));
                return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
            }

            void main() {
                float dist = length(vLocalCoord);
                if (dist > 1.0) {
                    discard;
                }

                float dabAlpha = 0.0;
                if (uBrushMode == 1) {
                    // Pencil: continuous canvas weave & paper tooth matching canvas resolution
                    vec2 canvasCoord = vWorldPos;

                    // 1. Canvas weave pattern (~4.2 canvas pixels per thread cycle)
                    vec2 threadGrid = canvasCoord * 0.24;
                    float warp = sin(threadGrid.x * 3.14159265);
                    float weft = sin(threadGrid.y * 3.14159265);
                    float crossWeave = warp * weft;
                    float threadCrown = (cos(threadGrid.x * 6.2831853) + cos(threadGrid.y * 6.2831853)) * 0.25;
                    float canvasPattern = clamp(0.5 + 0.48 * crossWeave + 0.22 * threadCrown, 0.0, 1.0);

                    // 2. Continuous multi-scale paper fibers (cold-press cellulose undulations)
                    float fiberLarge = paperTooth(canvasCoord * 0.12);
                    float fiberMed = paperTooth(canvasCoord * 0.24 + vec2(17.3, 43.7));
                    float fiberFine = paperTooth(canvasCoord * 0.44 + vec2(53.1, 29.7));

                    // 3. Composite paper/canvas surface tooth with pronounced bite
                    float tooth = canvasPattern * 0.35 + fiberLarge * 0.20 + fiberMed * 0.25 + fiberFine * 0.20;

                    // 4. Responsive graphite transfer: sharper tooth bite with prominent paper grain
                    float p = clamp(uPressure, 0.0, 1.0);
                    float threshold = mix(0.52, 0.16, p);
                    float toothBite = smoothstep(threshold - 0.04, threshold + 0.09, tooth);

                    // Brush tip radial feather
                    float edge = smoothstep(1.0, 0.15, dist);
                    dabAlpha = edge * toothBite * uColor.a;
                } else if (uBrushMode == 2) {
                    // Paintbrush: soft feathered edge
                    float edge = smoothstep(1.0, uHardness, dist);
                    dabAlpha = edge * uColor.a;
                } else {
                    // Pen / Eraser: firm anti-aliased edge
                    dabAlpha = smoothstep(1.0, uHardness, dist) * uColor.a;
                }

                if (dabAlpha <= 0.001) {
                    discard;
                }

                vec4 dst = fragColor;

                // Eraser mode directly attenuates tile
                if (uIsEraser == 1) {
                    fragColor = dst * (1.0 - dabAlpha);
                    return;
                }

                vec3 srcColor = uColor.rgb;

                // Empty tile / front-buffer pixel: direct premultiplied stamp
                if (dst.a <= 0.002) {
                    fragColor = vec4(srcColor * dabAlpha, dabAlpha);
                    return;
                }

                // Check if destination pixel was already painted with the current stroke's color.
                // Cross-multiplication test (|dst.rgb - srcColor * dst.a|) avoids 8-bit division noise at feathered edges.
                vec3 premulExpected = srcColor * dst.a;
                vec3 colorDiff = abs(dst.rgb - premulExpected);
                bool isSameStrokeColor = all(lessThan(colorDiff, vec3(0.045)));

                float outAlpha = dst.a + dabAlpha * (1.0 - dst.a);

                if (isSameStrokeColor) {
                    if (uBrushMode == 1) {
                        // Pencil: take max intra-stroke alpha so graphite preserves paper tooth peaks
                        // without compounding into a solid dense smudge along the stroke path
                        float pencilAlpha = max(dst.a, dabAlpha);
                        fragColor = vec4(srcColor * pencilAlpha, pencilAlpha);
                        return;
                    }
                    // Intra-stroke accumulation: smooth continuous stroke geometry with zero scallop rings
                    fragColor = vec4(srcColor * outAlpha, outAlpha);
                    return;
                }

                // Standard Porter-Duff Over digital art blending
                vec3 outPremul = srcColor * dabAlpha + dst.rgb * (1.0 - dabAlpha);
                fragColor = vec4(clamp(outPremul, 0.0, 1.0), clamp(outAlpha, 0.0, 1.0));
            }
        """.trimIndent()

        // 2. Standard fallback fragment shader without framebuffer fetch
        val fragmentShaderCodeFallback = """
            #version 300 es
            precision highp float;
            in vec2 vLocalCoord;
            in vec2 vWorldPos;

            uniform vec4 uColor;
            uniform float uHardness;
            uniform int uBrushMode;
            uniform float uPressure;
            uniform int uIsEraser;

            out vec4 fragColor;

            float hash(vec2 p) {
                vec3 p3 = fract(vec3(p.xyx) * 0.1031);
                p3 += dot(p3, p3.yzx + 33.33);
                return fract((p3.x + p3.y) * p3.z);
            }

            float paperTooth(vec2 p) {
                vec2 i = floor(p);
                vec2 f = fract(p);
                f = f * f * (3.0 - 2.0 * f);
                float a = hash(i);
                float b = hash(i + vec2(1.0, 0.0));
                float c = hash(i + vec2(0.0, 1.0));
                float d = hash(i + vec2(1.0, 1.0));
                return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
            }

            void main() {
                float dist = length(vLocalCoord);
                if (dist > 1.0) {
                    discard;
                }

                float dabAlpha = 0.0;
                if (uBrushMode == 1) {
                    // Pencil: continuous canvas weave & paper tooth matching canvas resolution
                    vec2 canvasCoord = vWorldPos;

                    // 1. Canvas weave pattern (~4.2 canvas pixels per thread cycle)
                    vec2 threadGrid = canvasCoord * 0.24;
                    float warp = sin(threadGrid.x * 3.14159265);
                    float weft = sin(threadGrid.y * 3.14159265);
                    float crossWeave = warp * weft;
                    float threadCrown = (cos(threadGrid.x * 6.2831853) + cos(threadGrid.y * 6.2831853)) * 0.25;
                    float canvasPattern = clamp(0.5 + 0.48 * crossWeave + 0.22 * threadCrown, 0.0, 1.0);

                    // 2. Continuous multi-scale paper fibers (cold-press cellulose undulations)
                    float fiberLarge = paperTooth(canvasCoord * 0.12);
                    float fiberMed = paperTooth(canvasCoord * 0.24 + vec2(17.3, 43.7));
                    float fiberFine = paperTooth(canvasCoord * 0.44 + vec2(53.1, 29.7));

                    // 3. Composite paper/canvas surface tooth with pronounced bite
                    float tooth = canvasPattern * 0.35 + fiberLarge * 0.20 + fiberMed * 0.25 + fiberFine * 0.20;

                    // 4. Responsive graphite transfer: sharper tooth bite with prominent paper grain
                    float p = clamp(uPressure, 0.0, 1.0);
                    float threshold = mix(0.52, 0.16, p);
                    float toothBite = smoothstep(threshold - 0.04, threshold + 0.09, tooth);

                    // Brush tip radial feather
                    float edge = smoothstep(1.0, 0.15, dist);
                    dabAlpha = edge * toothBite * uColor.a;
                } else if (uBrushMode == 2) {
                    float edge = smoothstep(1.0, uHardness, dist);
                    dabAlpha = edge * uColor.a;
                } else {
                    dabAlpha = smoothstep(1.0, uHardness, dist) * uColor.a;
                }

                if (dabAlpha <= 0.001) {
                    discard;
                }

                fragColor = vec4(uColor.rgb * dabAlpha, dabAlpha);
            }
        """.trimIndent()

        programId = GlUtils.tryCreateProgram(vertexShaderCode, fragmentShaderCodeFetch)
        if (programId != 0) {
            usesFramebufferFetch = true
            Log.i("DabShader", "Initialized with GL_EXT_shader_framebuffer_fetch")
        } else {
            programId = GlUtils.createProgram(vertexShaderCode, fragmentShaderCodeFallback)
            usesFramebufferFetch = false
            Log.i("DabShader", "Initialized with standard fallback shader")
        }

        uProjectionLoc = GLES30.glGetUniformLocation(programId, "uProjection")
        uCenterLoc = GLES30.glGetUniformLocation(programId, "uCenter")
        uRadiusLoc = GLES30.glGetUniformLocation(programId, "uRadius")
        uColorLoc = GLES30.glGetUniformLocation(programId, "uColor")
        uHardnessLoc = GLES30.glGetUniformLocation(programId, "uHardness")
        uBrushModeLoc = GLES30.glGetUniformLocation(programId, "uBrushMode")
        uPressureLoc = GLES30.glGetUniformLocation(programId, "uPressure")
        uWorldOffsetLoc = GLES30.glGetUniformLocation(programId, "uWorldOffset")
        uIsEraserLoc = GLES30.glGetUniformLocation(programId, "uIsEraser")

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
        brushMode: Int = 0,
        pressure: Float = 0.5f,
        worldOffsetX: Float = 0f,
        worldOffsetY: Float = 0f,
        projectionMatrix: FloatArray,
        isEraser: Boolean = false
    ) {
        if (programId == 0) return

        GLES30.glUseProgram(programId)

        GLES30.glUniformMatrix4fv(uProjectionLoc, 1, false, projectionMatrix, 0)
        GLES30.glUniform2f(uCenterLoc, centerX, centerY)
        GLES30.glUniform1f(uRadiusLoc, radius)
        GLES30.glUniform2f(uWorldOffsetLoc, worldOffsetX, worldOffsetY)

        val red = ((colorRgb shr 16) and 0xFF) / 255.0f
        val green = ((colorRgb shr 8) and 0xFF) / 255.0f
        val blue = (colorRgb and 0xFF) / 255.0f
        GLES30.glUniform4f(uColorLoc, red, green, blue, alpha)
        GLES30.glUniform1f(uHardnessLoc, hardness.coerceIn(0.01f, 0.99f))
        GLES30.glUniform1i(uBrushModeLoc, brushMode)
        GLES30.glUniform1f(uPressureLoc, pressure.coerceIn(0.01f, 1.0f))
        if (uIsEraserLoc != -1) {
            GLES30.glUniform1i(uIsEraserLoc, if (isEraser) 1 else 0)
        }

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
