package dev.maia.app.flow

import dev.maia.actions.notes.FakeNotes
import dev.maia.actions.notes.Note
import dev.maia.nlu.Field
import dev.maia.nlu.Intent
import dev.maia.nlu.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

/**
 * "Try again" on the note write fault (`docs/M4-copy.md` section 2, M4 row 8):
 * it reopens the card with the words intact and never writes by itself.
 */
class NoteRetryFlowTest {

    private val at: ZonedDateTime = ZonedDateTime.of(2026, 9, 13, 10, 0, 0, 0, Fixtures.zone)
    private val said = "note call the vet"
    private val vet = Note.of(Intent.CaptureNote(Field("call the vet", Provenance.Heard), transcript = said), at)
    private val folder = FakeNotes.defaultFolder
    private val writing = FlowSession(state = FlowState.Writing(vet, folder))

    @Test
    fun `a failed write keeps the note beside the fault, whose shape is unchanged`() {
        val failed = reduce(writing, FlowEvent.NoteWriteFailed("disk full"), now = 5_000)
        assertEquals(FlowState.Fault(FaultReason.NoteWriteFailed("disk full"), said), failed.session.state)
        assertEquals(vet, failed.session.retryNote)
    }

    @Test
    fun `try again reopens the note card, asks for the folder, and writes nothing`() {
        val failed = reduce(writing, FlowEvent.NoteWriteFailed("disk full"), now = 5_000).session
        val retry = reduce(failed, FlowEvent.RetryNote, now = 6_000)
        assertEquals(FlowState.NotePreview(vet), retry.session.state)
        assertEquals(listOf(Effect.LoadNotesFolder), retry.effects)
        assertNull(retry.session.retryNote)
        val loaded = reduce(retry.session, FlowEvent.NotesFolderLoaded(folder), now = 6_100)
        assertTrue("only the hold writes", loaded.effects.none { it is Effect.WriteNote })
    }

    @Test
    fun `leaving the fault forgets the note`() {
        val failed = reduce(writing, FlowEvent.NoteWriteFailed("disk full"), now = 5_000).session
        val left = reduce(failed, FlowEvent.Cancel, now = 6_000)
        assertTrue(left.session.state is FlowState.Idle)
        assertNull(left.session.retryNote)
        assertEquals(left.session, reduce(left.session, FlowEvent.RetryNote, now = 7_000).session)
    }

    @Test
    fun `try again anywhere else does nothing`() {
        val gone = reduce(writing, FlowEvent.NoteWriteFailed("revoked", folderGone = true), now = 5_000)
        assertEquals(FlowState.NoFolder(vet), gone.session.state)
        assertNull(gone.session.retryNote)
        val other = FlowSession(state = FlowState.Fault(FaultReason.QueueFull, said), retryNote = vet)
        assertEquals(SessionStep(other.copy(retryNote = null)), reduce(other, FlowEvent.RetryNote, now = 6_000))
    }
}
