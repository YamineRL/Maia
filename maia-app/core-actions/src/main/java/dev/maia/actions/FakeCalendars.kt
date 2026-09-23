package dev.maia.actions

import dev.maia.nlu.EventDraft
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicLong

/**
 * A [CalendarRepository] with no provider behind it.
 *
 * In the main source set on purpose. Compose previews cannot see test source,
 * and a card that can only be looked at by attaching a phone is a card nobody
 * looks at until it is too late to change. PRD principle 5 puts beauty in the
 * acceptance criteria, and that needs the screens to be cheap to open.
 *
 * It is also the only thing criterion 7 can be proved against.
 */
class FakeCalendars(
    initial: List<MaiaCalendar> = defaultSet,
    private var chosenId: Long? = null,
    events: List<CalendarEvent> = emptyList(),
) : CalendarRepository {

    private val calendars = initial.toMutableList()
    private val written = mutableListOf<CalendarEvent>().apply { addAll(events) }
    private val nextId = AtomicLong(1000)

    /** Flip to have [commit] throw, so the fault path can be exercised. */
    var denyWrites: Boolean = false

    /** Everything [commit] was given and [delete] has not taken back, in order. */
    val committed: List<CalendarEvent> get() = written.toList()

    override suspend fun calendars(): List<MaiaCalendar> = calendars.toList()

    override suspend fun defaultTarget(): CalendarTarget? {
        val writable = calendars.filter { it.writable }
        if (writable.isEmpty()) return null
        chosenId?.let { id ->
            writable.firstOrNull { it.id == id }?.let { return CalendarTarget(it, chosen = true) }
        }
        // The guess, and the rule behind it: the one the provider already
        // marks default, else the first writable one. Either way chosen is
        // false and the card says so.
        val guess = writable.firstOrNull { it.isDefault } ?: writable.first()
        return CalendarTarget(guess, chosen = false)
    }

    override suspend fun chooseTarget(calendarId: Long) {
        require(calendars.any { it.id == calendarId && it.writable }) {
            "no writable calendar with id $calendarId"
        }
        chosenId = calendarId
    }

    override suspend fun commit(draft: EventDraft, calendarId: Long): Long {
        if (denyWrites) throw CalendarWriteDenied("write permission revoked")
        require(calendars.any { it.id == calendarId && it.writable }) {
            "no writable calendar with id $calendarId"
        }
        val id = nextId.incrementAndGet()
        written += CalendarEvent(
            id = id,
            title = draft.title.value,
            start = draft.start.value,
            end = draft.end,
            allDay = draft.allDay,
            calendarId = calendarId,
        )
        return id
    }

    override suspend fun delete(eventId: Long): Boolean {
        if (denyWrites) throw CalendarWriteDenied("write permission revoked")
        return written.removeIf { it.id == eventId }
    }

    override suspend fun eventsIn(range: ClosedRange<ZonedDateTime>): List<CalendarEvent> =
        written.filter { it.overlaps(range) }.sortedBy { it.start }

    companion object {
        /** Two calendars and a read-only third, which is the interesting case. */
        val defaultSet: List<MaiaCalendar> = listOf(
            MaiaCalendar(1, "Personal", "nuh@example.org", 0xFF5B4BE1.toInt(), isDefault = true),
            MaiaCalendar(2, "Work", "nuh@example.org", 0xFFD83C8E.toInt()),
            MaiaCalendar(3, "Holidays", "subscribed", 0xFFE8A33D.toInt(), writable = false),
        )

        /** No calendars at all, which is a supported state and a whole screen. */
        val empty: List<MaiaCalendar> = emptyList()
    }
}
