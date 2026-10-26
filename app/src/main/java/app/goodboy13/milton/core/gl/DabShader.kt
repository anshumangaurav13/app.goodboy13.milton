package app.goodboy13.milton.core.gl

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
    private var uGrainTypeLoc: Int = -1
    private var uGrainScaleLoc: Int = -1
    private var uGrainDepthLoc: Int = -1

    var grainType: Int = 1
    var grainScale: Float = 1.0f
    var grainDepth: Float = 0.60f

    var usesFramebufferFetch: Boolean = false
        private set

    fun setGrainParams(type: Int, scale: Float, depth: Float) {
        grainType = type
        grainScale = scale
        grainDepth = depth
    }

    fun initGl() {
        val vertexShaderCode = """
            #version 300 es
            precision highp float;
            precision highp int;
            layout(location = 0) in vec2 aPosition; // Unit quad [-1..1, -1..1]
            uniform mat4 uProjection;
            uniform vec2 uCenter;
            uniform float uRadius;
            uniform vec2 uWorldOffset;
            out vec2 vLocalCoord;
            out vec2 vWorldPos;
            out vec2 vDabCenter;
            out float vRadius;
            void main() {
                vDabCenter = uWorldOffset + uCenter;
                vRadius = uRadius;
                float quadRadius = uRadius + 2.0;
                vLocalCoord = aPosition * (quadRadius / uRadius);
                vec2 worldPos = uCenter + aPosition * quadRadius;
                vWorldPos = uWorldOffset + worldPos;
                gl_Position = uProjection * vec4(worldPos, 0.0, 1.0);
            }
        """.trimIndent()

        // 1. High-fidelity fragment shader with GL_EXT_shader_framebuffer_fetch
        val fragmentShaderCodeFetch = """
            #version 300 es
            #extension GL_EXT_shader_framebuffer_fetch : require
            precision highp float;
            precision highp int;
            in vec2 vLocalCoord;
            in vec2 vWorldPos;
            in vec2 vDabCenter;
            in float vRadius;

            uniform vec4 uColor;
            uniform float uHardness;
            uniform int uBrushMode; // 0 = Pen, 1 = Pencil, 2 = Paintbrush
            uniform float uPressure;
            uniform int uIsEraser;
            uniform int uGrainType;
            uniform float uGrainScale;
            uniform float uGrainDepth;

            layout(location = 0) inout vec4 fragColor;

$GRAPHITE_HASH_FUNCTIONS_GLSL

            void main() {
                vec2 pixelCoord = floor(vWorldPos + vec2(0.01));
                float dist = length(vLocalCoord);
                if (uBrushMode == 1) {
                    vec2 pixelCenter = pixelCoord + vec2(0.5);
                    dist = length(pixelCenter - vDabCenter) / vRadius;
                }
                if (dist > 1.0) {
                    discard;
                }

                float dabAlpha = 0.0;
                if (uBrushMode == 1) {


$GRAPHITE_PHYSICS_GLSL
                } else if (uBrushMode == 2) {
                    // Paintbrush: realistic multi-filament bristle dynamics & wet-edge capillary ridge
                    float edge = smoothstep(1.0, uHardness, dist);
                    float bristleAngle = atan(vLocalCoord.y, vLocalCoord.x);
                    float bristleNoise = sin(bristleAngle * 16.0 + dist * 10.0) * 0.12 + cos(bristleAngle * 32.0 - dist * 18.0) * 0.08;
                    float wetEdge = 1.0 + 0.30 * smoothstep(0.65, 0.95, dist);
                    dabAlpha = clamp(edge * (1.0 + bristleNoise) * wetEdge * uColor.a, 0.0, 1.0);
                } else if (uBrushMode == 3) {
                    // Smudge tool: smooth quadratic/Gaussian pigment advection profile
                    float edge = smoothstep(1.0, uHardness, dist);
                    dabAlpha = edge * uColor.a * uPressure;
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

                // BrushMode 3: Pigment Advection Smudge with Leonardo's linear photometric blending (linearHack)
                if (uBrushMode == 3) {
                    if (dst.a <= 0.002) {
                        // Empty tile / background: deposit carried pigment with pressure falloff
                        vec3 srcLinear = pow(clamp(uColor.rgb, 0.0, 1.0), vec3(2.2));
                        vec3 outSrgb = pow(srcLinear, vec3(1.0 / 2.2));
                        float depositA = dabAlpha * 0.40;
                        fragColor = vec4(outSrgb * depositA, depositA);
                        return;
                    }

                    // Convert destination and carried pigment to linear photometric space
                    vec3 dstUnpremul = dst.rgb / max(dst.a, 0.001);
                    vec3 dstLinear = pow(clamp(dstUnpremul, 0.0, 1.0), vec3(2.2));
                    vec3 srcLinear = pow(clamp(uColor.rgb, 0.0, 1.0), vec3(2.2));

                    // Advect & physically mix pigments
                    float blendWeight = clamp(dabAlpha * 0.65, 0.0, 0.95);
                    vec3 mixedLinear = mix(dstLinear, srcLinear, blendWeight);
                    vec3 outSrgb = pow(clamp(mixedLinear, 0.0, 1.0), vec3(1.0 / 2.2));

                    float outA = clamp(dst.a + (uColor.a - dst.a) * dabAlpha * 0.25, 0.0, 1.0);
                    fragColor = vec4(outSrgb * outA, outA);
                    return;
                }

                vec3 srcColor = uColor.rgb;

                // Empty tile / front-buffer pixel: direct premultiplied stamp
                if (dst.a <= 0.002) {
                    fragColor = vec4(srcColor * dabAlpha, dabAlpha);
                    return;
                }

                // Check if destination pixel was already painted with the current stroke's color.
                vec3 premulExpected = srcColor * dst.a;
                vec3 colorDiff = abs(dst.rgb - premulExpected);
                bool isSameStrokeColor = all(lessThan(colorDiff, vec3(0.045)));

                float outAlpha = dst.a + dabAlpha * (1.0 - dst.a);

                if (isSameStrokeColor) {
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
            precision highp int;
            in vec2 vLocalCoord;
            in vec2 vWorldPos;
            in vec2 vDabCenter;
            in float vRadius;

            uniform vec4 uColor;
            uniform float uHardness;
            uniform int uBrushMode;
            uniform float uPressure;
            uniform int uIsEraser;
            uniform int uGrainType;
            uniform float uGrainScale;
            uniform float uGrainDepth;

            out vec4 fragColor;

$GRAPHITE_HASH_FUNCTIONS_GLSL

            void main() {
                vec2 pixelCoord = floor(vWorldPos + vec2(0.01));
                float dist = length(vLocalCoord);
                if (uBrushMode == 1) {
                    vec2 pixelCenter = pixelCoord + vec2(0.5);
                    dist = length(pixelCenter - vDabCenter) / vRadius;
                }
                if (dist > 1.0) {
                    discard;
                }

                float dabAlpha = 0.0;
                if (uBrushMode == 1) {


$GRAPHITE_PHYSICS_GLSL
                } else if (uBrushMode == 2) {
                    float edge = smoothstep(1.0, uHardness, dist);
                    float bristleAngle = atan(vLocalCoord.y, vLocalCoord.x);
                    float bristleNoise = sin(bristleAngle * 16.0 + dist * 10.0) * 0.12 + cos(bristleAngle * 32.0 - dist * 18.0) * 0.08;
                    float wetEdge = 1.0 + 0.30 * smoothstep(0.65, 0.95, dist);
                    dabAlpha = clamp(edge * (1.0 + bristleNoise) * wetEdge * uColor.a, 0.0, 1.0);
                } else if (uBrushMode == 3) {
                    float edge = smoothstep(1.0, uHardness, dist);
                    dabAlpha = edge * uColor.a * uPressure;
                } else {
                    dabAlpha = smoothstep(1.0, uHardness, dist) * uColor.a;
                }

                if (dabAlpha <= 0.001) {
                    discard;
                }

                vec3 srcColor = uColor.rgb;

                fragColor = vec4(srcColor * dabAlpha, dabAlpha);
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
        uGrainTypeLoc = GLES30.glGetUniformLocation(programId, "uGrainType")
        uGrainScaleLoc = GLES30.glGetUniformLocation(programId, "uGrainScale")
        uGrainDepthLoc = GLES30.glGetUniformLocation(programId, "uGrainDepth")

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

    fun beginBatch(projectionMatrix: FloatArray) {
        if (programId == 0) return
        GLES30.glUseProgram(programId)
        GLES30.glUniformMatrix4fv(uProjectionLoc, 1, false, projectionMatrix, 0)
        if (uGrainTypeLoc != -1) GLES30.glUniform1i(uGrainTypeLoc, grainType)
        if (uGrainScaleLoc != -1) GLES30.glUniform1f(uGrainScaleLoc, grainScale)
        if (uGrainDepthLoc != -1) GLES30.glUniform1f(uGrainDepthLoc, grainDepth)
        GLES30.glBindVertexArray(vaoId)
    }

    fun renderDabFast(
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
        isEraser: Boolean = false
    ) {
        if (programId == 0) return

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

        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
    }

    fun endBatch() {
        GLES30.glBindVertexArray(0)
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

        beginBatch(projectionMatrix)
        renderDabFast(
            centerX = centerX,
            centerY = centerY,
            radius = radius,
            colorRgb = colorRgb,
            alpha = alpha,
            hardness = hardness,
            brushMode = brushMode,
            pressure = pressure,
            worldOffsetX = worldOffsetX,
            worldOffsetY = worldOffsetY,
            isEraser = isEraser
        )
        endBatch()
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

    companion object {
        private const val GRAPHITE_HASH_FUNCTIONS_GLSL = """
            // 2D pseudo-random hash generator
            float hash12(vec2 p) {
                vec3 p3 = fract(vec3(p.xyx) * 0.1031);
                p3 += dot(p3, p3.yzx + 33.33);
                return fract((p3.x + p3.y) * p3.z);
            }

            // Smooth 2D value noise with cubic Hermite interpolation
            float valueNoise(vec2 p) {
                vec2 i = floor(p);
                vec2 f = fract(p);
                vec2 u = f * f * (3.0 - 2.0 * f);
                float a = hash12(i);
                float b = hash12(i + vec2(1.0, 0.0));
                float c = hash12(i + vec2(0.0, 1.0));
                float d = hash12(i + vec2(1.0, 1.0));
                return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
            }

            // Multi-octave procedural paper tooth evaluation (world-space stationary coordinates)
            // 0: Smooth (Hot Press), 1: Medium (Cold Press), 2: Rough (Charcoal), 3: Canvas (Linen), 4: Laid (Ribbed Wire)
            float evaluatePaperGrain(vec2 worldPos, int grainType, float grainScale) {
                vec2 p = worldPos / max(grainScale * 3.5, 0.5);

                if (grainType == 0) {
                    // Smooth (Hot Press): delicate high-frequency micro-grain
                    float n1 = valueNoise(p * 2.2);
                    float n2 = valueNoise(p * 4.5);
                    return clamp(n1 * 0.65 + n2 * 0.35, 0.0, 1.0);
                } else if (grainType == 1) {
                    // Medium (Cold Press): classic natural paper tooth
                    float n1 = valueNoise(p * 0.7);
                    float n2 = valueNoise(p * 1.5);
                    float n3 = valueNoise(p * 3.2);
                    return clamp(n1 * 0.50 + n2 * 0.35 + n3 * 0.15, 0.0, 1.0);
                } else if (grainType == 2) {
                    // Rough (Charcoal / Torchon): granulating ridges and deep hollow pockets
                    float n1 = valueNoise(p * 0.4);
                    float n2 = valueNoise(p * 0.9);
                    float n3 = valueNoise(p * 2.0);
                    float base = n1 * 0.55 + n2 * 0.35 + n3 * 0.10;
                    return smoothstep(0.20, 0.80, base);
                } else if (grainType == 3) {
                    // Canvas (Linen Weave): orthogonal woven warp-and-weft fabric
                    vec2 cp = p * 0.45;
                    cp += vec2(valueNoise(cp * 0.4), valueNoise(cp * 0.4 + vec2(12.3, 45.6))) * 0.25;
                    float warp = sin(cp.x * 6.283185);
                    float weft = sin(cp.y * 6.283185);
                    float weave = (warp * sin(cp.y * 3.141592) + weft * cos(cp.x * 3.141592)) * 0.5 + 0.5;
                    float micro = valueNoise(p * 1.6) * 0.20;
                    return clamp(weave * 0.80 + micro, 0.0, 1.0);
                } else if (grainType == 4) {
                    // Laid (Ribbed Wire): antique wire laid lines and vertical chain marks
                    vec2 lp = p * 0.45;
                    lp.x += valueNoise(lp * 0.3) * 0.18;
                    float laid = sin(lp.y * 4.712389) * 0.5 + 0.5;
                    float chain = smoothstep(0.85, 1.0, cos(lp.x * 0.628318) * 0.5 + 0.5);
                    float pulp = valueNoise(p * 1.0) * 0.25;
                    return clamp(laid * 0.60 + chain * 0.25 + pulp, 0.0, 1.0);
                }

                // Default Cold Press fallback
                return valueNoise(p);
            }

            // High-frequency stochastic micro-particle deposition hash per dab stamp
            float particleHash(vec2 p, vec2 dabCenter) {
                ivec2 ip = ivec2(floor(p));
                ivec2 ic = ivec2(floor(dabCenter * 8.0));
                uint h = (uint(ip.x) * 1664525u + uint(ip.y) * 1013904223u + uint(ic.x) * 374761393u + uint(ic.y) * 668265263u) ^ 0x6a09e667u;
                h = (h ^ (h >> 13u)) * 2246822519u;
                return float((h ^ (h >> 16u)) & 0xffffu) / 65535.0;
            }
        """

        private const val GRAPHITE_PHYSICS_GLSL = """
                    // 1. Soft lead contact edge falloff (smooth feathering instead of harsh cutoff)
                    float leadEdge = smoothstep(1.0, 0.50, dist);

                    // 2. Multi-octave procedural paper tooth (fixed to world coordinates)
                    float rawTooth = evaluatePaperGrain(vWorldPos, uGrainType, uGrainScale);
                    float pRand = particleHash(pixelCoord, vDabCenter);

                    // 3. Stylus pressure modulates fiber penetration & burnishing:
                    // Low pressure: graphite catches mostly on peaks (strong tooth)
                    // High pressure: graphite burnishes and compresses into valleys (solid shading)
                    float pressureNorm = clamp(uPressure, 0.0, 1.0);
                    float toothMod = (rawTooth - 0.5) * 2.0;
                    float contrast = mix(0.65, 0.20, pressureNorm) * uGrainDepth;
                    float toothCoverage = clamp(0.70 + toothMod * contrast, 0.05, 1.0);

                    // 4. Delicate micro-particle grain variance
                    float microGrain = 0.88 + 0.24 * pRand;

                    // 5. Dense, continuous graphite deposition (responsive to lead hardness & stylus pressure)
                    float leadDensity = (0.24 + 0.36 * pressureNorm);
                    dabAlpha = clamp(leadEdge * toothCoverage * microGrain * leadDensity * uColor.a, 0.0, 1.0);
        """
    }
}
