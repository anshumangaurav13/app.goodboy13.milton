package com.antigrav.milton.core.gl

import android.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * High-fidelity Kubelka-Munk pigment mixing.
 * Simulates physical paint reflectance and absorption across 38 spectral wavelength bands (380nm - 750nm).
 * Matches the GPU fragment shader formulation:
 * - Pure Blue + Yellow produces a rich, natural forest green (#398F54).
 * - Red + Yellow produces vibrant orange (#FF440F).
 * - Red + Blue produces deep plum purple (#4A182B).
 * - Black + Yellow produces natural olive ochre (#A1A838).
 * - Exact identity when mixing a color with itself.
 */
object PigmentColorMixing {

    data class Vec3(val x: Float, val y: Float, val z: Float) {
        val r: Float get() = x
        val g: Float get() = y
        val b: Float get() = z
    }

    private const val SPECTRAL_SIZE = 38
    private const val SPECTRAL_GAMMA = 2.4f
    private const val SPECTRAL_EPSILON = 1e-5f

    private val W_DATA = floatArrayOf(
        1.0011607f, 1.0011606f, 1.0011603f, 1.0011587f, 1.0011526f,
        1.0011325f, 1.0010850f, 1.0009969f, 1.0008653f, 1.0006963f,
        1.0005050f, 1.0003081f, 1.0001197f, 0.9999528f, 0.9998218f,
        0.9997386f, 0.9997095f, 0.9997319f, 0.9997994f, 0.9999003f,
        1.0000204f, 1.0001448f, 1.0002600f, 1.0003558f, 1.0004275f,
        1.0004762f, 1.0005072f, 1.0005252f, 1.0005351f, 1.0005402f,
        1.0005427f, 1.0005439f, 1.0005445f, 1.0005448f, 1.0005449f,
        1.0005450f, 1.0005450f, 1.0005450f
    )

    private val C_DATA = floatArrayOf(
        0.9705850f, 0.9705925f, 0.9706253f, 0.9707868f, 0.9713687f,
        0.9731632f, 0.9767402f, 0.9815876f, 0.9862803f, 0.9899491f,
        0.9924927f, 0.9941457f, 0.9951840f, 0.9957568f, 0.9959128f,
        0.9956062f, 0.9945976f, 0.9922157f, 0.9862365f, 0.9679433f,
        0.8912850f, 0.5362025f, 0.1541081f, 0.0574575f, 0.0315350f,
        0.0222634f, 0.0182023f, 0.0162991f, 0.0153656f, 0.0149112f,
        0.0146954f, 0.0145964f, 0.0145470f, 0.0145229f, 0.0145120f,
        0.0145067f, 0.0145045f, 0.0145038f
    )

    private val M_DATA = floatArrayOf(
        0.9906736f, 0.9906715f, 0.9906626f, 0.9906181f, 0.9904515f,
        0.9898711f, 0.9882866f, 0.9842907f, 0.9739349f, 0.9418178f,
        0.8173903f, 0.4324728f, 0.1384540f, 0.0537347f, 0.0292175f,
        0.0213137f, 0.0201350f, 0.0241323f, 0.0372236f, 0.0760507f,
        0.2053755f, 0.5412689f, 0.8158417f, 0.9128177f, 0.9463398f,
        0.9599277f, 0.9662606f, 0.9693260f, 0.9708545f, 0.9716051f,
        0.9719628f, 0.9721273f, 0.9722094f, 0.9722496f, 0.9722676f,
        0.9722765f, 0.9722802f, 0.9722813f
    )

    private val Y_DATA = floatArrayOf(
        0.0210523f, 0.0210565f, 0.0210746f, 0.0211649f, 0.0215028f,
        0.0226739f, 0.0258236f, 0.0334879f, 0.0519070f, 0.1007490f,
        0.2391299f, 0.5348043f, 0.7978076f, 0.9114499f, 0.9537980f,
        0.9712416f, 0.9793031f, 0.9833801f, 0.9854612f, 0.9864350f,
        0.9867383f, 0.9866179f, 0.9862778f, 0.9858606f, 0.9854749f,
        0.9851769f, 0.9849716f, 0.9848463f, 0.9847754f, 0.9847381f,
        0.9847196f, 0.9847110f, 0.9847067f, 0.9847046f, 0.9847036f,
        0.9847031f, 0.9847029f, 0.9847029f
    )

    private val R_DATA = floatArrayOf(
        0.0315606f, 0.0315521f, 0.0315148f, 0.0313318f, 0.0306730f,
        0.0286480f, 0.0246450f, 0.0192961f, 0.0142067f, 0.0102943f,
        0.0076191f, 0.0058980f, 0.0048233f, 0.0042299f, 0.0040599f,
        0.0043534f, 0.0053434f, 0.0076917f, 0.0135970f, 0.0316975f,
        0.1078612f, 0.4638126f, 0.8470554f, 0.9431854f, 0.9688622f,
        0.9780307f, 0.9820436f, 0.9839236f, 0.9848455f, 0.9852943f,
        0.9855073f, 0.9856051f, 0.9856538f, 0.9856777f, 0.9856884f,
        0.9856937f, 0.9856959f, 0.9856965f
    )

    private val G_DATA = floatArrayOf(
        0.0095561f, 0.0095582f, 0.0095673f, 0.0096129f, 0.0097837f,
        0.0103786f, 0.0120026f, 0.0160978f, 0.0267062f, 0.0595555f,
        0.1860398f, 0.5705798f, 0.8614678f, 0.9458791f, 0.9704655f,
        0.9784136f, 0.9795890f, 0.9755335f, 0.9622888f, 0.9231216f,
        0.7934340f, 0.4592701f, 0.1855741f, 0.0881775f, 0.0543630f,
        0.0406288f, 0.0342215f, 0.0311186f, 0.0295709f, 0.0288109f,
        0.0284486f, 0.0282820f, 0.0281988f, 0.0281582f, 0.0281399f,
        0.0281309f, 0.0281271f, 0.0281260f
    )

    private val B_DATA = floatArrayOf(
        0.9794048f, 0.9794007f, 0.9793829f, 0.9792944f, 0.9789630f,
        0.9778145f, 0.9747243f, 0.9671985f, 0.9490797f, 0.9008501f,
        0.7631504f, 0.4659222f, 0.2012633f, 0.0877524f, 0.0457177f,
        0.0284706f, 0.0205272f, 0.0165303f, 0.0145135f, 0.0136004f,
        0.0133604f, 0.0135489f, 0.0139594f, 0.0144434f, 0.0148854f,
        0.0152254f, 0.0154593f, 0.0156018f, 0.0156825f, 0.0157249f,
        0.0157458f, 0.0157556f, 0.0157605f, 0.0157630f, 0.0157641f,
        0.0157646f, 0.0157648f, 0.0157649f
    )

    private val CMF_X = floatArrayOf(
        0.0000647f, 0.0002194f, 0.0011206f, 0.0037666f, 0.0118806f,
        0.0232864f, 0.0345594f, 0.0372238f, 0.0324184f, 0.0212332f,
        0.0104910f, 0.0032958f, 0.0005070f, 0.0009487f, 0.0062737f,
        0.0168646f, 0.0286896f, 0.0426748f, 0.0562547f, 0.0694704f,
        0.0830532f, 0.0861261f, 0.0904661f, 0.0850039f, 0.0709067f,
        0.0506289f, 0.0354740f, 0.0214682f, 0.0125165f, 0.0068046f,
        0.0034646f, 0.0014976f, 0.0007697f, 0.0004074f, 0.0001690f,
        0.0000952f, 0.0000490f, 0.0000200f
    )

    private val CMF_Y = floatArrayOf(
        0.0000018f, 0.0000062f, 0.0000310f, 0.0001047f, 0.0003536f,
        0.0009515f, 0.0022823f, 0.0042073f, 0.0066888f, 0.0098884f,
        0.0152495f, 0.0214183f, 0.0334229f, 0.0513100f, 0.0704021f,
        0.0878387f, 0.0942491f, 0.0979567f, 0.0941522f, 0.0867810f,
        0.0788565f, 0.0635267f, 0.0537414f, 0.0426461f, 0.0316173f,
        0.0208852f, 0.0138601f, 0.0081026f, 0.0046301f, 0.0024914f,
        0.0012593f, 0.0005416f, 0.0002780f, 0.0001471f, 0.0000610f,
        0.0000344f, 0.0000177f, 0.0000072f
    )

    private val CMF_Z = floatArrayOf(
        0.0003050f, 0.0010368f, 0.0053131f, 0.0179544f, 0.0570776f,
        0.1136516f, 0.1733587f, 0.1962066f, 0.1860824f, 0.1399505f,
        0.0891745f, 0.0478962f, 0.0281456f, 0.0161377f, 0.0077591f,
        0.0042961f, 0.0020055f, 0.0008615f, 0.0003690f, 0.0001914f,
        0.0001496f, 0.0000923f, 0.0000681f, 0.0000288f, 0.0000158f,
        0.0000039f, 0.0000016f, 0.0000000f, 0.0000000f, 0.0000000f,
        0.0000000f, 0.0000000f, 0.0000000f, 0.0000000f, 0.0000000f,
        0.0000000f, 0.0000000f, 0.0000000f
    )

    private fun spectralUncompand(x: Float): Float {
        return if (x < 0.04045f) x / 12.92f else ((x + 0.055f) / 1.055f).pow(SPECTRAL_GAMMA)
    }

    private fun spectralCompand(x: Float): Float {
        return if (x < 0.0031308f) x * 12.92f else 1.055f * x.pow(1f / SPECTRAL_GAMMA) - 0.055f
    }

    private fun srgbToLinear(srgb: Vec3): Vec3 {
        return Vec3(spectralUncompand(srgb.r), spectralUncompand(srgb.g), spectralUncompand(srgb.b))
    }

    private fun linearToSrgb(lrgb: Vec3): Vec3 {
        return Vec3(
            spectralCompand(lrgb.r).coerceIn(0f, 1f),
            spectralCompand(lrgb.g).coerceIn(0f, 1f),
            spectralCompand(lrgb.b).coerceIn(0f, 1f)
        )
    }

    private fun linearToReflectance(lrgb: Vec3, R: FloatArray) {
        val w = min(lrgb.r, min(lrgb.g, lrgb.b))
        val baseR = lrgb.r - w
        val baseG = lrgb.g - w
        val baseB = lrgb.b - w

        val c = min(baseG, baseB)
        val m = min(baseR, baseB)
        val y = min(baseR, baseG)

        val r = min(max(0f, baseR - baseB), max(0f, baseR - baseG))
        val g = min(max(0f, baseG - baseB), max(0f, baseG - baseR))
        val b = min(max(0f, baseB - baseG), max(0f, baseB - baseR))

        for (i in 0 until SPECTRAL_SIZE) {
            val refl = w * W_DATA[i] + c * C_DATA[i] + m * M_DATA[i] + y * Y_DATA[i] + r * R_DATA[i] + g * G_DATA[i] + b * B_DATA[i]
            R[i] = max(SPECTRAL_EPSILON, refl)
        }
    }

    private fun reflectanceToXyz(R: FloatArray): Vec3 {
        var x = 0f
        var y = 0f
        var z = 0f
        for (i in 0 until SPECTRAL_SIZE) {
            val rVal = R[i]
            x += rVal * CMF_X[i]
            y += rVal * CMF_Y[i]
            z += rVal * CMF_Z[i]
        }
        return Vec3(x, y, z)
    }

    private fun xyzToSrgb(xyz: Vec3): Vec3 {
        val r =  3.24096994f * xyz.x - 1.53738318f * xyz.y - 0.49861076f * xyz.z
        val g = -0.96924364f * xyz.x + 1.87596750f * xyz.y + 0.04155506f * xyz.z
        val b =  0.05563008f * xyz.x - 0.20397696f * xyz.y + 1.05697151f * xyz.z
        return linearToSrgb(Vec3(r, g, b))
    }

    private fun ks(r: Float): Float {
        val num = (1f - r) * (1f - r)
        val den = 2f * max(r, 1e-5f)
        return num / den
    }

    private fun km(ksVal: Float): Float {
        return 1f + ksVal - sqrt(ksVal * ksVal + 2f * ksVal)
    }

    /**
     * Blends two colors using Kubelka-Munk spectral theory.
     */
    fun mixPigment(c1: Vec3, c2: Vec3, t: Float): Vec3 {
        val diffR = c1.r - c2.r
        val diffG = c1.g - c2.g
        val diffB = c1.b - c2.b
        if (diffR * diffR + diffG * diffG + diffB * diffB < 0.0001f) {
            return c2
        }
        val factor = t.coerceIn(0f, 1f)
        if (factor <= 0.001f) return c1
        if (factor >= 0.999f) return c2

        val lrgb1 = srgbToLinear(c1)
        val lrgb2 = srgbToLinear(c2)

        val R1 = FloatArray(SPECTRAL_SIZE)
        val R2 = FloatArray(SPECTRAL_SIZE)
        linearToReflectance(lrgb1, R1)
        linearToReflectance(lrgb2, R2)

        val lum1 = max(reflectanceToXyz(R1).y, 1e-5f)
        val lum2 = max(reflectanceToXyz(R2).y, 1e-5f)

        val factor1 = 1f - factor
        val factor2 = factor
        val conc1 = factor1 * factor1 * lum1
        val conc2 = factor2 * factor2 * lum2
        val totalConc = conc1 + conc2
        val invTotal = 1f / max(totalConc, 1e-6f)

        val RMix = FloatArray(SPECTRAL_SIZE)
        for (i in 0 until SPECTRAL_SIZE) {
            val ksMix = ks(R1[i]) * conc1 + ks(R2[i]) * conc2
            RMix[i] = km(ksMix * invTotal)
        }

        return xyzToSrgb(reflectanceToXyz(RMix))
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
