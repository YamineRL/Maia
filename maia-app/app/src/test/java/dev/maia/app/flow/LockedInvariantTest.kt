package dev.maia.app.flow

import dev.maia.app.flow.Fixtures.SENTENCE
import dev.maia.app.flow.Fixtures.dateless
import dev.maia.app.flow.Fixtures.heard
import dev.maia.app.flow.Fixtures.personal
import dev.maia.app.flow.Fixtures.readOnly
import dev.maia.app.flow.Fixtures.thursdayEight
import dev.maia.nlu.Field
import dev.maia.nlu.Intent
import dev.maia.nlu.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The three things that must be true of every road, not of some road:
 * nothing is written, nothing is read from the calendar, and nothing is said,
 * while the phone is locked. Criteria J2, J3 and J4.
 *
 * These are the criteria that justify the shape of the machine rather than the
 * other way round, so they are proved by search and not by example. The brief's
 * argument is that `Queued` has no edge to `Committing` and that the only edge
 * into `Preview` is the one that clears the lock; the search is what makes that
 * argument checkable by a machine every time someone adds an edge.
 */
class LockedInvariantTest {

    /**
     * The alphabet, and how it is bounded.
     *
     * Fifteen symbols to depth eight is 2.5e9 sequences, which is not a test.
     * Three bounds make it one, and none of them removes a reachable state:
     *
     * 1. **Search the state graph, not the sequence tree.** Sequences are
     *    enumerated breadth first and every distinct [FlowSession] reached at a
     *    given depth is expanded once. Two sequences that arrive at the same
     *    session have the same future, so exploring both proves nothing twice.
     *    Every sequence up to length eight is still covered: if a sequence
     *    reaches a session, some prefix-equal session was expanded at that
     *    depth.
     * 2. **A clock that advances by the depth.** `now` is `depth * 1000`, one
     *    second a step, which is past [UNDERSTAND_FLOOR_MS] and inside the
     *    [UNDO_WINDOW_MS], so both timed roads are open. Fixing it per depth is
     *    what makes states at a depth comparable at all; a free clock would
     *    make every session distinct by its timestamps and the dedupe would do
     *    nothing.
     * 3. **One representative per event shape.** One draft, one writable
     *    target, one read-only target, one null target, [FlowEvent.Hold] at 1
     *    and at 0.5, both lock flags on the invocations. A second title or a
     *    second event id explores nothing new here: no rule in the reducer
     *    branches on a title, and the ids are already covered by M2's own
     *    random walk in `FlowTablesTest`.
     *
     * Depth eight is the brief's number and is generous: the shortest road from
     * a locked invocation to a write, if one existed, is six.
     */
    private val alphabet: List<FlowEvent> = listOf(
        FlowEvent.Invoke(Origin.Assistant, locked = true),
        FlowEvent.Invoke(Origin.Launcher, locked = false),
        // M4 row 9: the note door's hint widens the alphabet.
        FlowEvent.Invoke(Origin.Shortcut, locked = true, family = CaptureFamily.Note),
        FlowEvent.Invoke(Origin.Shortcut, locked = false, family = CaptureFamily.Note),
        FlowEvent.Unlocked,
        FlowEvent.Hidden,
        FlowEvent.UnlockRequested,
        FlowEvent.Cancel,
        FlowEvent.CaptureStarted,
        FlowEvent.PartialHeard("dinner"),
        FlowEvent.FinalHeard(SENTENCE),
        FlowEvent.Parsed(Intent.CreateEvent(heard)),
        FlowEvent.Tick,
        FlowEvent.TargetLoaded(personal),
        FlowEvent.TargetLoaded(readOnly),
        FlowEvent.Hold(1f),
        FlowEvent.WriteSucceeded(42),
        FlowEvent.Undo,
    )

    private val depth = 8

    @Test
    fun `no road reaches a write, a delete, a calendar read or a voice while locked`() {
        var frontier = setOf(FlowSession())
        var explored = 0
        var lockedSteps = 0
        var lockedQueued = 0

        for (step in 1..depth) {
            val now = step * 1_000L
            val next = mutableSetOf<FlowSession>()
            for (session in frontier) {
                explored++
                for (event in alphabet) {
                    val result = reduce(session, event, now)
                    if (session.locked) {
                        lockedSteps++
                        // The lock falls only when the host says the keyguard
                        // is gone: either [FlowEvent.Unlocked], or a fresh
                        // invocation carrying its own reading of it. Nothing
                        // inside the flow lowers it, which is what gives the
                        // assertions below their meaning.
                        val hostSaysOpen = event == FlowEvent.Unlocked ||
                            (event is FlowEvent.Invoke && !event.locked)
                        if (!hostSaysOpen && !result.session.locked) {
                            fail("the lock fell on $event, from $session")
                        }
                    }
                    if (session.locked && event != FlowEvent.Unlocked) {
                        result.effects.forEach { effect ->
                            when (effect) {
                                // J2.
                                is Effect.WriteEvent -> fail("write while locked: $session on $event")
                                is Effect.DeleteEvent -> fail("delete while locked: $session on $event")
                                // J3. The only calendar read the flow has.
                                Effect.LoadTarget -> fail("calendar read while locked: $session on $event")
                                // J4.
                                is Effect.Speak -> fail("speech while locked: $session on $event")
                                else -> Unit
                            }
                        }
                        // And no state that can commit is reachable while the
                        // lock is still up, which is the structural half of the
                        // same claim.
                        val reached = result.session
                        if (reached.locked && (reached.state is FlowState.Preview || reached.state is FlowState.Committing)) {
                            fail("a committable state while locked: $session on $event")
                        }
                        if (reached.state is FlowState.Queued) lockedQueued++
                    }
                    next += result.session
                }
            }
            // Deduped per depth rather than globally: two sequences that reach
            // the same session at the same depth have the same future, because
            // the clock is a function of the depth. A global set would also
            // drop a session reached later under a different clock, which is
            // not the same claim.
            frontier = next
            assertTrue("the frontier exploded at depth $step: ${frontier.size}", frontier.size < 100_000)
        }

        // A search that reached nothing proves nothing, so say what it reached.
        assertTrue("the search never locked the phone", lockedSteps > 1_000)
        assertTrue("the search never kept a draft", lockedQueued > 0)
        assertTrue("the search explored suspiciously little: $explored", explored > 100)
    }

    /**
     * J4 again, and this time exhaustively over the thing J4 quantifies: every
     * kind of sentence the parser can return. The search above uses one intent,
     * because a search over five is five times as long and proves the same
     * thing about the roads; this proves the thing about the sentences.
     */
    @Test
    fun `no intent is ever spoken or read back while locked`() {
        for (intent in everyIntent) {
            val run = lockedCapture(intent, transcript = SENTENCE)
            assertEquals("$intent", emptyList<Effect>(), run.all<Effect.Speak>())
            assertEquals("$intent", emptyList<Effect>(), run.all<Effect.LoadTarget>())
            assertTrue("$intent", run.effects.none { it is Effect.WriteEvent || it is Effect.DeleteEvent })
            assertTrue("$intent must not reach a card", run.state !is FlowState.Preview)

            // Unlocking is what lets the flow speak again, and even then only
            // after a write, which is M2's rule and is left alone.
            run.send(FlowEvent.Unlocked, at = 40_000)
            assertEquals("$intent", emptyList<Effect>(), run.all<Effect.Speak>())
        }
    }

    /**
     * J3 on the one path that looks most like an exception: a locked sentence
     * with no date at all, which faults, and whose date is then picked. The
     * card that a picked date opens is a commit affordance and a provider read,
     * so it waits too.
     */
    @Test
    fun `picking a date on a locked fault keeps the draft rather than opening a card`() {
        val run = lockedCapture(Intent.CreateEvent(dateless), transcript = dateless.transcript)
        assertEquals(FaultReason.NoDateHeard, (run.state as FlowState.Fault).reason)
        run.send(FlowEvent.DatePicked(thursdayEight), at = 5_000)
        val queued = run.state as FlowState.Queued
        assertEquals(thursdayEight, queued.draft?.start?.value)
        assertEquals(emptyList<Effect>(), run.all<Effect.LoadTarget>())
    }

    private val everyIntent: List<Intent> = listOf(
        Intent.CreateEvent(heard),
        Intent.Unparsed(heard.copy(title = Field(SENTENCE, Provenance.Heard, 0..6))),
        Intent.CaptureNote(Field("call the vet", Provenance.Heard, 0..2), transcript = SENTENCE),
        Intent.Agenda(thursdayEight..thursdayEight.plusHours(12), transcript = SENTENCE),
        Intent.Availability(thursdayEight..thursdayEight.plusHours(4), transcript = SENTENCE),
    )
}
