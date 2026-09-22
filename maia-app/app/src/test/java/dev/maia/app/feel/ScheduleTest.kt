package dev.maia.app.feel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** M2 brief section 8: the schedule is the handoff's, and the ladder is a pure function. */
class ScheduleTest {

    private fun Pattern.summary() = pulses.map { Triple(it.primitive, it.scale, it.delayMs) }

    @Test
    fun `the four moments are the handoff's table`() {
        assertEquals(listOf(Triple(Primitive.Click, 0.70f, 0)), Schedule.invoke.summary())
        assertEquals(
            listOf(Triple(Primitive.Tick, 0.60f, 0), Triple(Primitive.Tick, 0.60f, 40), Triple(Primitive.Thud, 1.00f, 90)),
            Schedule.commit.summary(),
        )
        assertEquals(
            listOf(Triple(Primitive.Thud, 0.50f, 0), Triple(Primitive.Tick, 0.40f, 70), Triple(Primitive.Tick, 0.25f, 45)),
            Schedule.fault.summary(),
        )
    }

    @Test
    fun `commit resolves heavy and fault starts heavy, so a pocket can tell them apart`() {
        assertEquals(Primitive.Thud, Schedule.commit.pulses.last().primitive)
        assertEquals(Primitive.Thud, Schedule.fault.pulses.first().primitive)
        val faultScales = Schedule.fault.pulses.map { it.scale }
        assertEquals(faultScales.sortedDescending(), faultScales)
    }

    @Test
    fun `a slow first word ticks louder, and only past 1200 ms`() {
        assertEquals(0.35f, Schedule.firstPartial(null).pulses.single().scale)
        assertEquals(0.35f, Schedule.firstPartial(1_200).pulses.single().scale)
        assertEquals(0.50f, Schedule.firstPartial(1_201).pulses.single().scale)
    }

    @Test
    fun `the hold climbs in four steps and stops climbing`() {
        val scales = (0..5).map { Schedule.holdStep(it).pulses.single().scale }
        assertEquals(listOf(0.20f, 0.30f, 0.40f, 0.50f, 0.50f, 0.50f), scales.map { Math.round(it * 100) / 100f })
        assertEquals(600L, Schedule.HOLD_STEPS * Schedule.HOLD_STEP_MS)
    }

    @Test
    fun `every waveform is one shot, starts off, and is as long as its composition`() {
        listOf(Schedule.invoke, Schedule.commit, Schedule.fault, Schedule.firstPartial(0), Schedule.holdStep(0)).forEach { p ->
            assertEquals(0L, p.timings.first())
            assertEquals(0, p.amplitudes.first())
            assertEquals(p.pulses.size, p.amplitudes.count { it > 0 })
            assertTrue(p.amplitudes.all { it in 0..255 })
        }
    }

    @Test
    fun `the ladder`() {
        val all: (Set<Primitive>) -> Boolean = { true }
        val none: (Set<Primitive>) -> Boolean = { false }
        val noThud: (Set<Primitive>) -> Boolean = { Primitive.Thud !in it }
        assertEquals(Rung.Silent, rung(false, 36, all, Schedule.commit))
        assertEquals(Rung.Composition, rung(true, 30, all, Schedule.commit))
        assertEquals(Rung.Waveform, rung(true, 29, all, Schedule.commit))
        assertEquals(Rung.Waveform, rung(true, 36, none, Schedule.commit))
        // Per pattern: a motor without THUD composes the invoke click but not the commit.
        assertEquals(Rung.Waveform, rung(true, 36, noThud, Schedule.commit))
        assertEquals(Rung.Composition, rung(true, 36, noThud, Schedule.invoke))
    }
}
