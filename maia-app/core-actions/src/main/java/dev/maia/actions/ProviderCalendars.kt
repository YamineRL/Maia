package dev.maia.actions

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.provider.CalendarContract
import androidx.core.content.edit
import dev.maia.nlu.EventDraft
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Where the user's last calendar choice is kept.
 *
 * An interface with two lines in it rather than a `SharedPreferences` field,
 * because the alternative is a hidden global that no test can reach. The fake
 * repository keeps its choice in a var; this one has to persist, since a
 * ContentProvider has no memory between calls and the chooser is a thing the
 * user expects to do once.
 */
interface TargetStore {
    fun chosenCalendarId(): Long?
    fun setChosenCalendarId(id: Long)

    companion object {
        /** The obvious implementation, kept out of [ProviderCalendars] itself. */
        fun sharedPreferences(context: Context): TargetStore = object : TargetStore {
            private val prefs =
                context.applicationContext.getSharedPreferences("maia-calendar", Context.MODE_PRIVATE)

            override fun chosenCalendarId(): Long? =
                prefs.getLong(KEY, -1L).takeIf { it >= 0 }

            override fun setChosenCalendarId(id: Long) =
                prefs.edit { putLong(KEY, id) }
        }

        private const val KEY = "chosen_calendar_id"
    }
}

/**
 * [CalendarRepository] over a real `ContentResolver`.
 *
 * The one class in this module that knows column names, which is the whole
 * reason [MaiaCalendar] exists. Nothing here can be tested on this box: it
 * needs a provider, so it needs a phone. What could be pulled out and proved
 * has been, in [EventWriter].
 *
 * Everything runs on [io]. A provider call is disk plus a binder round trip
 * and has no business on the main thread.
 */
class ProviderCalendars(
    private val resolver: ContentResolver,
    private val store: TargetStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : CalendarRepository {

    constructor(context: Context) : this(
        context.contentResolver,
        TargetStore.sharedPreferences(context),
    )

    // ------------------------------------------------------------ calendars

    override suspend fun calendars(): List<MaiaCalendar> = onIo {
        query(
            uri = CalendarContract.Calendars.CONTENT_URI,
            projection = CALENDAR_PROJECTION,
            // Sorted here so the chooser and the "first writable" fallback in
            // defaultTarget see the same order every time. Provider order is
            // not specified and differs between AOSP and vendor builds.
            sortOrder = "${CalendarContract.Calendars.ACCOUNT_NAME} ASC, " +
                "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC",
        ) { it.toCalendar() }
            // A calendar the user has hidden in their calendar app is not a
            // sensible write target: the event would land somewhere they have
            // already said they do not want to look, which is the same failure
            // as the read-only case below, an event that exists and is never
            // seen. So hidden calendars do not reach the chooser at all.
            //
            // Null counts as visible, deliberately. VISIBLE is not guaranteed
            // to be populated by every provider, and a provider that leaves it
            // null would otherwise empty the list and put the user on the
            // no-calendars screen while holding a perfectly good calendar.
            // Erring towards showing one too many beats erring towards zero.
            .filter { it.visible }
            .map { it.calendar }
    }

    /** A calendar plus the one column [MaiaCalendar] has no reason to carry. */
    private data class Listed(val calendar: MaiaCalendar, val visible: Boolean)

    private fun Cursor.toCalendar(): Listed {
        val id = getLong(COL_ID)
        val account = getStringOrNull(COL_ACCOUNT_NAME).orEmpty()
        val name = getStringOrNull(COL_DISPLAY_NAME)?.takeIf { it.isNotBlank() } ?: account
        val access = if (isNull(COL_ACCESS_LEVEL)) 0 else getInt(COL_ACCESS_LEVEL)

        // CAL_ACCESS_CONTRIBUTOR is 500, confirmed against android-36 on
        // 2026-09-12 (NONE 0, FREEBUSY 100, READ 200, RESPOND 300, OVERRIDE
        // 400, CONTRIBUTOR 500, EDITOR 600, OWNER 700, ROOT 800).
        //
        // Read this before trusting it: the 500 floor is a HEURISTIC, not a
        // guarantee. CalendarProvider2 performs NO access-level check on
        // insert. verifyTransactionAllowed checks only URI and column
        // restrictions, and validateEventData checks only calendar_id,
        // eventTimezone, DTSTART and exactly one of DTEND/DURATION. An insert
        // into a read-only calendar SUCCEEDS, returns a real row URI, displays
        // in every client, and is then silently reverted by the sync adapter
        // some minutes later. So nothing downstream may detect a bad target by
        // catching an exception from commit(); there will not be one. This
        // filter is the only thing standing between the user and an event that
        // quietly disappears overnight.
        val writable = access >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR

        // IS_PRIMARY is documented but is null on some providers, and on a
        // GrapheneOS device with only DAVx5 collections it usually is. The
        // usual fallback, and the one the platform's own Calendar app uses:
        // treat ACCOUNT_NAME == OWNER_ACCOUNT as primary.
        val primary = if (isNull(COL_IS_PRIMARY)) {
            account.isNotEmpty() && account == getStringOrNull(COL_OWNER_ACCOUNT)
        } else {
            getInt(COL_IS_PRIMARY) == 1
        }

        return Listed(
            MaiaCalendar(
                id = id,
                displayName = name,
                accountName = account,
                colour = if (isNull(COL_COLOR)) null else getInt(COL_COLOR),
                writable = writable,
                isDefault = primary,
            ),
            visible = isNull(COL_VISIBLE) || getInt(COL_VISIBLE) == 1,
        )
    }

    // --------------------------------------------------------------- target

    /**
     * Section 5.2's rule in full, which is more than the fake implements.
     *
     * In order: the calendar the user picked, if it is still there and still
     * writable (a DAVx5 collection can be made read-only or removed between
     * one session and the next); else the one the provider flags primary; else
     * the only writable one when there is exactly one, which is the common
     * GrapheneOS case; else the first writable sorted by account name.
     *
     * Null means nothing writable, which is the no-calendars screen from
     * section 5.4 and not a fault.
     */
    override suspend fun defaultTarget(): CalendarTarget? {
        val writable = calendars().filter { it.writable }
        if (writable.isEmpty()) return null

        // SharedPreferences touches disk the first time it is opened, so the
        // read goes onto the same dispatcher as the provider calls rather than
        // whichever thread the card happened to ask from.
        onIo { store.chosenCalendarId() }?.let { id ->
            writable.firstOrNull { it.id == id }?.let { return CalendarTarget(it, chosen = true) }
        }

        val guess = writable.firstOrNull { it.isDefault }
            ?: writable.singleOrNull()
            // calendars() already sorts by account name, so "first" is the
            // sorted first and not whatever the provider happened to return.
            ?: writable.first()
        return CalendarTarget(guess, chosen = false)
    }

    override suspend fun chooseTarget(calendarId: Long) {
        val target = calendars().firstOrNull { it.id == calendarId }
        require(target != null && target.writable) { "no writable calendar with id $calendarId" }
        onIo { store.setChosenCalendarId(calendarId) }
    }

    // --------------------------------------------------------------- commit

    override suspend fun commit(draft: EventDraft, calendarId: Long): Long {
        // Checked up front because the provider will not check it for us: see
        // the note on the access-level filter above. An insert into a
        // read-only calendar succeeds and is reverted later, so the refusal
        // has to happen here or it never happens at all.
        val target = calendars().firstOrNull { it.id == calendarId }
        require(target != null && target.writable) { "no writable calendar with id $calendarId" }

        return onIo {
            val uri = denyingSecurityExceptions("insert") {
                resolver.insert(CalendarContract.Events.CONTENT_URI, valuesFor(draft, calendarId))
            } ?: throw CalendarWriteDenied("the calendar provider refused the insert")
            ContentUris.parseId(uri)
        }
    }

    // --------------------------------------------------------------- delete

    override suspend fun delete(eventId: Long): Boolean = onIo {
        // By id and nothing else. The provider answers with the rows it
        // touched, so zero is the event already being gone. On a synced
        // calendar this marks the row deleted rather than removing it, and
        // the sync adapter finishes the job; that cannot be observed off a
        // phone and is criterion 22 of the M2 brief.
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        denyingSecurityExceptions("delete") { resolver.delete(uri, null, null) } > 0
    }

    // ------------------------------------------------------------- read back

    /**
     * Instances, not Events, and the difference matters.
     *
     * `Events` holds the rule for a recurring event, once, with the DTSTART of
     * its first occurrence. Querying it for a range would miss every weekly
     * standup after the first week and report the user free when they are not.
     * `Instances` is the expanded view: the provider materialises occurrences
     * inside the range asked for, so a recurrence yields one row per
     * occurrence. The range goes in the URI path, not in the selection.
     */
    override suspend fun eventsIn(range: ClosedRange<ZonedDateTime>): List<CalendarEvent> {
        val zone = range.start.zone
        val from = range.start.toInstant().toEpochMilli()
        val to = range.endInclusive.toInstant().toEpochMilli()

        return onIo {
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
                .appendPath(from.toString())
                .appendPath(to.toString())
                .build()

            query(
                uri = uri,
                projection = INSTANCE_PROJECTION,
                sortOrder = "${CalendarContract.Instances.BEGIN} ASC",
            ) { it.toEvent(zone) }
                // Instances is inclusive at both ends, so an event that merely
                // touches the window comes back. CalendarEvent.overlaps is
                // half-open, and the fake and its tests already settled that
                // back-to-back events are not a clash. Same answer here.
                .filter { it.overlaps(range) }
        }
    }

    private fun Cursor.toEvent(zone: ZoneId): CalendarEvent {
        val allDay = !isNull(COL_I_ALL_DAY) && getInt(COL_I_ALL_DAY) == 1
        val begin = getLong(COL_I_BEGIN)
        val end = getLong(COL_I_END)
        return CalendarEvent(
            id = getLong(COL_I_EVENT_ID),
            title = getStringOrNull(COL_I_TITLE).orEmpty(),
            // An all-day instance comes back as UTC midnight, per the
            // convention EventWriter writes. Handed back unconverted it would
            // read as 02:00 local in Zurich and as the previous evening in New
            // York. The calendar date is the only meaningful part, so it is
            // re-anchored to local midnight in the caller's zone.
            start = if (allDay) utcDateAtLocalMidnight(begin, zone) else instantIn(begin, zone),
            end = if (allDay) utcDateAtLocalMidnight(end, zone) else instantIn(end, zone),
            allDay = allDay,
            calendarId = getLong(COL_I_CALENDAR_ID),
        )
    }

    private fun instantIn(millis: Long, zone: ZoneId): ZonedDateTime =
        Instant.ofEpochMilli(millis).atZone(zone)

    // LocalDate.ofInstant is Java 9 and only reached Android at API 34, so it
    // is spelled out the long way to keep minSdk 26 honest.
    private fun utcDateAtLocalMidnight(millis: Long, zone: ZoneId): ZonedDateTime =
        Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(zone)

    // ------------------------------------------------------------ the insert

    /**
     * The provider-shaped half of the write, kept next to the columns it names
     * and away from [EventWriter], which stays pure so it can be tested here.
     *
     * `EVENT_LOCATION` is written only when the draft carries one. The column
     * is nullable and the provider does not mind it missing, and writing an
     * empty string instead would put a location on the event that renders as
     * a blank line in every other calendar client.
     */
    internal fun valuesFor(draft: EventDraft, calendarId: Long): ContentValues {
        val times = EventWriter.timesFor(draft)
        return ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, draft.title.value)
            put(CalendarContract.Events.DTSTART, times.dtStart)
            put(CalendarContract.Events.DTEND, times.dtEnd)
            put(CalendarContract.Events.EVENT_TIMEZONE, times.eventTimezone)
            put(CalendarContract.Events.ALL_DAY, if (times.allDay) 1 else 0)
            draft.location?.value?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            // DURATION is deliberately absent. Setting it alongside DTEND
            // throws IllegalArgumentException("Cannot have both DTEND and
            // DURATION in an event") inside the provider.
        }
    }

    // ------------------------------------------------------------- plumbing

    private suspend fun <T> onIo(block: () -> T): T = withContext(io) { block() }

    /**
     * The permission going away mid-flight is the one fault the UI has to tell
     * apart from a real one, so it gets its own type on every provider call
     * and not just on the insert. A user who revokes calendar access from the
     * notification shade while the card is open lands here.
     */
    private inline fun <T> denyingSecurityExceptions(what: String, block: () -> T): T =
        try {
            block()
        } catch (e: SecurityException) {
            throw CalendarWriteDenied("calendar permission denied during $what: ${e.message}")
        }

    private fun <T> query(
        uri: android.net.Uri,
        projection: Array<String>,
        sortOrder: String?,
        map: (Cursor) -> T,
    ): List<T> = denyingSecurityExceptions("query") {
        resolver.query(uri, projection, null, null, sortOrder).use { cursor ->
            if (cursor == null) return@denyingSecurityExceptions emptyList()
            val out = ArrayList<T>(cursor.count)
            while (cursor.moveToNext()) out += map(cursor)
            out
        }
    }

    private fun Cursor.getStringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private companion object {
        // Index constants rather than getColumnIndexOrThrow at every row: the
        // projection is fixed, so the indices are too, and a cursor over a few
        // hundred instances is not the place to do string lookups per column.
        val CALENDAR_PROJECTION = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.OWNER_ACCOUNT,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.VISIBLE,
        )
        const val COL_ID = 0
        const val COL_DISPLAY_NAME = 1
        const val COL_ACCOUNT_NAME = 2
        const val COL_OWNER_ACCOUNT = 3
        const val COL_COLOR = 4
        const val COL_ACCESS_LEVEL = 5
        const val COL_IS_PRIMARY = 6
        const val COL_VISIBLE = 7

        val INSTANCE_PROJECTION = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_ID,
        )
        const val COL_I_EVENT_ID = 0
        const val COL_I_TITLE = 1
        const val COL_I_BEGIN = 2
        const val COL_I_END = 3
        const val COL_I_ALL_DAY = 4
        const val COL_I_CALENDAR_ID = 5
    }
}
