package dev.maia.app.flow

import dev.maia.app.flow.Fixtures.SENTENCE
import dev.maia.app.flow.Fixtures.heard
import dev.maia.app.flow.Fixtures.personal
import dev.maia.nlu.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M4 brief section 5.4: "the origin does not change what a sentence means". The
 * Quick note door carries [CaptureFamily.Note] on its invocation, and M4 row 9
 * carries it without acting on it, so every road taken through that door must
 * be the road taken through a door with no hint, step for step.
 */
class QuickNoteDoorTest {

    private val doors: List<FlowEvent.Invoke> = listOf(
        FlowEvent.Invoke(Origin.Shortcut, locked = false),
        FlowEvent.Invoke(Origin.Shortcut, locked = true),
    )

    private val rest: List<FlowEvent> = listOf(
        FlowEvent.CaptureStarted,
        FlowEvent.FinalHeard(SENTENCE),
        FlowEvent.Parsed(Intent.CreateEvent(heard)),
        FlowEvent.Tick,
        FlowEvent.TargetLoaded(personal),
        FlowEvent.Hold(1f),
        FlowEvent.Unlocked,
        FlowEvent.Cancel,
    )

    private fun noted(event: FlowEvent): FlowEvent =
        if (event is FlowEvent.Invoke) event.copy(family = CaptureFamily.Note) else event

    /** Every sequence up to length six over the alphabet, run twice, compared at every step. */
    @Test
    fun `the note hint changes no state and no effect on any road`() {
        val alphabet = doors + rest
        var compared = 0
        fun walk(event: FlowSession, note: FlowSession, depth: Int) {
            if (depth == 6) return
            for (symbol in alphabet) {
                val now = (depth + 1) * 1_000L
                val a = reduce(event, symbol, now)
                val b = reduce(note, noted(symbol), now)
                assertEquals("after $symbol from $event", a, b)
                compared++
                walk(a.session, b.session, depth + 1)
            }
        }
        walk(FlowSession(), FlowSession(), 0)
        assertTrue("compared suspiciously little: $compared", compared > 100_000)
    }

    @Test
    fun `a sentence that parses as an event through the quick note door opens the event card`() {
        var session = FlowSession()
        val road = listOf(
            FlowEvent.Invoke(Origin.Shortcut, locked = false, family = CaptureFamily.Note) to 0L,
            FlowEvent.CaptureStarted to 50L,
            FlowEvent.FinalHeard(SENTENCE) to 2_000L,
            FlowEvent.Parsed(Intent.CreateEvent(heard)) to 2_005L,
            FlowEvent.Tick to 2_400L,
            FlowEvent.TargetLoaded(personal) to 2_500L,
        )
        for ((event, at) in road) session = reduce(session, event, at).session
        assertTrue("expected the event card, got ${session.state}", session.state is FlowState.Preview)
    }
}
