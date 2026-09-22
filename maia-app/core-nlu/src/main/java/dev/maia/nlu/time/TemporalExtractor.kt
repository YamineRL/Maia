package dev.maia.nlu.time

import dev.maia.nlu.text.Token
import dev.maia.nlu.text.Tokens
import java.time.DayOfWeek
import java.time.Month
import java.time.temporal.ChronoUnit

/**
 * Finds the temporal phrases in a normalised sentence and says which tokens
 * they used up.
 *
 * The second half of that sentence is the part that matters. Whatever is left
 * after the temporal phrases are removed is what the event is called, so an
 * extractor that finds the right date but lies about which words it read
 * produces a card titled "dinner with sam thursday at".
 *
 * Nothing here resolves anything. Output is [DateSpec] and friends, which know
 * nothing about today. [TemporalResolver] does that, against an injected clock.
 */
object TemporalExtractor {

    private val weekdays: Map<String, DayOfWeek> = mapOf(
        "monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY, "thursday" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY,
    )

    private val months: Map<String, Month> = Month.entries.associateBy {
        it.name.lowercase()
    } + mapOf("sept" to Month.SEPTEMBER)

    private val dateUnits: Map<String, ChronoUnit> = mapOf(
        "day" to ChronoUnit.DAYS, "days" to ChronoUnit.DAYS,
        "week" to ChronoUnit.WEEKS, "weeks" to ChronoUnit.WEEKS,
        "month" to ChronoUnit.MONTHS, "months" to ChronoUnit.MONTHS,
        "year" to ChronoUnit.YEARS, "years" to ChronoUnit.YEARS,
    )

    private val clockUnits: Map<String, ChronoUnit> = mapOf(
        "minute" to ChronoUnit.MINUTES, "minutes" to ChronoUnit.MINUTES,
        "min" to ChronoUnit.MINUTES, "mins" to ChronoUnit.MINUTES,
        "hour" to ChronoUnit.HOURS, "hours" to ChronoUnit.HOURS,
    )

    private val parts: Map<String, Part> = mapOf(
        "morning" to Part.Morning,
        "afternoon" to Part.Afternoon,
        "evening" to Part.Evening,
    )

    private data class Hit<out T : kotlin.Any>(val spec: T, val from: Int, val until: Int)

    fun extract(tokens: Tokens): Temporal {
        val t = tokens.list
        var date: Hit<DateSpec>? = null
        var time: Hit<TimeSpec>? = null
        var duration: Hit<DurationSpec>? = null
        val consumed = sortedSetOf<Int>()

        var i = 0
        while (i < t.size) {
            // Duration is tried first at every position. "in two weeks" is a
            // date and "for two hours" is a duration, and both are a number
            // followed by a unit, so whichever runs second never gets the
            // chance to be wrong about the other.
            val hit = (if (duration == null) tryDuration(t, i) else null)
                ?: (if (date == null) tryDate(t, i) else null)
                ?: (if (time == null) tryTime(t, i) else null)

            if (hit == null) {
                i += 1
                continue
            }
            when (val spec = hit.spec) {
                is DurationSpec -> duration = Hit(spec, hit.from, hit.until)
                is DateSpec -> date = Hit(spec, hit.from, hit.until)
                is TimeSpec -> time = Hit(spec, hit.from, hit.until)
            }
            (hit.from until hit.until).forEach(consumed::add)
            i = hit.until
        }

        // "tonight" and "this evening" carry both halves. The date half is
        // claimed above; the time half only lands if nothing else claimed it.
        if (time == null) {
            val idx = t.indexOfFirst { it.text == "tonight" }
            if (idx >= 0) time = Hit(TimeSpec.PartOfDay(Part.Evening), idx, idx + 1)
        }

        return Temporal(
            date = date?.spec,
            dateSpan = date?.let { words(t, it.from, it.until) },
            time = time?.spec,
            timeSpan = time?.let { words(t, it.from, it.until) },
            duration = duration?.spec,
            durationSpan = duration?.let { words(t, it.from, it.until) },
            consumed = consumed,
        )
    }

    private fun words(t: List<Token>, from: Int, until: Int): IntRange =
        t[from].span.first..t[until - 1].span.last

    // ------------------------------------------------------------------ date

    private fun tryDate(t: List<Token>, i: Int): Hit<DateSpec>? {
        val w = t[i].text
        fun at(n: Int) = t.getOrNull(i + n)?.text

        when (w) {
            "today" -> return Hit(DateSpec.Today, i, i + 1)
            "tonight" -> return Hit(DateSpec.Today, i, i + 1)
            "tomorrow" -> return Hit(DateSpec.Tomorrow, i, i + 1)
        }

        // "a week from friday"
        if ((w == "a" || w == "one") && at(1) in setOf("week", "weeks") && at(2) == "from") {
            val base = tryDate(t, i + 3)
            if (base != null) {
                return Hit(
                    DateSpec.Compound(base.spec, DateSpec.Offset(1, ChronoUnit.WEEKS)),
                    i,
                    base.until,
                )
            }
        }

        // "in two weeks", and also "in ten minutes". Both are when it starts,
        // so both are a date offset; only "for ten minutes" is a length. The
        // clock units have to be listed here as well as in tryDuration, which
        // is why tryDuration refuses a bare number that "in" introduced.
        if (w == "in") {
            val n = t.getOrNull(i + 1)?.takeIf { it.kind == Token.Kind.Cardinal }?.value
            val unit = at(2)?.let { dateUnits[it] ?: clockUnits[it] }
            if (n != null && unit != null) return Hit(DateSpec.Offset(n, unit), i, i + 3)
        }

        // "this thursday", "next weekend"
        val which = when (w) {
            "this" -> Which.This
            "next" -> Which.Next
            else -> null
        }
        if (which != null) {
            val day = at(1)?.let { weekdays[it] }
            if (day != null) return Hit(DateSpec.Weekday(day, which), i, i + 2)
            if (at(1) == "weekend") return Hit(DateSpec.WeekendOf(which), i, i + 2)
            // "this evening" is a time, not a date. Leave it.
        }

        weekdays[w]?.let { return Hit(DateSpec.Weekday(it, Which.Nearest), i, i + 1) }
        if (w == "weekend") return Hit(DateSpec.WeekendOf(Which.Nearest), i, i + 1)

        // "the third", "the third of march"
        if (w == "the" && t.getOrNull(i + 1)?.kind == Token.Kind.Ordinal) {
            val day = t[i + 1].value!!
            val month = if (at(2) == "of") months[at(3)] else months[at(2)]
            val until = when {
                at(2) == "of" && month != null -> i + 4
                month != null -> i + 3
                else -> i + 2
            }
            return Hit(DateSpec.DayOfMonth(day, month), i, until)
        }

        // "march the third", "march third", "march 3"
        months[w]?.let { month ->
            var n = i + 1
            if (t.getOrNull(n)?.text == "the") n += 1
            val day = t.getOrNull(n)?.takeIf {
                it.kind == Token.Kind.Ordinal || it.kind == Token.Kind.Cardinal
            }?.value
            if (day != null && day in 1..31) {
                return Hit(DateSpec.DayOfMonth(day, month), i, n + 1)
            }
        }
        return null
    }

    // ------------------------------------------------------------------ time

    private fun tryTime(t: List<Token>, i: Int): Hit<TimeSpec>? {
        // tryDuration calls this at i + 1, which is past the end for a sentence
        // that is nothing but the word "until". The bounds check below is after
        // the "at" test and so cannot cover this one.
        if (i >= t.size) return null
        var p = i
        val hadAt = t[p].text == "at"
        if (hadAt) p += 1
        if (p >= t.size) return null

        fun at(n: Int) = t.getOrNull(p + n)
        fun textAt(n: Int) = at(n)?.text

        when (t[p].text) {
            "noon" -> return Hit(TimeSpec.Clock(12, 0, Meridiem.Pm), i, p + 1)
            "midday" -> return Hit(TimeSpec.Clock(12, 0, Meridiem.Pm), i, p + 1)
            "midnight" -> return Hit(TimeSpec.Clock(0, 0), i, p + 1)
        }

        // "half past three", "quarter past three", "quarter to four"
        if (t[p].text == "half" || t[p].text == "quarter") {
            val minute = if (t[p].text == "half") 30 else 15
            val dir = textAt(1)
            val hourTok = at(2)?.takeIf { it.kind == Token.Kind.Cardinal }
            if (hourTok != null && (dir == "past" || dir == "to")) {
                val meridiem = at(3)?.takeIf { it.kind == Token.Kind.Meridiem }
                val end = if (meridiem != null) p + 4 else p + 3
                val h = hourTok.value!!
                val spec = if (dir == "past") {
                    TimeSpec.Clock(h, minute, meridiem(meridiem))
                } else {
                    TimeSpec.Clock(if (h == 0) 23 else h - 1, 60 - minute, meridiem(meridiem))
                }
                return Hit(spec, i, end)
            }
        }

        parts[t[p].text]?.let { return Hit(TimeSpec.PartOfDay(it), i, p + 1) }

        val hourTok = t[p].takeIf { it.kind == Token.Kind.Cardinal } ?: return null
        val h = hourTok.value ?: return null
        if (h !in 0..23) return null

        // "three p m"
        at(1)?.takeIf { it.kind == Token.Kind.Meridiem }?.let {
            return Hit(TimeSpec.Clock(h, 0, meridiem(it)), i, p + 2)
        }
        // "eight o clock", optionally with a meridiem after it
        if (at(1)?.kind == Token.Kind.OClock) {
            val m = at(2)?.takeIf { it.kind == Token.Kind.Meridiem }
            return Hit(TimeSpec.Clock(h, 0, meridiem(m)), i, if (m != null) p + 3 else p + 2)
        }
        // "eight thirty", "eight oh five"
        val minuteTok = at(1)?.takeIf { it.kind == Token.Kind.Cardinal }
        val minute = minuteTok?.value
        if (minute != null && minute in 0..59 && h in 1..12) {
            var end = p + 2
            // "eight oh five" is three tokens, because the zero is its own word.
            var mm = minute
            if (minute == 0) {
                val unitsTok = at(2)?.takeIf { it.kind == Token.Kind.Cardinal }
                val units = unitsTok?.value
                if (units != null && units in 1..9) {
                    mm = units
                    end = p + 3
                } else {
                    return Hit(TimeSpec.Clock(h, 0, null), i, p + 2)
                }
            }
            val m = t.getOrNull(end)?.takeIf { it.kind == Token.Kind.Meridiem }
            if (m != null) end += 1
            return Hit(TimeSpec.Clock(h, mm, meridiem(m)), i, end)
        }

        // A bare number is a time only when "at" introduced it. Without that
        // anchor, "meet at nine elms" and "table for four" both become times,
        // and section 3.3 of the M1 brief is explicit that the mitigation for
        // that is the card rather than a cleverer rule.
        if (hadAt && h in 1..23) return Hit(TimeSpec.Clock(h, 0), i, p + 1)
        return null
    }

    private fun meridiem(tok: Token?): Meridiem? = when (tok?.text) {
        "am" -> Meridiem.Am
        "pm" -> Meridiem.Pm
        else -> null
    }

    // -------------------------------------------------------------- duration

    private fun tryDuration(t: List<Token>, i: Int): Hit<DurationSpec>? {
        val w = t[i].text

        if (w == "for") {
            val n = t.getOrNull(i + 1)?.takeIf { it.kind == Token.Kind.Cardinal }?.value
            val unit = t.getOrNull(i + 2)?.text?.let { clockUnits[it] ?: dateUnits[it] }
            if (n != null && unit != null) return Hit(DurationSpec.Of(n, unit), i, i + 3)
            return null
        }

        if (w == "until" || w == "till" || w == "til") {
            val end = tryTime(t, i + 1) ?: return null
            return Hit(DurationSpec.Until(end.spec), i, end.until)
        }

        // A bare "forty five minutes". Only clock units, and never when "in"
        // introduced it, because "in ten minutes" is when it starts.
        if (t[i].kind == Token.Kind.Cardinal && i > 0 && t[i - 1].text != "in") {
            val unit = t.getOrNull(i + 1)?.text?.let { clockUnits[it] }
            if (unit != null) return Hit(DurationSpec.Of(t[i].value!!, unit), i, i + 2)
        }
        return null
    }
}

/**
 * What a sentence said about time, and which of its words said it.
 *
 * [consumed] is token indices, for the title extractor. The three spans are
 * positions in the original word sequence, for the card's underlining.
 */
data class Temporal(
    val date: DateSpec? = null,
    val dateSpan: IntRange? = null,
    val time: TimeSpec? = null,
    val timeSpan: IntRange? = null,
    val duration: DurationSpec? = null,
    val durationSpan: IntRange? = null,
    val consumed: Set<Int> = emptySet(),
) {
    val isEmpty: Boolean get() = date == null && time == null && duration == null
}
