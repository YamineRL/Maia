package dev.maia.app.card

import dev.maia.actions.notes.FakeNotes
import dev.maia.actions.notes.Note
import dev.maia.actions.notes.NotesFolder
import dev.maia.nlu.Field
import dev.maia.nlu.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** The note card's copy decisions, M4 row 8. Pure, so checked here and not by eye. */
class NoteRowsTest {

    private val eight = ZonedDateTime.of(2026, 9, 17, 20, 0, 0, 0, ZoneId.of("Europe/Zurich"))
    private fun tree(id: String) = NotesFolder("content://com.android.externalstorage.documents/tree/$id", "whatever")

    @Test
    fun `the Due row's mark follows who said the time`() {
        assertNull(dueMark(null))
        assertEquals(Mark.Heard, dueMark(Field(eight, Provenance.Heard)))
        assertEquals(Mark.Heard, dueMark(Field(eight, Provenance.Corrected)))
        assertEquals(Mark.Guessed, dueMark(Field(eight, Provenance.Inferred)))
    }

    @Test
    fun `the Due row reads short and the description reads long`() {
        assertEquals("Thu 17 Sep  20:00", dueValue(eight, Locale.US))
        assertEquals("Thursday 17 September at 20:00", dueSpoken(eight, Locale.US))
    }

    @Test
    fun `the confirmation time is when the note was filed, not when it is due`() {
        val note = Note(Field("call the vet", Provenance.Heard), at = eight.withHour(9).withMinute(5), remindAt = Field(eight, Provenance.Heard))
        assertEquals("09:05", noteTime(note))
        assertEquals("20:00", clockTime(eight))
    }

    @Test
    fun `the top of a volume is a storage root, and a folder in it is not`() {
        assertTrue(isStorageRoot(tree("primary%3A")))
        assertTrue(isStorageRoot(tree("1A2B-3C4D%3A")))
        assertFalse(isStorageRoot(tree("primary%3ADocuments%2FNotes")))
        assertFalse(isStorageRoot(tree("1A2B-3C4D%3ANotes")))
    }

    @Test
    fun `a uri that is not a tree is never called a root`() {
        assertFalse(isStorageRoot(FakeNotes.defaultFolder))
        assertFalse(isStorageRoot(NotesFolder("content://com.android.externalstorage.documents/document/primary%3A", "primary")))
        assertFalse(isStorageRoot(NotesFolder("", "Notes")))
    }

    @Test
    fun `the tree's document id is decoded, and a plus is a plus`() {
        assertEquals("primary:Documents/Notes", treeDocumentId("content://x/tree/primary%3ADocuments%2FNotes"))
        assertEquals("primary:a+b", treeDocumentId("content://x/tree/primary%3Aa+b/document/primary%3Aa+b"))
        assertNull(treeDocumentId("fake://notes"))
    }
}
