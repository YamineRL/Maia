package dev.maia.app.card

import dev.maia.actions.CalendarTarget
import dev.maia.nlu.EventDraft
import dev.maia.nlu.Provenance
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * How a field is marked, which is the most important detail in the product.
 *
 * The design handoff marks a guess four ways at once: a hollow diamond in the
 * gutter, angle brackets around the value, a dashed underline, and a hatched
 * ground with a "guess" chip in the label line. Four, because any one of them
 * can be lost to greyscale, to colourblindness, to direct sunlight, and three
 * still remain. Colour is never one of the four: the aperture is the only
 * saturated thing anywhere in Maia.
 *
 * This enum is what all four render from, so there is exactly one place where
 * a field's standing is decided.
 */
enum class Mark {
    /** Came from words the user said. Filled diamond, plain value. */
    Heard,

    /** Maia supplied it. All four cues. */
    Guessed,

    /** Nobody supplied it. Dashed diamond, a name instead of a blank. */
    Empty,
}

/** Which row, so a tap knows which editor to open. */
enum class CardField { Title, When, Duration, Location, Calendar }

/**
 * One row of the card, already in the words the screen shows.
 *
 * Rendering and deciding are split here on purpose. Deciding is what has to be
 * right: a duration Maia invented shown as one the user spoke is the failure
 * that costs trust, and it is silent. So it is decided in this file, which is
 * pure Kotlin with no Android in it, and pinned by tests.
 */
data class CardRow(
    val field: CardField,
    val label: String,
    val value: String,
    val mark: Mark,
    /**
     * Why the row looks the way it does, in the user's words.
     *
     * The handoff calls this out as the thing that turns a guess into
     * something a person can accept: "you did not say how long" reads as an
     * account of what happened, where a bare marker reads as an accusation.
     * Null on a heard field, which needs no explanation.
     */
    val reason: String?,
    /** Times, dates and durations are set in mono so digits line up. */
    val mono: Boolean = false,
)

/**
 * The card, as a list of rows.
 *
 * [target] is null only on the no-calendars screen, which is a different
 * screen entirely, so the calendar row here always has something to say.
 */
fun cardRows(
    draft: EventDraft,
    target: CalendarTarget?,
    locale: Locale = Locale.getDefault(),
): List<CardRow> = listOf(
    CardRow(
        field = CardField.Title,
        label = "Title",
        value = draft.title.value,
        mark = draft.title.provenance.mark(),
        reason = draft.title.provenance.reasonFor(CardField.Title),
    ),
    CardRow(
        field = CardField.When,
        label = "When",
        value = whenLine(draft, locale),
        mark = draft.start.provenance.mark(),
        reason = draft.start.provenance.reasonFor(CardField.When),
        mono = true,
    ),
    CardRow(
        field = CardField.Duration,
        label = "Duration",
        value = if (draft.allDay) "all day" else clock(draft.duration.value),
        mark = draft.duration.provenance.mark(),
        reason = draft.duration.provenance.reasonFor(CardField.Duration),
        mono = !draft.allDay,
    ),
    CardRow(
        field = CardField.Location,
        label = "Location",
        value = draft.location?.value ?: "not set",
        // A location is never guessed: the parser does not produce one, so it
        // is either absent or something the user typed. The row still reads
        // provenance instead of assuming, so that the day the parser learns
        // to hear "at the Trattoria" this row is already correct.
        mark = draft.location?.provenance?.mark() ?: Mark.Empty,
        reason = if (draft.location == null) "optional, the event writes without it" else null,
    ),
    CardRow(
        field = CardField.Calendar,
        label = "Calendar",
        value = target?.calendar?.displayName ?: "none",
        mark = if (target == null || target.guessed) Mark.Guessed else Mark.Heard,
        reason = when {
            target == null -> "no calendar accepts new events"
            target.guessed -> "you have not picked one, so this is Maia's guess"
            else -> null
        },
    ),
)

/** The counter under the title: "2 heard, 2 guessed, 1 empty". */
fun tally(rows: List<CardRow>): String {
    val counts = Mark.entries.associateWith { mark -> rows.count { it.mark == mark } }
    return listOf(
        counts[Mark.Heard] to "heard",
        counts[Mark.Guessed] to "guessed",
        counts[Mark.Empty] to "empty",
    ).filter { (n, _) -> (n ?: 0) > 0 }
        .joinToString(" · ") { (n, word) -> "$n $word" }
}

/**
 * A corrected field is heard, not guessed.
 *
 * This is the rule the whole provenance model exists for, stated once. The
 * user changed it with their thumb, which is a stronger statement of intent
 * than saying it out loud, so the marks come off and they stay off.
 */
private fun Provenance.mark(): Mark = when (this) {
    Provenance.Heard -> Mark.Heard
    Provenance.Inferred -> Mark.Guessed
    Provenance.Corrected -> Mark.Heard
}

private fun Provenance.reasonFor(field: CardField): String? = when {
    this != Provenance.Inferred -> null
    field == CardField.Duration -> "you did not say how long"
    field == CardField.When -> "Maia filled in the part you did not say"
    field == CardField.Title -> "Maia could not tell which words were the title"
    else -> "Maia filled this in"
}

private val dayMonth = DateTimeFormatter.ofPattern("EEE d MMM")
private val hourMinute = DateTimeFormatter.ofPattern("HH:mm")

private fun whenLine(draft: EventDraft, locale: Locale): String {
    val date = dayMonth.withLocale(locale).format(draft.start.value)
    // An all-day event has no time to show, and showing 00:00 would be Maia
    // inventing a precision the user did not ask for.
    return if (draft.allDay) date else "$date  ${hourMinute.format(draft.start.value)}"
}

/**
 * A duration as `h:mm`, which is how the handoff sets it, and the hours are
 * not capped at 24: a three day span reads 72:00 rather than silently
 * wrapping to 0:00, which would be the same bug as an event on the wrong day.
 */
internal fun clock(duration: Duration): String {
    val total = maxOf(duration.toMinutes(), 0)
    return "%d:%02d".format(total / 60, total % 60)
}
