package dev.maia.app.answer

import dev.maia.actions.CalendarEvent
import dev.maia.actions.CalendarRepository
import dev.maia.actions.CalendarTarget
import dev.maia.actions.MaiaCalendar
import dev.maia.nlu.EventDraft
import dev.maia.nlu.Intent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The reads a phone can do alone, driven on the JVM.
 *
 * `LocalReader` holds no Android: the repository is a fake, the clock is
 * fixed, and the battery is a lambda, so every result here is the one the
 * driver would report. The sentences themselves are `LocalAnswer`'s and
 * have their own test; what this one owns is the mapping: which intent
 * reads what, which null becomes which fault, and the permission error
 * that has to arrive as a fault and not as an empty answer.
 */
class LocalReaderTest {

    private val zone = ZoneId.of("Europe/London")
    private val clock = Clock.fixed(Instant.parse("2026-09-22T10:30:00Z"), zone)

    private class FakeCalendar(
        var events: List<CalendarEvent> = emptyList(),
        var fail: SecurityException? = null,
    ) : CalendarRepository {
        override suspend fun calendars(): List<MaiaCalendar> = emptyList()
        override suspend fun defaultTarget(): CalendarTarget? = null
        override suspend fun chooseTarget(calendarId: Long) = Unit
        override suspend fun commit(draft: EventDraft, calendarId: Long): Long = 0
        override suspend fun delete(eventId: Long): Boolean = false
        override suspend fun eventsIn(range: ClosedRange<ZonedDateTime>): List<CalendarEvent> {
            fail?.let { throw it }
            return events
        }
    }

    private fun at(hour: Int, minute: Int = 0): ZonedDateTime =
        ZonedDateTime.of(2026, 9, 22, hour, minute, 0, 0, zone)

    private fun event(title: String, start: Int, end: Int, allDay: Boolean = false) =
        CalendarEvent(1L, title, at(start), at(end), allDay)

    private fun dayRange() = at(0)..at(23, 59)

    private fun reader(
        calendar: FakeCalendar = FakeCalendar(),
        battery: () -> Int? = { null },
    ) = LocalReader(calendar, clock, battery)

    private fun read(reader: LocalReader, intent: Intent): LocalRead =
        runBlocking { reader.read(intent) }

    // ------------------------------------------------------------- agenda

    @Test
    fun `an empty agenda is an answer, not a fault`() {
        val read = read(reader(), Intent.Agenda(dayRange())) as LocalRead.Done
        assertEquals("You have no events on Tuesday 22 September", read.text)
        assertEquals(AnswerSource.Calendar, read.source)
    }

    @Test
    fun `an agenda lists what the provider returned, with the count`() {
        val calendar = FakeCalendar(listOf(event("Dentist", 14, 15), event("Standup", 9, 10)))
        val read = read(reader(calendar), Intent.Agenda(dayRange())) as LocalRead.Done
        assertEquals(
            "You have 2 events on Tuesday 22 September: Standup at 09:00, Dentist at 14:00",
            read.text,
        )
    }

    @Test
    fun `a denied calendar is the permission fault, not an empty agenda`() {
        val calendar = FakeCalendar(fail = SecurityException("READ_CALENDAR denied"))
        assertEquals(
            LocalRead.Failed(AnswerFault.Permission(PermNeeded.Calendar)),
            read(reader(calendar), Intent.Agenda(dayRange())),
        )
        assertEquals(
            LocalRead.Failed(AnswerFault.Permission(PermNeeded.Calendar)),
            read(reader(calendar), Intent.Availability(dayRange())),
        )
    }

    // ------------------------------------------------------- availability

    @Test
    fun `a clear day is free`() {
        assertEquals(
            LocalRead.Done("You are free on Tuesday 22 September", AnswerSource.Calendar),
            read(reader(), Intent.Availability(dayRange())),
        )
    }

    @Test
    fun `a day with events is busy, and the events are named`() {
        val calendar = FakeCalendar(listOf(event("Standup", 9, 10), event("Lunch", 12, 13)))
        val read = read(reader(calendar), Intent.Availability(dayRange())) as LocalRead.Done
        assertTrue(read.text.startsWith("You are busy"))
        assertTrue(read.text.contains("Standup at 09:00"))
    }

    // ------------------------------------------------------ calculation

    @Test
    fun `a calculation echoes the expression with its result`() {
        assertEquals(
            LocalRead.Done("12*8 = 96", AnswerSource.Phone),
            read(reader(), Intent.Calculate("12*8")),
        )
    }

    @Test
    fun `an expression that does not evaluate is a fault, not a sentence`() {
        assertEquals(
            LocalRead.Failed(AnswerFault.Unusable),
            read(reader(), Intent.Calculate("what is")),
        )
    }

    // ------------------------------------------------------------ facts

    @Test
    fun `the time comes from the injected clock`() {
        val read = read(reader(), Intent.DeviceFact(Intent.DeviceFact.Kind.TIME)) as LocalRead.Done
        // 10:30 UTC is 11:30 in London in September.
        assertEquals("It's 11:30", read.text)
        assertEquals(AnswerSource.Phone, read.source)
    }

    @Test
    fun `the date comes from the injected clock`() {
        val read = read(reader(), Intent.DeviceFact(Intent.DeviceFact.Kind.DATE)) as LocalRead.Done
        assertEquals("It's Tuesday 22 September", read.text)
    }

    @Test
    fun `the battery is read and never guessed`() {
        assertEquals(
            LocalRead.Done("Battery is at 64%", AnswerSource.Phone),
            read(reader(battery = { 64 }), Intent.DeviceFact(Intent.DeviceFact.Kind.BATTERY)),
        )
        // An absent reading is the honest fault, not a number.
        assertEquals(
            LocalRead.Failed(AnswerFault.Unusable),
            read(reader(battery = { null }), Intent.DeviceFact(Intent.DeviceFact.Kind.BATTERY)),
        )
    }

    @Test
    fun `anything that is not a read is a fault, not an answer`() {
        assertEquals(
            LocalRead.Failed(AnswerFault.Unusable),
            read(reader(), Intent.SetTimer(60_000)),
        )
    }
}
