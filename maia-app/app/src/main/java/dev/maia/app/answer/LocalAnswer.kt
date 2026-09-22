package dev.maia.app.answer

import dev.maia.actions.CalendarEvent
import dev.maia.nlu.Intent
import dev.maia.nlu.calc.Calc
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * What a local read produced: the whole [text] for the answer body and an
 * optional shorter [spokenText] for the voice.
 *
 * Null spokenText means the text is spoken whole. The split exists for the
 * list answers, where "you have three events on Tuesday" is the thing to say
 * and the titles are the thing to read: section 3.1's rule that the complete
 * answer is shown even when the spoken form is shortened.
 */
data class LocalResult(
    val text: String,
    val spokenText: String? = null,
)

/**
 * The local-answer builders: pure sentences out of pure inputs.
 *
 * Every Android-shaped question (which events exist, what the battery holds,
 * what time it is) is answered by the driver before these are called, so this
 * file never asks anything: events arrive already read, the battery arrives
 * already read, and the clock arrives as a [ZonedDateTime]. That is also what
 * makes the empty cases honest here. An empty list is a real answer ("you
 * have no events"), not an error, and a null battery level is a null result,
 * because claiming a number nobody read would be worse than saying nothing.
 *
 * The strings are the same fixed-form English the rest of the app's decided
 * copy uses, kept here so the copy layer can resource-ify them in one sweep.
 * Times and dates follow the card's conventions: 24-hour `HH:mm`, day names
 * out of `java.time` rather than hand-rolled.
 */

/**
 * "You have 3 events on Tuesday 23 September: standup at 09:30, lunch at 12:00."
 *
 * The spoken form stops at the count. Reciting titles and times of a whole
 * day is the failure section 3.5's two-sentence bound exists to prevent.
 */
fun agenda(
    events: List<CalendarEvent>,
    range: ClosedRange<ZonedDateTime>,
    locale: Locale = Locale.ENGLISH,
): LocalResult {
    val window = windowText(range, locale)
    if (events.isEmpty()) return LocalResult("You have no events $window")
    val ordered = events.sortedBy { it.start }
    val list = ordered.joinToString(", ") { eventText(it, locale) }
    val count = "${ordered.size} ${if (ordered.size == 1) "event" else "events"}"
    return LocalResult(
        text = "You have $count $window: $list",
        spokenText = "You have $count $window",
    )
}

/**
 * The same window, the other question: "you are free" or "you are busy".
 *
 * Busy means at least one event overlaps the asked range, which is
 * [CalendarEvent.overlaps] and not the list the caller happened to pass:
 * a caller that hands an unfiltered list still gets a right answer.
 */
fun availability(
    events: List<CalendarEvent>,
    range: ClosedRange<ZonedDateTime>,
    locale: Locale = Locale.ENGLISH,
): LocalResult {
    val window = windowText(range, locale)
    val busy = events.filter { it.overlaps(range) }.sortedBy { it.start }
    if (busy.isEmpty()) return LocalResult("You are free $window")
    val list = busy.joinToString(", ") { eventText(it, locale) }
    return LocalResult(
        text = "You are busy $window: $list",
        spokenText = "You are busy $window",
    )
}

/**
 * The facts the phone knows without reading anything: "It's 14:32",
 * "It's Tuesday 23 September", "Battery is at 68%".
 *
 * [batteryPct] is read by the driver because a locked or dying battery
 * manager is not this file's problem. Null means nobody could read it, and
 * the result is null rather than a sentence with a hole in it.
 */
fun deviceFact(
    kind: Intent.DeviceFact.Kind,
    batteryPct: Int?,
    now: ZonedDateTime,
    locale: Locale = Locale.ENGLISH,
): LocalResult? = when (kind) {
    Intent.DeviceFact.Kind.TIME -> LocalResult("It's ${TIME.format(now)}")
    Intent.DeviceFact.Kind.DATE -> LocalResult("It's ${DATE.withLocale(locale).format(now)}")
    Intent.DeviceFact.Kind.BATTERY ->
        batteryPct?.let { LocalResult("Battery is at $it%") }
}

/**
 * "12*8 = 96", or null when [Calc] could not read the expression.
 *
 * The expression is echoed because it is what was actually computed, not what
 * was asked: "twelve times eight" heard as "twenty times eight" shows
 * `20*8 = 160`, which is a mis-hearing the user can see rather than a wrong
 * answer they cannot explain. The result goes through [Calc.format], which
 * is the product's one way of writing a number out loud.
 */
fun calculate(expression: String): LocalResult? =
    when (val result = Calc.eval(expression)) {
        is Calc.Ok -> LocalResult("$expression = ${Calc.format(result.value)}")
        Calc.Err -> null
    }

/**
 * One event inside a list: "standup at 09:30", or "conference, all day" when
 * it has no time. An all-day event shown with `at 00:00` would be Maia
 * inventing a precision the calendar does not carry.
 */
private fun eventText(event: CalendarEvent, locale: Locale): String =
    if (event.allDay) "${event.title}, all day"
    else "${event.title} at ${TIME.format(event.start)}"

/**
 * The asked window in words: "on Tuesday 23 September" for a day-sized
 * range, "on Tuesday 23 September, 12:00 to 18:00" for part of one day, and
 * "between ... and ..." for a range that crosses midnight.
 *
 * Twenty hours is the day-sized threshold rather than 24: the parser's day
 * ranges end at 23:59 of the same day, which is a second short of 24 hours
 * and would read as "between" on a boundary the user thinks of as one day.
 */
private fun windowText(range: ClosedRange<ZonedDateTime>, locale: Locale): String {
    val sameDay = range.start.toLocalDate() == range.endInclusive.toLocalDate()
    return if (sameDay) {
        val day = DATE.withLocale(locale).format(range.start)
        if (ChronoUnit.HOURS.between(range.start, range.endInclusive) >= DAYISH_HOURS) {
            "on $day"
        } else {
            "on $day, ${TIME.format(range.start)} to ${TIME.format(range.endInclusive)}"
        }
    } else {
        "between ${DAY_TIME.withLocale(locale).format(range.start)} and " +
            DAY_TIME.withLocale(locale).format(range.endInclusive)
    }
}

private const val DAYISH_HOURS = 20L

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)
private val DAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM HH:mm", Locale.ENGLISH)
