package dev.maia.app.flow

import dev.maia.actions.CalendarTarget
import dev.maia.actions.MaiaCalendar
import dev.maia.actions.notes.FakeNotes
import dev.maia.actions.notes.Note
import dev.maia.actions.notes.NoteMarkdown
import dev.maia.actions.notes.NoteRef
import dev.maia.actions.notes.NotesFolder
import dev.maia.app.feel.Schedule
import dev.maia.app.flow.Fixtures.heard
import dev.maia.app.flow.Fixtures.personal
import dev.maia.app.flow.Fixtures.thursdayEight
import dev.maia.nlu.Edit
import dev.maia.nlu.Field
import dev.maia.nlu.Intent
import dev.maia.nlu.Provenance
import dev.maia.orb.ApertureState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.time.ZonedDateTime

/**
 * M4 brief section 5.2 and section 6: the note states, the note effects and the
 * locked note path, as reducer tests. Criteria J7 and J9 to J13, and J16. J8 is
 * proved by search in [NoteInvariantTest].
 */
class NoteFlowTest {

    private val said = "note call the vet"
    private val capturedAt: ZonedDateTime = ZonedDateTime.of(2026, 9, 13, 10, 0, 0, 0, Fixtures.zone)
    private val vet = Intent.CaptureNote(Field("call the vet", Provenance.Heard, 1..3), transcript = said)
    private val vetNote = Note.of(vet, capturedAt)
    private val folder: NotesFolder = FakeNotes.defaultFolder
    private val ref = NoteRef("2026-09-13.md", "fake://notes/2026-09-13.md", createdFile = true)

    /** Press, capture, the final, the parse and the floor: M2's road, with a note. */
    private fun toNotePreview(intent: Intent.CaptureNote = vet): Run =
        Run(FlowState.Idle())
            .send(FlowEvent.Press, at = 0)
            .send(FlowEvent.CaptureStarted, at = 50)
            .send(FlowEvent.FinalHeard(said), at = 2_000)
            .send(FlowEvent.Parsed(intent, capturedAt), at = 2_005)
            .send(FlowEvent.Tick, at = 2_000 + UNDERSTAND_FLOOR_MS)

    /** The session reducer, one event after another, keeping every effect. */
    private class NoteDrive(var session: FlowSession = FlowSession()) {
        val effects = mutableListOf<Effect>()
        val state: FlowState get() = session.state

        fun send(event: FlowEvent, at: Long): NoteDrive {
            val step = reduce(session, event, at)
            session = step.session
            effects += step.effects
            return this
        }
    }

    /** A locked capture that parses to [intent]; the floor lands at [from] + 2 400. */
    private fun locked(intent: Intent, from: Long = 0, drive: NoteDrive = NoteDrive()): NoteDrive = drive
        .send(FlowEvent.Invoke(Origin.Launcher, locked = true), at = from)
        .send(FlowEvent.CaptureStarted, at = from + 50)
        .send(FlowEvent.FinalHeard(said), at = from + 2_000)
        .send(FlowEvent.Parsed(intent, capturedAt), at = from + 2_005)
        .send(FlowEvent.Tick, at = from + 2_400)

    // J7 ----------------------------------------------------------------------

    @Test
    fun `an unlocked note opens the note card and asks for the folder, and writes nothing`() {
        val step = reduce(FlowState.Understanding(said, since = 0), FlowEvent.Parsed(vet, capturedAt), now = 500)
        assertEquals(Step(FlowState.NotePreview(vetNote), listOf(Effect.LoadNotesFolder)), step)

        val run = toNotePreview()
        assertEquals(FlowState.NotePreview(vetNote), run.state)
        assertEquals(listOf(Effect.LoadNotesFolder), run.all<Effect.LoadNotesFolder>())
        assertEquals(emptyList<Effect>(), run.all<Effect.WriteNote>())
        assertEquals(emptyList<Effect>(), run.all<Effect.LoadTarget>())
    }

    @Test
    fun `a note parsed without its own transcript keeps the sentence on screen`() {
        val run = toNotePreview(vet.copy(transcript = ""))
        assertEquals(said, (run.state as FlowState.NotePreview).note.transcript)
    }

    @Test
    fun `a note parsed with no capture time is not filed anywhere`() {
        // Only a hand-built event lacks the stamp; the runner always adds it.
        val step = reduce(FlowState.Understanding(said, since = 0), FlowEvent.Parsed(vet), now = 500)
        assertEquals(Step(FlowState.Idle(said)), step)
    }

    // J9 ----------------------------------------------------------------------

    @Test
    fun `a locked note is queued, and the unlock opens its card with the same note`() {
        val drive = locked(vet)
        val queued = drive.state as FlowState.Queued
        assertEquals(vetNote, queued.note)
        assertNull("a note is not an event draft", queued.draft)
        assertEquals(noteSummary(vetNote), queued.summary)
        assertEquals(listOf(QueuedDraft(Pending.Note(vetNote), 2_400, noteSummary(vetNote))), drive.session.queue)
        assertTrue(drive.effects.none { it is Effect.LoadNotesFolder || it is Effect.WriteNote })

        val before = drive.effects.size
        drive.send(FlowEvent.Unlocked, at = 30_000)
        assertEquals(FlowState.NotePreview(vetNote), drive.state)
        assertEquals(emptyList<QueuedDraft>(), drive.session.queue)
        assertEquals(listOf(Effect.LoadNotesFolder, Effect.PostDraftWaiting(0)), drive.effects.drop(before))
    }

    @Test
    fun `a note and an event queued together come back oldest first`() {
        val eventFirst = locked(Intent.CreateEvent(heard))
        locked(vet, from = 10_000, drive = eventFirst)
        assertEquals(listOf("dinner with sam", "call the vet"), eventFirst.session.queue.map { it.draft.title.value })
        eventFirst.send(FlowEvent.Unlocked, at = 30_000)
        assertEquals(heard, (eventFirst.state as FlowState.Preview).draft)
        eventFirst.send(FlowEvent.Cancel, at = 31_000)
        assertEquals(FlowState.NotePreview(vetNote), eventFirst.state)

        val noteFirst = locked(vet)
        locked(Intent.CreateEvent(heard), from = 10_000, drive = noteFirst)
        noteFirst.send(FlowEvent.Unlocked, at = 30_000)
        assertEquals(FlowState.NotePreview(vetNote), noteFirst.state)
        noteFirst.send(FlowEvent.Cancel, at = 31_000)
        assertEquals(heard, (noteFirst.state as FlowState.Preview).draft)
    }

    @Test
    fun `discarding a kept note drops that note and only that note`() {
        val drive = locked(Intent.CreateEvent(heard))
        locked(vet, from = 10_000, drive = drive)
        drive.send(FlowEvent.Cancel, at = 13_000)
        assertEquals(listOf("dinner with sam"), drive.session.queue.map { it.draft.title.value })
    }

    // J10 ---------------------------------------------------------------------

    @Test
    fun `the note summary is built from the note alone and has nowhere to put a folder`() {
        assertEquals(LockedSummary("call the vet", "", said), noteSummary(vetNote))
        val reminded = vetNote.copy(remindAt = Field(thursdayEight, Provenance.Heard))
        assertEquals("Thursday 17 September at 20:00", noteSummary(reminded).whenText)

        val method = Class.forName("dev.maia.app.flow.LockedSummaryKt").declaredMethods.single { it.name == "noteSummary" }
        assertEquals(listOf<Class<*>>(Note::class.java), method.parameterTypes.toList())

        val forbidden = setOf(NotesFolder::class.java, NoteRef::class.java, CalendarTarget::class.java, MaiaCalendar::class.java)
        Note::class.java.declaredFields.forEach { assertFalse("Note.${it.name}", it.type in forbidden) }
        LockedSummary::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.forEach {
            assertEquals("LockedSummary.${it.name}", String::class.java, it.type)
        }
    }

    // J11 ---------------------------------------------------------------------

    @Test
    fun `the note hold commits exactly once`() {
        val run = toNotePreview()
            .send(FlowEvent.NotesFolderLoaded(folder), at = 2_500)
            .send(FlowEvent.Hold(0.5f), at = 2_800)
            .send(FlowEvent.Hold(1f), at = 3_100)
            .send(FlowEvent.Hold(1f), at = 3_150)
        assertEquals(listOf(Effect.WriteNote(vetNote)), run.all<Effect.WriteNote>())
        assertEquals(FlowState.Writing(vetNote, folder), run.state)
    }

    @Test
    fun `the note hold feels exactly like the event hold`() {
        val note = reduce(FlowState.NotePreview(vetNote, folder), FlowEvent.Hold(0.5f), now = 0)
        val event = reduce(FlowState.Preview(heard, target = personal), FlowEvent.Hold(0.5f), now = 0)
        assertEquals(event.effects, note.effects)
        assertEquals((event.state as FlowState.Preview).holdSteps, (note.state as FlowState.NotePreview).holdSteps)
        val commit = reduce(FlowState.NotePreview(vetNote, folder), FlowEvent.Hold(1f), now = 0)
        assertEquals(listOf(Effect.Haptic(Schedule.commit), Effect.WriteNote(vetNote)), commit.effects)
    }

    @Test
    fun `a hold at one before the folder is known resets and writes nothing`() {
        val run = toNotePreview().send(FlowEvent.Hold(1f), at = 2_450)
        assertEquals(FlowState.NotePreview(vetNote), run.state)
        assertEquals(emptyList<Effect>(), run.all<Effect.WriteNote>())
    }

    // J12 ---------------------------------------------------------------------

    @Test
    fun `a failed write faults with the transcript verbatim, and a gone folder holds the note`() {
        val writing = FlowState.Writing(vetNote, folder)
        val failed = reduce(writing, FlowEvent.NoteWriteFailed("disk full"), now = 5_000)
        assertEquals(
            Step(FlowState.Fault(FaultReason.NoteWriteFailed("disk full"), said), listOf(Effect.Haptic(Schedule.fault))),
            failed,
        )
        assertEquals(Step(FlowState.Idle(said)), reduce(failed.state, FlowEvent.Cancel, now = 6_000))

        val gone = reduce(writing, FlowEvent.NoteWriteFailed("revoked", folderGone = true), now = 5_000)
        assertEquals(FlowState.NoFolder(vetNote), gone.state)
        assertTrue(gone.effects.none { it is Effect.WriteNote })
        val picked = reduce(gone.state, FlowEvent.NotesFolderLoaded(folder), now = 7_000)
        assertEquals(FlowState.NotePreview(vetNote, folder), picked.state)
    }

    @Test
    fun `the runner tells a folder that went away from a write that failed`() = runTest {
        val gone = FakeNotes().apply { folderRevoked = true }
        assertTrue((writeNote(gone, vetNote) as FlowEvent.NoteWriteFailed).folderGone)
        val denied = FakeNotes().apply { denyWrites = true }
        assertFalse((writeNote(denied, vetNote) as FlowEvent.NoteWriteFailed).folderGone)
        val empty = vetNote.copy(body = Field("", Provenance.Heard))
        assertFalse((writeNote(FakeNotes(), empty) as FlowEvent.NoteWriteFailed).folderGone)
    }

    // J13 ---------------------------------------------------------------------

    @Test
    fun `no folder gives the no-folder screen and never a write`() {
        val run = toNotePreview().send(FlowEvent.NotesFolderLoaded(null), at = 2_500)
        assertEquals(FlowState.NoFolder(vetNote), run.state)
        run.send(FlowEvent.Hold(1f), at = 3_000)
            .send(FlowEvent.Hold(1f), at = 3_100)
            .send(FlowEvent.Tick, at = 4_000)
            .send(FlowEvent.NoteWritten(ref), at = 4_100)
        assertEquals(FlowState.NoFolder(vetNote), run.state)
        assertEquals(emptyList<Effect>(), run.all<Effect.WriteNote>())

        run.send(FlowEvent.NotesFolderLoaded(folder), at = 5_000).send(FlowEvent.Hold(1f), at = 5_600)
        assertEquals(listOf(Effect.WriteNote(vetNote)), run.all<Effect.WriteNote>())
    }

    @Test
    fun `the runner reports no folder as null, not as a fault`() = runTest {
        assertEquals(FlowEvent.NotesFolderLoaded(null), loadNotesFolder(FakeNotes(folder = null)))
        assertEquals(FlowEvent.NotesFolderLoaded(folder), loadNotesFolder(FakeNotes()))
    }

    @Test
    fun `loading the folder warms the day the note will be filed under`() = runTest {
        // The spike measured the append at a median of 15 ms and the children
        // query that finds today's file at about 345 ms in a folder of 100
        // files, so the storage layer is asked to resolve when the card opens
        // and not under the hold. This asserts the call site still makes that
        // call: the reducer cannot see it, so nothing else would.
        val notes = FakeNotes()
        assertEquals(FlowEvent.NotesFolderLoaded(folder), loadNotesFolder(notes, vetNote.date))
        assertEquals(listOf(vetNote.date), notes.prepared)
        assertTrue("a warm writes nothing", notes.files.isEmpty())
    }

    @Test
    fun `a warm that fails is not news, and a folderless one is never asked for`() = runTest {
        // A failed warm costs the resolve happening later after all. Turning it
        // into a fault would put a stack trace in front of a user whose note is
        // still perfectly writable.
        val failing = FakeNotes().apply { prepareFails = true }
        assertEquals(FlowEvent.NotesFolderLoaded(folder), loadNotesFolder(failing, vetNote.date))

        val none = FakeNotes(folder = null)
        assertEquals(FlowEvent.NotesFolderLoaded(null), loadNotesFolder(none, vetNote.date))
        assertEquals("there is nothing to warm without a folder", emptyList<Any>(), none.prepared)
    }

    // J16 ---------------------------------------------------------------------

    @Test
    fun `nothing is spoken, read or written on a locked note path`() {
        val drive = locked(vet)
            .send(FlowEvent.UnlockRequested, at = 3_000)
            .send(FlowEvent.Hidden, at = 3_100)
        locked(vet, from = 4_000, drive = drive)
            .send(FlowEvent.Hold(1f), at = 7_000)
            .send(FlowEvent.NotesFolderLoaded(folder), at = 7_100)
            .send(FlowEvent.NoteWritten(ref), at = 7_200)
            .send(FlowEvent.Cancel, at = 7_300)
        assertTrue(drive.session.locked)
        // The closing Cancel discards the second kept note, so one is left.
        assertEquals(1, drive.session.queue.size)
        assertTrue(
            drive.effects.none {
                it is Effect.Speak || it is Effect.WriteNote || it is Effect.LoadNotesFolder || it is Effect.LoadTarget
            },
        )
    }

    @Test
    fun `FakeNotes answers carry an unlocked note from the sentence to its confirmation, silently`() = runTest {
        val notes = FakeNotes()
        val run = toNotePreview()
        run.send(loadNotesFolder(notes), at = 2_500)
        run.send(FlowEvent.Hold(1f), at = 3_200)
        run.send(writeNote(notes, run.all<Effect.WriteNote>().single().note), at = 3_300)
        val done = run.state as FlowState.NoteConfirmed
        assertEquals(vetNote, done.note)
        assertEquals(NoteMarkdown.fileName(vetNote), done.ref.fileName)
        assertTrue(notes.fileOn(vetNote.date).orEmpty().contains("call the vet"))
        // D4: a note's confirmation is Effect.SpeakNote, never Effect.Speak, because
        // "Noted." says nothing from the note's own words (docs/M4-copy.md section 4).
        assertTrue(run.all<Effect.Speak>().isEmpty())
        assertEquals(listOf(Effect.SpeakNote), run.all<Effect.SpeakNote>())
        assertEquals(Step(FlowState.Idle()), reduce(done, FlowEvent.Cancel, now = 4_000))
        assertEquals(FlowState.Invoking(4_000), reduce(done, FlowEvent.Press, now = 4_000).state)
    }

    // The new states' tables ------------------------------------------------

    @Test
    fun `the note states ignore every event that is not theirs`() {
        val everywhere = listOf(
            FlowEvent.Tick, FlowEvent.Undo, FlowEvent.TargetLoaded(personal), FlowEvent.TargetUnreadable("x"),
            FlowEvent.WriteSucceeded(42), FlowEvent.WriteFailed(denied = false), FlowEvent.Deleted(42, existed = true),
            FlowEvent.SpeechStarted, FlowEvent.SpeechEnded, FlowEvent.CaptureStarted, FlowEvent.PartialHeard("din"),
            FlowEvent.FinalHeard(said), FlowEvent.DatePicked(thursdayEight), FlowEvent.Edited(Edit.Start(thursdayEight)),
            FlowEvent.Parsed(vet, capturedAt), FlowEvent.DownloadRequested, FlowEvent.ModelsReady,
        )
        val own = mapOf<FlowState, List<FlowEvent>>(
            FlowState.NotePreview(vetNote, folder) to listOf(FlowEvent.NoteWritten(ref), FlowEvent.NoteWriteFailed("x")),
            FlowState.NoFolder(vetNote) to listOf(
                FlowEvent.NoteWritten(ref), FlowEvent.NoteWriteFailed("x"), FlowEvent.NotesFolderLoaded(null),
                FlowEvent.Hold(1f), FlowEvent.Press,
            ),
            FlowState.Writing(vetNote, folder) to listOf(
                FlowEvent.NotesFolderLoaded(folder), FlowEvent.Hold(1f), FlowEvent.Cancel, FlowEvent.Press,
            ),
            FlowState.NoteConfirmed(vetNote, ref) to listOf(
                FlowEvent.NoteWritten(ref), FlowEvent.NoteWriteFailed("x"), FlowEvent.NotesFolderLoaded(folder),
                FlowEvent.Hold(1f),
            ),
        )
        own.forEach { (state, ignored) ->
            (everywhere + ignored).forEach { event ->
                assertEquals("$state on $event", Step(state), reduce(state, event, now = 9_000))
            }
        }
    }

    @Test
    fun `the note states rest the orb except while writing`() {
        assertEquals(
            listOf(ApertureState.Dormant, ApertureState.Dormant, ApertureState.Thinking, ApertureState.Dormant),
            listOf(
                FlowState.NotePreview(vetNote),
                FlowState.NoFolder(vetNote),
                FlowState.Writing(vetNote, folder),
                FlowState.NoteConfirmed(vetNote, ref),
            ).map { it.aperture },
        )
    }
}
