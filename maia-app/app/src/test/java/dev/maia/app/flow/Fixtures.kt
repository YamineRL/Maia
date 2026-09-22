package dev.maia.app.flow

import dev.maia.actions.CalendarTarget
import dev.maia.actions.MaiaCalendar
import dev.maia.nlu.EventDraft
import dev.maia.nlu.Field
import dev.maia.nlu.Provenance
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

/** Shared by the flow tests. Built by hand so no test depends on the parser's grammar. */
object Fixtures {
    val zone: ZoneId = ZoneId.of("Europe/Zurich")
    val thursdayEight: ZonedDateTime = ZonedDateTime.of(2026, 9, 17, 20, 0, 0, 0, zone)

    const val SENTENCE = "dinner with sam thursday at eight p m"

    val heard = EventDraft(
        title = Field("dinner with sam", Provenance.Heard, 0..2),
        start = Field(thursdayEight, Provenance.Heard),
        duration = Field(Duration.ofHours(1), Provenance.Inferred),
        transcript = SENTENCE,
    )

    /** What the parser builds for "dinner with sam": now, inferred, one hour, inferred. */
    val dateless = EventDraft(
        title = Field("dinner with sam", Provenance.Heard, 0..2),
        start = Field(ZonedDateTime.of(2026, 9, 13, 10, 0, 0, 0, zone), Provenance.Inferred),
        duration = Field(Duration.ofHours(1), Provenance.Inferred),
        transcript = "dinner with sam",
    )

    val personal = CalendarTarget(MaiaCalendar(7, "Personal", "me@example.org"), chosen = true)
    val readOnly = CalendarTarget(MaiaCalendar(9, "Holidays", "me@example.org", writable = false), chosen = false)

    /** One of each state, for the tables. */
    val everyState: List<FlowState> = listOf(
        FlowState.FirstRun(),
        FlowState.Idle(),
        FlowState.Invoking(pressedAt = 0),
        FlowState.Listening(pressedAt = 0, partial = "din", firstPartialFelt = true),
        FlowState.Understanding(SENTENCE, since = 0),
        FlowState.Preview(heard),
        FlowState.NoCalendar(heard),
        FlowState.Committing(heard, personal),
        FlowState.Confirmed(heard, eventId = 42, undoDeadline = UNDO_WINDOW_MS),
        FlowState.Fault(FaultReason.NoDateHeard, "dinner with sam", dateless),
    )
}

/** Runs events through the reducer one after another, keeping every effect in order. */
class Run(var state: FlowState, var now: Long = 0) {
    val effects = mutableListOf<Effect>()

    fun send(event: FlowEvent, at: Long = now): Run {
        now = at
        val step = reduce(state, event, now)
        state = step.state
        effects += step.effects
        return this
    }

    inline fun <reified T : Effect> all(): List<T> = effects.filterIsInstance<T>()
}
