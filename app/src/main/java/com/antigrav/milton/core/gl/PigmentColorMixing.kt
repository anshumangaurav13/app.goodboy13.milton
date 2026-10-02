package com.antigrav.milton.core.gl

import android.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * High-fidelity pigment and perceptual Oklab color mixing.
 * Matches the GPU fragment shader subtractive absorption formulation:
 * Translucent blue glazed over yellow produces vibrant, rich green.
 */
object PigmentColorMixing {

    data class Vec3(val x: Float, val y: Float, val z: Float) {
        val r: Float get() = x
        val g: Float get() = y
        val b: Float get() = z
    }

    fun rgbToOklab(r: Float, g: Float, b: Float): Vec3 {
        val l = 0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b
        val m = 0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b
        val s = 0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b

        val l_ = if (l > 0f) l.pow(1f / 3f) else 0f
        val m_ = if (m > 0f) m.pow(1f / 3f) else 0f
        val s_ = if (s > 0f) s.pow(1f / 3f) else 0f

        return Vec3(
            0.2104542553f * l_ + 0.7936177850f * m_ - 0.0040720468f * s_,
            1.9779984951f * l_ - 2.4285922050f * m_ + 0.4505937099f * s_,
            0.0259040371f * l_ + 0.7827717662f * m_ - 0.8086757660f * s_
        )
    }

    fun oklabToRgb(ok: Vec3): Vec3 {
        val l_ = ok.x + 0.3963377774f * ok.y + 0.2158037573f * ok.z
        val m_ = ok.x - 0.1055613458f * ok.y - 0.0638541728f * ok.z
        val s_ = ok.x - 0.0894841775f * ok.y - 1.2914855480f * ok.z

        val l = l_ * l_ * l_
        val m = m_ * m_ * m_
        val s = s_ * s_ * s_

        val r = (+4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s).coerceIn(0f, 1f)
        val g = (-1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s).coerceIn(0f, 1f)
        val b = (-0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s).coerceIn(0f, 1f)

        return Vec3(r, g, b)
    }

    private fun toPigment(c: Vec3): Vec3 {
        val gBoost = 0.45f * max(0f, c.b - max(c.r, c.g)) * (1f - c.r)
        val bBoost = 0.35f * max(0f, c.r - max(c.g, c.b)) * (1f - c.g)
        return Vec3(c.r, min(1f, c.g + gBoost), min(1f, c.b + bBoost))
    }

    fun mixPigment(c1: Vec3, c2: Vec3, t: Float): Vec3 {
        val diffR = c1.r - c2.r
        val diffG = c1.g - c2.g
        val diffB = c1.b - c2.b
        if (diffR * diffR + diffG * diffG + diffB * diffB < 0.0004f) {
            return c2
        }

        val clampedT = t.coerceIn(0f, 1f)

        // Subtractive CMY coordinates (absorptions)
        val c1c = 1f - c1.r
        val c1m = 1f - c1.g
        val c1y = 1f - c1.b

        val c2c = 1f - c2.r
        val c2m = 1f - c2.g
        val c2y = 1f - c2.b

        val mixC = (1f - clampedT) * c1c + clampedT * c2c
        var mixM = (1f - clampedT) * c1m + clampedT * c2m
        val mixY = (1f - clampedT) * c1y + clampedT * c2y

        // Natural pigment transmission:
        // Yellow absorbs Blue, Cyan absorbs Red, both transmit Green.
        // When Cyan and Yellow are combined, Green absorption (Magenta) is suppressed,
        // provided both inputs are not already Green absorbers (e.g. Red + Blue).
        val cyOverlap = min(mixC, mixY)
        val sharedGreenAbsorption = min(c1m, c2m)
        val canFormGreen = max(0f, 1f - 2f * sharedGreenAbsorption)
        mixM *= max(0f, 1f - 1.6f * cyOverlap * canFormGreen)

        return Vec3(
            (1f - mixC).coerceIn(0f, 1f),
            (1f - mixM).coerceIn(0f, 1f),
            (1f - mixY).coerceIn(0f, 1f)
        )
    }

    fun mixColorsInt(colorDst: Int, colorSrc: Int, alpha: Float): Int {
        val c1 = Vec3(
            Color.red(colorDst) / 255f,
            Color.green(colorDst) / 255f,
            Color.blue(colorDst) / 255f
        )
        val c2 = Vec3(
            Color.red(colorSrc) / 255f,
            Color.green(colorSrc) / 255f,
            Color.blue(colorSrc) / 255f
        )
        val res = mixPigment(c1, c2, alpha)
        return Color.rgb(
            (res.r * 255f).toInt().coerceIn(0, 255),
            (res.g * 255f).toInt().coerceIn(0, 255),
            (res.b * 255f).toInt().coerceIn(0, 255)
        )
    }
}
