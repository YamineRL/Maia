package dev.maia.nlu.speech

import dev.maia.nlu.EventDraft
import dev.maia.nlu.Provenance
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The sentence Maia says after an event is written.
 *
 * The design seat's example is the whole specification: "Saved, dinner with
 * Sam, Thursday at eight." Short, offline, one sentence, not a paragraph. It is
 * built here, in pure Kotlin, so it can be tested on the JVM against the same
 * drafts the card is (criterion 10.1.9), and so the voice says exactly the
 * facts the card showed: the same title, the same day, the same time.
 *
 * Every rule below the example is a proposal, because the design is silent on
 * them. They are written as words rather than digits throughout. A Piper voice
 * phonemises through espeak-ng, and how espeak reads "20:15" or "17 Sept" is a
 * guess this module cannot check, where "quarter past eight" can only be said
 * one way.
 *
 * **Day** (proposal). The same date as [sentence]'s `now` is "today", the next
 * is "tomorrow", two to [WEEKDAY_HORIZON_DAYS] days ahead is the weekday name.
 * Seven days out would share today's weekday and be ambiguous, so from there,
 * and for any date already past, it is "the nineteenth of September", with the
 * year added only when it is not this year. Dates are compared in the event's
 * zone, since that is the calendar the user will look at.
 *
 * **Time** (proposal). A twelve hour clock in words: "at eight", "at quarter
 * past eight", "at half past eight", "at quarter to nine", "at eight oh five",
 * "at eight twenty". 12:00 is "midday" and 00:00 is "midnight", said against
 * the date the event starts on, so a midnight event on Friday is "Friday at
 * midnight". That reading is the calendar's and not everyone's, and is worth
 * the design seat's eye.
 *
 * **Morning or evening** (proposal). The example says "at eight" for 20:00
 * with no part of the day, so a time the user said is spoken the same way. A
 * time Maia filled in ([Provenance.Inferred]) gets "in the morning", "in the
 * afternoon" or "in the evening", because the confirmation is a receipt and a
 * guess has to be named in it, the same rule the card follows with its marks.
 *
 * **All day** (proposal). "Saved, holiday, Thursday, all day."
 *
 * **Title** (proposal). Spoken as it stands on the draft, whitespace collapsed
 * and trailing punctuation dropped so it cannot end the sentence early. It is
 * not capitalised: transcripts are lowercase, there is no way to tell "sam" the
 * person from "sam" anything else without guessing, and a voice sounds the same
 * either way. A title the user corrected keeps its capitals. A blank title is
 * left out rather than spoken as a pause: "Saved, Thursday at eight."
 */
object Confirmation {

    /** The furthest ahead a bare weekday name is used. Six, so it is never today's. */
    const val WEEKDAY_HORIZON_DAYS = 6L

    /**
     * [locale] names the weekday and the month. The rest of the sentence is
     * British English, because that is the only voice proposed, so this should
     * stay [Locale.UK] until a voice speaks another language.
     */
    fun sentence(draft: EventDraft, now: ZonedDateTime, locale: Locale = Locale.UK): String {
        val start = draft.start.value
        val today = now.withZoneSameInstant(start.zone).toLocalDate()
        val day = day(start.toLocalDate(), today, locale)
        val occasion = if (draft.allDay) {
            "$day, all day"
        } else {
            "$day at ${time(start.toLocalTime(), draft.start.provenance == Provenance.Inferred)}"
        }
        val title = title(draft.title.value)
        return if (title.isEmpty()) "Saved, $occasion." else "Saved, $title, $occasion."
    }

    internal fun title(raw: String): String =
        raw.trim().split(WHITESPACE).joinToString(" ").trimEnd(*TRAILING).trim()

    internal fun day(date: LocalDate, today: LocalDate, locale: Locale): String {
        val ahead = ChronoUnit.DAYS.between(today, date)
        return when {
            ahead == 0L -> "today"
            ahead == 1L -> "tomorrow"
            ahead in 2..WEEKDAY_HORIZON_DAYS -> date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
            else -> buildString {
                append("the ").append(ordinal(date.dayOfMonth))
                append(" of ").append(date.month.getDisplayName(TextStyle.FULL, locale))
                if (date.year != today.year) append(' ').append(year(date.year))
            }
        }
    }

    internal fun time(time: LocalTime, inferred: Boolean): String {
        val hour = time.hour
        val minute = time.minute
        if (hour == 0 && minute == 0) return "midnight"
        if (hour == 12 && minute == 0) return "midday"
        val clock = when (minute) {
            0 -> cardinal(twelveHour(hour))
            15 -> "quarter past ${cardinal(twelveHour(hour))}"
            30 -> "half past ${cardinal(twelveHour(hour))}"
            45 -> "quarter to ${cardinal(twelveHour(hour + 1))}"
            in 1..9 -> "${cardinal(twelveHour(hour))} oh ${cardinal(minute)}"
            else -> "${cardinal(twelveHour(hour))} ${cardinal(minute)}"
        }
        return if (inferred) "$clock ${partOfDay(hour)}" else clock
    }

    private fun twelveHour(hour: Int): Int = (hour + 11) % 12 + 1

    private fun partOfDay(hour: Int): String = when (hour) {
        in 0..11 -> "in the morning"
        in 12..17 -> "in the afternoon"
        else -> "in the evening"
    }

    /** 0 to 99 in words, hyphenated the British way: "twenty-five". */
    internal fun cardinal(n: Int): String {
        require(n in 0..99) { "only 0 to 99 is spoken here, was $n" }
        if (n < 20) return UNITS[n]
        val tens = TENS[n / 10]
        return if (n % 10 == 0) tens else "$tens-${UNITS[n % 10]}"
    }

    /** 1 to 31, the days of a month: "first", "twenty-second", "thirtieth". */
    internal fun ordinal(n: Int): String {
        require(n in 1..31) { "only days of the month are spoken here, was $n" }
        if (n < 20) return ORDINAL_UNITS[n]
        if (n % 10 == 0) return TENS[n / 10].dropLast(1) + "ieth"
        return "${TENS[n / 10]}-${ORDINAL_UNITS[n % 10]}"
    }

    /**
     * "twenty twenty-seven", "two thousand and nine". Outside this century it
     * falls back to digits: an event that far out is a typo more often than a
     * plan, and the card shows it in full anyway.
     */
    internal fun year(year: Int): String = when (year) {
        2000 -> "two thousand"
        in 2001..2009 -> "two thousand and ${cardinal(year - 2000)}"
        in 2010..2099 -> "twenty ${cardinal(year - 2000)}"
        else -> year.toString()
    }

    private val WHITESPACE = Regex("\\s+")
    private val TRAILING = charArrayOf('.', ',', ';', ':', '!', '?', ' ')

    private val UNITS = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
        "seventeen", "eighteen", "nineteen",
    )

    private val TENS = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

    private val ORDINAL_UNITS = listOf(
        "", "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth",
        "tenth", "eleventh", "twelfth", "thirteenth", "fourteenth", "fifteenth", "sixteenth",
        "seventeenth", "eighteenth", "nineteenth",
    )
}
