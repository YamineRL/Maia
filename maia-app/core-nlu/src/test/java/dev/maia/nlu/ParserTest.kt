package dev.maia.nlu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Section 8 criteria 5 and 6 of the M1 brief, which is what step 5 is for.
 *
 * The clock is Saturday 12 September 2026, 11:30 in Zurich, the same fixture
 * [dev.maia.nlu.time.TemporalResolverTest] uses, so a date asserted here can be
 * checked against the rules asserted there.
 */
class ParserTest {

    private val zone = ZoneId.of("Europe/Zurich")
    private val clock = Clock.fixed(Instant.parse("2026-09-12T09:30:00Z"), zone)
    private val parser = Parser(clock)

    // ------------------------------------------------ criterion 5, the five

    @Test
    fun `an event parses and then takes a correction`() {
        val intent = parser.parse("schedule lunch with sam tomorrow at noon")
        val draft = (intent as Intent.CreateEvent).draft
        assertEquals("lunch with sam", draft.title.value)
        assertEquals(Provenance.Heard, draft.title.provenance)
        assertEquals("2026-09-13T12:00+02:00[Europe/Zurich]", draft.start.value.toString())

        val edited = draft.with(Edit.Title("lunch with samir"))
        assertEquals("lunch with samir", edited.title.value)
        assertEquals(Provenance.Corrected, edited.title.provenance)
        // The neighbour is untouched, and still says where it came from.
        assertEquals(draft.start, edited.start)
    }

    @Test
    fun `a note parses and then takes a correction`() {
        val intent = parser.parse("remind me to call the plumber")
        val note = intent as Intent.CaptureNote
        assertEquals("call the plumber", note.body.value)
        assertEquals(Provenance.Heard, note.body.provenance)
        assertNull(note.remindAt)

        val corrected = note.copy(body = Field("call the roofer", Provenance.Corrected))
        assertEquals(Provenance.Corrected, corrected.body.provenance)
    }

    @Test
    fun `an agenda question parses to the day it asked about`() {
        val agenda = parser.parse("what do i have tomorrow") as Intent.Agenda
        assertEquals("2026-09-13T00:00+02:00[Europe/Zurich]", agenda.range.start.toString())
        assertEquals(
            "2026-09-14T00:00+02:00[Europe/Zurich]",
            agenda.range.endInclusive.toString(),
        )
    }

    @Test
    fun `an availability question parses to the window it asked about`() {
        val free = parser.parse("am i free thursday afternoon") as Intent.Availability
        // Thursday is the 17th, and the afternoon window is 12 to 18.
        assertEquals("2026-09-17T12:00+02:00[Europe/Zurich]", free.range.start.toString())
        assertEquals("2026-09-17T18:00+02:00[Europe/Zurich]", free.range.endInclusive.toString())
    }

    @Test
    fun `an unparsed sentence takes a correction like any other`() {
        val draft = (parser.parse("tomorrow at three p m") as Intent.Unparsed).draft
        val edited = draft.with(Edit.Title("dentist"))
        assertEquals("dentist", edited.title.value)
        assertEquals(Provenance.Corrected, edited.title.provenance)
    }

    // --------------------------------- criterion 5, not re-inferred

    @Test
    fun `a corrected field survives a re-parse of the same sentence`() {
        val first = (parser.parse("schedule standup tomorrow") as Intent.CreateEvent).draft
        val edited = first.with(Edit.Start(first.start.value.plusHours(3)))
        assertEquals(Provenance.Corrected, edited.start.provenance)

        val second = (parser.parse("schedule standup tomorrow") as Intent.CreateEvent).draft
        val merged = edited.keepingCorrections(second)
        assertEquals(edited.start.value, merged.start.value)
        assertEquals(Provenance.Corrected, merged.start.provenance)
    }

    @Test
    fun `an uncorrected field does take the re-parsed value`() {
        val first = (parser.parse("schedule standup tomorrow") as Intent.CreateEvent).draft
        val edited = first.with(Edit.Title("morning standup"))
        val second = (parser.parse("schedule standup thursday") as Intent.CreateEvent).draft
        val merged = edited.keepingCorrections(second)

        assertEquals("morning standup", merged.title.value)
        assertEquals(second.start.value, merged.start.value)
    }

    @Test
    fun `promoting an all day event to a time is a correction of both halves`() {
        val draft = (parser.parse("dentist thursday") as Intent.CreateEvent).draft
        assertTrue(draft.allDay)
        assertEquals(Duration.ofDays(1), draft.duration.value)

        val timed = draft.with(Edit.Start(draft.start.value.withHour(15)))
            .with(Edit.Length(Duration.ofMinutes(30)))
        assertEquals(Provenance.Corrected, timed.start.provenance)
        assertEquals(Provenance.Corrected, timed.duration.provenance)
        assertEquals(
            "2026-09-17T15:30+02:00[Europe/Zurich]",
            timed.end.toString(),
        )
    }

    // ------------------------------------------------------- criterion 6

    @Test
    fun `an unparsed sentence keeps the raw transcript as the title`() {
        val raw = "tomorrow at three p m"
        val draft = (parser.parse(raw) as Intent.Unparsed).draft
        assertEquals(raw, draft.title.value)
        assertEquals(raw, draft.transcript)
        // Half an understanding is still kept: the time did resolve.
        assertEquals("2026-09-13T15:00+02:00[Europe/Zurich]", draft.start.value.toString())
    }

    @Test
    fun `an empty transcript produces a draft and not an exception`() {
        val draft = (parser.parse("") as Intent.Unparsed).draft
        assertEquals("", draft.title.value)
        assertNotNull(draft.start.value)
        assertEquals(Provenance.Inferred, draft.start.provenance)
    }

    @Test
    fun `nothing in the path throws on any input`() {
        val inputs = listOf(
            "", "   ", "a", "the", "at", "for", "in", "until", "next",
            "schedule", "note", "remind me to", "am i free",
            "at at at", "twenty twenty six", "the thirty first of february",
            "in one hundred years", "quarter to", "half past",
            "please please please", "note note note",
            "what", "show me my", "book a",
        )
        for (input in inputs) {
            val intent = parser.parse(input)
            assertNotNull("parse returned null for [$input]", intent)
        }
    }

    // ------------------------------------------------------ provenance rules

    @Test
    fun `an hour the user never said is marked inferred`() {
        val draft = (parser.parse("schedule gym") as Intent.CreateEvent).draft
        assertEquals(Provenance.Inferred, draft.start.provenance)
        assertEquals(Provenance.Inferred, draft.duration.provenance)
    }

    @Test
    fun `a duration the user did say is marked heard`() {
        val draft = (parser.parse("schedule standup tomorrow at nine a m for thirty minutes")
            as Intent.CreateEvent).draft
        assertEquals(Provenance.Heard, draft.start.provenance)
        assertEquals(Provenance.Heard, draft.duration.provenance)
        assertEquals(Duration.ofMinutes(30), draft.duration.value)
    }

    @Test
    fun `an hour with no meridiem is a guess and says so`() {
        // "nine" is either hour of the day, and the resolver picks one. The
        // card has to be able to show that it picked, which is the whole point
        // of the distinction between Heard and Inferred.
        val draft = (parser.parse("schedule standup tomorrow at nine")
            as Intent.CreateEvent).draft
        assertEquals(Provenance.Inferred, draft.start.provenance)
        assertEquals("2026-09-13T09:00+02:00[Europe/Zurich]", draft.start.value.toString())
    }

    @Test
    fun `the title span points back at the words in the sentence`() {
        val draft = (parser.parse("schedule lunch with sam tomorrow at noon")
            as Intent.CreateEvent).draft
        // Words 1 through 3 of the sentence: lunch, with, sam.
        assertEquals(1..3, draft.title.span)
    }

    // ------------------------------------------------------- open item A
    //
    // M4-report's open item A: on device, these three sentences left the
    // Understanding screen hung with no crash and nothing in logcat. The
    // 2 second timeout makes this a real regression test rather than a
    // functional one -- it fails loudly if parse() itself ever stops
    // returning for one of them, which a plain assertEquals would not
    // catch if the call never came back. It passing does not by itself
    // clear parse() of the hang; it rules the pure parse pass in or out
    // as the layer to keep looking at.

    @Test(timeout = 2000)
    fun `note by milk does not hang the parser`() {
        val intent = parser.parse("note by milk")
        assertNotNull(intent)
    }

    @Test(timeout = 2000)
    fun `notes called vette does not hang the parser`() {
        val intent = parser.parse("notes called vette")
        assertNotNull(intent)
    }

    @Test(timeout = 2000)
    fun `bare lunch with sam tomorrow at noon does not hang the parser`() {
        val intent = parser.parse("lunch with sam tomorrow at noon")
        assertNotNull(intent)
    }
}
