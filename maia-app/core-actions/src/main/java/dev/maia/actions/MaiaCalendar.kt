package dev.maia.actions

/**
 * One calendar the user could write to.
 *
 * Deliberately not a `CalendarContract` cursor row. The chooser, the card and
 * every test in this module deal in this type, which is why none of them need
 * an Android runtime to run. [ProviderCalendars] is the only thing that knows
 * the column names.
 */
data class MaiaCalendar(
    val id: Long,
    /** What the user sees. `CALENDAR_DISPLAY_NAME`, falling back to the account. */
    val displayName: String,
    /** The account the calendar belongs to, shown when two calendars share a name. */
    val accountName: String,
    /** ARGB, as the provider stores it. Null when the calendar declares no colour. */
    val colour: Int? = null,
    /**
     * Whether Maia may write to it.
     *
     * A calendar the provider exposes read only is still worth listing, in the
     * chooser's disabled state, because a user looking for the work calendar
     * that DAVx5 syncs one way needs to be told it cannot be written to rather
     * than left wondering where it went.
     */
    val writable: Boolean = true,
    /** True for the one the user picked, or the one Maia guessed. */
    val isDefault: Boolean = false,
)
