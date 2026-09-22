package dev.maia.nlu.time

import dev.maia.nlu.Provenance
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Month
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every assertion here is exact rather than approximate, which is the whole
 * reason the resolver takes a Clock instead of reading one.
 *
 * The fixed instant is Saturday 12 September 2026, 11:30 local. Zurich is on
 * CEST in September, so 09:30Z is 11:30 in the zone the tests assert in.
 */
class TemporalResolverTest {

    private val zone = ZoneId.of("Europe/Zurich")
    private val saturday = Clock.fixed(Instant.parse("2026-09-12T09:30:00Z"), zone)
    private val resolver = TemporalResolver(saturday)

    private fun date(spec: DateSpec) = resolver.resolveDate(spec, LocalDate.of(2026, 9, 12))

    @Test
    fun `the fixture is the day it claims to be`() {
        assertEquals(DayOfWeek.SATURDAY, resolver.now().dayOfWeek)
        assertEquals(LocalDateTime.of(2026, 9, 12, 11, 30), resolver.now().toLocalDateTime())
    }

    @Test
    fun `nothing temporal in the sentence resolves to nothing`() {
        assertNull(resolver.resolve(date = null, time = null, duration = null))
    }

    // ------------------------------------------------------------ weekdays

    @Test
    fun `a bare weekday is the next one to come`() {
        assertEquals(
            LocalDate.of(2026, 9, 17),
            date(DateSpec.Weekday(DayOfWeek.THURSDAY, Which.Nearest)),
        )
    }

    /**
     * The rule that gets argued about. Asserted from a Tuesday, because on the
     * Saturday fixture both readings happen to give the same answer and a test
     * that cannot fail proves nothing.
     */
    @Test
    fun `next weekday is the following ISO week, not merely the next occurrence`() {
        val tuesday = TemporalResolver(
            Clock.fixed(Instant.parse("2026-09-08T09:30:00Z"), zone),
        )
        val today = LocalDate.of(2026, 9, 8)
        assertEquals(DayOfWeek.TUESDAY, today.dayOfWeek)
        assertEquals(
            LocalDate.of(2026, 9, 10),
            tuesday.resolveDate(DateSpec.Weekday(DayOfWeek.THURSDAY, Which.Nearest), today),
        )
        assertEquals(
            LocalDate.of(2026, 9, 17),
            tuesday.resolveDate(DateSpec.Weekday(DayOfWeek.THURSDAY, Which.Next), today),
        )
    }

    @Test
    fun `today counts as the nearest instance of its own weekday`() {
        assertEquals(
            LocalDate.of(2026, 9, 12),
            date(DateSpec.Weekday(DayOfWeek.SATURDAY, Which.Nearest)),
        )
    }

    @Test
    fun `but it stops counting once the hour has passed`() {
        // 09:00 on the Saturday is ninety minutes gone, so this is next week.
        val r = resolver.resolve(
            DateSpec.Weekday(DayOfWeek.SATURDAY, Which.Nearest),
            TimeSpec.Clock(9, 0, Meridiem.Am),
        )!!
        assertEquals(LocalDate.of(2026, 9, 19), r.start.value.toLocalDate())
    }

    @Test
    fun `this weekend is the coming saturday`() {
        assertEquals(LocalDate.of(2026, 9, 12), date(DateSpec.WeekendOf(Which.Nearest)))
    }

    // --------------------------------------------------------------- dates

    @Test
    fun `today and tomorrow`() {
        assertEquals(LocalDate.of(2026, 9, 12), date(DateSpec.Today))
        assertEquals(LocalDate.of(2026, 9, 13), date(DateSpec.Tomorrow))
    }

    @Test
    fun `a day of the month that has passed belongs to next month`() {
        assertEquals(LocalDate.of(2026, 10, 3), date(DateSpec.DayOfMonth(3)))
        assertEquals(LocalDate.of(2026, 9, 30), date(DateSpec.DayOfMonth(30)))
    }

    @Test
    fun `a named month that has passed belongs to next year`() {
        assertEquals(
            LocalDate.of(2027, 3, 3),
            date(DateSpec.DayOfMonth(3, Month.MARCH)),
        )
        assertEquals(
            LocalDate.of(2026, 12, 3),
            date(DateSpec.DayOfMonth(3, Month.DECEMBER)),
        )
    }

    @Test
    fun `an offset counts from today`() {
        assertEquals(
            LocalDate.of(2026, 9, 26),
            date(DateSpec.Offset(2, ChronoUnit.WEEKS)),
        )
    }

    @Test
    fun `a week from friday is the nearest friday plus seven`() {
        assertEquals(
            LocalDate.of(2026, 9, 25),
            date(
                DateSpec.Compound(
                    base = DateSpec.Weekday(DayOfWeek.FRIDAY, Which.Nearest),
                    plus = DateSpec.Offset(1, ChronoUnit.WEEKS),
                ),
            ),
        )
    }

    // --------------------------------------------------------------- times

    @Test
    fun `a spoken meridiem is heard, not guessed`() {
        val r = resolver.resolve(DateSpec.Today, TimeSpec.Clock(3, 0, Meridiem.Pm))!!
        assertEquals(LocalDateTime.of(2026, 9, 12, 15, 0), r.start.value.toLocalDateTime())
        assertEquals(Provenance.Heard, r.start.provenance)
    }

    @Test
    fun `midnight and midday are the two the naive formula gets wrong`() {
        val midnight = resolver.resolve(DateSpec.Tomorrow, TimeSpec.Clock(12, 0, Meridiem.Am))!!
        assertEquals(0, midnight.start.value.hour)
        val midday = resolver.resolve(DateSpec.Tomorrow, TimeSpec.Clock(12, 0, Meridiem.Pm))!!
        assertEquals(12, midday.start.value.hour)
    }

    @Test
    fun `a bare hour resolves inside the waking window and says it guessed`() {
        // Four has one reading in 07 to 21, so there is nothing to choose.
        val four = resolver.resolve(DateSpec.Today, TimeSpec.Clock(4))!!
        assertEquals(16, four.start.value.hour)
        assertEquals(Provenance.Inferred, four.start.provenance)
    }

    @Test
    fun `when both readings are awake, the next one to come wins`() {
        // Nine could be 09:00 or 21:00 and it is 11:30, so 09:00 is gone.
        val nine = resolver.resolve(DateSpec.Today, TimeSpec.Clock(9))!!
        assertEquals(21, nine.start.value.hour)
    }

    @Test
    fun `on a later day nothing has passed yet, so the earlier reading wins`() {
        // Eight on Thursday is eight in the morning. The next-to-come rule is
        // about today and only today: applied to a future day it reads the
        // current wall clock against a date the clock says nothing about, and
        // turns every morning appointment more than a day out into an evening.
        val eight = resolver.resolve(
            DateSpec.Weekday(DayOfWeek.THURSDAY, Which.Nearest),
            TimeSpec.Clock(8),
        )!!
        assertEquals(LocalDateTime.of(2026, 9, 17, 8, 0), eight.start.value.toLocalDateTime())
    }

    @Test
    fun `an impossible day in a named month clamps rather than throwing`() {
        val r = resolver.resolve(
            DateSpec.DayOfMonth(31, java.time.Month.FEBRUARY),
            TimeSpec.Clock(10, 0, Meridiem.Am),
        )!!
        assertEquals(LocalDateTime.of(2027, 2, 28, 10, 0), r.start.value.toLocalDateTime())
    }

    @Test
    fun `a twenty four hour reading needs no guess`() {
        val r = resolver.resolve(DateSpec.Today, TimeSpec.Clock(14, 30))!!
        assertEquals(LocalDateTime.of(2026, 9, 12, 14, 30), r.start.value.toLocalDateTime())
        assertEquals(Provenance.Heard, r.start.provenance)
    }

    @Test
    fun `a bare time already past means tomorrow`() {
        val r = resolver.resolve(date = null, time = TimeSpec.Clock(9, 0, Meridiem.Am))!!
        assertEquals(LocalDateTime.of(2026, 9, 13, 9, 0), r.start.value.toLocalDateTime())
    }

    @Test
    fun `a part of day starts at its anchor, not at the edge of its window`() {
        val r = resolver.resolve(
            DateSpec.Weekday(DayOfWeek.THURSDAY, Which.Nearest),
            TimeSpec.PartOfDay(Part.Afternoon),
        )!!
        assertEquals(LocalDateTime.of(2026, 9, 17, 14, 0), r.start.value.toLocalDateTime())
        assertEquals(Provenance.Inferred, r.start.provenance)
    }

    // ----------------------------------------------------------- durations

    @Test
    fun `no duration given is one hour, marked`() {
        val r = resolver.resolve(DateSpec.Today, TimeSpec.Clock(3, 0, Meridiem.Pm))!!
        assertEquals(Duration.ofHours(1), r.duration.value)
        assertEquals(Provenance.Inferred, r.duration.provenance)
        assertEquals(LocalDateTime.of(2026, 9, 12, 16, 0), r.end.toLocalDateTime())
    }

    @Test
    fun `a spoken duration is heard`() {
        val r = resolver.resolve(
            DateSpec.Today,
            TimeSpec.Clock(3, 0, Meridiem.Pm),
            DurationSpec.Of(45, ChronoUnit.MINUTES),
        )!!
        assertEquals(Duration.ofMinutes(45), r.duration.value)
        assertEquals(Provenance.Heard, r.duration.provenance)
    }

    @Test
    fun `an until that reads as earlier crosses into the next day`() {
        val r = resolver.resolve(
            DateSpec.Today,
            TimeSpec.Clock(11, 0, Meridiem.Pm),
            DurationSpec.Until(TimeSpec.Clock(1, 0, Meridiem.Am)),
        )!!
        assertEquals(Duration.ofHours(2), r.duration.value)
        assertEquals(LocalDateTime.of(2026, 9, 13, 1, 0), r.end.toLocalDateTime())
    }

    // -------------------------------------------------------------- all day

    @Test
    fun `a day with no time is all day rather than an invented hour`() {
        val r = resolver.resolve(DateSpec.Weekday(DayOfWeek.THURSDAY, Which.Nearest), null)!!
        assertTrue(r.allDay)
        assertEquals(LocalDateTime.of(2026, 9, 17, 0, 0), r.start.value.toLocalDateTime())
        assertEquals(Duration.ofDays(1), r.duration.value)
    }

    // --------------------------------------------------------------- range

    @Test
    fun `afternoon is the same twelve to eighteen the card uses`() {
        val range = resolver.resolveRange(
            DateSpec.Weekday(DayOfWeek.THURSDAY, Which.Nearest),
            TimeSpec.PartOfDay(Part.Afternoon),
        )
        assertEquals(LocalDateTime.of(2026, 9, 17, 12, 0), range.start.toLocalDateTime())
        assertEquals(LocalDateTime.of(2026, 9, 17, 18, 0), range.endInclusive.toLocalDateTime())
    }

    @Test
    fun `a day with no part of day is the whole day`() {
        val range = resolver.resolveRange(DateSpec.Today, null)
        assertEquals(LocalDateTime.of(2026, 9, 12, 0, 0), range.start.toLocalDateTime())
        assertEquals(LocalDateTime.of(2026, 9, 13, 0, 0), range.endInclusive.toLocalDateTime())
    }

    // ----------------------------------------------------------------- zone

    @Test
    fun `the clock carries the zone, so one parameter settles both`() {
        val tokyo = TemporalResolver(
            Clock.fixed(Instant.parse("2026-09-12T09:30:00Z"), ZoneId.of("Asia/Tokyo")),
        )
        assertEquals(18, tokyo.now().hour)
        assertEquals(
            ZoneId.of("Asia/Tokyo"),
            tokyo.resolve(DateSpec.Today, TimeSpec.Clock(8, 0, Meridiem.Pm))!!.start.value.zone,
        )
    }
}
