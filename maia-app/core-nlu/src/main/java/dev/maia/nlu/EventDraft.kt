package dev.maia.nlu

import java.time.Duration
import java.time.ZonedDateTime

/**
 * The event as it currently stands, which is not the same thing as the event
 * the user said.
 *
 * Every field carries where it came from, because PRD section 5 stage 5 makes
 * the card the trust boundary: the user is being asked to approve something,
 * and an hour Maia invented has to look different from an hour they spoke. The
 * card reads [Field.provenance] to decide that, and [Field.span] to underline
 * the words behind a value.
 */
data class EventDraft(
    val title: Field<String>,
    val start: Field<ZonedDateTime>,
    val duration: Field<Duration>,
    val allDay: Boolean = false,
    /**
     * Where, when the user typed one.
     *
     * Null rather than an empty [Field] because the card draws three states
     * and they are not the same thing: heard, guessed, and never supplied. The
     * parser does not populate this at M1 and may never, so in practice a
     * non-null location is always [Provenance.Corrected]: the user put it
     * there with their thumb. The card still reads [Field.provenance] rather
     * than assuming that, because assuming it is how the assumption outlives
     * the release that makes it false.
     */
    val location: Field<String>? = null,
    /** What was heard, verbatim. Kept for the note body and for the fault card. */
    val transcript: String = "",
) {
    val end: ZonedDateTime get() = start.value.plus(duration.value)

    /**
     * Apply one edit from the card.
     *
     * Typed rather than a `(field, value)` pair, because the untyped version
     * puts a cast at every call site and the compiler stops helping exactly
     * where a wrong type would be silent until a user saw the wrong date.
     */
    fun with(edit: Edit): EventDraft = when (edit) {
        is Edit.Title -> copy(title = Field(edit.value, Provenance.Corrected))
        is Edit.Start -> copy(start = Field(edit.value, Provenance.Corrected))
        is Edit.Length -> copy(duration = Field(edit.value, Provenance.Corrected))
        is Edit.Location ->
            // Blank clears it back to never-supplied rather than storing an
            // empty string, so the row returns to "not set" and the writer has
            // nothing to put in EVENT_LOCATION.
            copy(
                location = edit.value.trim()
                    .takeIf { it.isNotEmpty() }
                    ?.let { Field(it, Provenance.Corrected) },
            )

        is Edit.AllDay ->
            if (edit.value) {
                copy(
                    start = Field(
                        start.value.toLocalDate().atStartOfDay(start.value.zone),
                        Provenance.Corrected,
                    ),
                    duration = Field(Duration.ofDays(1), Provenance.Corrected),
                    allDay = true,
                )
            } else {
                copy(allDay = false)
            }
    }

    /**
     * Take everything from [fresh] except what the user has already corrected.
     *
     * This is the whole of the "not re-inferred" rule. A re-parse happens when
     * the transcript changes under a draft that has been edited, and without
     * this the second parse quietly reinstates the hour the user just fixed.
     * Provenance is the only thing that can tell those apart after the fact,
     * which is why it is on the field and not in a side table.
     */
    fun keepingCorrections(fresh: EventDraft): EventDraft = EventDraft(
        title = if (title.provenance == Provenance.Corrected) title else fresh.title,
        start = if (start.provenance == Provenance.Corrected) start else fresh.start,
        duration = if (duration.provenance == Provenance.Corrected) duration else fresh.duration,
        allDay = if (start.provenance == Provenance.Corrected ||
            duration.provenance == Provenance.Corrected
        ) {
            allDay
        } else {
            fresh.allDay
        },
        location = if (location?.provenance == Provenance.Corrected) location else fresh.location,
        transcript = fresh.transcript,
    )
}

/** One change the user made on the card. */
sealed interface Edit {
    data class Title(val value: String) : Edit
    data class Start(val value: ZonedDateTime) : Edit
    data class Length(val value: Duration) : Edit
    /** Blank clears it. Never arrives from the parser, only from the card. */
    data class Location(val value: String) : Edit
    data class AllDay(val value: Boolean) : Edit
}
