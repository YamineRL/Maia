package dev.maia.app.card

import dev.maia.actions.CalendarTarget
import dev.maia.actions.MaiaCalendar
import dev.maia.nlu.Edit
import dev.maia.nlu.EventDraft
import dev.maia.nlu.Field
import dev.maia.nlu.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * What the user is shown about where each value came from.
 *
 * This is the trust boundary in test form. Every case below is a way the card
 * could quietly lie: a guess presented as something the user said, a
 * correction demoted back to a guess, a calendar Maia picked shown as one the
 * user chose. None of them throws, none of them is visible in a screenshot
 * taken by the person who wrote the code, and all of them cost the product the
 * one thing it is selling.
 */
class CardRowsTest {

    private val zurich = ZoneId.of("Europe/Zurich")
    private val start = ZonedDateTime.of(LocalDateTime.of(2026, 9, 17, 20, 0), zurich)

    private val personal = MaiaCalendar(
        id = 1,
        displayName = "Personal",
        accountName = "me@example.org",
        isDefault = true,
    )

    private fun draft(
        titleFrom: Provenance = Provenance.Heard,
        startFrom: Provenance = Provenance.Heard,
        durationFrom: Provenance = Provenance.Inferred,
        allDay: Boolean = false,
    ) = EventDraft(
        title = Field("Dinner with Sam", titleFrom),
        start = Field(start, startFrom),
        duration = Field(Duration.ofHours(1), durationFrom),
        allDay = allDay,
    )

    private fun rows(
        draft: EventDraft = draft(),
        target: CalendarTarget? = CalendarTarget(personal, chosen = false),
    ) = cardRows(draft, target, Locale.UK)

    private fun List<CardRow>.row(field: CardField) = single { it.field == field }

    // ------------------------------------------------------------- the marks

    @Test
    fun `the handoff's example card is two heard, two guessed and one empty`() {
        val rows = rows()

        assertEquals(Mark.Heard, rows.row(CardField.Title).mark)
        assertEquals(Mark.Heard, rows.row(CardField.When).mark)
        assertEquals(Mark.Guessed, rows.row(CardField.Duration).mark)
        assertEquals(Mark.Empty, rows.row(CardField.Location).mark)
        assertEquals(Mark.Guessed, rows.row(CardField.Calendar).mark)
        assertEquals("2 heard · 2 guessed · 1 empty", tally(rows))
    }

    @Test
    fun `a corrected field is heard, not a guess`() {
        // The rule the provenance model exists for. The user changed it with
        // their thumb, which is a stronger statement than saying it out loud,
        // so the brackets come off and they stay off.
        val corrected = draft().with(Edit.Length(Duration.ofMinutes(90)))
        val row = rows(corrected).row(CardField.Duration)

        assertEquals(Mark.Heard, row.mark)
        assertEquals("1:30", row.value)
        assertNull("a corrected field explains nothing", row.reason)
    }

    @Test
    fun `a guessed row always says why, and a heard one never does`() {
        val rows = rows()
        assertEquals("you did not say how long", rows.row(CardField.Duration).reason)
        assertNull(rows.row(CardField.Title).reason)
        assertNotNull(rows.row(CardField.Calendar).reason)
    }

    @Test
    fun `every guessed row carries a reason, whichever field it is`() {
        val allGuessed = draft(
            titleFrom = Provenance.Inferred,
            startFrom = Provenance.Inferred,
            durationFrom = Provenance.Inferred,
        )
        cardRows(allGuessed, CalendarTarget(personal, chosen = false), Locale.UK)
            .filter { it.mark == Mark.Guessed }
            .forEach { assertNotNull("${it.field} is marked a guess and says nothing", it.reason) }
    }

    // --------------------------------------------------------------- calendar

    @Test
    fun `a calendar the user chose is not marked a guess`() {
        val row = rows(target = CalendarTarget(personal, chosen = true)).row(CardField.Calendar)
        assertEquals(Mark.Heard, row.mark)
        assertNull(row.reason)
    }

    @Test
    fun `a calendar Maia picked is marked a guess even though nothing was wrong with it`() {
        val row = rows(target = CalendarTarget(personal, chosen = false)).row(CardField.Calendar)
        assertEquals(Mark.Guessed, row.mark)
        assertEquals("Personal", row.value)
    }

    // --------------------------------------------------------------- location

    @Test
    fun `location is empty until the user types one, and then it is theirs`() {
        assertEquals("not set", rows().row(CardField.Location).value)

        val withPlace = draft().with(Edit.Location("Trattoria del Sole"))
        val row = rows(withPlace).row(CardField.Location)
        assertEquals(Mark.Heard, row.mark)
        assertEquals("Trattoria del Sole", row.value)
        assertNull(row.reason)
    }

    @Test
    fun `clearing a location returns it to never supplied rather than to blank`() {
        val cleared = draft().with(Edit.Location("Somewhere")).with(Edit.Location("   "))
        assertNull(cleared.location)
        assertEquals(Mark.Empty, rows(cleared).row(CardField.Location).mark)
    }

    // ------------------------------------------------------------- the values

    @Test
    fun `the when line is the date and the time, and both are shown`() {
        // "Sept", not "Sep": the rows are built in Locale.UK, and CLDR's en-GB
        // abbreviation is four letters. Android's ICU agrees, so this is what a
        // British phone shows, and the month name belongs to the locale rather
        // than to a hardcoded pattern.
        assertEquals("Thu 17 Sept  20:00", rows().row(CardField.When).value)
    }

    @Test
    fun `an all day event shows no time at all`() {
        // 00:00 would be Maia inventing a precision the user never asked for.
        val allDay = draft().with(Edit.AllDay(true))
        val rows = rows(allDay)
        assertEquals("Thu 17 Sept", rows.row(CardField.When).value)
        assertEquals("all day", rows.row(CardField.Duration).value)
    }

    @Test
    fun `durations read as hours and minutes, past twenty four hours too`() {
        assertEquals("0:45", clock(Duration.ofMinutes(45)))
        assertEquals("1:00", clock(Duration.ofHours(1)))
        assertEquals("2:05", clock(Duration.ofMinutes(125)))
        // Not 0:00. Wrapping here would be the same class of bug as an event
        // on the wrong day: silently plausible and completely wrong.
        assertEquals("72:00", clock(Duration.ofDays(3)))
        assertEquals("0:00", clock(Duration.ofMinutes(-30)))
    }

    @Test
    fun `the tally names only the marks that are actually present`() {
        val everythingHeard = draft(durationFrom = Provenance.Heard)
            .with(Edit.Location("Home"))
        val rows = cardRows(everythingHeard, CalendarTarget(personal, chosen = true), Locale.UK)
        assertEquals("5 heard", tally(rows))
    }
}
