package dev.maia.app.card

import dev.maia.actions.CalendarRepository
import dev.maia.actions.FakeCalendars
import dev.maia.nlu.Edit
import dev.maia.nlu.EventDraft
import dev.maia.nlu.Field
import dev.maia.nlu.Provenance
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * M1 criterion 7: the card and the calendar chooser, driven by a fake
 * [CalendarRepository] with no Android classes anywhere in the path.
 *
 * The ViewModel is an AndroidViewModel and is not what is under test here.
 * What is: the sequence of repository answers the ViewModel routes on, and
 * what the card shows the user at each step of it.
 */
class CardFlowTest {

    private val start = ZonedDateTime.of(LocalDateTime.of(2026, 9, 17, 20, 0), ZoneId.of("Europe/Zurich"))

    private val draft = EventDraft(
        title = Field("dinner with sam", Provenance.Heard),
        start = Field(start, Provenance.Heard),
        duration = Field(Duration.ofHours(1), Provenance.Inferred),
    )

    private suspend fun calendarRow(repository: CalendarRepository) =
        cardRows(draft, repository.defaultTarget(), Locale.UK).single { it.field == CardField.Calendar }

    @Test
    fun `a first card lands on a calendar Maia guessed, and says so`() = runTest {
        val row = calendarRow(FakeCalendars())
        assertEquals("Personal", row.value)
        assertEquals(Mark.Guessed, row.mark)
        assertEquals("you have not picked one, so this is Maia's guess", row.reason)
    }

    @Test
    fun `choosing a calendar takes the guess marks off, and they stay off`() = runTest {
        val repository = FakeCalendars()
        repository.chooseTarget(2)
        val row = calendarRow(repository)
        assertEquals("Work", row.value)
        assertEquals(Mark.Heard, row.mark)
        assertNull(row.reason)
        // The next card, for a different sentence, opens on the same choice.
        assertEquals(Mark.Heard, calendarRow(repository).mark)
    }

    @Test
    fun `the chooser lists a read only calendar last and it cannot be chosen`() = runTest {
        val repository = FakeCalendars()
        val order = chooserOrder(repository.calendars())
        assertEquals(listOf("Personal", "Work", "Holidays"), order.map { it.displayName })
        assertTrue(!order.last().writable)
        val refused = runCatching { repository.chooseTarget(3) }
        assertTrue(refused.exceptionOrNull() is IllegalArgumentException)
        // And the refusal changed nothing: still a guess, still Personal.
        val row = calendarRow(repository)
        assertEquals("Personal", row.value)
        assertEquals(Mark.Guessed, row.mark)
    }

    @Test
    fun `no writable calendar is the no-calendars route, not an error`() = runTest {
        val readOnly = FakeCalendars.defaultSet.filter { !it.writable }
        assertNull(FakeCalendars(readOnly).defaultTarget())
        assertNull(FakeCalendars(FakeCalendars.empty).defaultTarget())
    }

    @Test
    fun `what the hold commits is the card as edited, to the calendar shown`() = runTest {
        val repository = FakeCalendars()
        repository.chooseTarget(2)
        val edited = draft.with(Edit.Title("Dinner with Sam")).with(Edit.Length(Duration.ofMinutes(90)))
        val target = repository.defaultTarget()!!
        val id = repository.commit(edited, target.calendar.id)
        val written = repository.committed.single()
        assertEquals(id, written.id)
        assertEquals(2L, written.calendarId)
        assertEquals("Dinner with Sam", written.title)
        assertEquals(start.plusMinutes(90), written.end)
    }
}
