package dev.maia.app.settings

import android.content.Context

/**
 * Where a heard event goes: the calendar this phone syncs, or Proton
 * Calendar's own new-event screen.
 *
 * Proton never syncs through `CalendarContract` (M4-R9), so on a phone whose
 * only calendar is Proton the answer is the same every time, and asking it
 * again on the no-calendar screen after every sentence is the app forgetting
 * what it was told. Choosing Proton there sets this; Settings sets it back.
 */
enum class EventsTo { Phone, Proton }

/** The one preference file for choices the user makes in Settings. */
class MaiaPrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var eventsTo: EventsTo
        get() = if (prefs.getString(EVENTS_TO, null) == PROTON) EventsTo.Proton else EventsTo.Phone
        set(value) {
            prefs.edit().putString(EVENTS_TO, if (value == EventsTo.Proton) PROTON else PHONE).apply()
        }

    /**
     * Where the weather is looked up when a question names no place. Typed
     * once in Settings and never leaves the phone except inside that one
     * search; blank is no city, and the search engine guesses.
     */
    var homeCity: String
        get() = prefs.getString(HOME_CITY, null).orEmpty()
        set(value) {
            prefs.edit().putString(HOME_CITY, value.trim()).apply()
        }

    private companion object {
        const val FILE = "maia_settings"
        const val EVENTS_TO = "events_to"
        const val HOME_CITY = "home_city"
        const val PROTON = "proton"
        const val PHONE = "phone"
    }
}
