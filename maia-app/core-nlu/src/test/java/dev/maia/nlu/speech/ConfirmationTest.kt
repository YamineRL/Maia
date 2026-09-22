package dev.maia.nlu.speech

import dev.maia.nlu.Edit
import dev.maia.nlu.EventDraft
import dev.maia.nlu.Field
import dev.maia.nlu.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * Criterion 10.1.9: the spoken confirmation says what the card showed.
 *
 * The draft is the card's own fixture from `CardRowsTest`, Thursday 17
 * September 2026 at 20:00 in Zurich, which the card renders as
 * "Thu 17 Sept  20:00". `now` is Saturday 12 September at 11:30, the clock
 * [dev.maia.nlu.ParserTest] uses, so Thursday is five days ahead.
 */
class ConfirmationTest {

    private val zurich = ZoneId.of("Europe/Zurich")
    private val now = ZonedDateTime.of(LocalDateTime.of(2026, 9, 12, 11, 30), zurich)
    private val thursdayEight = ZonedDateTime.of(LocalDateTime.of(2026, 9, 17, 20, 0), zurich)

    private fun draft(
        title: String = "Dinner with Sam",
        start: ZonedDateTime = thursdayEight,
        startFrom: Provenance = Provenance.Heard,
        allDay: Boolean = false,
    ) = EventDraft(
        title = Field(title, Provenance.Heard),
        start = Field(start, startFrom),
        duration = Field(Duration.ofHours(1), Provenance.Inferred),
        allDay = allDay,
    )

    private fun say(draft: EventDraft) = Confirmation.sentence(draft, now, Locale.UK)

    private fun at(date: LocalDateTime) = ZonedDateTime.of(date, zurich)

    private fun spokenTime(hour: Int, minute: Int, inferred: Boolean = false) =
        Confirmation.time(LocalTime.of(hour, minute), inferred)

    // ------------------------------------------------------- the card's cases

    @Test
    fun `the design's example, from the card's fixture`() {
        assertEquals("Saved, Dinner with Sam, Thursday at eight.", say(draft()))
    }

    @Test
    fun `a title as heard is spoken as heard, with no guessed capitals`() {
        // CardFlowTest's draft before the user corrected the title.
        assertEquals("Saved, dinner with sam, Thursday at eight.", say(draft(title = "dinner with sam")))
    }

    @Test
    fun `a corrected title keeps the capitals the user typed`() {
        val corrected = draft(title = "dinner with sam").with(Edit.Title("Dinner with Samir"))
        assertEquals("Saved, Dinner with Samir, Thursday at eight.", say(corrected))
    }

    @Test
    fun `an all day event says all day and no time, as the card does`() {
        val allDay = draft(title = "holiday").with(Edit.AllDay(true))
        assertEquals("Saved, holiday, Thursday, all day.", say(allDay))
    }

    // ------------------------------------------------------------------- days

    @Test
    fun `today and tomorrow are said as such`() {
        assertEquals(
            "Saved, lunch, today at one.",
            say(draft(title = "lunch", start = at(LocalDateTime.of(2026, 9, 12, 13, 0)))),
        )
        assertEquals(
            "Saved, lunch with sam, tomorrow at midday.",
            say(draft(title = "lunch with sam", start = at(LocalDateTime.of(2026, 9, 13, 12, 0)))),
        )
    }

    @Test
    fun `two to six days ahead is the weekday`() {
        assertEquals("Monday", Confirmation.day(now.toLocalDate().plusDays(2), now.toLocalDate(), Locale.UK))
        assertEquals("Friday", Confirmation.day(now.toLocalDate().plusDays(6), now.toLocalDate(), Locale.UK))
    }

    @Test
    fun `a week ahead is a date, because the weekday would be today's`() {
        assertEquals(
            "Saved, gym, the nineteenth of September at seven.",
            say(draft(title = "gym", start = at(LocalDateTime.of(2026, 9, 19, 7, 0)))),
        )
    }

    @Test
    fun `a past date is a date, never a weekday`() {
        assertEquals(
            "the eleventh of September",
            Confirmation.day(now.toLocalDate().minusDays(1), now.toLocalDate(), Locale.UK),
        )
    }

    @Test
    fun `the year is said only when it is not this year`() {
        assertEquals(
            "Saved, dentist, the fourth of January twenty twenty-seven at half past nine.",
            say(draft(title = "dentist", start = at(LocalDateTime.of(2027, 1, 4, 9, 30)))),
        )
        assertEquals(
            "the first of October",
            Confirmation.day(now.toLocalDate().withMonth(10).withDayOfMonth(1), now.toLocalDate(), Locale.UK),
        )
    }

    @Test
    fun `today is decided in the event's zone, not the clock's`() {
        // 00:30 on Sunday in Zurich is still Saturday in UTC.
        val utcNow = ZonedDateTime.of(LocalDateTime.of(2026, 9, 12, 22, 30), ZoneId.of("UTC"))
        val sundayEvening = draft(title = "call", start = at(LocalDateTime.of(2026, 9, 13, 20, 0)))
        assertEquals("Saved, call, today at eight.", Confirmation.sentence(sundayEvening, utcNow, Locale.UK))
    }

    // ------------------------------------------------------------------ times

    @Test
    fun `the quarters are said the British way`() {
        assertEquals("eight", spokenTime(20, 0))
        assertEquals("quarter past eight", spokenTime(20, 15))
        assertEquals("half past eight", spokenTime(20, 30))
        assertEquals("quarter to nine", spokenTime(20, 45))
    }

    @Test
    fun `other minutes are said as numbers, with oh under ten`() {
        assertEquals("eight oh five", spokenTime(8, 5))
        assertEquals("eight twenty", spokenTime(20, 20))
        assertEquals("eight fifty-nine", spokenTime(20, 59))
        assertEquals("seven forty", spokenTime(19, 40))
    }

    @Test
    fun `midday and midnight are words, and the hours around them are twelve`() {
        assertEquals("midday", spokenTime(12, 0))
        assertEquals("midnight", spokenTime(0, 0))
        assertEquals("half past twelve", spokenTime(12, 30))
        assertEquals("half past twelve", spokenTime(0, 30))
        assertEquals("quarter to twelve", spokenTime(11, 45))
        assertEquals("quarter to twelve", spokenTime(23, 45))
        assertEquals("one", spokenTime(13, 0))
    }

    @Test
    fun `a time Maia filled in names the part of the day`() {
        assertEquals("nine in the morning", spokenTime(9, 0, inferred = true))
        assertEquals("quarter to one in the morning", spokenTime(0, 45, inferred = true))
        assertEquals("three in the afternoon", spokenTime(15, 0, inferred = true))
        assertEquals("eight in the evening", spokenTime(20, 0, inferred = true))
        // Midday and midnight already say which half they are.
        assertEquals("midday", spokenTime(12, 0, inferred = true))
        assertEquals("midnight", spokenTime(0, 0, inferred = true))
        assertEquals(
            "Saved, Dinner with Sam, Thursday at eight in the evening.",
            say(draft(startFrom = Provenance.Inferred)),
        )
    }

    @Test
    fun `a corrected time is the user's and is said plainly`() {
        val corrected = draft(startFrom = Provenance.Inferred).with(Edit.Start(thursdayEight))
        assertEquals("Saved, Dinner with Sam, Thursday at eight.", say(corrected))
    }

    // ------------------------------------------------------------------ title

    @Test
    fun `stray whitespace and trailing punctuation cannot break the sentence`() {
        assertEquals(
            "Saved, dinner with sam, Thursday at eight.",
            say(draft(title = "  dinner   with\tsam . ")),
        )
    }

    @Test
    fun `a blank title is left out rather than spoken as a pause`() {
        assertEquals("Saved, Thursday at eight.", say(draft(title = "   ")))
    }

    // ------------------------------------------------------------------ shape

    @Test
    fun `every sentence is one short sentence with no em dash`() {
        val starts = (0..23).flatMap { h -> listOf(0, 5, 15, 30, 45, 52).map { m -> h to m } }
        for ((h, m) in starts) {
            for (days in listOf(0L, 1L, 3L, 7L, 200L)) {
                val start = at(LocalDateTime.of(2026, 9, 12, h, m).plusDays(days))
                for (inferred in listOf(false, true)) {
                    val s = say(draft(start = start, startFrom = if (inferred) Provenance.Inferred else Provenance.Heard))
                    assertTrue(s, s.startsWith("Saved, ") && s.endsWith("."))
                    assertEquals(s, 1, s.count { it == '.' })
                    assertFalse(s, s.any { it.code == 0x2014 || it.code == 0x2013 })
                    assertFalse(s, s.any { it.isDigit() && days < 7 })
                    assertTrue(s, s.split(' ').size <= 18)
                }
            }
        }
    }

    @Test
    fun `numbers in words`() {
        assertEquals("zero", Confirmation.cardinal(0))
        assertEquals("nineteen", Confirmation.cardinal(19))
        assertEquals("twenty", Confirmation.cardinal(20))
        assertEquals("forty-two", Confirmation.cardinal(42))
        assertEquals("first", Confirmation.ordinal(1))
        assertEquals("twelfth", Confirmation.ordinal(12))
        assertEquals("twentieth", Confirmation.ordinal(20))
        assertEquals("twenty-second", Confirmation.ordinal(22))
        assertEquals("thirtieth", Confirmation.ordinal(30))
        assertEquals("thirty-first", Confirmation.ordinal(31))
        assertEquals("two thousand and nine", Confirmation.year(2009))
        assertEquals("twenty thirty", Confirmation.year(2030))
        assertEquals("2150", Confirmation.year(2150))
    }
}
