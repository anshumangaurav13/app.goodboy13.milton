package com.antigrav.milton

import com.antigrav.milton.core.gl.PigmentColorMixing
import com.antigrav.milton.core.gl.PigmentColorMixing.Vec3
import org.junit.Assert.assertTrue
import org.junit.Test

class PigmentColorMixingTest {

    @Test
    fun testBlueOverYellowProducesGreen() {
        val yellow = Vec3(1.0f, 1.0f, 0.0f)
        val blue = Vec3(0.0f, 0.0f, 1.0f)

        val mixed = PigmentColorMixing.mixPigment(yellow, blue, 0.5f)

        // Resulting color must have green as the dominant channel
        assertTrue("Green channel must be dominant: g=${mixed.g}, r=${mixed.r}, b=${mixed.b}",
            mixed.g > mixed.r && mixed.g > mixed.b)
        assertTrue("Green must be high: ${mixed.g}", mixed.g >= 0.50f)
    }

    @Test
    fun testUltramarineOverCadmiumYellowProducesLushGreen() {
        val yellow = Vec3(1.0f, 0.95f, 0.05f)
        val blue = Vec3(0.05f, 0.35f, 1.0f)

        val mixed = PigmentColorMixing.mixPigment(yellow, blue, 0.5f)

        assertTrue("Green channel must be dominant: g=${mixed.g}, r=${mixed.r}, b=${mixed.b}",
            mixed.g > mixed.r && mixed.g > mixed.b)
        assertTrue("Green must be vibrant: ${mixed.g}", mixed.g >= 0.70f)
    }

    @Test
    fun testRedOverYellowProducesOrange() {
        val red = Vec3(1.0f, 0.05f, 0.05f)
        val yellow = Vec3(1.0f, 0.95f, 0.05f)

        val mixed = PigmentColorMixing.mixPigment(red, yellow, 0.5f)

        // Orange has maximum red, moderate green, minimal blue
        assertTrue("Red must be dominant: ${mixed.r}", mixed.r >= 0.90f)
        assertTrue("Green must be present for orange: ${mixed.g}", mixed.g in 0.15f..0.70f)
        assertTrue("Blue must be minimal: ${mixed.b}", mixed.b <= 0.20f)
    }

    @Test
    fun testIdentityMixing() {
        val blue = Vec3(0.1f, 0.4f, 0.9f)
        val mixed = PigmentColorMixing.mixPigment(blue, blue, 0.5f)

        assertTrue("Mixing same color should preserve red: ${mixed.r}", kotlin.math.abs(mixed.r - blue.r) < 0.05f)
        assertTrue("Mixing same color should preserve green: ${mixed.g}", kotlin.math.abs(mixed.g - blue.g) < 0.05f)
        assertTrue("Mixing same color should preserve blue: ${mixed.b}", kotlin.math.abs(mixed.b - blue.b) < 0.05f)
    }
}
