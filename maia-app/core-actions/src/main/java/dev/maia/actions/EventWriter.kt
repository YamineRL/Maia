package dev.maia.actions

import dev.maia.nlu.EventDraft
import java.time.Duration
import java.time.ZoneOffset

/**
 * The four values `CalendarContract.Events` actually wants for a time.
 *
 * Separated from the insert on purpose. The all-day arithmetic below is the
 * single most dangerous piece of this module and it is pure `java.time`: no
 * ContentValues, no ContentResolver, no Android at all. Kept that way it can
 * be proved on this box in milliseconds, and `EventWriterTest` does exactly
 * that. The provider-shaped half lives in [ProviderCalendars.valuesFor].
 */
data class EventTimes(
    /** `Events.DTSTART`, epoch millis, UTC. */
    val dtStart: Long,
    /** `Events.DTEND`, epoch millis, UTC, and exclusive. */
    val dtEnd: Long,
    /** `Events.EVENT_TIMEZONE`. An IANA id, or exactly "UTC" for all-day. */
    val eventTimezone: String,
    /** `Events.ALL_DAY`, as a flag rather than the provider's 0/1 int. */
    val allDay: Boolean,
)

/**
 * Turns an [EventDraft] into something the calendar provider will store
 * correctly, which is not the same as something it will accept.
 *
 * Verified against android-36 (javap), AOSP `CalendarProvider2` and
 * developer.android.com on 2026-09-12.
 */
object EventWriter {

    /** A day, in seconds. All-day spans are counted in these, never in millis. */
    private const val SECONDS_PER_DAY = 24L * 60 * 60

    /**
     * The pure half of the write.
     *
     * Timed events are trivial: the draft already carries a zone, the provider
     * wants the instants, and `EVENT_TIMEZONE` is the zone the user meant so a
     * later DST change moves the event the way they would expect.
     *
     * All-day is where the traps are, and all three were confirmed rather than
     * remembered:
     *
     *  - `ALL_DAY = 1` requires `EVENT_TIMEZONE = "UTC"` with both `DTSTART`
     *    and `DTEND` on a UTC midnight boundary.
     *  - `DTEND` is EXCLUSIVE. A single all-day event ends at the midnight
     *    that starts the NEXT day. `DTEND == DTSTART` is a zero-length
     *    instance, not a one-day one.
     *  - `CalendarProvider2.fixAllDayTime` does not reject a non-midnight
     *    all-day time. It silently truncates hours, minutes and seconds in
     *    UTC and logs a warning. So local midnight at a positive UTC offset
     *    (Europe/Zurich, 2026-09-13T00:00+02:00) is 2026-09-12T22:00Z, which
     *    truncates to 2026-09-12 and stores the event on the wrong day,
     *    permanently, with nothing thrown to say so.
     *
     * Which is why the date is taken from the draft's LOCAL date and then
     * rebuilt at UTC midnight, and never by millisecond arithmetic on the
     * draft's instant.
     *
     * `EVENT_END_TIMEZONE` does not matter here and is omitted. `DURATION`
     * would also be accepted, but setting it alongside `DTEND` throws
     * IllegalArgumentException("Cannot have both DTEND and DURATION in an
     * event"), so this module only ever writes `DTEND`.
     */
    fun timesFor(draft: EventDraft): EventTimes {
        val start = draft.start.value
        if (!draft.allDay) {
            return EventTimes(
                dtStart = start.toInstant().toEpochMilli(),
                dtEnd = draft.end.toInstant().toEpochMilli(),
                eventTimezone = start.zone.id,
                allDay = false,
            )
        }

        val firstDay = start.toLocalDate()
        val days = allDaySpan(draft.duration.value)
        return EventTimes(
            dtStart = firstDay.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            dtEnd = firstDay.plusDays(days).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            eventTimezone = "UTC",
            allDay = true,
        )
    }

    /**
     * How many whole days an all-day draft covers.
     *
     * The rule, chosen here because the brief leaves it open: round the
     * draft's duration UP to whole days, with a floor of one. An all-day event
     * has no sub-day length to express, so a duration that spills past a
     * midnight has to claim the day it spills into or the event stops before
     * the user's last day. Rounding down is worse in both directions: it would
     * turn the common case, the one-day span `EventDraft.with(Edit.AllDay)`
     * writes as exactly `Duration.ofDays(1)`, into zero days, and a zero
     * length all-day instance is a row the user cannot see.
     *
     * `Edit.AllDay` therefore lands on 1 exactly, and "the conference is
     * Monday to Wednesday" (three days) lands on 3.
     */
    private fun allDaySpan(duration: Duration): Long {
        if (duration <= Duration.ZERO) return 1
        val seconds = duration.seconds + if (duration.nano > 0) 1 else 0
        return ((seconds + SECONDS_PER_DAY - 1) / SECONDS_PER_DAY).coerceAtLeast(1)
    }
}
