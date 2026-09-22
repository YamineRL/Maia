package dev.maia.actions

import dev.maia.nlu.EventDraft
import java.time.ZonedDateTime

/**
 * Everything Maia does to a calendar.
 *
 * An interface rather than a class because criterion 7 of the M1 brief says
 * the card and the chooser must be drivable with no Android classes involved,
 * and because the alternative is a UI that can only be exercised on a phone
 * the author is holding. [FakeCalendars] is the other implementation and it
 * lives in the main source set, not the test one, so previews and the debug
 * build can use it too.
 *
 * Suspending because the real one talks to a ContentProvider, which is disk
 * and IPC and must never touch the main thread. The fake honours the same
 * signature and so cannot let a caller forget that.
 */
interface CalendarRepository {

    /** Every calendar the provider knows about, writable or not. */
    suspend fun calendars(): List<MaiaCalendar>

    /**
     * Where a commit should go, or null when there is nowhere to put it.
     *
     * Null is the no-calendars state, which is a real screen and not an error:
     * a GrapheneOS device with no account configured has no calendars at all
     * until DAVx5 or a local provider makes one.
     */
    suspend fun defaultTarget(): CalendarTarget?

    /** Remember the user's choice, so the next commit does not guess. */
    suspend fun chooseTarget(calendarId: Long)

    /**
     * Write [draft] to [calendarId] and return the new event's id.
     *
     * Throws nothing the caller is expected to catch in the normal case. A
     * permission the user revoked mid-flight is the exception, and it arrives
     * as [CalendarWriteDenied] so the UI can tell it apart from a real fault.
     */
    suspend fun commit(draft: EventDraft, calendarId: Long): Long

    /**
     * Delete an event Maia wrote, by the id [commit] returned, and report
     * whether there was anything to delete.
     *
     * Only the undo on the confirmation screen calls this, inside its 8 s
     * window, with the id of the event just written. Nothing in Maia deletes
     * by title or by time. False means the event was already gone, removed by
     * the user or a sync, and the screen says so rather than claiming an
     * undo. On a synced calendar the provider marks the row deleted and the
     * sync adapter carries that to the server, so undo is not instant there.
     */
    suspend fun delete(eventId: Long): Boolean

    /** Events overlapping [range], for the agenda read-back and for "am I free". */
    suspend fun eventsIn(range: ClosedRange<ZonedDateTime>): List<CalendarEvent>
}

/** An event already on the calendar. Only the fields the read-back reads. */
data class CalendarEvent(
    val id: Long,
    val title: String,
    val start: ZonedDateTime,
    val end: ZonedDateTime,
    val allDay: Boolean = false,
    val calendarId: Long = 0,
) {
    fun overlaps(range: ClosedRange<ZonedDateTime>): Boolean =
        start < range.endInclusive && end > range.start
}

/** The permission went away between listing and committing. */
class CalendarWriteDenied(message: String) : Exception(message)
