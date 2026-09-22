package dev.maia.app.card

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import dev.maia.actions.ProtonHandoff
import dev.maia.nlu.EventDraft

/**
 * M4-R9 2d, acted on: the plain intent handoff the user chose over ICSx5 and
 * Sync for Proton. Maia builds the event, fires `ACTION_INSERT` at Proton's
 * own compose screen, and the user taps save inside Proton's UI. Nothing here
 * reaches `CalendarContract` and nothing here learns whether the user
 * actually saved: unlike [dev.maia.actions.CalendarRepository.commit] there
 * is no id, no confirmation, nothing to undo. The card treats this as a
 * fire-and-forget side door, the same way [openDavx5] in NoCalendars.kt is
 * one.
 *
 * No package-visibility check first, on purpose: `<queries>` probes are on
 * PRD section 14's never-requested list. Fire, and catch, exactly like
 * [openDavx5].
 */
internal fun openInProtonCalendar(context: Context, draft: EventDraft) {
    val extras = ProtonHandoff.extrasFor(draft)
    val intent = Intent(Intent.ACTION_INSERT)
        .setDataAndType(CalendarContract.Events.CONTENT_URI, "vnd.android.cursor.item/event")
        .setPackage(ProtonHandoff.PACKAGE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, extras.beginMillis)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, extras.endMillis)
        .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, extras.allDay)
        .putExtra(Intent.EXTRA_TITLE, extras.title)
    extras.location?.let { intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }

    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // Not installed. The one thing worth doing instead is pointing at
        // where to get it, same shape as NoCalendars.kt's DAVx5 fallback.
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(PROTON_CALENDAR_LISTING))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

private const val PROTON_CALENDAR_LISTING = "https://proton.me/calendar/download"
