package dev.maia.actions

import dev.maia.nlu.Field
import dev.maia.nlu.Intent
import dev.maia.nlu.Parser
import dev.maia.nlu.Provenance
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Criterion 7 of the M1 brief: the card and the chooser drive a repository
 * with no Android classes involved.
 *
 * There is no `android.` import in this file and none in the code it exercises,
 * which is the assertion the criterion actually makes. Everything below runs
 * on the desktop JVM in milliseconds.
 */
class FakeCalendarsTest {

    private val zone = ZoneId.of("Europe/Zurich")
    private val clock = Clock.fixed(Instant.parse("2026-09-12T09:30:00Z"), zone)
    private val parser = Parser(clock)

    private fun draft(sentence: String) =
        (parser.parse(sentence) as Intent.CreateEvent).draft

    // ------------------------------------------------------------- chooser

    @Test
    fun `the chooser sees every calendar including the one it cannot write to`() = runTest {
        val repo = FakeCalendars()
        val all = repo.calendars()
        assertEquals(3, all.size)
        assertEquals(listOf("Personal", "Work", "Holidays"), all.map { it.displayName })
        assertFalse(all.single { it.displayName == "Holidays" }.writable)
    }

    @Test
    fun `with no choice made the target is a guess and says so`() = runTest {
        val target = FakeCalendars().defaultTarget()!!
        assertEquals("Personal", target.calendar.displayName)
        assertTrue(target.guessed)
        assertFalse(target.chosen)
    }

    @Test
    fun `once chosen the target stops being a guess`() = runTest {
        val repo = FakeCalendars()
        repo.chooseTarget(2)
        val target = repo.defaultTarget()!!
        assertEquals("Work", target.calendar.displayName)
        assertTrue(target.chosen)
    }

    @Test
    fun `a read only calendar cannot be chosen`() = runTest {
        val repo = FakeCalendars()
        val threw = runCatching { repo.chooseTarget(3) }.isFailure
        assertTrue("choosing a read only calendar should not be allowed", threw)
    }

    @Test
    fun `with no calendars there is no target and that is not an error`() = runTest {
        assertNull(FakeCalendars(FakeCalendars.empty).defaultTarget())
    }

    @Test
    fun `with only read only calendars there is still no target`() = runTest {
        val readOnly = listOf(MaiaCalendar(9, "Holidays", "subscribed", writable = false))
        assertNull(FakeCalendars(readOnly).defaultTarget())
    }

    // ---------------------------------------------------------------- card

    @Test
    fun `a parsed draft commits as the event it described`() = runTest {
        val repo = FakeCalendars()
        val draft = draft("schedule lunch with sam tomorrow at noon")
        val id = repo.commit(draft, repo.defaultTarget()!!.calendar.id)

        val written = repo.committed.single { it.id == id }
        assertEquals("lunch with sam", written.title)
        assertEquals("2026-09-13T12:00+02:00[Europe/Zurich]", written.start.toString())
        assertEquals("2026-09-13T13:00+02:00[Europe/Zurich]", written.end.toString())
        assertFalse(written.allDay)
        assertEquals(1L, written.calendarId)
    }

    @Test
    fun `an edit on the card is what gets committed`() = runTest {
        val repo = FakeCalendars()
        val edited = draft("schedule standup tomorrow at nine a m")
            .with(dev.maia.nlu.Edit.Title("morning standup"))
            .with(dev.maia.nlu.Edit.Length(Duration.ofMinutes(15)))
        repo.commit(edited, 1)

        val written = repo.committed.single()
        assertEquals("morning standup", written.title)
        assertEquals(Duration.ofMinutes(15), Duration.between(written.start, written.end))
    }

    @Test
    fun `an all day draft commits as an all day event`() = runTest {
        val repo = FakeCalendars()
        repo.commit(draft("dentist thursday"), 1)
        val written = repo.committed.single()
        assertTrue(written.allDay)
        assertEquals(Duration.ofDays(1), Duration.between(written.start, written.end))
    }

    @Test
    fun `a revoked permission arrives as its own type`() = runTest {
        val repo = FakeCalendars()
        repo.denyWrites = true
        val thrown = runCatching { repo.commit(draft("schedule gym tomorrow"), 1) }
            .exceptionOrNull()
        assertTrue(
            "expected CalendarWriteDenied, got $thrown",
            thrown is CalendarWriteDenied,
        )
    }

    @Test
    fun `committing to a calendar that cannot be written to is refused`() = runTest {
        val repo = FakeCalendars()
        val threw = runCatching { repo.commit(draft("schedule gym tomorrow"), 3) }.isFailure
        assertTrue(threw)
    }

    // ------------------------------------------------------------ read back

    @Test
    fun `the agenda range returns what overlaps it and nothing else`() = runTest {
        val repo = FakeCalendars()
        repo.commit(draft("schedule lunch tomorrow at noon"), 1)
        repo.commit(draft("schedule gym thursday at six p m"), 1)

        val tomorrow = parser.parse("what do i have tomorrow") as Intent.Agenda
        val found = repo.eventsIn(tomorrow.range)
        assertEquals(listOf("lunch"), found.map { it.title })
    }

    @Test
    fun `free means nothing overlaps the window that was asked about`() = runTest {
        val repo = FakeCalendars()
        repo.commit(draft("schedule review thursday at ten a m"), 1)

        val afternoon = parser.parse("am i free thursday afternoon") as Intent.Availability
        assertTrue(repo.eventsIn(afternoon.range).isEmpty())

        val morning = parser.parse("am i free thursday morning") as Intent.Availability
        assertEquals(listOf("review"), repo.eventsIn(morning.range).map { it.title })
    }

    @Test
    fun `an event touching the edge of a window does not count as overlapping it`() = runTest {
        // The review ends at noon and the afternoon window starts at noon.
        // Half open is the only reading that does not report a clash for every
        // back to back pair of events on the calendar.
        val repo = FakeCalendars()
        val ten = draft("schedule review thursday at ten a m")
            .copy(duration = Field(Duration.ofHours(2), Provenance.Heard))
        repo.commit(ten, 1)

        val afternoon = parser.parse("am i free thursday afternoon") as Intent.Availability
        assertTrue(repo.eventsIn(afternoon.range).isEmpty())
    }

    @Test
    fun `the read back comes out in time order`() = runTest {
        val repo = FakeCalendars()
        repo.commit(draft("schedule dinner tomorrow at seven p m"), 1)
        repo.commit(draft("schedule lunch tomorrow at noon"), 1)
        repo.commit(draft("schedule standup tomorrow at nine a m"), 1)

        val day = parser.parse("what do i have tomorrow") as Intent.Agenda
        assertEquals(
            listOf("standup", "lunch", "dinner"),
            repo.eventsIn(day.range).map { it.title },
        )
    }
}
