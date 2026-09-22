package dev.maia.actions

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M2 brief criterion 7: undo deletes exactly the event it was given. */
class FakeCalendarsDeleteTest {
    private val zone = ZoneId.of("Europe/London")
    private val at = ZonedDateTime.of(2026, 9, 17, 20, 0, 0, 0, zone)

    private fun event(id: Long, title: String) =
        CalendarEvent(id = id, title = title, start = at, end = at.plusHours(1), calendarId = 1)

    @Test
    fun `delete removes exactly that event and is false the second time`() = runTest {
        val calendars = FakeCalendars(events = listOf(event(7, "dinner"), event(8, "dinner")))
        assertTrue(calendars.delete(7))
        assertEquals(listOf(8L), calendars.committed.map { it.id })
        assertFalse(calendars.delete(7))
        assertEquals(listOf(8L), calendars.committed.map { it.id })
    }

    @Test
    fun `an id that was never written is not deleted`() = runTest {
        val calendars = FakeCalendars(events = listOf(event(7, "dinner")))
        assertFalse(calendars.delete(99))
        assertEquals(1, calendars.committed.size)
    }

    @Test(expected = CalendarWriteDenied::class)
    fun `a revoked permission surfaces as a denial, not as already gone`() = runTest {
        val calendars = FakeCalendars(events = listOf(event(7, "dinner")))
        calendars.denyWrites = true
        calendars.delete(7)
    }
}
