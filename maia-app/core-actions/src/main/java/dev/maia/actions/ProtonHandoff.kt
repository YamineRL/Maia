package dev.maia.actions

import dev.maia.nlu.EventDraft

/**
 * What Android's `ACTION_INSERT` "add to calendar" intent wants, computed
 * with no Android class in sight, for the same reason [EventTimes] exists:
 * the trap is in the arithmetic, not the plumbing, and the arithmetic can be
 * proved on this box.
 *
 * M4-R9 2d: Proton Calendar never touches `CalendarContract` (question 1 of
 * that research), so this is a different intent entirely from
 * [ProviderCalendars.valuesFor]: a screen handoff to Proton's own compose
 * UI, not a provider insert, built from the same [EventWriter.timesFor] so
 * the two paths never disagree about when the event actually is.
 */
data class ProtonHandoffExtras(
    val title: String,
    /** `EXTRA_EVENT_BEGIN_TIME`, epoch millis, UTC. */
    val beginMillis: Long,
    /** `EXTRA_EVENT_END_TIME`, epoch millis, UTC, exclusive for all-day. */
    val endMillis: Long,
    /** `EXTRA_EVENT_ALL_DAY`. */
    val allDay: Boolean,
    /** `EXTRA_EVENT_LOCATION`. Null when the draft carries none. */
    val location: String?,
)

/**
 * The pure half of the M4-R9 2d handoff.
 *
 * [PACKAGE] is what `setPackage` must be pinned to: on the phone this was
 * verified against (M4-R9 evidence, 2026-09-13), AOSP Calendar and KashCal
 * both register for the same `ACTION_INSERT` filter, so a bare intent would
 * hit Android's disambiguation sheet instead of Proton every time.
 */
object ProtonHandoff {
    const val PACKAGE = "me.proton.android.calendar"

    fun extrasFor(draft: EventDraft): ProtonHandoffExtras {
        val times = EventWriter.timesFor(draft)
        return ProtonHandoffExtras(
            title = draft.title.value,
            beginMillis = times.dtStart,
            endMillis = times.dtEnd,
            allDay = times.allDay,
            location = draft.location?.value,
        )
    }
}
