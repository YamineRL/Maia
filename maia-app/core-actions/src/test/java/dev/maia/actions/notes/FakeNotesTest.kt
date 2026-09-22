package dev.maia.actions.notes

import dev.maia.nlu.Field
import dev.maia.nlu.Intent
import dev.maia.nlu.Parser
import dev.maia.nlu.Provenance
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The repository seam, driven with no Android class involved, which is the
 * substrate criterion J14 stands on: there is no `android.` import in this
 * file, in [FakeNotes], in [NoteMarkdown] or in [Note].
 *
 * What this cannot prove is written down in [FakeNotes]'s own doc comment and
 * repeated here so nobody quotes a green run as evidence for it: gates G4, G5,
 * G7 and G8 are facts about a `DocumentsProvider` and need the phone. This
 * file proves the contract above the seam, not the storage under it.
 */
class FakeNotesTest {

    private val zurich = ZoneId.of("Europe/Zurich")
    private val clock = Clock.fixed(Instant.parse("2026-09-13T07:41:00Z"), zurich)

    // -------------------------------------------------------- the happy path

    @Test
    fun `the day's first note creates the file with its heading`() = runTest {
        val notes = FakeNotes()
        val ref = notes.append(note("call the dentist about the crown", "2026-09-13T09:41"))

        assertTrue(ref.createdFile)
        assertEquals("2026-09-13.md", ref.fileName)
        assertEquals(
            "# 2026-09-13\n\n- 09:41 call the dentist about the crown\n",
            notes.fileNamed("2026-09-13.md"),
        )
    }

    @Test
    fun `the second note of the day appends and writes no second heading`() = runTest {
        val notes = FakeNotes()
        notes.append(note("call the dentist about the crown", "2026-09-13T09:41"))
        val second = notes.append(
            note("book the car in for its service", "2026-09-13T12:07", "2026-09-14T09:00"),
        )

        assertFalse("the file already existed", second.createdFile)
        assertEquals(1, notes.files.size)
        assertEquals(
            """
            # 2026-09-13

            - 09:41 call the dentist about the crown
            - [ ] 12:07 book the car in for its service (due 2026-09-14 09:00)

            """.trimIndent(),
            notes.fileNamed("2026-09-13.md"),
        )
    }

    @Test
    fun `a note the next day opens a new file and leaves the old one alone`() = runTest {
        val notes = FakeNotes()
        notes.append(note("today", "2026-09-13T09:41"))
        val yesterday = notes.fileNamed("2026-09-13.md")
        val ref = notes.append(note("tomorrow", "2026-09-14T08:05"))

        assertTrue(ref.createdFile)
        assertEquals(listOf("2026-09-13.md", "2026-09-14.md"), notes.files.keys.toList())
        assertEquals("the earlier file is never rewritten", yesterday, notes.fileNamed("2026-09-13.md"))
        assertEquals("# 2026-09-14\n\n- 08:05 tomorrow\n", notes.fileNamed("2026-09-14.md"))
    }

    @Test
    fun `a whole sentence reaches the file with the words the user said`() = runTest {
        val notes = FakeNotes()
        val intent = Parser(clock).parse("remind me to call the plumber") as Intent.CaptureNote
        notes.append(Note.of(intent, ZonedDateTime.now(clock)))

        // 07:41 UTC is 09:41 in Zurich, and the parser found no time, so it is
        // a plain bullet and not a task.
        assertEquals(
            "# 2026-09-13\n\n- 09:41 call the plumber\n",
            notes.fileNamed("2026-09-13.md"),
        )
    }

    // ------------------------------------------------------- the empty states

    @Test
    fun `no folder chosen is a state and not an error`() = runTest {
        val notes = FakeNotes(folder = null)
        assertNull(notes.folder())

        val failure = runCatching { notes.append(note("held", "2026-09-13T09:41")) }
        assertTrue(failure.exceptionOrNull() is NotesFolderGone)
        assertTrue("nothing was written", notes.files.isEmpty())
    }

    @Test
    fun `a revoked folder stops reporting itself and stops accepting writes`() = runTest {
        val notes = FakeNotes()
        notes.append(note("before", "2026-09-13T09:41"))
        notes.folderRevoked = true

        assertNull(notes.folder())
        assertTrue(
            runCatching { notes.append(note("after", "2026-09-13T09:42")) }
                .exceptionOrNull() is NotesFolderGone,
        )
        // The note is the caller's to hold, and the file is untouched.
        assertEquals("# 2026-09-13\n\n- 09:41 before\n", notes.fileNamed("2026-09-13.md"))

        // Picking again recovers, which is gate G9's requirement stated as a
        // contract the flow can rely on.
        notes.choose(FakeNotes.defaultFolder)
        notes.append(note("after", "2026-09-13T09:42"))
        assertEquals(
            "# 2026-09-13\n\n- 09:41 before\n- 09:42 after\n",
            notes.fileNamed("2026-09-13.md"),
        )
    }

    @Test
    fun `a refused write is a fault and writes nothing`() = runTest {
        val notes = FakeNotes()
        notes.denyWrites = true
        assertTrue(
            runCatching { notes.append(note("nope", "2026-09-13T09:41")) }
                .exceptionOrNull() is NoteWriteFailed,
        )
        assertTrue(notes.files.isEmpty())
    }

    @Test
    fun `an empty body never reaches a file`() = runTest {
        val notes = FakeNotes()
        val failure = runCatching { notes.append(note("   ", "2026-09-13T09:41")) }
        assertTrue(failure.exceptionOrNull() is NoteWriteFailed)
        assertTrue("not even an empty file with a heading", notes.files.isEmpty())
    }

    @Test
    fun `preparing a day is a warm and never a write`() = runTest {
        // The seam the spike's G6 bought: resolving today's file costs about
        // 345 ms in a folder of 100 files against 15 ms for the append, so the
        // resolve happens when the card opens. It must create nothing, because
        // a note that is never committed must leave no file behind.
        val notes = FakeNotes()
        val day = LocalDateTime.parse("2026-09-13T09:41").toLocalDate()

        notes.prepare(day)
        assertEquals(listOf(day), notes.prepared)
        assertTrue("a warm writes nothing", notes.files.isEmpty())
    }

    // ---------------------------------------------------------------- the store

    @Test
    fun `the folder store keeps one tree and exactly one cached child`() {
        val store = InMemoryNotesFolderStore()
        assertNull(store.treeUri())

        store.setTreeUri("content://tree/notes")
        val thirteenth = LocalDateTime.parse("2026-09-13T09:41").toLocalDate()
        val fourteenth = thirteenth.plusDays(1)

        store.setCachedChild(thirteenth, "content://doc/13")
        assertEquals("content://doc/13", store.cachedChild(thirteenth))
        assertNull("a different date is a miss, never yesterday's file", store.cachedChild(fourteenth))

        // One entry, not a map: caching the new day drops the old one.
        store.setCachedChild(fourteenth, "content://doc/14")
        assertNull(store.cachedChild(thirteenth))
        assertEquals("content://doc/14", store.cachedChild(fourteenth))

        // G8: a cached child can go stale under a sync client, so dropping it
        // is always available and never touches the tree.
        store.clearCachedChild()
        assertNull(store.cachedChild(fourteenth))
        assertEquals("content://tree/notes", store.treeUri())

        // Releasing the grant takes the child with it.
        store.setCachedChild(fourteenth, "content://doc/14")
        store.clearTree()
        assertNull(store.treeUri())
        assertNull(store.cachedChild(fourteenth))
    }

    // ---------------------------------------------------------------- helpers

    private fun note(body: String, at: String, remindAt: String? = null) = Note(
        body = Field(body, Provenance.Heard),
        at = zoned(at),
        remindAt = remindAt?.let { Field(zoned(it), Provenance.Heard) },
        transcript = body,
    )

    private fun zoned(local: String): ZonedDateTime = LocalDateTime.parse(local).atZone(zurich)
}
