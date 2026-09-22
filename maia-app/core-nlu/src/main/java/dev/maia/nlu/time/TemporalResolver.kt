package dev.maia.nlu.time

import dev.maia.nlu.Field
import dev.maia.nlu.Provenance
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZonedDateTime

/**
 * Pass three of three: a resolution-free [DateSpec] plus [TimeSpec] against an
 * actual moment.
 *
 * The [Clock] is the only way this class learns what time it is. That is not
 * negotiable and the review should reject a `LocalDate.now()` anywhere inside
 * the parser on sight: `Clock` carries its own `ZoneId`, so one constructor
 * parameter settles both the instant and the timezone, and every relative date
 * in PRD section 7 becomes an exact assertion in a test rather than an
 * approximate one.
 *
 * Production passes `Clock.systemDefaultZone()`. Tests pass
 * `Clock.fixed(...)`.
 *
 * The rules below are judgements, not facts, and each one will be wrong for
 * somebody. They are written down here, with a test each, because the
 * alternative is that they get decided by accident.
 */
class TemporalResolver(private val clock: Clock) {

    /** The default when nobody said how long, from PRD section 7. */
    private val defaultDuration: Duration = Duration.ofHours(1)

    fun now(): ZonedDateTime = ZonedDateTime.now(clock)

    /**
     * @return null only when [date], [time] and [duration] are all null, which
     *   means the sentence carried nothing temporal at all.
     */
    fun resolve(
        date: DateSpec?,
        time: TimeSpec?,
        duration: DurationSpec? = null,
    ): Resolved? {
        if (date == null && time == null && duration == null) return null
        val now = now()

        // An all-day event is the least wrong reading of a sentence that names
        // a day and no time. Guessing an hour would be inventing information
        // the user never gave, and the card can promote it in one tap.
        // A stated length is a statement that this is not an all day event.
        // "block out two hours tomorrow" names no hour, but a person who says
        // how long it runs has not described a whole day.
        val allDay = time == null && date != null && duration == null

        val baseDate = date?.let { resolveDate(it, now.toLocalDate()) } ?: now.toLocalDate()
        val heardDate = date != null

        if (allDay) {
            val start = baseDate.atStartOfDay(clock.zone)
            return Resolved(
                start = Field(start, Provenance.Heard),
                duration = Field(Duration.ofDays(1), Provenance.Inferred),
                allDay = true,
            )
        }

        val resolvedTime = resolveTime(time, now, baseDate)
        var start = baseDate.atTime(resolvedTime.value).atZone(clock.zone)

        // Rolling forward is what makes a bare `thursday` mean the next one and
        // a bare `at four` mean today if today still has a four in it. Only
        // relative specs roll: an explicit date the user named stays where they
        // put it, even if it is in the past, because that is a correction the
        // card should show rather than a guess the parser should make.
        start = rollForward(start, date, now)

        val resolvedDuration = resolveDuration(duration, start)
        return Resolved(
            start = Field(start, if (heardDate && resolvedTime.provenance == Provenance.Heard) {
                Provenance.Heard
            } else {
                Provenance.Inferred
            }),
            duration = resolvedDuration,
            allDay = false,
        )
    }

    /** The range a read-back question asks about: "am I free thursday afternoon". */
    fun resolveRange(date: DateSpec?, time: TimeSpec?): ClosedRange<ZonedDateTime> {
        val now = now()
        val day = date?.let { resolveDate(it, now.toLocalDate()) } ?: now.toLocalDate()
        val part = (time as? TimeSpec.PartOfDay)?.part
        return if (part == null) {
            day.atStartOfDay(clock.zone)..day.plusDays(1).atStartOfDay(clock.zone)
        } else {
            val w = DayWindows.window(part)
            day.atTime(w.start).atZone(clock.zone)..day.atTime(w.endInclusive).atZone(clock.zone)
        }
    }

    // ------------------------------------------------------------------ date

    internal fun resolveDate(spec: DateSpec, today: LocalDate): LocalDate = when (spec) {
        is DateSpec.Today -> today
        is DateSpec.Tomorrow -> today.plusDays(1)
        is DateSpec.Weekday -> weekday(spec.day, spec.which, today)
        is DateSpec.DayOfMonth -> dayOfMonth(spec, today)
        is DateSpec.Offset -> today.plus(spec.amount.toLong(), spec.unit)
        is DateSpec.WeekendOf -> weekday(DayOfWeek.SATURDAY, spec.which, today)
        is DateSpec.Compound ->
            resolveDate(spec.base, today).plus(spec.plus.amount.toLong(), spec.plus.unit)
    }

    private fun weekday(day: DayOfWeek, which: Which, today: LocalDate): LocalDate = when (which) {
        // `next thursday` is the Thursday of the following ISO week.
        //
        // The alternative reading, "the next one to occur", makes `next
        // thursday` and a bare `thursday` the same phrase on six days out of
        // seven, which means the word `next` carries no information at all,
        // and people who say it mean something by it. The cost is that said on
        // a Friday this is thirteen days out and some people mean six. One
        // constant, one test, cheap to change.
        Which.Next -> {
            val mondayNextWeek = today.with(DayOfWeek.MONDAY).plusWeeks(1)
            mondayNextWeek.plusDays((day.value - DayOfWeek.MONDAY.value).toLong())
        }
        // A bare `thursday` is the next Thursday, today included. Whether
        // today survives depends on the time, which rollForward decides once
        // the time is known.
        Which.This, Which.Nearest -> {
            val ahead = (day.value - today.dayOfWeek.value + 7) % 7
            today.plusDays(ahead.toLong())
        }
    }

    private fun dayOfMonth(spec: DateSpec.DayOfMonth, today: LocalDate): LocalDate {
        // Nobody says "the third" about a date that has been and gone.
        if (spec.month != null) {
            // "the thirty first of february" is a thing people say and a date
            // that does not exist. Clamping to the last of the month keeps the
            // card populated and one tap from right, which principle 4 asks
            // for; throwing here would take the whole utterance down.
            val length = YearMonth.of(today.year, spec.month).lengthOfMonth()
            val candidate = LocalDate.of(today.year, spec.month, minOf(spec.day, length))
            if (candidate >= today) return candidate
            val next = YearMonth.of(today.year + 1, spec.month)
            return LocalDate.of(next.year, spec.month, minOf(spec.day, next.lengthOfMonth()))
        }
        val candidate = today.withDayOfMonth(minOf(spec.day, today.lengthOfMonth()))
        return if (spec.day >= today.dayOfMonth) {
            candidate
        } else {
            val next = today.plusMonths(1)
            next.withDayOfMonth(minOf(spec.day, next.lengthOfMonth()))
        }
    }

    // ------------------------------------------------------------------ time

    /**
     * [onDate] is the day the time will land on, when one is known.
     *
     * It only matters for the bare-hour guess below. "at nine" said at half
     * eleven in the morning means tonight if it means today, and means the
     * morning if it means tomorrow, and a resolver that compares against the
     * wall clock regardless gets the second one wrong every time.
     */
    internal fun resolveTime(
        spec: TimeSpec?,
        now: ZonedDateTime,
        onDate: LocalDate? = null,
    ): Field<LocalTime> = when (spec) {
        null -> Field(now.toLocalTime().withSecond(0).withNano(0), Provenance.Inferred)
        is TimeSpec.PartOfDay -> Field(DayWindows.anchor(spec.part), Provenance.Inferred)
        is TimeSpec.Clock -> clockTime(spec, now, onDate)
    }

    private fun clockTime(
        spec: TimeSpec.Clock,
        now: ZonedDateTime,
        onDate: LocalDate? = null,
    ): Field<LocalTime> {
        if (spec.meridiem != null) {
            val h = when {
                spec.meridiem == Meridiem.Am && spec.hour == 12 -> 0
                spec.meridiem == Meridiem.Pm && spec.hour < 12 -> spec.hour + 12
                else -> spec.hour
            }
            return Field(LocalTime.of(h % 24, spec.minute), Provenance.Heard)
        }
        // No meridiem. A 24 hour reading needs no guess.
        if (spec.hour > 12) return Field(LocalTime.of(spec.hour, spec.minute), Provenance.Heard)

        // Otherwise guess, and mark it. Marked is the load bearing half: the
        // guess will be wrong sometimes, and a card that makes it one tap to
        // fix is the difference between a confident assistant and a lying one.
        val candidates = listOf(spec.hour, (spec.hour + 12) % 24)
            .filter { it in DayWindows.BARE_HOUR }
            .distinct()
        val chosen = when {
            candidates.isEmpty() -> spec.hour
            candidates.size == 1 -> candidates.single()
            // Both readings are plausible. On today, take the next one still
            // to come; on any other day nothing has passed yet, so take the
            // earlier one, which is the daytime reading.
            onDate != null && onDate != now.toLocalDate() -> candidates.first()
            else -> candidates.firstOrNull { LocalTime.of(it, spec.minute) > now.toLocalTime() }
                ?: candidates.first()
        }
        return Field(LocalTime.of(chosen, spec.minute), Provenance.Inferred)
    }

    private fun rollForward(
        start: ZonedDateTime,
        date: DateSpec?,
        now: ZonedDateTime,
    ): ZonedDateTime {
        if (!start.isBefore(now)) return start
        return when (date) {
            // A bare time, today: it must mean tomorrow.
            null, is DateSpec.Today -> start.plusDays(1)
            // `thursday` landed on today and today's slot has passed.
            is DateSpec.Weekday ->
                if (date.which == Which.Next) start else start.plusWeeks(1)
            is DateSpec.WeekendOf ->
                if (date.which == Which.Next) start else start.plusWeeks(1)
            else -> start
        }
    }

    // -------------------------------------------------------------- duration

    private fun resolveDuration(spec: DurationSpec?, start: ZonedDateTime): Field<Duration> =
        when (spec) {
            null -> Field(defaultDuration, Provenance.Inferred)
            is DurationSpec.Of ->
                Field(Duration.of(spec.amount.toLong(), spec.unit), Provenance.Heard)
            is DurationSpec.Until -> {
                val end = resolveTime(spec.end, start)
                var endAt = start.toLocalDate().atTime(end.value).atZone(clock.zone)
                // "from eleven until one" crosses midday, not midnight, but
                // "from eleven pm until one" crosses both.
                if (!endAt.isAfter(start)) endAt = endAt.plusDays(1)
                Field(Duration.between(start, endAt), end.provenance)
            }
        }
}

/**
 * What the resolver produced. Both fields carry their own provenance because
 * the card marks them separately: a heard time with a guessed duration is the
 * single most common shape a sentence about a calendar has.
 */
data class Resolved(
    val start: Field<ZonedDateTime>,
    val duration: Field<Duration>,
    val allDay: Boolean,
) {
    val end: ZonedDateTime get() = start.value.plus(duration.value)
}
