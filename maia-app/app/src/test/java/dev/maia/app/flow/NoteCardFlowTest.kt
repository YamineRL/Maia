package dev.maia.app.flow

import dev.maia.actions.notes.FakeNotes
import dev.maia.actions.notes.Note
import dev.maia.actions.notes.NoteRef
import dev.maia.app.flow.Fixtures.heard
import dev.maia.app.flow.Fixtures.personal
import dev.maia.nlu.Field
import dev.maia.nlu.Intent
import dev.maia.nlu.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

/**
 * M4 row 8's reducer edges: U2's "Keep as a note instead" on the event card,
 * editing a note's body on the card, and D4's spoken "Noted.".
 */
class NoteCardFlowTest {

    private val at: ZonedDateTime = ZonedDateTime.of(2026, 9, 13, 10, 0, 0, 0, Fixtures.zone)
    private val said = "note call the vet"
    private val vet = Note.of(Intent.CaptureNote(Field("call the vet", Provenance.Heard), transcript = said), at)
    private val folder = FakeNotes.defaultFolder
    private val ref = NoteRef("2026-09-13.md", null, createdFile = true)

    private val forbiddenWhileLocked: (Effect) -> Boolean = {
        it is Effect.WriteNote || it is Effect.WriteEvent || it == Effect.LoadNotesFolder ||
            it == Effect.LoadTarget || it is Effect.Speak || it == Effect.SpeakNote
    }

    @Test
    fun `keep as a note turns the event card's words into a note card and asks for the folder`() {
        val step = reduce(FlowState.Preview(heard, personal), FlowEvent.KeepAsNote(at), now = 3_000)
        assertEquals(Step(FlowState.NotePreview(Note.of(heard, at)), listOf(Effect.LoadNotesFolder)), step)
        assertTrue("keeping as a note writes nothing", step.effects.none { it is Effect.WriteNote || it is Effect.WriteEvent })
    }

    @Test
    fun `keep as a note on a locked phone keeps the note and reads nothing`() {
        val step = reduce(FlowState.Preview(heard, personal), FlowEvent.KeepAsNote(at), now = 3_000, locked = true)
        val kept = step.state as FlowState.Queued
        assertEquals(Note.of(heard, at), kept.note)
        assertEquals(noteSummary(Note.of(heard, at)), kept.summary)
        assertTrue(step.effects.none(forbiddenWhileLocked))
    }

    @Test
    fun `keep as a note is only an event card's action`() {
        val card = FlowState.NotePreview(vet, folder)
        assertEquals(card, reduce(card, FlowEvent.KeepAsNote(at), now = 0).state)
        assertEquals(emptyList<Effect>(), reduce(card, FlowEvent.KeepAsNote(at), now = 0).effects)
        val done = FlowState.NoteConfirmed(vet, ref)
        assertEquals(done, reduce(done, FlowEvent.KeepAsNote(at), now = 0).state)
    }

    @Test
    fun `an edited body is the user's words, trimmed, and clears the card's error`() {
        val card = FlowState.NotePreview(vet, folder, error = "could not write")
        val step = reduce(card, FlowEvent.NoteBodyEdited("  call the vet about Rex "), now = 0)
        assertEquals(
            Step(FlowState.NotePreview(vet.copy(body = Field("call the vet about Rex", Provenance.Corrected)), folder)),
            step,
        )
    }

    @Test
    fun `a blank body is not an edit`() {
        val card = FlowState.NotePreview(vet, folder)
        listOf("", "   ").forEach {
            val step = reduce(card, FlowEvent.NoteBodyEdited(it), now = 0)
            assertEquals(card, step.state)
            assertEquals(emptyList<Effect>(), step.effects)
        }
    }

    @Test
    fun `a body edit anywhere but the note card changes nothing`() {
        listOf(FlowState.Writing(vet, folder), FlowState.NoteConfirmed(vet, ref), FlowState.Preview(heard, personal)).forEach {
            val step = reduce(it, FlowEvent.NoteBodyEdited("something else"), now = 0)
            assertEquals(it, step.state)
            assertEquals(emptyList<Effect>(), step.effects)
        }
    }

    @Test
    fun `a written note is confirmed and says Noted once`() {
        val step = reduce(FlowState.Writing(vet, folder), FlowEvent.NoteWritten(ref), now = 0)
        assertEquals(Step(FlowState.NoteConfirmed(vet, ref), listOf(Effect.SpeakNote)), step)
        assertTrue("never the event sentence", step.effects.none { it is Effect.Speak })
    }

    @Test
    fun `the confirmation's own speech events do not say it again`() {
        val done = FlowState.NoteConfirmed(vet, ref)
        listOf(FlowEvent.SpeechStarted, FlowEvent.SpeechEnded, FlowEvent.NoteWritten(ref)).forEach {
            assertEquals(Step(done), reduce(done, it, now = 0))
        }
    }

    @Test
    fun `a locked note never reaches the spoken confirmation`() {
        var session = FlowSession()
        val effects = mutableListOf<Effect>()
        listOf(
            FlowEvent.Invoke(Origin.Launcher, locked = true),
            FlowEvent.CaptureStarted,
            FlowEvent.FinalHeard(said),
            FlowEvent.Parsed(Intent.CaptureNote(Field("call the vet", Provenance.Heard), transcript = said), at),
            FlowEvent.Tick,
            FlowEvent.Hold(1f),
            FlowEvent.NotesFolderLoaded(folder),
            FlowEvent.NoteWritten(ref),
        ).forEachIndexed { i, event ->
            val step = reduce(session, event, now = 1_000L + i * 2_400L)
            session = step.session
            effects += step.effects
        }
        assertTrue(session.locked)
        assertTrue(effects.none(forbiddenWhileLocked))
    }
}
