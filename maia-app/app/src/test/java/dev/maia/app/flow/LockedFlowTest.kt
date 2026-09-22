package dev.maia.app.flow

import dev.maia.app.flow.Fixtures.SENTENCE
import dev.maia.app.flow.Fixtures.heard
import dev.maia.app.flow.Fixtures.personal
import dev.maia.app.flow.Fixtures.zone
import dev.maia.nlu.EventDraft
import dev.maia.nlu.Field
import dev.maia.nlu.Intent
import dev.maia.nlu.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

/** Runs events through the session reducer, keeping every effect in order. */
class Drive(var session: FlowSession = FlowSession(), var now: Long = 0) {
    val effects = mutableListOf<Effect>()

    val state: FlowState get() = session.state

    fun send(event: FlowEvent, at: Long = now): Drive {
        now = at
        val step = reduce(session, event, now)
        session = step.session
        effects += step.effects
        return this
    }

    inline fun <reified T : Effect> all(): List<T> = effects.filterIsInstance<T>()
}

/**
 * A whole locked capture, press to parse, on the reducer's clock.
 *
 * The offsets are M2's own path test, so a locked run and an unlocked run
 * differ in one argument and nothing else.
 */
fun lockedCapture(
    intent: Intent,
    transcript: String = SENTENCE,
    at: Long = 0,
    session: FlowSession = FlowSession(),
    locked: Boolean = true,
    origin: Origin = Origin.Assistant,
): Drive = Drive(session)
    .send(FlowEvent.Invoke(origin, locked), at = at)
    .send(FlowEvent.CaptureStarted, at = at + 50)
    .send(FlowEvent.PartialHeard("dinner"), at = at + 300)
    .send(FlowEvent.FinalHeard(transcript), at = at + 2_000)
    .send(FlowEvent.Parsed(intent), at = at + 2_005)
    .send(FlowEvent.Tick, at = at + 2_000 + UNDERSTAND_FLOOR_MS)

/** A draft whose title says which one it is, for asserting the queue's order. */
fun namedDraft(title: String, start: ZonedDateTime = Fixtures.thursdayEight): EventDraft =
    heard.copy(title = Field(title, Provenance.Heard, 0..1), start = Field(start, Provenance.Heard), transcript = title)

/**
 * M3 brief sections 2.2, 2.3, 4.2 and 4.3, as the machine rather than the
 * screen: criteria J5 to J9, and the two rules in prose beside them.
 */
class LockedFlowTest {

    // J5 ---------------------------------------------------------------------

    @Test
    fun `a sentence heard while locked is kept, not previewed`() {
        val run = lockedCapture(Intent.CreateEvent(heard))
        val queued = run.state as FlowState.Queued
        assertEquals(heard, queued.draft)
        assertEquals(2_400L, queued.heardAt)
        assertEquals(lockedSummary(heard), queued.summary)
        assertEquals(listOf(QueuedDraft(heard, 2_400L, lockedSummary(heard))), run.session.queue)
        // The card and the provider are both behind the unlock.
        assertEquals(emptyList<Effect>(), run.all<Effect.LoadTarget>())
        assertTrue(run.effects.none { it is Effect.WriteEvent })
    }

    @Test
    fun `unlocking a kept sentence opens the card on the draft that was queued`() {
        val run = lockedCapture(Intent.CreateEvent(heard)).send(FlowEvent.Unlocked, at = 9_000)
        val preview = run.state as FlowState.Preview
        assertEquals(heard, preview.draft)
        assertNull("the card is opened, not committed", preview.target)
        assertFalse(run.session.locked)
        assertEquals(emptyList<QueuedDraft>(), run.session.queue)
        // Only now is the provider asked where a commit would go.
        assertEquals(listOf(Effect.LoadTarget), run.all<Effect.LoadTarget>())
        assertEquals(listOf(Effect.PostDraftWaiting(0)), run.all<Effect.PostDraftWaiting>())
    }

    @Test
    fun `the unlocked card still commits, so the queue is a delay and not a dead end`() {
        val run = lockedCapture(Intent.CreateEvent(heard))
            .send(FlowEvent.Unlocked, at = 9_000)
            .send(FlowEvent.TargetLoaded(personal), at = 9_100)
            .send(FlowEvent.Hold(1f), at = 9_500)
        val committing = run.state as FlowState.Committing
        assertEquals(heard, committing.draft)
        assertEquals(listOf(Effect.WriteEvent(heard, personal.calendar.id)), run.all<Effect.WriteEvent>())
    }

    // J6 ---------------------------------------------------------------------

    @Test
    fun `a draft queued at 23_50 and unlocked at 00_10 keeps its date and says when it was said`() {
        val lateNight = ZonedDateTime.of(2026, 9, 16, 23, 50, 0, 0, zone).toInstant().toEpochMilli()
        val afterMidnight = ZonedDateTime.of(2026, 9, 17, 0, 10, 0, 0, zone).toInstant().toEpochMilli()
        // "tomorrow at nine", resolved when it was spoken: the 17th.
        val tomorrow = namedDraft("breakfast", ZonedDateTime.of(2026, 9, 17, 9, 0, 0, 0, zone))

        val run = lockedCapture(Intent.CreateEvent(tomorrow), at = lateNight - 2_400)
            .send(FlowEvent.Unlocked, at = afterMidnight)

        val preview = run.state as FlowState.Preview
        assertEquals("the resolved date is not re-parsed at unlock", tomorrow.start, preview.draft.start)
        assertEquals(LockedCopy.SAID_AT + "23:50", preview.heardNote)
    }

    @Test
    fun `a draft queued and unlocked on the same day carries no note`() {
        val run = lockedCapture(Intent.CreateEvent(heard)).send(FlowEvent.Unlocked, at = 9_000)
        assertNull((run.state as FlowState.Preview).heardNote)
    }

    // J7 ---------------------------------------------------------------------

    @Test
    fun `a second invocation during a live session never opens a second capture`() {
        val locks = listOf(false, true)
        for (first in Origin.entries) {
            for (second in Origin.entries) {
                for (locked in locks) {
                    val opened = Drive()
                        .send(FlowEvent.Invoke(first, locked), at = 0)
                    val live = listOf(
                        opened.session,
                        opened.session.copy(state = FlowState.Listening(0, partial = "din")),
                        opened.session.copy(state = FlowState.Understanding(SENTENCE, since = 0)),
                    )
                    for (session in live) {
                        val run = Drive(session, now = 100)
                            .send(FlowEvent.Invoke(second, locked), at = 100)
                        assertEquals(
                            "$first then $second, locked=$locked, from ${session.state}",
                            emptyList<Effect>(),
                            run.effects,
                        )
                        assertEquals(session, run.session)
                    }
                }
            }
        }
    }

    @Test
    fun `the first invocation is the one that opens the capture`() {
        val run = Drive().send(FlowEvent.Invoke(Origin.Tile, locked = true), at = 0)
        assertEquals(listOf(Effect.StartCapture), run.all<Effect.StartCapture>())
        assertTrue(run.session.locked)
        assertEquals(Origin.Tile, run.session.origin)
    }

    // J8 ---------------------------------------------------------------------

    @Test
    fun `hidden while listening stops the microphone and keeps nothing`() {
        val run = Drive()
            .send(FlowEvent.Invoke(Origin.Assistant, locked = true), at = 0)
            .send(FlowEvent.CaptureStarted, at = 50)
            .send(FlowEvent.PartialHeard("dinner with"), at = 400)
            .send(FlowEvent.Hidden, at = 900)
        assertEquals(FlowState.Idle(), run.state)
        assertEquals(listOf(Effect.StopCapture(discardAudio = true)), run.all<Effect.StopCapture>())
        assertEquals(emptyList<QueuedDraft>(), run.session.queue)
        assertTrue(run.effects.none { it is Effect.PostDraftWaiting })
    }

    @Test
    fun `hidden while a draft is kept leaves it in the queue and says one is waiting`() {
        val run = lockedCapture(Intent.CreateEvent(heard)).send(FlowEvent.Hidden, at = 5_000)
        assertEquals(FlowState.Idle(), run.state)
        assertEquals(listOf(QueuedDraft(heard, 2_400L, lockedSummary(heard))), run.session.queue)
        assertEquals(listOf(Effect.PostDraftWaiting(1)), run.all<Effect.PostDraftWaiting>())
        // And it is still there to unlock into, which is the point of keeping it.
        run.send(FlowEvent.Unlocked, at = 60_000)
        assertEquals(heard, (run.state as FlowState.Preview).draft)
    }

    @Test
    fun `the unlock action asks the host and changes nothing by itself`() {
        val run = lockedCapture(Intent.CreateEvent(heard))
        val before = run.session
        run.send(FlowEvent.UnlockRequested, at = 3_000)
        assertEquals(before, run.session)
        assertEquals(listOf(Effect.RequestUnlock), run.all<Effect.RequestUnlock>())
    }

    @Test
    fun `discarding from the locked screen drops that draft and takes the window down`() {
        val run = lockedCapture(Intent.CreateEvent(heard)).send(FlowEvent.Cancel, at = 4_000)
        assertEquals(FlowState.Idle(), run.state)
        assertEquals(emptyList<QueuedDraft>(), run.session.queue)
        assertEquals(listOf(Effect.HideSession), run.all<Effect.HideSession>())
        assertEquals(listOf(Effect.PostDraftWaiting(0)), run.all<Effect.PostDraftWaiting>())
    }

    // J9 ---------------------------------------------------------------------

    @Test
    fun `five drafts wait, oldest first, and the sixth is refused with no draft`() {
        var run = Drive()
        val titles = listOf("one", "two", "three", "four", "five")
        titles.forEachIndexed { index, title ->
            run = lockedCapture(
                Intent.CreateEvent(namedDraft(title)),
                transcript = title,
                at = index * 10_000L,
                session = run.session,
            )
            run.send(FlowEvent.Hidden, at = index * 10_000L + 5_000)
        }
        assertEquals(QUEUE_CAP, run.session.queue.size)
        assertEquals(titles, run.session.queue.map { it.draft.title.value })

        val sixth = lockedCapture(Intent.CreateEvent(namedDraft("six")), transcript = "six", at = 60_000, session = run.session)
        val fault = sixth.state as FlowState.Fault
        assertEquals(FaultReason.QueueFull, fault.reason)
        assertNull("a refused capture keeps no draft", fault.draft)
        assertEquals("and heard nothing to keep", "", fault.transcript)
        assertEquals("the microphone never opened", emptyList<Effect>(), sixth.all<Effect.StartCapture>())
        assertEquals(titles, sixth.session.queue.map { it.draft.title.value })
    }

    @Test
    fun `after unlock the cards come oldest first, and a discard moves to the next`() {
        var run = Drive()
        val titles = listOf("one", "two", "three")
        titles.forEachIndexed { index, title ->
            run = lockedCapture(
                Intent.CreateEvent(namedDraft(title)),
                transcript = title,
                at = index * 10_000L,
                session = run.session,
            )
            run.send(FlowEvent.Hidden, at = index * 10_000L + 5_000)
        }
        run.effects.clear()
        run.send(FlowEvent.Unlocked, at = 100_000)
        assertEquals("one", (run.state as FlowState.Preview).draft.title.value)
        run.send(FlowEvent.Cancel, at = 101_000)
        assertEquals("two", (run.state as FlowState.Preview).draft.title.value)
        run.send(FlowEvent.Cancel, at = 102_000)
        assertEquals("three", (run.state as FlowState.Preview).draft.title.value)
        assertEquals(listOf(2, 1, 0), run.all<Effect.PostDraftWaiting>().map { it.count })
        run.send(FlowEvent.Cancel, at = 103_000)
        assertEquals(FlowState.Idle("three"), run.state)
    }

    @Test
    fun `a full queue keeps a draft out even if a sentence somehow reaches understanding`() {
        // Not reachable through Invoke, which refuses first. Driven here by
        // building the state directly, because it is the last place a draft can
        // enter memory and the cap is the only thing holding it.
        val full = (1..QUEUE_CAP).map { QueuedDraft(namedDraft("kept $it"), it * 1_000L, lockedSummary(namedDraft("kept $it"))) }
        val session = FlowSession(
            state = FlowState.Understanding(SENTENCE, since = 0, intent = Intent.CreateEvent(heard)),
            locked = true,
            queue = full,
        )
        val run = Drive(session).send(FlowEvent.Tick, at = UNDERSTAND_FLOOR_MS)
        assertEquals(FaultReason.QueueFull, (run.state as FlowState.Fault).reason)
        assertEquals(full, run.session.queue)
    }

    // Section 2.2: a card in hand is not replaced ----------------------------

    @Test
    fun `an invocation over a card refuses and keeps the card`() {
        val run = Drive(FlowSession(state = FlowState.Preview(heard, target = personal, hold = 0.4f, holdSteps = 2)))
            .send(FlowEvent.Invoke(Origin.Shortcut, locked = false), at = 500)
        val preview = run.state as FlowState.Preview
        assertEquals(heard, preview.draft)
        assertEquals(personal, preview.target)
        assertEquals(LockedCopy.CARD_IN_HAND, preview.error)
        assertEquals("no second capture", emptyList<Effect>(), run.all<Effect.StartCapture>())
    }

    // Section 4.3: read-back while locked ------------------------------------

    @Test
    fun `a question heard while locked is shown back and never answered`() {
        val day = Fixtures.thursdayEight
        val questions = listOf(
            Intent.Agenda(day..day.plusHours(12), transcript = "what do i have tomorrow"),
            Intent.Availability(day..day.plusHours(4), transcript = "am i free thursday afternoon"),
            // Intent.CaptureNote left this list by user decision, 2026-09-13 13:32 CEST
            // (M4 row 5): a locked note is now queued for its card, NoteFlowTest (J9).
        )
        for (intent in questions) {
            val transcript = when (intent) {
                is Intent.Agenda -> intent.transcript
                is Intent.Availability -> intent.transcript
                is Intent.CaptureNote -> intent.transcript
                else -> ""
            }
            val run = lockedCapture(intent, transcript = transcript)
            val queued = run.state as FlowState.Queued
            assertNull("a question has no draft to commit", queued.draft)
            assertEquals(questionSummary(transcript), queued.summary)
            assertEquals("nothing is spoken", emptyList<Effect>(), run.all<Effect.Speak>())
            assertEquals("and nothing is read", emptyList<Effect>(), run.all<Effect.LoadTarget>())
            // M9 changed what a kept question is: it queues as a command and
            // the answer surface picks it up on unlock, which is the only
            // road by which the sentence is ever answered. What has not
            // changed is that nothing is read or asked while locked.
            val kept = run.session.queue.single().draft as Pending.Command
            run.send(FlowEvent.Unlocked, at = 30_000)
            assertEquals(FlowState.Idle(transcript), run.state)
            assertEquals(
                "the kept question is handed to the answer surface",
                listOf(Effect.Assist(kept.command)),
                run.all<Effect.Assist>(),
            )
        }
    }

    /**
     * The table M2's `FlowTablesTest` writes for every other state, for this
     * one. It lives here rather than there because the M2 fixtures and their
     * tables are another session's file this morning, and a state whose events
     * need the queue cannot be driven by a state-only table anyway.
     */
    @Test
    fun `the locked screen acts on the four it owns and an invocation, and ignores the rest`() {
        val kept = lockedCapture(Intent.CreateEvent(heard)).session
        val acts = setOf(
            FlowEvent.Unlocked::class.java,
            FlowEvent.Hidden::class.java,
            FlowEvent.Cancel::class.java,
            FlowEvent.UnlockRequested::class.java,
            // A kept draft is already in the queue, so the next sentence is
            // heard rather than refused.
            FlowEvent.Press::class.java,
            FlowEvent.Invoke::class.java,
        )
        val everyEvent = listOf(
            FlowEvent.DownloadRequested, FlowEvent.DownloadProgress("f", 1, 2), FlowEvent.DownloadFailed("x"),
            FlowEvent.ModelsReady, FlowEvent.Press, FlowEvent.Invoke(Origin.Tile, locked = true),
            FlowEvent.CaptureStarted, FlowEvent.PartialHeard("dinner"), FlowEvent.FinalHeard("dinner"),
            FlowEvent.CaptureFailed("x"), FlowEvent.Cancel, FlowEvent.Parsed(Intent.CreateEvent(heard)),
            FlowEvent.Tick, FlowEvent.TargetLoaded(personal), FlowEvent.TargetUnreadable("x"),
            FlowEvent.DatePicked(Fixtures.thursdayEight), FlowEvent.Hold(1f), FlowEvent.WriteSucceeded(42),
            FlowEvent.WriteFailed(denied = false, "x"), FlowEvent.Undo, FlowEvent.Deleted(42, existed = true),
            FlowEvent.DeleteFailed(42), FlowEvent.SpeechStarted, FlowEvent.SpeechEnded,
            FlowEvent.Unlocked, FlowEvent.Hidden, FlowEvent.UnlockRequested,
        )
        for (event in everyEvent) {
            val step = reduce(kept, event, now = 9_999)
            if (event.javaClass in acts) {
                assertTrue("a locked screen should act on $event", step != SessionStep(kept))
            } else {
                assertEquals("a locked screen should ignore $event", SessionStep(kept), step)
            }
        }
        assertEquals(dev.maia.orb.ApertureState.Dormant, kept.state.aperture)
    }

    @Test
    fun `a locked capture is felt and not heard`() {
        val run = lockedCapture(Intent.CreateEvent(heard))
        assertEquals(emptyList<Effect>(), run.all<Effect.Speak>())
        assertNotNull("D2: the acknowledgement is a haptic", run.all<Effect.Haptic>().lastOrNull())
    }
}
