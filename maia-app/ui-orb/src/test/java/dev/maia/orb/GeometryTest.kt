package dev.maia.orb

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryTest {
    private val u = 100.0

    private fun points(f: Frame): List<Pair<Double, Double>> {
        val out = Geometry.band(f, u, 0.0, 0.0, 1.0, 1.0)
        return out.toList().chunked(2).map { (x, y) ->
            // Canvas y grows downward, so flip it back to maths angles.
            atan2(-y.toDouble(), x.toDouble()) to hypot(x.toDouble(), y.toDouble())
        }
    }

    @Test
    fun `the opening is centred at the top and nothing is drawn inside it`() {
        val f = ApertureModel.staticFrame(ApertureState.Dormant)
        val inside = points(f).filter { Signals.angDist(it.first, PI / 2) < f.gapHalf - 1e-3 }
        assertTrue("points inside the gap: ${inside.size}", inside.isEmpty())
    }

    @Test
    fun `fault closes the ring`() {
        val f = ApertureModel.staticFrame(ApertureState.Fault)
        val nearTop = points(f).count { Signals.angDist(it.first, PI / 2) < 0.02 }
        assertTrue(nearTop > 0)
    }

    @Test
    fun `the rim gathers weight at the bottom`() {
        val f = ApertureModel.staticFrame(ApertureState.Listening)
        val bottom = Geometry.base(f, -PI / 2, u)
        val side = Geometry.base(f, 0.0, u)
        assertTrue("bottom $bottom side $side", bottom > side)
    }

    @Test
    fun `the comet is sharp ahead and long behind`() {
        val still = ApertureModel.staticFrame(ApertureState.Thinking)
        val comet = Frame(
            still.r, still.gapHalf, still.rim, still.bottom, still.lum, still.desat, still.double,
            still.scribe, still.offsetX, still.mic, still.dash, still.hotAngle, still.hotAmount, comet = true,
        )
        val ahead = Geometry.outer(comet, comet.hotAngle - 0.4, u)
        val behind = Geometry.outer(comet, comet.hotAngle + 0.4, u)
        assertTrue("ahead $ahead behind $behind", behind > ahead * 1.5)
        // Frozen, it stays the symmetric arc it always was.
        assertEquals(
            Geometry.outer(still, still.hotAngle - 0.4, u),
            Geometry.outer(still, still.hotAngle + 0.4, u),
            1e-9,
        )
    }

    @Test
    fun `docking compresses the radius and floors the rim at a dp`() {
        val small = 32.0 * 2.625
        val dormant = ApertureModel.staticFrame(ApertureState.Dormant).docked(1.0, small, 2.625)
        val listening = ApertureModel.staticFrame(ApertureState.Listening).docked(1.0, small, 2.625)
        assertEquals(DOCK_R, dormant.r, 1e-9)
        assertEquals(DOCK_R + (0.62 - ApertureState.Dormant.pose.r) * DOCK_KEEP, listening.r, 1e-9)
        assertTrue("rim is at least a dp", dormant.rim * small >= 2.625 - 1e-9)
        val hero = ApertureModel.staticFrame(ApertureState.Dormant)
        assertTrue("undocked is untouched", hero.docked(0.0, small, 2.625) === hero)
        assertEquals((hero.r + DOCK_R) / 2, hero.docked(0.5, small, 2.625).r, 1e-9)
    }

    @Test
    fun `signal pushes outward only, the inner edge stays a circle`() {
        val quiet = ApertureModel.staticFrame(ApertureState.Thinking)
        val loud = Frame(
            quiet.r, quiet.gapHalf, quiet.rim, quiet.bottom, quiet.lum, quiet.desat, quiet.double,
            quiet.scribe, quiet.offsetX, DoubleArray(Signals.TAPS) { 1.0 }, 1.0, quiet.hotAngle, quiet.hotAmount,
        )
        val n = Geometry.SAMPLES + 1
        val a = Geometry.band(quiet, u, 0.0, 0.0, 1.0, 1.0)
        val b = Geometry.band(loud, u, 0.0, 0.0, 1.0, 1.0)
        for (i in n until 2 * n) {
            assertEquals(a[2 * i], b[2 * i], 1e-4f)
            assertEquals(a[2 * i + 1], b[2 * i + 1], 1e-4f)
        }
        val outerA = (0 until n).sumOf { hypot(a[2 * it].toDouble(), a[2 * it + 1].toDouble()) }
        val outerB = (0 until n).sumOf { hypot(b[2 * it].toDouble(), b[2 * it + 1].toDouble()) }
        assertTrue(outerB > outerA)
    }
}
