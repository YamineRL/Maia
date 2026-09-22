package dev.maia.app.answer

import dev.maia.actions.CalendarEvent
import dev.maia.nlu.Intent
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The local answers as sentences. Every input is handed in, the way the
 * driver will hand it: events already read, battery already read, the clock
 * already stamped. 2026-09-23 is a Wednesday.
 */
class LocalAnswerTest {

    private val zone: ZoneId = ZoneId.of("Europe/Paris")
    private fun at(day: Int, hour: Int, minute: Int = 0): ZonedDateTime =
        ZonedDateTime.of(2026, 9, day, hour, minute, 0, 0, zone)
    private fun event(title: String, start: ZonedDateTime, end: ZonedDateTime, allDay: Boolean = false) =
        CalendarEvent(id = 1, title = title, start = start, end = end, allDay = allDay)

    private fun wholeDay(day: Int = 23): ClosedRange<ZonedDateTime> = at(day, 0)..at(day, 23, 59)

    // --------------------------------------------------------------- agenda

    @Test
    fun `an empty day is an answer, not an error`() {
        val result = agenda(emptyList(), wholeDay())
        assertEquals("You have no events on Wednesday 23 September", result.text)
    }

    @Test
    fun `a day lists its events in order and says only the count`() {
        val result = agenda(
            listOf(
                event("lunch", at(23, 12), at(23, 13)),
                event("standup", at(23, 9, 30), at(23, 9, 45)),
            ),
            wholeDay(),
        )
        assertEquals(
            "You have 2 events on Wednesday 23 September: standup at 09:30, lunch at 12:00",
            result.text,
        )
        // The voice gets the count and stops: the titles are for reading.
        assertEquals("You have 2 events on Wednesday 23 September", result.spokenText)
    }

    @Test
    fun `an all day event has no invented time`() {
        val result = agenda(
            listOf(event("conference", at(23, 0), at(24, 0), allDay = true)),
            wholeDay(),
        )
        assertEquals("You have 1 event on Wednesday 23 September: conference, all day", result.text)
    }

    @Test
    fun `a part-day window says the hours`() {
        val result = agenda(emptyList(), at(23, 12)..at(23, 18))
        assertEquals("You have no events on Wednesday 23 September, 12:00 to 18:00", result.text)
    }

    // ---------------------------------------------------------- availability

    @Test
    fun `nothing overlapping means free`() {
        val result = availability(
            listOf(event("morning", at(23, 9), at(23, 10))),
            at(23, 12)..at(23, 18),
        )
        assertEquals("You are free on Wednesday 23 September, 12:00 to 18:00", result.text)
    }

    @Test
    fun `an overlap means busy, named by the overlapping events`() {
        val result = availability(
            listOf(
                event("lunch", at(23, 12), at(23, 13)),
                event("morning", at(23, 9), at(23, 10)),
            ),
            at(23, 12)..at(23, 18),
        )
        // "morning" does not overlap the window and stays out of the answer.
        assertEquals(
            "You are busy on Wednesday 23 September, 12:00 to 18:00: lunch at 12:00",
            result.text,
        )
        assertEquals("You are busy on Wednesday 23 September, 12:00 to 18:00", result.spokenText)
    }

    // ----------------------------------------------------------- device fact

    @Test
    fun `the time is the clock, twenty four hour`() {
        assertEquals(
            "It's 14:32",
            deviceFact(Intent.DeviceFact.Kind.TIME, batteryPct = null, now = at(23, 14, 32))!!.text,
        )
    }

    @Test
    fun `the date is the day name and the date`() {
        assertEquals(
            "It's Wednesday 23 September",
            deviceFact(Intent.DeviceFact.Kind.DATE, batteryPct = null, now = at(23, 14))!!.text,
        )
    }

    @Test
    fun `the battery is the number that was read, or nothing`() {
        assertEquals(
            "Battery is at 68%",
            deviceFact(Intent.DeviceFact.Kind.BATTERY, batteryPct = 68, now = at(23, 14))!!.text,
        )
        assertNull(deviceFact(Intent.DeviceFact.Kind.BATTERY, batteryPct = null, now = at(23, 14)))
    }

    // ------------------------------------------------------------ calculate

    @Test
    fun `a calculation shows what was computed`() {
        // The expression is echoed because it is what was evaluated, which is
        // how a mis-hearing stays visible instead of becoming a wrong answer.
        assertEquals("12*8 = 96", calculate("12*8")!!.text)
        assertEquals("15/100*200 = 30", calculate("15/100*200")!!.text)
    }

    @Test
    fun `an expression that does not parse produces nothing`() {
        assertNull(calculate("lots of things"))
        assertNull(calculate("1/0"))
    }
}
