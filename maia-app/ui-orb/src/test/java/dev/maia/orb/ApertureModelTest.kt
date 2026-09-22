package dev.maia.orb

import kotlin.math.PI
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApertureModelTest {
    private val hz = 120
    private val dt = 1.0 / hz

    private class Clock(val model: ApertureModel) {
        var t = 0.0
        fun run(seconds: Double, mic: Double = 0.0, speech: Double = 0.0): List<Frame> =
            List((seconds * 120).toInt()) {
                t += 1.0 / 120
                model.frame(1.0 / 120, t, mic, speech)
            }
    }

    @Test
    fun `each state settles onto its pose`() {
        for (state in ApertureState.entries) {
            val clock = Clock(ApertureModel())
            clock.model.setState(state)
            val f = clock.run(1.5).last()
            val p = state.pose
            // Dormant breathes all three, so it is only ever near its pose.
            val dormant = state == ApertureState.Dormant
            assertEquals("$state r", p.r, f.r, if (dormant) p.r * 0.05 else 0.005)
            assertEquals("$state gap", p.gapHalf, f.gapHalf, if (dormant) 0.055 else 0.005)
            val lumTolerance = when (state) {
                ApertureState.Speaking -> 0.2
                ApertureState.Dormant -> p.lum * 0.21
                else -> 0.005
            }
            assertEquals("$state lum", p.lum, f.lum, lumTolerance)
            assertEquals("$state desat", p.desat, f.desat, 0.005)
        }
    }

    @Test
    fun `dormant breathes radius, luminance and opening over one 5 s cycle`() {
        val frames = Clock(ApertureModel()).run(10.0)
        val tail = frames.drop(frames.size / 2)
        val p = ApertureState.Dormant.pose
        assertEquals(p.r * 1.045, tail.maxOf { it.r }, p.r * 0.006)
        assertEquals(p.r * 0.955, tail.minOf { it.r }, p.r * 0.006)
        assertEquals(p.lum * 1.2, tail.maxOf { it.lum }, p.lum * 0.03)
        assertEquals(p.lum * 0.8, tail.minOf { it.lum }, p.lum * 0.03)
        // The opening narrows on the inhale: widest gap with the smallest radius.
        val inhale = tail.maxBy { it.r }
        val exhale = tail.minBy { it.r }
        assertTrue("gap narrows on the inhale", inhale.gapHalf < exhale.gapHalf - 0.08)
    }

    @Test
    fun `listening follows the voice with the whole form`() {
        val clock = Clock(ApertureModel())
        clock.model.setState(ApertureState.Listening)
        val quiet = clock.run(1.5, mic = 0.0).last()
        val loud = clock.run(1.5, mic = 1.0).last()
        val p = ApertureState.Listening.pose
        assertEquals(p.gapHalf, quiet.gapHalf, 0.005)
        assertEquals(p.gapHalf + ApertureModel.LISTEN_GAP, loud.gapHalf, 0.01)
        assertEquals(p.r * (1 + ApertureModel.LISTEN_R), loud.r, 0.005)
        assertEquals(ApertureModel.LISTEN_GLOW_FLOOR, quiet.glow, 0.01)
        assertEquals(ApertureModel.LISTEN_GLOW_FLOOR + ApertureModel.LISTEN_GLOW, loud.glow, 0.01)
        assertEquals(Frame.MIC_GAIN_LIVE, loud.micGain, 0.0)
        clock.model.setState(ApertureState.Thinking)
        assertEquals(1.0, clock.run(1.5).last().glow, 0.01)
    }

    @Test
    fun `mic taps move only while listening and die away after`() {
        val clock = Clock(ApertureModel())
        clock.model.setState(ApertureState.Listening)
        val listening = clock.run(1.0, mic = 0.8).last()
        assertTrue(listening.mic.max() > 0.3)
        assertTrue("uneven rim", listening.mic.max() - listening.mic.min() > 0.1)
        clock.model.setState(ApertureState.Thinking)
        val thinking = clock.run(1.0, mic = 0.8).last()
        assertTrue(thinking.mic.all { it < 1e-3 })
    }

    @Test
    fun `thinking arc travels 1_15 revolutions per second`() {
        val clock = Clock(ApertureModel())
        clock.model.setState(ApertureState.Thinking)
        val frames = clock.run(1.0)
        // Born at the opening, one frame's travel clockwise of it.
        assertEquals(PI / 2 - 1.15 * 2 * PI / 120, frames[0].hotAngle, 1e-9)
        assertTrue(frames[0].comet)
        val a = frames[0].hotAngle
        val b = frames[119].hotAngle
        assertEquals(-1.15 * 2 * PI * (119.0 / 120), b - a, 1e-9)
        assertEquals(ApertureModel.HOT_AMOUNT, frames.last().hotAmount, 0.01)
    }

    @Test
    fun `fault shake peaks near 6 dp, reverses inside 260 ms, then holds still`() {
        val clock = Clock(ApertureModel(ApertureState.Thinking))
        clock.model.setState(ApertureState.Fault)
        val frames = clock.run(1.0)
        val xs = frames.map { it.offsetX }
        val peak = xs.maxOf { abs(it) }
        assertEquals(6.0, peak, 0.5)
        val firstQuarterSecond = xs.take((0.26 * hz).toInt())
        assertTrue("reverses", firstQuarterSecond.any { it > 1 } && firstQuarterSecond.any { it < -1 })
        assertTrue("still by 800 ms", xs.drop((0.8 * hz).toInt()).all { abs(it) < 0.1 })
        assertEquals(1.0, frames.last().double, 0.01)
    }

    @Test
    fun `entering the same state again does not re-kick the shake`() {
        val clock = Clock(ApertureModel(ApertureState.Thinking))
        clock.model.setState(ApertureState.Fault)
        clock.run(1.0)
        clock.model.setState(ApertureState.Fault)
        assertTrue(clock.run(0.3).all { abs(it.offsetX) < 0.1 })
    }

    @Test
    fun `reduced motion gives six static frames that differ from each other`() {
        val frames = ApertureState.entries.associateWith { state ->
            val model = ApertureModel()
            model.setState(state)
            model.frame(dt, 3.0, micLevel = 0.9, speech = 0.9, reducedMotion = true)
        }
        fun signature(f: Frame) =
            listOf(f.r, f.gapHalf, f.desat, f.dash, f.hotAmount, f.mic.sum(), f.double, f.scribe)
        val signatures = frames.values.map(::signature)
        assertEquals(6, signatures.toSet().size)
        val listening = frames.getValue(ApertureState.Listening)
        assertTrue("listening rim frozen uneven", listening.mic.max() - listening.mic.min() > 0.1)
        assertEquals(ApertureModel.HOT_AMOUNT, frames.getValue(ApertureState.Thinking).hotAmount, 0.0)
        assertEquals(1.0, frames.getValue(ApertureState.Speaking).dash, 0.0)
        assertEquals(0.0, frames.getValue(ApertureState.Fault).offsetX, 0.0)

        // Working and Fault are the pair worth checking, because both add a
        // second circle. They are separated three times over, so the
        // redundancy rule holds with no colour and no movement: radius (0.42
        // of R against 1.16), chroma (full accent against desaturated), and
        // the gap (a visible seam against fully shut).
        val working = frames.getValue(ApertureState.Working)
        val fault = frames.getValue(ApertureState.Fault)
        assertTrue("working is the only bright inner circle", working.scribe > fault.scribe * 5)
        assertEquals(0.0, working.desat, 0.0)
        assertEquals(0.0, working.double, 0.0)
        assertTrue("working keeps a seam of gap", working.gapHalf > 0.0)
        assertEquals(0.0, fault.gapHalf, 0.0)
        // And Working is told from Thinking frozen, which is the reason it is
        // a sixth state at all.
        assertTrue(working.scribe > frames.getValue(ApertureState.Thinking).scribe)
    }

    @Test
    fun `the working arc steps once per tool call and never goes backwards`() {
        val model = ApertureModel()
        model.setState(ApertureState.Working)
        fun angle(t: Double) = model.frame(dt, t).hotAngle
        val start = angle(0.0)
        repeat(4) { model.advanceWork() }
        var last = start
        var t = 0.0
        repeat(120) {
            t += dt
            val now = angle(t)
            assertTrue("the arc retraced, which would claim work was undone", now <= last + 1e-9)
            last = now
        }
        // Four steps of 24 degrees, plus the drift over the two seconds it
        // took to settle. It never claims to know how much is left.
        val stepped = start - last
        assertTrue("four steps did not land", stepped > 4 * ApertureModel.WORK_STEP * 0.9)
    }

    @Test
    fun `working drifts so a long single tool call does not look like a hang`() {
        val clock = Clock(ApertureModel())
        clock.model.setState(ApertureState.Working)
        val frames = clock.run(10.0)
        val drift = frames.first().hotAngle - frames.last().hotAngle
        assertEquals(0.18 * 2 * PI * 10, drift, 0.1)
    }

    @Test
    fun `thinking hands its arc to working without a jump, easing its rate`() {
        val clock = Clock(ApertureModel())
        clock.model.setState(ApertureState.Thinking)
        val before = clock.run(1.0).last().hotAngle
        clock.model.setState(ApertureState.Working)
        val after = clock.run(2.0)
        assertEquals("no jump", before, after.first().hotAngle, 1.15 * 2 * PI / 120 + 1e-9)
        val steps = after.zipWithNext { a, b -> a.hotAngle - b.hotAngle }
        assertTrue("rate falls gradually", steps[10] < steps[0] && steps[10] > steps.last())
        assertEquals(0.18 * 2 * PI / 120, steps.last(), 1e-3)
    }

    @Test
    fun `a tool call flares the arc and the flare dies away`() {
        val clock = Clock(ApertureModel())
        clock.model.setState(ApertureState.Working)
        val rest = clock.run(1.0).last().hotAmount
        clock.model.advanceWork()
        val flared = clock.run(1.0 / 120).last().hotAmount
        assertEquals(rest * (1 + ApertureModel.FLARE), flared, 0.05)
        assertEquals(rest, clock.run(2.0).last().hotAmount, 0.01)
    }

    @Test
    fun `an echo leaves the rim once on entering listening, speaking or working`() {
        for (state in ApertureState.entries) {
            val clock = Clock(ApertureModel(ApertureState.Thinking.takeIf { state != it } ?: ApertureState.Dormant))
            clock.model.setState(state)
            val frames = clock.run(1.0)
            val sends = state in ApertureModel.ECHO_STATES
            assertEquals("$state", sends, frames.first().echo < 1)
            assertTrue("$state echo is over by 0.75 s", frames.last().echo >= 1)
        }
    }

    @Test
    fun `speaking lobes step half a lobe per syllable and swell with the voice`() {
        val clock = Clock(ApertureModel())
        clock.model.setState(ApertureState.Speaking)
        val a = clock.run(1.0, speech = 0.3).last()
        val b = clock.run(1.0, speech = 0.9).last()
        val c = clock.run(1.0, speech = 0.3).last()
        val d = clock.run(1.0, speech = 0.9).last()
        assertEquals(0.0, a.phase, 1e-3)
        assertEquals(-ApertureModel.LOBE_STEP, b.phase, 1e-3)
        assertEquals(b.phase, c.phase, 1e-3)
        assertEquals(-2 * ApertureModel.LOBE_STEP, d.phase, 1e-3)
        assertEquals(0.9, d.swell, 0.0)
        assertEquals(0.3, c.swell, 0.0)
    }

    @Test
    fun `reduced motion frames are the static poses exactly`() {
        for (state in ApertureState.entries) {
            val model = ApertureModel(ApertureState.Dormant)
            model.setState(state)
            model.advanceWork()
            val f = model.frame(dt, 3.0, micLevel = 0.9, speech = 0.9, reducedMotion = true)
            val s = ApertureModel.staticFrame(state)
            assertEquals(s.r, f.r, 0.0)
            assertEquals(s.gapHalf, f.gapHalf, 0.0)
            assertEquals(s.lum, f.lum, 0.0)
            assertEquals(s.hotAngle, f.hotAngle, 0.0)
            assertEquals(s.hotAmount, f.hotAmount, 0.0)
            assertFalse(f.comet)
            assertEquals(Frame.MIC_GAIN_STILL, f.micGain, 0.0)
            assertEquals(1.0, f.glow, 0.0)
            assertEquals(0.0, f.phase, 0.0)
            assertTrue(f.echo >= 1)
        }
    }

    @Test
    fun `reduced motion does not move between two frames`() {
        val model = ApertureModel()
        model.setState(ApertureState.Listening)
        val a = model.frame(dt, 1.0, micLevel = 0.2, reducedMotion = true)
        val b = model.frame(dt, 2.0, micLevel = 0.9, reducedMotion = true)
        assertEquals(a.r, b.r, 0.0)
        assertTrue(a.mic.contentEquals(b.mic))
    }

    @Test
    fun `leaving reduced motion starts from the static pose, not a jump`() {
        val model = ApertureModel()
        model.setState(ApertureState.Listening)
        val still = model.frame(dt, 1.0, reducedMotion = true)
        val next = model.frame(dt, 1.0 + dt)
        assertEquals(still.r, next.r, 1e-3)
        assertNotEquals(0.0, next.r)
        assertFalse(next.r.isNaN())
    }
}
