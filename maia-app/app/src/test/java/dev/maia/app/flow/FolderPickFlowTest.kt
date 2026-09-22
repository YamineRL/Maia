package dev.maia.app.flow

import dev.maia.actions.notes.FakeNotes
import dev.maia.actions.notes.Note
import dev.maia.actions.notes.NotesFolder
import dev.maia.nlu.Field
import dev.maia.nlu.Intent
import dev.maia.nlu.Provenance
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

/**
 * M4 row 7: what the host sends after the folder picker, through the reducer.
 *
 * `MainActivity` takes the grant and then sends `loadNotesFolder`'s answer,
 * the same event `Effect.LoadNotesFolder` produces. These pin that this is
 * enough: a picked folder returns the held note to its card with the folder
 * named and writes nothing, and a pick that took no grant leaves the screen as
 * it was.
 */
class FolderPickFlowTest {

    private val capturedAt: ZonedDateTime = ZonedDateTime.of(2026, 9, 13, 10, 0, 0, 0, Fixtures.zone)
    private val vet = Intent.CaptureNote(Field("call the vet", Provenance.Heard, 1..3), transcript = "note call the vet")
    private val vetNote = Note.of(vet, capturedAt)
    private val folder: NotesFolder = FakeNotes.defaultFolder

    @Test
    fun `a picked folder returns the held note to its card, named, and writes nothing`() = runTest {
        val noFolder = reduce(FlowState.NotePreview(vetNote), loadNotesFolder(FakeNotes(folder = null)), now = 0)
        assertEquals(FlowState.NoFolder(vetNote), noFolder.state)

        val picked = reduce(noFolder.state, loadNotesFolder(FakeNotes(folder = folder)), now = 10)
        assertEquals(FlowState.NotePreview(vetNote, folder), picked.state)
        assertTrue(picked.effects.none { it is Effect.WriteNote })
    }

    @Test
    fun `a pick that took no grant re-reads to nothing and leaves the screen unchanged`() = runTest {
        val step = reduce(FlowState.NoFolder(vetNote), loadNotesFolder(FakeNotes(folder = null)), now = 0)
        assertEquals(FlowState.NoFolder(vetNote), step.state)
        assertTrue(step.effects.isEmpty())
    }
}
