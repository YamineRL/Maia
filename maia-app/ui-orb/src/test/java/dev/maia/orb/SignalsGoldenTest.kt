package dev.maia.orb

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The ported helpers against values printed by the handoff's own JavaScript.
 *
 * `signals-golden.txt` was produced by importing `docs/design/aperture.js` in
 * node and printing `micProfile`, `speechEnv` and `angDist` at fixed inputs.
 * If design revises those functions, regenerate it the same way.
 */
class SignalsGoldenTest {
    private val lines = javaClass.getResource("/signals-golden.txt")!!.readText().trim().lines()

    @Test
    fun `micProfile matches aperture js`() {
        val rows = lines.filter { it.startsWith("mic ") }
        assertEquals(4, rows.size)
        for (row in rows) {
            val v = row.split(' ').drop(1).map(String::toDouble)
            val expected = v.drop(2)
            val actual = Signals.micProfile(v[0], v[1])
            assertEquals(Signals.TAPS, expected.size)
            expected.forEachIndexed { i, e -> assertEquals("$row [$i]", e, actual[i], 1e-12) }
        }
    }

    @Test
    fun `speechEnv matches aperture js`() {
        val rows = lines.filter { it.startsWith("env ") }
        assertEquals(7, rows.size)
        for (row in rows) {
            val (t, e) = row.split(' ').drop(1).map(String::toDouble)
            assertEquals(row, e, Signals.speechEnv(t), 1e-12)
        }
    }

    @Test
    fun `angDist matches aperture js`() {
        val rows = lines.filter { it.startsWith("ang ") }
        assertEquals(3, rows.size)
        for (row in rows) {
            val (a, b, d) = row.split(' ').drop(1).map(String::toDouble)
            assertEquals(row, d, Signals.angDist(a, b), 1e-12)
        }
    }
}
