package dev.maia.nlu.time

import java.time.DayOfWeek
import java.time.Month
import java.time.temporal.ChronoUnit

/**
 * Pass two of three: what the grammar extracted, before anything knows what
 * day it is.
 *
 * This separation is the load bearing idea in the temporal code. Nothing in
 * this file reads a clock, so a test can assert on the extraction independently
 * of the resolution, and when something breaks the failure says which of the
 * two halves broke. Every type here is a pure value with equals.
 */
sealed interface DateSpec {
    data object Today : DateSpec
    data object Tomorrow : DateSpec
    data class Weekday(val day: DayOfWeek, val which: Which) : DateSpec

    /** `the third`, or `the third of march`. */
    data class DayOfMonth(val day: Int, val month: Month? = null) : DateSpec

    /** `in two weeks`. With no base it resolves against today. */
    data class Offset(val amount: Int, val unit: ChronoUnit) : DateSpec

    data class WeekendOf(val which: Which) : DateSpec

    /** `a week from friday` is Weekday(FRIDAY, Nearest) plus Offset(1, WEEKS). */
    data class Compound(val base: DateSpec, val plus: Offset) : DateSpec
}

enum class Which {
    /** `this thursday`. */
    This,

    /** `next thursday`. Resolves to the following ISO week, see TemporalResolver. */
    Next,

    /** A bare `thursday`, with no qualifier. */
    Nearest,
}

sealed interface TimeSpec {
    /** [meridiem] is null when the speaker did not say one, which is the usual case. */
    data class Clock(val hour: Int, val minute: Int = 0, val meridiem: Meridiem? = null) : TimeSpec

    data class PartOfDay(val part: Part) : TimeSpec
}

enum class Meridiem { Am, Pm }

enum class Part { Morning, Afternoon, Evening }

sealed interface DurationSpec {
    data class Of(val amount: Int, val unit: ChronoUnit) : DurationSpec

    /** `from three until four`. */
    data class Until(val end: TimeSpec) : DurationSpec
}
