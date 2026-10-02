package com.antigrav.milton.core.gl

import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * High-performance GPU shader for stamping circular anti-aliased brush dabs.
 * Supports programmable GL_EXT_shader_framebuffer_fetch with realistic subtractive
 * Oklab pigment mixing (translucent blue glazed over yellow produces green).
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

        // 1. High-fidelity fragment shader with GL_EXT_shader_framebuffer_fetch + Oklab pigment mixing
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
                float dist = length(vLocalCoord);
                if (dist > 1.0) {
                    discard;
                }

                float dabAlpha = 0.0;
                if (uBrushMode == 1) {
                    // Pencil
                    float edge = smoothstep(1.0, 0.15, dist);
                    float t1 = paperTooth(vWorldPos * 0.70);
                    float t2 = paperTooth(vWorldPos * 1.65);
                    float t3 = paperTooth(vWorldPos * 3.60);
                    float fineGrain = hash(floor(vWorldPos * 2.4));
                    float tooth = t1 * 0.40 + t2 * 0.35 + t3 * 0.25;
                    tooth = mix(tooth, fineGrain, 0.18);

                    float threshold = mix(0.56, 0.18, clamp(uPressure, 0.0, 1.0));
                    float toothBite = smoothstep(threshold - 0.18, threshold + 0.22, tooth);
                    dabAlpha = edge * toothBite * uColor.a;
                } else if (uBrushMode == 2) {
                    // Paintbrush
                    float edge = smoothstep(1.0, uHardness, dist);
                    dabAlpha = edge * uColor.a;
                } else {
                    // Pen / Eraser
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

                // Subtractive Oklab pigment mixing
                vec3 srcColor = uColor.rgb;
                if (dst.a <= 0.001) {
                    fragColor = vec4(srcColor * dabAlpha, dabAlpha);
                } else {
                    vec3 dstColor = clamp(dst.rgb / dst.a, 0.0, 1.0);
                    vec3 mixed;
                    if (distance(dstColor, srcColor) < 0.015) {
                        mixed = srcColor;
                    } else {
                        float t = clamp(dabAlpha / max(dst.a * 0.6 + dabAlpha, 0.001), 0.0, 1.0);
                        mixed = mix_pigment_oklab(dstColor, srcColor, t);
                    }
                    float outAlpha = dst.a + dabAlpha * (1.0 - dst.a);
                    fragColor = vec4(mixed * outAlpha, outAlpha);
                }
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
                    float edge = smoothstep(1.0, 0.15, dist);
                    float t1 = paperTooth(vWorldPos * 0.70);
                    float t2 = paperTooth(vWorldPos * 1.65);
                    float t3 = paperTooth(vWorldPos * 3.60);
                    float fineGrain = hash(floor(vWorldPos * 2.4));
                    float tooth = t1 * 0.40 + t2 * 0.35 + t3 * 0.25;
                    tooth = mix(tooth, fineGrain, 0.18);

                    float threshold = mix(0.56, 0.18, clamp(uPressure, 0.0, 1.0));
                    float toothBite = smoothstep(threshold - 0.18, threshold + 0.22, tooth);
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
            Log.i("DabShader", "Initialized with GL_EXT_shader_framebuffer_fetch Oklab pigment mixing")
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
