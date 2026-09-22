package dev.maia.app.answer

import dev.maia.actions.CalendarEvent
import dev.maia.actions.CalendarRepository
import dev.maia.nlu.Intent
import java.time.Clock
import java.time.ZonedDateTime
import java.util.Locale

/**
 * The reads a phone can do alone: agenda, availability, a device fact, a
 * calculation (M9 PRD section 3.2).
 *
 * Every collaborator is a constructor parameter, so this class is a JVM
 * test with a fake repository and a fixed clock, and no service is ever
 * looked up inside it. The battery in particular arrives as a lambda
 * because the sticky intent that answers it is the host's Android, not this
 * class's.
 *
 * The sentences are `LocalAnswer`'s, built in this package: this class
 * gathers what they need (the events, the battery percent, the now) and
 * maps what they return onto the driver's two outcomes. A null
 * [LocalResult] is a read that produced nothing sayable, which is a fault
 * rather than a sentence with a hole in it.
 */
class LocalReader(
    private val calendar: CalendarRepository,
    private val clock: Clock,
    /** Percent, or null when the platform will not say. Never throws. */
    private val battery: () -> Int?,
    private val locale: () -> Locale = { Locale.getDefault() },
) {

    suspend fun read(intent: Intent): LocalRead = when (intent) {
        is Intent.Agenda -> result(
            agenda(events(intent.range) ?: return denied(), intent.range, locale()),
            AnswerSource.Calendar,
        )
        is Intent.Availability -> result(
            availability(events(intent.range) ?: return denied(), intent.range, locale()),
            AnswerSource.Calendar,
        )
        is Intent.Calculate -> result(calculate(intent.expression), AnswerSource.Phone)
        is Intent.DeviceFact -> result(
            deviceFact(intent.kind, battery(), ZonedDateTime.now(clock), locale()),
            AnswerSource.Phone,
        )
        // The machine only ever emits RunRead for the four above. Anything
        // else reaching here is its bug, reported rather than answered.
        else -> LocalRead.Failed(AnswerFault.Unusable)
    }

    /**
     * A built answer becomes a `Done` with only its display text: the
     * `ReadDone` event carries one string, and the machine chooses the
     * spoken form itself through `shortForSpeech`, so [LocalResult]'s
     * spoken half deliberately does not cross this seam. A null result is
     * the builder declining to say anything: a battery nobody could read,
     * an expression [dev.maia.nlu.calc.Calc] could not evaluate. That is
     * `Unusable`, the contract's "no answer could be produced" fault, and
     * never a guessed value.
     */
    private fun result(built: LocalResult?, source: AnswerSource): LocalRead = when (built) {
        null -> LocalRead.Failed(AnswerFault.Unusable)
        else -> LocalRead.Done(built.text, source)
    }

    private fun denied(): LocalRead = LocalRead.Failed(AnswerFault.Permission(PermNeeded.Calendar))

    /** Null is the SecurityException: READ_CALENDAR was never granted or was revoked. */
    private suspend fun events(range: ClosedRange<ZonedDateTime>): List<CalendarEvent>? =
        try {
            calendar.eventsIn(range)
        } catch (e: SecurityException) {
            null
        }
}

/** What one local read came to, mapped to an [AnswerEvent] by the driver. */
sealed interface LocalRead {
    /** The answer text and where it came from, for the source row. */
    data class Done(val text: String, val source: AnswerSource) : LocalRead

    /** The read could not happen; [fault] is already screen-shaped. */
    data class Failed(val fault: AnswerFault) : LocalRead
}
