package dev.maia.nlu.time

import dev.maia.nlu.text.Normaliser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Month
import java.time.temporal.ChronoUnit

class TemporalExtractorTest {

    private fun extract(s: String) = TemporalExtractor.extract(Normaliser.normalise(s))

    @Test
    fun `a sentence with no time in it yields nothing`() {
        val t = extract("call the dentist")
        assertTrue(t.isEmpty)
        assertTrue(t.consumed.isEmpty())
    }

    @Test
    fun `tomorrow is a date`() {
        assertEquals(DateSpec.Tomorrow, extract("lunch tomorrow").date)
    }

    @Test
    fun `a bare weekday is the nearest one`() {
        assertEquals(
            DateSpec.Weekday(DayOfWeek.THURSDAY, Which.Nearest),
            extract("dentist thursday").date,
        )
    }

    @Test
    fun `next thursday says next`() {
        assertEquals(
            DateSpec.Weekday(DayOfWeek.THURSDAY, Which.Next),
            extract("dentist next thursday").date,
        )
    }

    @Test
    fun `in two weeks is an offset`() {
        assertEquals(
            DateSpec.Offset(2, ChronoUnit.WEEKS),
            extract("review in two weeks").date,
        )
    }

    @Test
    fun `a week from friday is a compound`() {
        assertEquals(
            DateSpec.Compound(
                DateSpec.Weekday(DayOfWeek.FRIDAY, Which.Nearest),
                DateSpec.Offset(1, ChronoUnit.WEEKS),
            ),
            extract("standup a week from friday").date,
        )
    }

    @Test
    fun `the third of march is a day of month`() {
        assertEquals(
            DateSpec.DayOfMonth(3, Month.MARCH),
            extract("party the third of march").date,
        )
    }

    @Test
    fun `march the third says the same thing the other way round`() {
        assertEquals(
            DateSpec.DayOfMonth(3, Month.MARCH),
            extract("party march the third").date,
        )
    }

    @Test
    fun `a bare ordinal has no month`() {
        assertEquals(DateSpec.DayOfMonth(21), extract("rent the twenty first").date)
    }

    @Test
    fun `this weekend is a weekend`() {
        assertEquals(DateSpec.WeekendOf(Which.This), extract("camping this weekend").date)
    }

    @Test
    fun `a meridiem time reads its hour`() {
        assertEquals(TimeSpec.Clock(3, 0, Meridiem.Pm), extract("call at three p m").time)
    }

    @Test
    fun `o clock is an hour`() {
        assertEquals(TimeSpec.Clock(8, 0, null), extract("call at eight o clock").time)
    }

    @Test
    fun `a two part time reads its minutes`() {
        assertEquals(TimeSpec.Clock(8, 30, null), extract("call at eight thirty").time)
    }

    @Test
    fun `oh five is five past`() {
        assertEquals(TimeSpec.Clock(9, 5, Meridiem.Am), extract("call at nine oh five a m").time)
    }

    @Test
    fun `half past three is half past three`() {
        assertEquals(TimeSpec.Clock(3, 30, null), extract("call at half past three").time)
    }

    @Test
    fun `quarter to four is three forty five`() {
        assertEquals(TimeSpec.Clock(3, 45, null), extract("call at quarter to four").time)
    }

    @Test
    fun `noon is midday and midnight is not`() {
        assertEquals(TimeSpec.Clock(12, 0, Meridiem.Pm), extract("lunch at noon").time)
        assertEquals(TimeSpec.Clock(0, 0, null), extract("deploy at midnight").time)
    }

    @Test
    fun `a part of day is a time`() {
        assertEquals(TimeSpec.PartOfDay(Part.Afternoon), extract("gym thursday afternoon").time)
    }

    @Test
    fun `tonight is both a date and an evening`() {
        val t = extract("dinner tonight")
        assertEquals(DateSpec.Today, t.date)
        assertEquals(TimeSpec.PartOfDay(Part.Evening), t.time)
    }

    @Test
    fun `a bare number is a time only after at`() {
        assertEquals(TimeSpec.Clock(9, 0, null), extract("meet at nine").time)
        assertNull(extract("table for four people").time)
    }

    @Test
    fun `for an hour is a duration`() {
        assertEquals(
            DurationSpec.Of(1, ChronoUnit.HOURS),
            extract("gym tomorrow for one hour").duration,
        )
    }

    @Test
    fun `a bare run of minutes is a duration`() {
        // Not "at nine forty five minutes", which is a quarter to ten with a
        // stray word after it. A length said without "for" has to stand alone.
        assertEquals(
            DurationSpec.Of(45, ChronoUnit.MINUTES),
            extract("block forty five minutes tomorrow").duration,
        )
    }

    @Test
    fun `until reads the end as a time`() {
        assertEquals(
            DurationSpec.Until(TimeSpec.Clock(5, 0, Meridiem.Pm)),
            extract("workshop at nine until five p m").duration,
        )
    }

    @Test
    fun `in ten minutes is when it starts and not how long it lasts`() {
        val t = extract("call in ten minutes")
        assertEquals(DateSpec.Offset(10, ChronoUnit.MINUTES), t.date)
        assertNull(t.duration)
    }

    @Test
    fun `every temporal word is marked consumed and nothing else is`() {
        // "lunch with sam tomorrow at noon" -> indices 3 and 4,5 are temporal.
        val t = extract("lunch with sam tomorrow at noon")
        assertEquals(setOf(3, 4, 5), t.consumed)
    }

    @Test
    fun `the spans point at the words that were read`() {
        assertEquals(1..1, extract("lunch tomorrow").dateSpan)
    }

    @Test
    fun `a span covers both halves of a merged token`() {
        // "p m" is two spoken words and one token. The card underlines the
        // phrase the user said, so the span has to reach back over both.
        assertEquals(1..4, extract("call at three p m").timeSpan)
    }

    @Test
    fun `a date and a time and a duration coexist`() {
        val t = extract("standup tomorrow at nine for thirty minutes")
        assertEquals(DateSpec.Tomorrow, t.date)
        assertEquals(TimeSpec.Clock(9, 0, null), t.time)
        assertEquals(DurationSpec.Of(30, ChronoUnit.MINUTES), t.duration)
    }
}
