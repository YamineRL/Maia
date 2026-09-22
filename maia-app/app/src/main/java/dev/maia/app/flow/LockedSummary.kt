package dev.maia.app.flow

import dev.maia.actions.notes.Note as CapturedNote
import dev.maia.nlu.EventDraft
import dev.maia.nlu.Field
import dev.maia.nlu.Provenance
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Everything a locked screen is allowed to draw, and there is deliberately
 * nowhere in it to put anything else.
 *
 * M3 brief section 4.1 lists what a lock screen may show: the words the person
 * holding the phone just said, and what those words parsed to. Not the calendar
 * the event would land in, not the account it belongs to, not its colour, not
 * any event that already exists. A calendar name is a disclosure on its own: a
 * calendar called "Clinic" says something about the owner to a stranger holding
 * their phone.
 *
 * That rule is enforced here by type rather than by care. The locked composable
 * takes this and nothing else, this is built by [lockedSummary] from an
 * [EventDraft] alone, and an [EventDraft] has no idea a calendar exists. There
 * is no path by which a provider read can reach the lock screen, because there
 * is no field it could arrive in.
 */
data class LockedSummary(
    /** The parsed what, as plain text. Never the preview card. */
    val title: String,
    /**
     * The parsed when, as plain text. A function of the transcript and the
     * clock, which is why it is safe: nothing was read to compute it.
     */
    val whenText: String,
    /** What was heard, verbatim. Safe because they just said it out loud. */
    val transcript: String,
)

/**
 * One draft waiting for the unlock, and the instant its sentence was heard.
 *
 * Separate from [FlowState.Queued] on purpose, even though the two carry nearly
 * the same fields. [FlowState.Queued] is a screen and can stand for a read-back
 * question, which has no draft and which nothing can do anything with until M4;
 * the queue carries only things the card can open after the unlock, so its
 * element type has a non-null draft and the invariant is in the type rather
 * than in a comment.
 *
 * [heardAt] is on the reducer's `now` clock, the same one as
 * [FlowState.Invoking.pressedAt], so that a test with a fake clock can roll a
 * queued draft over midnight without a real one.
 */
data class QueuedDraft(
    val draft: Pending,
    val heardAt: Long,
    val summary: LockedSummary,
) {
    /** M3's constructor, for an event, which every M3 caller still uses. */
    constructor(draft: EventDraft, heardAt: Long, summary: LockedSummary) :
        this(Pending.Event(draft), heardAt, summary)
}

/**
 * What a queued card will open as, once. M4 interface I4: the queue element
 * widened from an [EventDraft] to this, because a note heard while locked is
 * kept exactly like an event (M4 brief section 6). Still never null: a question
 * has nothing to open and still does not enter the queue.
 *
 * [title] is the one thing both carry, the text a notification or a list may
 * name, so a reader of the queue that only wants a name does not branch.
 */
sealed interface Pending {
    val title: Field<String>

    data class Event(val draft: EventDraft) : Pending {
        override val title: Field<String> get() = draft.title
    }

    data class Note(val note: CapturedNote) : Pending {
        override val title: Field<String> get() = note.body
    }

    /**
     * An everyday-assistant sentence kept while locked. M9 section 6: the
     * locked screen shows the words back and answers nothing, but the
     * question itself may wait for the unlock exactly as a draft does, and
     * [dev.maia.app.flow.Session.openQueued] hands it to the answer surface
     * once the keyguard is gone. It is a command and not an answer: nothing
     * is read, asked or opened while the phone is still locked.
     */
    data class Command(val command: AssistantCommand) : Pending {
        override val title: Field<String> get() = Field(command.spoken, Provenance.Heard)
    }
}

/**
 * The lock screen's text for a draft, from the draft and nothing else.
 *
 * Pure, and takes one argument for a reason: a two-argument version that also
 * took the target would compile, and then one day someone would pass it.
 */
fun lockedSummary(draft: EventDraft): LockedSummary = LockedSummary(
    title = draft.title.value,
    whenText = whenText(draft),
    transcript = draft.transcript,
)

/**
 * The lock screen's text for a question that was heard but cannot be answered
 * while locked, which is section 4.3: the words are shown back, and there is no
 * when and no what, because answering would mean reading the calendar.
 */
fun questionSummary(transcript: String): LockedSummary =
    LockedSummary(title = transcript, whenText = "", transcript = transcript)

/**
 * The lock screen's text for a note: its body, and its reminder time when the
 * sentence carried one (M4 brief section 6). From the note alone, for the same
 * reason [lockedSummary] takes one argument, and a note has no folder in it to
 * leak. Named apart from [lockedSummary] by user decision (2026-09-13), so that
 * M3's `LockedSummaryTest` still finds exactly one `lockedSummary`.
 */
fun noteSummary(note: CapturedNote): LockedSummary = LockedSummary(
    title = note.body.value,
    whenText = note.remindAt?.let { whenText(it.value, allDay = false) }.orEmpty(),
    transcript = note.transcript,
)

/**
 * Design owns every word here (open question D4). These are placeholders so
 * that the machine can be finished and tested before the copy exists, and each
 * one is a single constant so that replacing it is one edit and not a search.
 */
object LockedCopy {
    /** Section 2.2: a second invocation over a card in hand, refused. */
    const val CARD_IN_HAND = "finish or discard this card first"

    /** Section 4.2: the sixth locked capture, refused before the microphone opens. */
    const val QUEUE_FULL = "too many drafts are waiting for an unlock"

    /**
     * Section 4.2's inferred-style note, shown only when the day rolled over
     * between the sentence and the unlock, so that a date resolved last night
     * does not read as a mistake this morning. The time is appended.
     */
    const val SAID_AT = "said at "
}

/**
 * The note a queued draft carries into the card when the day has changed under
 * it, and null when it has not.
 *
 * "Tomorrow" said at 23:50 and unlocked at 00:10 resolved at 23:50 and stays
 * resolved: the draft is not re-parsed, because re-parsing would silently move
 * the event a day and the user would have no way to know. What the card does
 * instead is say when the sentence was spoken, which is the one fact that makes
 * the resolved date make sense again.
 *
 * The zone comes from the draft's own resolved start rather than from the
 * device, so this stays pure and so that a draft resolved in one zone is not
 * judged against another.
 */
fun saidAtNote(heardAt: Long, now: Long, zone: ZoneId): String? {
    val heard = Instant.ofEpochMilli(heardAt).atZone(zone)
    val opened = Instant.ofEpochMilli(now).atZone(zone)
    if (heard.toLocalDate() == opened.toLocalDate()) return null
    return LockedCopy.SAID_AT + CLOCK_TIME.format(heard)
}

/**
 * A resolved start as plain text.
 *
 * Twenty-four hour and English month names, which is a placeholder and not a
 * decision: the locale-aware version belongs with the rest of the card's
 * formatting once design has said what the locked pose looks like. It is fixed
 * here so that the tests assert a string and not the box's default locale.
 */
private fun whenText(draft: EventDraft): String = whenText(draft.start.value, draft.allDay)

private fun whenText(start: ZonedDateTime, allDay: Boolean): String {
    val day = DAY.format(start)
    return if (allDay) "$day, all day" else "$day at ${CLOCK_TIME.format(start)}"
}

private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)
private val CLOCK_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
