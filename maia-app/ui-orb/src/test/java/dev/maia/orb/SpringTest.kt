package dev.maia.orb

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpringTest {
    private fun run(params: SpringParams, seconds: Double, hz: Int, from: Double = 0.0, to: Double = 1.0): List<Double> {
        val s = Spring(from, params)
        s.to(to)
        val frames = (seconds * hz).toInt()
        return List(frames) { s.step(1.0 / hz) }
    }

    @Test
    fun `critically damped settle never overshoots and is within 1 percent by 800 ms`() {
        val xs = run(Springs.settle, 0.8, 120)
        assertTrue(xs.all { it <= 1.0 + 1e-9 })
        assertEquals(1.0, xs.last(), 0.01)
    }

    @Test
    fun `underdamped invoke radius overshoots then settles`() {
        val xs = run(Springs.invokeR, 1.5, 120)
        assertTrue("peak ${xs.max()}", xs.max() > 1.05)
        assertEquals(1.0, xs.last(), 0.01)
    }

    @Test
    fun `every table spring settles within 1 percent inside 1 second`() {
        listOf(
            Springs.invokeR, Springs.invokeGap, Springs.contractR, Springs.contractGap,
            Springs.handoffR, Springs.settle, Springs.hold, Springs.tap,
            Springs.follow, Springs.lobeStep,
        ).forEach { p ->
            assertEquals("$p", 1.0, run(p, 1.0, 120).last(), 0.01)
        }
    }

    @Test
    fun `the curve does not depend on refresh rate`() {
        for (p in listOf(Springs.invokeR, Springs.contractGap, Springs.settle)) {
            val at60 = run(p, 0.5, 60).last()
            val at90 = run(p, 0.5, 90).last()
            val at120 = run(p, 0.5, 120).last()
            assertEquals("$p 60 vs 120", at120, at60, 5e-3)
            assertEquals("$p 90 vs 120", at120, at90, 5e-3)
        }
    }

    @Test
    fun `a stalled frame is clamped to 120 ms`() {
        val stalled = Spring(0.0, Springs.settle).apply { to(1.0) }.step(5.0)
        val clamped = Spring(0.0, Springs.settle).apply { to(1.0) }.step(0.12)
        assertEquals(clamped, stalled, 0.0)
        assertTrue(abs(stalled - 1.0) > 0.1)
    }
}
