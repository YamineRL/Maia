package dev.maia.actions

import dev.maia.nlu.EventDraft
import dev.maia.nlu.Field
import dev.maia.nlu.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * The half of the calendar write that can be proved without a phone.
 *
 * Step 8 of the M1 brief says "compiles and is reviewed, it cannot be tested
 * here", and that is true of the ContentResolver. It is not true of the
 * all-day arithmetic, which is the most dangerous thing in the module and is
 * pure java.time. So it was split out into [EventWriter.timesFor] and is
 * pinned here: a wrong answer below is an event on the wrong day, silently,
 * with nothing thrown and no way to notice until a user misses something.
 *
 * No android import in this file, and none in the code it exercises.
 */
class EventWriterTest {

    private val zurich = ZoneId.of("Europe/Zurich") // UTC+2 in September
    private val utc = ZoneOffset.UTC

    private fun draft(
        start: ZonedDateTime,
        duration: Duration,
        allDay: Boolean = false,
        title: String = "test",
    ) = EventDraft(
        title = Field(title, Provenance.Heard),
        start = Field(start, Provenance.Heard),
        duration = Field(duration, Provenance.Heard),
        allDay = allDay,
    )

    private fun millis(text: String): Long = Instant.parse(text).toEpochMilli()

    // ----------------------------------------------------------------- timed

    @Test
    fun `a timed event keeps its own zone and its exact instants`() {
        val start = ZonedDateTime.of(LocalDateTime.of(2026, 9, 13, 12, 0), zurich)
        val times = EventWriter.timesFor(draft(start, Duration.ofHours(1)))

        assertEquals(millis("2026-09-13T10:00:00Z"), times.dtStart)
        assertEquals(millis("2026-09-13T11:00:00Z"), times.dtEnd)
        assertEquals("Europe/Zurich", times.eventTimezone)
        assertTrue(!times.allDay)
    }

    @Test
    fun `a timed event in a negative offset zone is not shifted either`() {
        val newYork = ZoneId.of("America/New_York") // UTC-4 in September
        val start = ZonedDateTime.of(LocalDateTime.of(2026, 9, 13, 9, 30), newYork)
        val times = EventWriter.timesFor(draft(start, Duration.ofMinutes(45)))

        assertEquals(millis("2026-09-13T13:30:00Z"), times.dtStart)
        assertEquals(millis("2026-09-13T14:15:00Z"), times.dtEnd)
        assertEquals("America/New_York", times.eventTimezone)
    }

    // --------------------------------------------------------------- all day

    @Test
    fun `a one day all day event runs UTC midnight to the next UTC midnight`() {
        val start = ZonedDateTime.of(LocalDateTime.of(2026, 9, 13, 0, 0), zurich)
        val times = EventWriter.timesFor(draft(start, Duration.ofDays(1), allDay = true))

        assertEquals(millis("2026-09-13T00:00:00Z"), times.dtStart)
        // Exclusive. The midnight that starts the NEXT day, not the same one.
        assertEquals(millis("2026-09-14T00:00:00Z"), times.dtEnd)
        assertEquals("UTC", times.eventTimezone)
        assertTrue(times.allDay)
    }

    @Test
    fun `local midnight at a positive offset does not slip to the day before`() {
        // The whole reason the date is taken locally and rebuilt at UTC
        // midnight. 2026-09-13T00:00+02:00 is 2026-09-12T22:00Z, and
        // CalendarProvider2.fixAllDayTime would truncate that to 2026-09-12
        // without complaint, putting the event a day early forever.
        val start = ZonedDateTime.of(LocalDateTime.of(2026, 9, 13, 0, 0), zurich)
        assertEquals(
            millis("2026-09-12T22:00:00Z"),
            start.toInstant().toEpochMilli(),
        )

        val times = EventWriter.timesFor(draft(start, Duration.ofDays(1), allDay = true))
        assertEquals(millis("2026-09-13T00:00:00Z"), times.dtStart)
    }

    @Test
    fun `an all day draft whose start carries an hour is still anchored to midnight`() {
        // The draft can reach here un-normalised: allDay is a flag on
        // EventDraft and only Edit.AllDay resets the start to local midnight.
        val start = ZonedDateTime.of(LocalDateTime.of(2026, 9, 13, 14, 45), zurich)
        val times = EventWriter.timesFor(draft(start, Duration.ofDays(1), allDay = true))

        assertEquals(millis("2026-09-13T00:00:00Z"), times.dtStart)
        assertEquals(millis("2026-09-14T00:00:00Z"), times.dtEnd)
    }

    @Test
    fun `a negative offset zone gets the same local date, not the UTC one`() {
        val newYork = ZoneId.of("America/New_York")
        val start = ZonedDateTime.of(LocalDateTime.of(2026, 9, 13, 20, 0), newYork)
        // That instant is already 2026-09-14 in UTC.
        assertEquals(millis("2026-09-14T00:00:00Z"), start.toInstant().toEpochMilli())

        val times = EventWriter.timesFor(draft(start, Duration.ofDays(1), allDay = true))
        assertEquals(millis("2026-09-13T00:00:00Z"), times.dtStart)
        assertEquals(millis("2026-09-14T00:00:00Z"), times.dtEnd)
    }

    @Test
    fun `a multi day all day span covers every day it touches`() {
        val start = ZonedDateTime.of(LocalDateTime.of(2026, 9, 14, 0, 0), zurich)
        val times = EventWriter.timesFor(draft(start, Duration.ofDays(3), allDay = true))

        assertEquals(millis("2026-09-14T00:00:00Z"), times.dtStart)
        assertEquals(millis("2026-09-17T00:00:00Z"), times.dtEnd)
    }

    @Test
    fun `a span that spills past a midnight claims the day it spills into`() {
        // The documented rule: round up to whole days. 36 hours of all-day is
        // two days, because an all-day event has no half day to express and
        // rounding down would stop before the user's last day.
        val start = ZonedDateTime.of(LocalDateTime.of(2026, 9, 14, 0, 0), zurich)
        val times = EventWriter.timesFor(
            draft(start, Duration.ofHours(36), allDay = true),
        )
        assertEquals(millis("2026-09-14T00:00:00Z"), times.dtStart)
        assertEquals(millis("2026-09-16T00:00:00Z"), times.dtEnd)
    }

    @Test
    fun `a zero or absurd duration still produces one whole day`() {
        val start = ZonedDateTime.of(LocalDateTime.of(2026, 9, 14, 0, 0), zurich)
        for (d in listOf(Duration.ZERO, Duration.ofMinutes(-30), Duration.ofMinutes(5))) {
            val times = EventWriter.timesFor(draft(start, d, allDay = true))
            assertEquals(
                "duration $d should still be one day",
                millis("2026-09-15T00:00:00Z"),
                times.dtEnd,
            )
        }
    }

    @Test
    fun `an all day span crossing a DST change is still whole UTC days`() {
        // Europe/Zurich leaves summer time on 2026-10-25. Counting all-day
        // spans in local millis would produce a 25 hour day and an end that is
        // not on a UTC midnight, which the provider truncates in silence.
        val start = ZonedDateTime.of(LocalDateTime.of(2026, 10, 24, 0, 0), zurich)
        val times = EventWriter.timesFor(draft(start, Duration.ofDays(2), allDay = true))

        assertEquals(millis("2026-10-24T00:00:00Z"), times.dtStart)
        assertEquals(millis("2026-10-26T00:00:00Z"), times.dtEnd)
    }

    // ------------------------------------------------------------- invariant

    @Test
    fun `DTEND is strictly after DTSTART for every draft shape`() {
        val starts = listOf(
            ZonedDateTime.of(LocalDateTime.of(2026, 9, 13, 0, 0), zurich),
            ZonedDateTime.of(LocalDateTime.of(2026, 9, 13, 23, 59), zurich),
            ZonedDateTime.of(LocalDateTime.of(2026, 10, 25, 2, 30), zurich),
            ZonedDateTime.of(LocalDateTime.of(2026, 9, 13, 20, 0), ZoneId.of("America/New_York")),
            ZonedDateTime.of(LocalDateTime.of(2026, 9, 13, 6, 0), utc),
        )
        val durations = listOf(
            Duration.ofMinutes(1),
            Duration.ofMinutes(30),
            Duration.ofHours(25),
            Duration.ofDays(1),
            Duration.ofDays(4),
        )
        for (s in starts) for (d in durations) for (allDay in listOf(false, true)) {
            val times = EventWriter.timesFor(draft(s, d, allDay = allDay))
            assertTrue(
                "dtEnd must exceed dtStart for $s / $d / allDay=$allDay",
                times.dtEnd > times.dtStart,
            )
            if (allDay) {
                assertEquals("UTC", times.eventTimezone)
                assertEquals(0L, times.dtStart % 86_400_000L)
                assertEquals(0L, times.dtEnd % 86_400_000L)
            }
        }
    }

    @Test
    fun `the all day flag from the card lands on exactly one day`() {
        // EventDraft.with(Edit.AllDay) sets duration to exactly Duration.ofDays(1),
        // so this is the shape the card will actually hand over.
        val start = ZonedDateTime.of(LocalDateTime.of(2026, 9, 13, 15, 20), zurich)
        val fromCard = draft(start, Duration.ofHours(1))
            .with(dev.maia.nlu.Edit.AllDay(true))
        val times = EventWriter.timesFor(fromCard)

        assertEquals(millis("2026-09-13T00:00:00Z"), times.dtStart)
        assertEquals(millis("2026-09-14T00:00:00Z"), times.dtEnd)
        assertEquals("UTC", times.eventTimezone)
    }
}
