package dev.maia.nlu.time

import java.time.LocalTime

/**
 * What `afternoon` means, in one place.
 *
 * Two different questions need this and they need different answers from the
 * same word, which is why there are two tables rather than one. "Am I free
 * thursday afternoon" asks about a window. "Lunch thursday afternoon" asks
 * when something starts, and starting it at 12:00 sharp because that is where
 * the window opens is not what anybody meant.
 */
object DayWindows {

    /** The range a read-back query tests for conflicts. */
    fun window(part: Part): ClosedRange<LocalTime> = when (part) {
        Part.Morning -> LocalTime.of(9, 0)..LocalTime.of(12, 0)
        Part.Afternoon -> LocalTime.of(12, 0)..LocalTime.of(18, 0)
        Part.Evening -> LocalTime.of(18, 0)..LocalTime.of(22, 0)
    }

    /** Where an event gets placed when the part of day is all that was said. */
    fun anchor(part: Part): LocalTime = when (part) {
        Part.Morning -> LocalTime.of(9, 0)
        Part.Afternoon -> LocalTime.of(14, 0)
        Part.Evening -> LocalTime.of(19, 0)
    }

    /**
     * The window a bare hour with no meridiem is resolved inside.
     *
     * `at four` is 16:00 and not 04:00 because nobody schedules dinner for four
     * in the morning, and the honest form of that reasoning is a stated window
     * rather than a rule about pm. Both ends are inclusive of the hour.
     */
    val BARE_HOUR: IntRange = 7..21
}
