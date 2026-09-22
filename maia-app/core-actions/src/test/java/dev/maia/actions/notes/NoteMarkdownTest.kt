package dev.maia.actions.notes

import dev.maia.nlu.Field
import dev.maia.nlu.Intent
import dev.maia.nlu.Parser
import dev.maia.nlu.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * M4 criteria J2 to J6: the note file, byte for byte.
 *
 * Everything here is pure. No dispatcher, no provider, no Android class, no
 * wall clock: every time in this file comes from a fixed [Clock], which is
 * what makes J2's midnight cases assertable at all.
 */
class NoteMarkdownTest {

    private val zurich = ZoneId.of("Europe/Zurich")

    /** 23:59 local on the 13th. In UTC it is still the 13th. */
    private val lateOnThe13th =
        ZonedDateTime.now(Clock.fixed(Instant.parse("2026-09-13T21:59:00Z"), zurich))

    /** 00:01 local on the 13th. In UTC it is still the 12th. */
    private val earlyOnThe13th =
        ZonedDateTime.now(Clock.fixed(Instant.parse("2026-09-12T22:01:00Z"), zurich))

    // ------------------------------------------------------------------- J2

    @Test
    fun `a note at two minutes to midnight belongs to that day's file`() {
        assertEquals("2026-09-13", lateOnThe13th.toLocalDate().toString())
        assertEquals("23:59", lateOnThe13th.toLocalTime().toString())
        assertEquals("2026-09-13.md", NoteMarkdown.fileName(lateOnThe13th.toLocalDate()))
    }

    @Test
    fun `a note a minute after midnight belongs to the new day's file`() {
        assertEquals("2026-09-13", earlyOnThe13th.toLocalDate().toString())
        assertEquals("00:01", earlyOnThe13th.toLocalTime().toString())
        assertEquals("2026-09-13.md", NoteMarkdown.fileName(earlyOnThe13th.toLocalDate()))
    }

    @Test
    fun `the file name is the local date and never the UTC date`() {
        // The whole point of J2. Both notes above are on the 13th locally and
        // sit on opposite sides of midnight UTC, so a renderer that reached
        // for the instant rather than the zoned date would put them in two
        // different files, or in two files neither of which is the 13th.
        assertEquals(
            NoteMarkdown.fileName(Note(body("x"), lateOnThe13th)),
            NoteMarkdown.fileName(Note(body("x"), earlyOnThe13th)),
        )
        assertEquals("2026-09-13", lateOnThe13th.toInstant().toString().substring(0, 10))
        assertEquals("2026-09-12", earlyOnThe13th.toInstant().toString().substring(0, 10))
    }

    @Test
    fun `a note in another zone keeps its own day`() {
        // Same instant as lateOnThe13th, read in Auckland, where it is already
        // the 14th. The file follows the note's zone, not the device's.
        val auckland = lateOnThe13th.withZoneSameInstant(ZoneId.of("Pacific/Auckland"))
        assertEquals("2026-09-14.md", NoteMarkdown.fileName(Note(body("x"), auckland)))
    }

    // ------------------------------------------------------------------- J3

    @Test
    fun `a plain note is one bullet with a 24 hour time`() {
        val at = zoned("2026-09-13T09:41")
        assertEquals(
            "- 09:41 call the dentist about the crown\n",
            NoteMarkdown.line("call the dentist about the crown", at),
        )
    }

    @Test
    fun `a note with a reminder on another day carries the full date`() {
        val at = zoned("2026-09-13T12:07")
        assertEquals(
            "- [ ] 12:07 book the car in for its service (due 2026-09-14 09:00)\n",
            NoteMarkdown.line(
                "book the car in for its service",
                at,
                zoned("2026-09-14T09:00"),
            ),
        )
    }

    @Test
    fun `a note with a reminder the same day carries the time alone`() {
        val at = zoned("2026-09-13T12:07")
        assertEquals(
            "- [ ] 12:07 book the car in for its service (due 17:30)\n",
            NoteMarkdown.line(
                "book the car in for its service",
                at,
                zoned("2026-09-13T17:30"),
            ),
        )
    }

    @Test
    fun `the header is written once, with the ISO date and a blank line`() {
        assertEquals("# 2026-09-13\n\n", NoteMarkdown.header(lateOnThe13th.toLocalDate()))
    }

    @Test
    fun `the exact file from brief section 3 point 2 is reproduced byte for byte`() {
        val day = zoned("2026-09-13T00:00").toLocalDate()
        val file = NoteMarkdown.header(day) +
            NoteMarkdown.line("call the dentist about the crown", zoned("2026-09-13T09:41")) +
            NoteMarkdown.line(
                "book the car in for its service",
                zoned("2026-09-13T12:07"),
                zoned("2026-09-14T09:00"),
            )

        assertEquals(
            """
            # 2026-09-13

            - 09:41 call the dentist about the crown
            - [ ] 12:07 book the car in for its service (due 2026-09-14 09:00)

            """.trimIndent(),
            file,
        )
    }

    @Test
    fun `a midnight note reads as 00 00 and not as 24 00`() {
        assertEquals("- 00:00 midnight thought\n", NoteMarkdown.line("midnight thought", zoned("2026-09-13T00:00")))
    }

    @Test
    fun `the times are ASCII digits whatever the device locale is`() {
        val was = java.util.Locale.getDefault()
        try {
            // A locale whose default numbering system is not Latin. Without
            // Locale.ROOT on the formatters this line comes out in Devanagari
            // digits and syncs to a laptop that way.
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("hi-IN-u-nu-deva"))
            assertEquals("- 09:41 x\n", NoteMarkdown.line("x", zoned("2026-09-13T09:41")))
            assertEquals("2026-09-13.md", NoteMarkdown.fileName(zoned("2026-09-13T09:41").toLocalDate()))
        } finally {
            java.util.Locale.setDefault(was)
        }
    }

    // ------------------------------------------------------------------- J4

    @Test
    fun `carriage returns and line feeds become a single space`() {
        assertEquals("one two three", NoteMarkdown.sanitise("one\ntwo\r\nthree"))
    }

    @Test
    fun `runs of whitespace collapse and the ends are trimmed`() {
        assertEquals("one two", NoteMarkdown.sanitise("   one \t\t two   "))
    }

    @Test
    fun `markdown the user said is left exactly as they said it`() {
        // Brief section 3.3: nothing escapes the user's words. A body that
        // renders as emphasis in somebody's viewer is the user's file
        // rendering the user's words, and a backslash they never said is a
        // worse answer than italics they did not intend.
        val said = "*star* _under_ `tick` #hash - dash [box] \\slash"
        assertEquals(said, NoteMarkdown.sanitise(said))
        assertEquals(
            "- 09:41 *star* _under_ `tick` #hash - dash [box] \\slash\n",
            NoteMarkdown.line(said, zoned("2026-09-13T09:41")),
        )
    }

    @Test
    fun `a body that starts with a dash or a hash cannot break the line`() {
        // It cannot, and not because it was escaped: the timestamp prefix is
        // always in front of it.
        assertTrue(NoteMarkdown.line("- item", zoned("2026-09-13T09:41"))!!.startsWith("- 09:41 - item"))
        assertTrue(NoteMarkdown.line("# heading", zoned("2026-09-13T09:41"))!!.startsWith("- 09:41 # heading"))
    }

    // ------------------------------------------------------------------- J5

    @Test
    fun `an empty or whitespace only body produces no line and no plan`() {
        val at = zoned("2026-09-13T09:41")
        assertNull(NoteMarkdown.line("", at))
        assertNull(NoteMarkdown.line("   ", at))
        assertNull(NoteMarkdown.line("\n\t \r\n", at))
        assertNull(NoteMarkdown.plan(Note(body("  "), at)))
        // And not even a file: an empty body creates nothing to create.
        assertNull(NoteMarkdown.plan(Note(body(""), at, remindAt = heard(zoned("2026-09-13T17:00")))))
    }

    // ------------------------------------------------------------------- J6

    @Test
    fun `header and lines round trip as one line per note, ending in a newline`() {
        val day = zoned("2026-09-13T00:00").toLocalDate()
        val notes = listOf(
            Note(body("call the dentist"), zoned("2026-09-13T09:41")),
            Note(body("buy\nmilk"), zoned("2026-09-13T10:02")),
            Note(
                body("book the car in"),
                zoned("2026-09-13T12:07"),
                remindAt = heard(zoned("2026-09-14T09:00")),
            ),
        )

        val file = notes.fold(NoteMarkdown.header(day)) { acc, note -> acc + NoteMarkdown.line(note) }

        assertTrue(file.endsWith("\n"))
        assertTrue("no CR anywhere in the file", !file.contains('\r'))
        // Valid UTF-8, and unchanged by the round trip through bytes.
        assertEquals(file, String(file.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        // No BOM.
        assertTrue(file.toByteArray(Charsets.UTF_8).first() == '#'.code.toByte())

        val lines = file.trimEnd('\n').split("\n")
        // Heading, blank line, then exactly one line per note. The embedded
        // newline in the second note's body did not become a second line.
        assertEquals(2 + notes.size, lines.size)
        assertEquals("# 2026-09-13", lines[0])
        assertEquals("", lines[1])
        assertEquals("- 09:41 call the dentist", lines[2])
        assertEquals("- 10:02 buy milk", lines[3])
        assertEquals("- [ ] 12:07 book the car in (due 2026-09-14 09:00)", lines[4])
    }

    // ------------------------------------------------ the plan, and the note

    @Test
    fun `the plan names the file, the heading and the one line to append`() {
        val plan = NoteMarkdown.plan(
            Note(body("call the dentist"), zoned("2026-09-13T09:41")),
        )
        assertNotNull(plan)
        assertEquals("2026-09-13.md", plan!!.fileName)
        assertEquals("# 2026-09-13\n\n", plan.headerIfCreated)
        assertEquals("- 09:41 call the dentist\n", plan.line)
    }

    @Test
    fun `a note built from a parsed sentence keeps the parser's provenance`() {
        val clock = Clock.fixed(Instant.parse("2026-09-13T07:41:00Z"), zurich)
        val intent = Parser(clock).parse("remind me to call the dentist at five") as Intent.CaptureNote
        val note = Note.of(intent, ZonedDateTime.now(clock))

        assertEquals(Provenance.Heard, note.body.provenance)
        assertTrue("the sentence carried a time, so the note is a task", note.isTask)
        assertTrue(NoteMarkdown.line(note)!!.startsWith("- [ ] 09:41 "))
    }

    @Test
    fun `a sentence kept as a note does not invent a reminder`() {
        // Decision U2, the "keep as a note" exit from the card. The parser
        // falls back to "now", marked Inferred, whenever a sentence carried no
        // time, so that the card opens on something pushable. That fallback
        // must not become a due date in the user's file: it would put a task
        // box and a "(due ...)" on every kept sentence, timed to the second
        // they spoke, which they would then have to delete by hand in a file
        // Maia does not own.
        val clock = Clock.fixed(Instant.parse("2026-09-13T07:41:00Z"), zurich)
        val draft = (Parser(clock).parse("wibble") as Intent.CreateEvent).draft
        val note = Note.of(draft, ZonedDateTime.now(clock))

        assertEquals(Provenance.Inferred, draft.start.provenance)
        assertNull(note.remindAt)
        assertEquals("- 09:41 wibble\n", NoteMarkdown.line(note))
        assertEquals("wibble", note.body.value)
    }

    @Test
    fun `a kept sentence whose time the user did say becomes a reminder`() {
        // The other half of the same rule. "tomorrow at three p m" is heard,
        // not understood: every word of it is temporal, so nothing is left to
        // be a title and it arrives as Unparsed. The time is real, though, and
        // provenance is what says so, so keeping it as a note keeps the time.
        val clock = Clock.fixed(Instant.parse("2026-09-13T07:41:00Z"), zurich)
        val draft = (Parser(clock).parse("tomorrow at three p m") as Intent.Unparsed).draft
        val note = Note.of(draft, ZonedDateTime.now(clock))

        assertEquals(Provenance.Heard, draft.start.provenance)
        assertEquals(
            "- [ ] 09:41 tomorrow at three p m (due 2026-09-14 15:00)\n",
            NoteMarkdown.line(note),
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun zoned(local: String): ZonedDateTime =
        java.time.LocalDateTime.parse(local).atZone(zurich)

    private fun body(text: String) = Field(text, Provenance.Heard)

    private fun heard(at: ZonedDateTime) = Field(at, Provenance.Heard)
}
