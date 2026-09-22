package dev.maia.app.screens

import dev.maia.actions.notes.FakeNotes
import dev.maia.actions.notes.Note
import dev.maia.actions.notes.NoteMarkdown
import dev.maia.app.flow.Fixtures
import dev.maia.app.flow.FlowSession
import dev.maia.app.flow.FlowState
import dev.maia.app.flow.lockedSummary
import dev.maia.app.flow.noteSummary
import dev.maia.nlu.Field
import dev.maia.nlu.Intent
import dev.maia.nlu.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

/**
 * D3, the locked note pose (M4 row 8), and the copy's stranger test: someone
 * holding a locked phone learns what was said and nothing about where notes go.
 */
class LockedNoteSceneTest {

    private val at: ZonedDateTime = ZonedDateTime.of(2026, 9, 13, 10, 0, 0, 0, Fixtures.zone)
    private val said = "note call the vet thursday at eight"
    private val due = Field(ZonedDateTime.of(2026, 9, 17, 20, 0, 0, 0, Fixtures.zone), Provenance.Heard)
    private val vet = Note.of(Intent.CaptureNote(Field("call the vet", Provenance.Heard), transcript = said), at)

    private fun kept(note: Note) = lockedScene(
        FlowSession(FlowState.Queued(null, heardAt = 0, summary = noteSummary(note), note = note), locked = true),
    ) as LockedScene.Kept

    @Test
    fun `a kept note is the kept screen, marked as a note`() {
        val scene = kept(vet)
        assertTrue(scene.note)
        assertFalse(scene.whenGuessed)
        assertEquals(noteSummary(vet), scene.summary)
    }

    @Test
    fun `a kept event is not marked as a note`() {
        val session = FlowSession(
            FlowState.Queued(Fixtures.heard, heardAt = 0, summary = lockedSummary(Fixtures.heard)),
            locked = true,
        )
        assertFalse((lockedScene(session) as LockedScene.Kept).note)
    }

    @Test
    fun `a guessed due time is marked guessed, a heard one is not`() {
        assertFalse(kept(vet.copy(remindAt = due)).whenGuessed)
        assertTrue(kept(vet.copy(remindAt = due.copy(provenance = Provenance.Inferred))).whenGuessed)
    }

    @Test
    fun `the stranger test - no folder and no file name reach the locked note pose`() {
        val folder = FakeNotes.defaultFolder
        val file = NoteMarkdown.fileName(vet)
        listOf(vet, vet.copy(remindAt = due)).forEach { note ->
            val scene = kept(note)
            val carried = scene.toString()
            assertFalse(carried, carried.contains(folder.name))
            assertFalse(carried, carried.contains(folder.uri))
            assertFalse(carried, carried.contains(file))
            assertFalse(carried, carried.contains(".md"))
        }
        // The type itself has no room for one: a summary, and two booleans.
        assertEquals(
            listOf("note", "summary", "whenGuessed"),
            LockedScene.Kept::class.java.declaredFields.map { it.name }.filterNot { it.startsWith("$") }.sorted(),
        )
    }
}
