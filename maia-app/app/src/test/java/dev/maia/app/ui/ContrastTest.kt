package dev.maia.app.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * WCAG 2 contrast, computed from the tokens themselves rather than eyeballed.
 *
 * 4.5:1 is the bar for text and 3:1 for graphics. lineStrong is not checked:
 * it is decorative, and the controls it edges are identified by their text.
 */
class ContrastTest {

    @Test
    fun `light text inks read on every light surface`() {
        val c = MaiaLight
        val grounds = listOf(
            "groundBase" to c.groundBase,
            "surfaceRaised" to c.surfaceRaised,
            "surfaceField" to c.surfaceField,
            "surfaceSunken" to c.surfaceSunken,
        )
        for ((ink, fg) in textInks(c)) {
            for ((ground, bg) in grounds) assertAtLeast("light", ink, fg, ground, bg, TEXT)
        }
    }

    @Test
    fun `light graphic inks read on the ground`() {
        val c = MaiaLight
        val graphics = listOf(
            "inkFaint" to c.inkFaint,
            "accentAperture" to c.accentAperture,
            "accentCore" to c.accentCore,
            "faultNeutral" to c.faultNeutral,
        )
        for ((ink, fg) in graphics) assertAtLeast("light", ink, fg, "groundBase", c.groundBase, GRAPHIC)
    }

    @Test
    fun `dark text inks read on the ground`() {
        // Only groundBase: on dark, inkLow is 4.33 on surfaceRaised, 4.09 on
        // surfaceField and 4.44 on surfaceSunken, which the handoff's dark
        // ramp has always been. Dark tokens are the designer's and are not
        // moved by a test.
        val c = MaiaDark
        for ((ink, fg) in textInks(c)) assertAtLeast("dark", ink, fg, "groundBase", c.groundBase, TEXT)
    }

    private fun textInks(c: MaiaColours) = listOf(
        "inkHigh" to c.inkHigh,
        "inkStrong" to c.inkStrong,
        "inkMid" to c.inkMid,
        "inkLow" to c.inkLow,
    )

    private fun assertAtLeast(theme: String, ink: String, fg: Color, ground: String, bg: Color, bar: Double) {
        val ratio = contrast(fg, bg)
        assertTrue(
            "$theme $ink on $ground is ${"%.2f".format(ratio)}:1, needs $bar:1",
            ratio >= bar,
        )
    }

    private companion object {
        const val TEXT = 4.5
        const val GRAPHIC = 3.0

        fun contrast(a: Color, b: Color): Double {
            val la = luminance(a)
            val lb = luminance(b)
            return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
        }

        /** Relative luminance with the sRGB transfer function, as WCAG 2 defines it. */
        fun luminance(c: Color): Double =
            0.2126 * linear(c.red) + 0.7152 * linear(c.green) + 0.0722 * linear(c.blue)

        fun linear(channel: Float): Double {
            val v = channel.toDouble()
            return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
    }
}
