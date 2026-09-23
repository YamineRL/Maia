package dev.maia.app.flow

import dev.maia.app.feel.Schedule
import dev.maia.app.flow.Fixtures.SENTENCE
import dev.maia.app.flow.Fixtures.dateless
import dev.maia.app.flow.Fixtures.heard
import dev.maia.app.flow.Fixtures.personal
import dev.maia.app.flow.Fixtures.readOnly
import dev.maia.app.flow.Fixtures.thursdayEight
import dev.maia.nlu.Edit
import dev.maia.nlu.Field
import dev.maia.nlu.Intent
import dev.maia.nlu.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.ZonedDateTime

/** M2 brief sections 2, 3, 6 and 8: the product's rules, as reducer tests. */
class FlowReducerTest {

    /** Press, capture, one partial, the final, the parse and the floor, then the provider. */
    private fun toPreview(draft: dev.maia.nlu.EventDraft = heard, target: dev.maia.actions.CalendarTarget? = personal): Run =
        Run(FlowState.Idle())
            .send(FlowEvent.Press, at = 0)
            .send(FlowEvent.CaptureStarted, at = 50)
            .send(FlowEvent.PartialHeard("dinner"), at = 300)
            .send(FlowEvent.FinalHeard(draft.transcript), at = 2_000)
            .send(FlowEvent.Parsed(Intent.CreateEvent(draft)), at = 2_005)
            .send(FlowEvent.Tick, at = 2_000 + UNDERSTAND_FLOOR_MS)
            .send(FlowEvent.TargetLoaded(target), at = 2_450)

    private fun toConfirmed(writeDoneAt: Long = 10_000): Run =
        toPreview()
            .send(FlowEvent.Hold(1f), at = 3_000)
            .send(FlowEvent.WriteSucceeded(eventId = 42), at = writeDoneAt)

    @Test
    fun `the handoff's path is reachable end to end`() {
        val run = toConfirmed()
        val confirmed = run.state as FlowState.Confirmed
        assertEquals(42L, confirmed.eventId)
        assertEquals(listOf(Effect.Speak(heard)), run.all<Effect.Speak>())
        run.send(FlowEvent.Tick, at = confirmed.undoDeadline).send(FlowEvent.Cancel)
        assertEquals(FlowState.Idle(), run.state)
    }

    @Test
    fun `the app starts on first run only when the models are absent`() {
        assertEquals(FlowState.FirstRun(), initialState(modelsPresent = false))
        assertEquals(FlowState.Idle(), initialState(modelsPresent = true))
    }

    @Test
    fun `first run reports bytes, starts the download once, and ends on ready`() {
        val run = Run(FlowState.FirstRun())
            .send(FlowEvent.DownloadRequested)
            .send(FlowEvent.DownloadRequested)
            .send(FlowEvent.DownloadProgress("encoder.onnx", 12_000_000, 70_300_000))
        assertEquals(listOf(Effect.DownloadModels), run.effects)
        assertEquals(Download("encoder.onnx", 12_000_000, 70_300_000, running = true), (run.state as FlowState.FirstRun).download)
        run.send(FlowEvent.DownloadFailed("connection reset"))
        assertEquals("connection reset", (run.state as FlowState.FirstRun).download.error)
        run.send(FlowEvent.DownloadRequested).send(FlowEvent.ModelsReady)
        assertEquals(listOf(Effect.DownloadModels, Effect.DownloadModels), run.effects)
        assertEquals(FlowState.Idle(), run.state)
    }

    // ---------------------------------------------------------------- invoke and listen

    @Test
    fun `the invoke haptic plays on the press, with capture`() {
        val step = reduce(FlowState.Idle(), FlowEvent.Press, now = 5)
        assertEquals(FlowState.Invoking(5), step.state)
        assertEquals(listOf(Effect.Haptic(Schedule.invoke), Effect.StartCapture), step.effects)
    }

    @Test
    fun `the first-partial haptic fires exactly once per session`() {
        val run = Run(FlowState.Idle())
            .send(FlowEvent.Press, at = 0)
            .send(FlowEvent.CaptureStarted, at = 40)
            .send(FlowEvent.PartialHeard(""), at = 100)
            .send(FlowEvent.PartialHeard("dinner"), at = 400)
            .send(FlowEvent.PartialHeard("dinner with"), at = 600)
            .send(FlowEvent.PartialHeard("dinner with sam"), at = 900)
        assertEquals(listOf(Effect.Haptic(Schedule.firstPartial(400))), run.all<Effect.Haptic>().drop(1))

        run.send(FlowEvent.Cancel).send(FlowEvent.Press, at = 5_000).send(FlowEvent.PartialHeard("again"), at = 5_300)
        assertEquals(2, run.all<Effect.Haptic>().count { it.pattern == Schedule.firstPartial(300) || it.pattern == Schedule.firstPartial(400) })
    }

    @Test
    fun `a first partial later than 1200 ms uses the late tick`() {
        val onTime = Run(FlowState.Idle()).send(FlowEvent.Press, at = 0).send(FlowEvent.PartialHeard("a"), at = 1_200)
        val late = Run(FlowState.Idle()).send(FlowEvent.Press, at = 0).send(FlowEvent.PartialHeard("a"), at = 1_201)
        assertEquals(0.35f, onTime.all<Effect.Haptic>().last().pattern.pulses.single().scale)
        assertEquals(0.50f, late.all<Effect.Haptic>().last().pattern.pulses.single().scale)
    }

    @Test
    fun `a partial replayed before capture reports started still lands in listening`() {
        val run = Run(FlowState.Invoking(0)).send(FlowEvent.PartialHeard("dinner"), at = 200)
        assertEquals("dinner", (run.state as FlowState.Listening).partial)
    }

    @Test
    fun `cancel during listening discards the audio and neither speaks nor writes`() {
        val run = Run(FlowState.Idle())
            .send(FlowEvent.Press, at = 0)
            .send(FlowEvent.PartialHeard("dinner with"), at = 300)
            .send(FlowEvent.Cancel, at = 500)
        assertEquals(FlowState.Idle(), run.state)
        assertEquals(Effect.StopCapture(discardAudio = true), run.effects.last())
        assertTrue(run.all<Effect.Speak>().isEmpty())
        assertTrue(run.all<Effect.WriteEvent>().isEmpty())
        assertTrue(run.all<Effect.Parse>().isEmpty())
    }

    @Test
    fun `the surface the press came from rides to the parse`() {
        // The run screen leaves composition while the capture holds the
        // window, so the parse cannot ask what was showing: the invocation
        // carries it, stamped once like the keyguard read.
        val run = Run(FlowState.Idle())
            .send(FlowEvent.Invoke(Origin.Launcher, locked = false, surface = Surface.Agent), at = 0)
            .send(FlowEvent.CaptureStarted, at = 50)
            .send(FlowEvent.PartialHeard("din"), at = 300)
            .send(FlowEvent.FinalHeard(SENTENCE), at = 1_000)
        assertEquals(Effect.Parse(SENTENCE, Surface.Agent), run.all<Effect.Parse>().single())
    }

    @Test
    fun `a press over no surface parses neutral`() {
        val run = Run(FlowState.Idle())
            .send(FlowEvent.Press, at = 0)
            .send(FlowEvent.FinalHeard(SENTENCE), at = 1_000)
        assertEquals(Effect.Parse(SENTENCE, Surface.Neutral), run.all<Effect.Parse>().single())
    }

    @Test
    fun `a final of silence returns to idle without parsing`() {
        val step = reduce(FlowState.Listening(0), FlowEvent.FinalHeard("  "), now = 3_000)
        assertEquals(FlowState.Idle(), step.state)
        assertEquals(listOf(Effect.StopCapture(discardAudio = true)), step.effects)
    }

    // ---------------------------------------------------------------- understand

    @Test
    fun `understanding holds for the floor even when the parse is instant`() {
        val run = Run(FlowState.Listening(0, partial = SENTENCE))
            .send(FlowEvent.FinalHeard(SENTENCE), at = 1_000)
        assertEquals(
            listOf(Effect.StopCapture(false), Effect.Parse(SENTENCE), Effect.ScheduleTick(1_000 + UNDERSTAND_FLOOR_MS)),
            run.effects,
        )
        run.send(FlowEvent.Parsed(Intent.CreateEvent(heard)), at = 1_004)
        assertTrue(run.state is FlowState.Understanding)
        run.send(FlowEvent.Tick, at = 1_000 + UNDERSTAND_FLOOR_MS - 1)
        assertTrue(run.state is FlowState.Understanding)
        run.send(FlowEvent.Tick, at = 1_000 + UNDERSTAND_FLOOR_MS)
        assertEquals(FlowState.Preview(heard), run.state)
        assertEquals(Effect.LoadTarget, run.effects.last())
    }

    // Open item A (docs/M4-status.md, 2026-09-14): Parsed was seen not to
    // arrive at all on device, for reasons not yet found. These two lock in
    // the UNDERSTAND_TIMEOUT_MS/UNDERSTAND_POLL_MS backstop itself, which had
    // no coverage -- a change to either constant, or to the give-up branch,
    // would previously have shipped untested.
    @Test
    fun `understanding gives up and returns to idle if Parsed never arrives`() {
        val step = reduce(FlowState.Understanding(SENTENCE, since = 0), FlowEvent.Tick, now = UNDERSTAND_TIMEOUT_MS)
        assertEquals(Step(FlowState.Idle(SENTENCE)), step)
    }

    @Test
    fun `understanding keeps polling before the timeout is reached`() {
        val step = reduce(FlowState.Understanding(SENTENCE, since = 0), FlowEvent.Tick, now = UNDERSTAND_TIMEOUT_MS - 1)
        assertEquals(
            Step(FlowState.Understanding(SENTENCE, since = 0), listOf(Effect.ScheduleTick(UNDERSTAND_TIMEOUT_MS - 1 + UNDERSTAND_POLL_MS))),
            step,
        )
    }

    @Test
    fun `a parse slower than the floor opens the card on arrival`() {
        val step = reduce(FlowState.Understanding(SENTENCE, since = 0), FlowEvent.Parsed(Intent.CreateEvent(heard)), now = 900)
        assertEquals(FlowState.Preview(heard), step.state)
    }

    @Test
    fun `an unparsed sentence still opens the card, even with no date`() {
        val step = reduce(FlowState.Understanding("wibble", since = 0), FlowEvent.Parsed(Intent.Unparsed(dateless)), now = 500)
        assertEquals(FlowState.Preview(dateless), step.state)
    }

    @Test
    fun `notes, agenda and availability return to idle with the transcript and no fault`() {
        val range = thursdayEight..thursdayEight.plusHours(2)
        listOf(
            // Intent.CaptureNote left this list by user decision, 2026-09-13 13:32 CEST
            // (M4 row 5): an unlocked note now opens the note card, NoteFlowTest (J7).
            Intent.Agenda(range),
            Intent.Availability(range),
        ).forEach { intent ->
            val step = reduce(FlowState.Understanding("what do i have", since = 0), FlowEvent.Parsed(intent), now = 500)
            // M9: reads are handed to the answer surface, which replies aloud
            // and on screen; the sentence flow itself still lands on idle.
            assertEquals(
                Step(
                    FlowState.Idle("what do i have"),
                    listOf(Effect.Assist(AssistantCommand.Read(intent, "what do i have"))),
                ),
                step,
            )
        }
    }

    // ---------------------------------------------------------------- 4g, no date heard

    @Test
    fun `the no-date rule is the parser's nothing-temporal signature`() {
        assertTrue(noDateHeard(dateless))
        assertFalse(noDateHeard(heard))
        // A day alone resolves all day, with a heard start.
        assertFalse(noDateHeard(dateless.copy(start = dateless.start.copy(provenance = Provenance.Heard), allDay = true)))
        // A stated length is a temporal phrase.
        assertFalse(noDateHeard(dateless.copy(duration = Field(Duration.ofHours(2), Provenance.Heard))))
        // A start the user set is never faulted.
        assertFalse(noDateHeard(dateless.with(Edit.Start(thursdayEight))))
        // Once the parser records the words behind a start, those words count as heard.
        assertFalse(noDateHeard(dateless.copy(start = dateless.start.copy(span = 3..4))))
    }

    @Test
    fun `an event with no date faults, keeps the transcript, and plays the fault haptic`() {
        val step = reduce(FlowState.Understanding("dinner with sam", since = 0), FlowEvent.Parsed(Intent.CreateEvent(dateless)), now = 500)
        assertEquals(FlowState.Fault(FaultReason.NoDateHeard, "dinner with sam", dateless), step.state)
        assertEquals(listOf(Effect.Haptic(Schedule.fault)), step.effects)
    }

    @Test
    fun `a date picked on the fault resumes to the card with the transcript`() {
        val step = reduce(FlowState.Fault(FaultReason.NoDateHeard, "dinner with sam", dateless), FlowEvent.DatePicked(thursdayEight), now = 0)
        val preview = step.state as FlowState.Preview
        assertEquals(thursdayEight, preview.draft.start.value)
        assertEquals(Provenance.Corrected, preview.draft.start.provenance)
        assertEquals("dinner with sam", preview.draft.transcript)
        assertEquals(listOf(Effect.LoadTarget), step.effects)
    }

    @Test
    fun `a capture failure keeps what was heard and plays the fault haptic`() {
        val run = Run(FlowState.Idle())
            .send(FlowEvent.Press)
            .send(FlowEvent.PartialHeard("dinner with sam"), at = 300)
            .send(FlowEvent.CaptureFailed("microphone busy"), at = 800)
        assertEquals(FlowState.Fault(FaultReason.CaptureFailed("microphone busy"), "dinner with sam"), run.state)
        assertEquals(listOf(Effect.StopCapture(true), Effect.Haptic(Schedule.fault)), run.effects.takeLast(2))
        run.send(FlowEvent.Cancel)
        assertEquals(FlowState.Idle("dinner with sam"), run.state)
    }

    // ---------------------------------------------------------------- preview, hold, commit

    @Test
    fun `no writable calendar blocks commit and no write is ever emitted`() {
        listOf(null, readOnly).forEach { target ->
            val run = toPreview(target = target)
            assertEquals(FlowState.NoCalendar(heard), run.state)
            run.send(FlowEvent.Hold(0.5f)).send(FlowEvent.Hold(1f))
            assertEquals(FlowState.NoCalendar(heard), run.state)
            assertTrue(run.all<Effect.WriteEvent>().isEmpty())
        }
    }

    @Test
    fun `a calendar appearing lifts the block`() {
        val run = toPreview(target = null).send(FlowEvent.TargetLoaded(personal))
        assertEquals(FlowState.Preview(heard, target = personal), run.state)
    }

    @Test
    fun `a hold with the target still unknown resets and does not write`() {
        val step = reduce(FlowState.Preview(heard, target = null, hold = 0.9f, holdSteps = 4), FlowEvent.Hold(1f), now = 0)
        assertEquals(FlowState.Preview(heard, target = null), step.state)
        assertTrue(step.effects.isEmpty())
    }

    @Test
    fun `only a completed hold commits, with the commit haptic`() {
        val run = toPreview()
        val before = run.effects.size
        run.send(FlowEvent.Hold(0.1f)).send(FlowEvent.Hold(0.6f)).send(FlowEvent.Hold(0.99f))
        assertTrue(run.state is FlowState.Preview)
        run.send(FlowEvent.Hold(0f))
        assertEquals(0f, (run.state as FlowState.Preview).hold)
        assertTrue(run.all<Effect.WriteEvent>().isEmpty())

        run.send(FlowEvent.Hold(1f))
        assertEquals(FlowState.Committing(heard, personal), run.state)
        assertEquals(
            listOf(Effect.Haptic(Schedule.commit), Effect.WriteEvent(heard, calendarId = 7)),
            run.effects.takeLast(2),
        )
        assertEquals(Schedule.HOLD_STEPS, run.effects.drop(before).filterIsInstance<Effect.Haptic>().count { it.pattern != Schedule.commit })
    }

    @Test
    fun `the hold steps each play once per press, in order`() {
        val run = Run(FlowState.Preview(heard, target = personal))
            .send(FlowEvent.Hold(0.05f))
            .send(FlowEvent.Hold(0.1f))
            .send(FlowEvent.Hold(0.8f))
        assertEquals((0..3).map { Effect.Haptic(Schedule.holdStep(it)) }, run.effects)
    }

    @Test
    fun `edits go through the draft and clear the error`() {
        val step = reduce(FlowState.Preview(heard, personal, error = "oops"), FlowEvent.Edited(Edit.Title("supper")), now = 0)
        val preview = step.state as FlowState.Preview
        assertEquals("supper", preview.draft.title.value)
        assertNull(preview.error)
    }

    @Test
    fun `a denied write returns to the card with M1's message and the fault haptic`() {
        val step = reduce(FlowState.Committing(heard, personal), FlowEvent.WriteFailed(denied = true), now = 0)
        assertEquals(FlowState.Preview(heard, personal, error = WRITE_DENIED), step.state)
        assertEquals(listOf(Effect.Haptic(Schedule.fault)), step.effects)
    }

    @Test
    fun `discarding the card keeps the transcript`() {
        assertEquals(FlowState.Idle(SENTENCE), reduce(FlowState.Preview(heard), FlowEvent.Cancel, 0).state)
    }

    // ---------------------------------------------------------------- confirm and undo

    @Test
    fun `the undo window is 8 s from the write completing, not from the hold`() {
        val run = toConfirmed(writeDoneAt = 10_000)
        assertEquals(10_000 + UNDO_WINDOW_MS, (run.state as FlowState.Confirmed).undoDeadline)
        assertEquals(Effect.ScheduleTick(18_000), run.effects.last())
        // 8 s after the hold began is 11 s; undo is still available at 17.999 s.
        run.send(FlowEvent.Undo, at = 17_999)
        assertEquals(listOf(Effect.DeleteEvent(42)), run.all<Effect.DeleteEvent>())
    }

    @Test
    fun `undo after the window is impossible`() {
        val run = toConfirmed(writeDoneAt = 10_000).send(FlowEvent.Undo, at = 18_000)
        assertEquals(UndoStatus.Expired, (run.state as FlowState.Confirmed).undo)
        assertTrue(run.all<Effect.DeleteEvent>().isEmpty())
        run.send(FlowEvent.Undo, at = 18_001)
        assertTrue(run.all<Effect.DeleteEvent>().isEmpty())
    }

    @Test
    fun `the tick closes the window`() {
        val run = toConfirmed(writeDoneAt = 10_000).send(FlowEvent.Tick, at = 17_999)
        assertEquals(UndoStatus.Offered, (run.state as FlowState.Confirmed).undo)
        run.send(FlowEvent.Tick, at = 18_000).send(FlowEvent.Undo, at = 18_000)
        assertEquals(UndoStatus.Expired, (run.state as FlowState.Confirmed).undo)
        assertTrue(run.all<Effect.DeleteEvent>().isEmpty())
    }

    @Test
    fun `undo deletes by the id the write returned and never reopens the card`() {
        val run = toConfirmed().send(FlowEvent.Undo, at = 11_000).send(FlowEvent.Undo, at = 11_001)
        assertEquals(listOf(Effect.DeleteEvent(42)), run.all<Effect.DeleteEvent>())
        // A result for some other id is not this undo's answer.
        run.send(FlowEvent.Deleted(eventId = 41, existed = true))
        assertEquals(UndoStatus.Undoing, (run.state as FlowState.Confirmed).undo)
        run.send(FlowEvent.Deleted(eventId = 42, existed = true))
        val confirmed = run.state as FlowState.Confirmed
        assertEquals(UndoStatus.Undone, confirmed.undo)
        assertEquals(1, run.all<Effect.LoadTarget>().size)
    }

    @Test
    fun `a delete that found nothing says already gone, not undone`() {
        val run = toConfirmed().send(FlowEvent.Undo, at = 11_000).send(FlowEvent.Deleted(42, existed = false))
        assertEquals(UndoStatus.AlreadyGone, (run.state as FlowState.Confirmed).undo)
    }

    @Test
    fun `a delete that threw never claims an undo`() {
        val run = toConfirmed().send(FlowEvent.Undo, at = 11_000).send(FlowEvent.DeleteFailed(42, "provider died"))
        assertEquals(UndoStatus.Failed, (run.state as FlowState.Confirmed).undo)
    }

    @Test
    fun `leaving the screen ends the window and keeps the event`() {
        val run = toConfirmed().send(FlowEvent.SpeechStarted, at = 10_100).send(FlowEvent.Cancel, at = 11_000)
        assertEquals(FlowState.Idle(), run.state)
        assertEquals(Effect.StopSpeaking, run.effects.last())
        run.send(FlowEvent.Undo, at = 11_100)
        assertTrue(run.all<Effect.DeleteEvent>().isEmpty())
    }

    @Test
    fun `a new invocation ends the window and keeps the event`() {
        val run = toConfirmed().send(FlowEvent.Press, at = 11_000)
        assertEquals(FlowState.Invoking(11_000), run.state)
        run.send(FlowEvent.Undo, at = 11_100)
        assertTrue(run.all<Effect.DeleteEvent>().isEmpty())
    }

    @Test
    fun `a late delete result after leaving changes nothing`() {
        val run = toConfirmed().send(FlowEvent.Undo, at = 11_000).send(FlowEvent.Cancel).send(FlowEvent.Deleted(42, true))
        assertEquals(FlowState.Idle(), run.state)
    }

    @Test
    fun `speaking is tracked on the confirmation`() {
        val run = toConfirmed().send(FlowEvent.SpeechStarted)
        assertTrue((run.state as FlowState.Confirmed).speaking)
        run.send(FlowEvent.SpeechEnded)
        assertFalse((run.state as FlowState.Confirmed).speaking)
    }

    @Test
    fun `retry from a fault is a fresh invocation`() {
        val step = reduce(FlowState.Fault(FaultReason.CaptureFailed("x"), "dinner"), FlowEvent.Press, now = 9)
        assertEquals(Step(FlowState.Invoking(9), listOf(Effect.Haptic(Schedule.invoke), Effect.StartCapture)), step)
    }

    @Test
    fun `a date picked on a capture fault does nothing`() {
        val state = FlowState.Fault(FaultReason.CaptureFailed("x"), "dinner")
        assertEquals(Step(state), reduce(state, FlowEvent.DatePicked(ZonedDateTime.now(Fixtures.zone)), 0))
    }
}
