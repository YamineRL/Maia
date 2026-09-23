package dev.maia.app.flow

import dev.maia.actions.CalendarTarget
import dev.maia.actions.notes.Note
import dev.maia.actions.notes.NoteRef
import dev.maia.actions.notes.NotesFolder
import dev.maia.audio.Word
import dev.maia.nlu.EventDraft
import dev.maia.nlu.Intent
import java.time.ZonedDateTime

/**
 * Where the one sentence loop is, as the handoff's nine stages and nothing else.
 *
 * Each state carries exactly what its screen draws and what the next step needs,
 * so a screen never reaches past its state into a flag somewhere else. That is
 * the failure M1's `UiState` had: a card error, a hold progress and a committed
 * id all alive at once, and only the `screen` field saying which of them meant
 * anything.
 */
sealed interface FlowState {

    /** Models absent. Nothing can be heard until they are on the phone. */
    data class FirstRun(val download: Download = Download()) : FlowState

    /**
     * Resting. [transcript] is the last sentence heard when nothing was done
     * with it, a note or an agenda question before M4, or a card that was
     * discarded, so the words are still on screen rather than silently gone.
     */
    data class Idle(val transcript: String? = null) : FlowState

    /**
     * The press has landed and capture is starting. [pressedAt] times the
     * first word; [surface] is the modal screen the invocation came from,
     * carried unchanged to [Effect.Parse].
     */
    data class Invoking(val pressedAt: Long, val surface: Surface = Surface.Neutral) : FlowState

    data class Listening(
        val pressedAt: Long,
        val partial: String = "",
        val words: List<Word> = emptyList(),
        /** The first-partial haptic plays once per session, and this is "once". */
        val firstPartialFelt: Boolean = false,
        /** Set at the press, like [Invoking.surface]; the capture only carries it. */
        val surface: Surface = Surface.Neutral,
    ) : FlowState

    /**
     * The sentence is complete and being read. [intent] arrives in single-digit
     * milliseconds but is only acted on once [since] plus [UNDERSTAND_FLOOR_MS]
     * has passed, so the user sees what they said before the card replaces it.
     */
    data class Understanding(
        val transcript: String,
        val since: Long,
        val intent: Intent? = null,
        /**
         * The wall-clock moment of the parse, from [FlowEvent.Parsed.at]. A note
         * is named by it (M4 brief section 3.2) and the reducer has no wall
         * clock of its own. Null only for a parse a test built by hand.
         */
        val at: ZonedDateTime? = null,
    ) : FlowState

    data class Preview(
        val draft: EventDraft,
        /** Null while the provider is being asked, or when it could not be read. */
        val target: CalendarTarget? = null,
        /** 0 to 1 across the 600 ms hold. Only ever driven by a finger. */
        val hold: Float = 0f,
        /** How many of the hold's haptic steps have already played in this press. */
        val holdSteps: Int = 0,
        val error: String? = null,
        /**
         * Set only when this card was opened from the queue after the day rolled
         * over, and then it is [saidAtNote]'s "said at 23:50". Null on every
         * ordinary card, including every card opened the same day it was spoken.
         */
        val heardNote: String? = null,
    ) : FlowState

    /**
     * Heard while locked, and kept. M3 brief section 2.3.
     *
     * This is what a locked [Understanding] becomes instead of [Preview], and it
     * is the only place a locked sentence can land. The road to a write runs
     * [Preview] -> [Committing] and nothing else, and the only edge into
     * [Preview] from here is [FlowEvent.Unlocked], which clears the lock as it
     * goes. So "no write while locked" is not a check that could be forgotten,
     * it is a shape: there is no arrow.
     *
     * [draft] is null for a read-back question (section 4.3), which was heard
     * and understood but cannot be answered without reading the calendar. The
     * screen shows the words back and says to unlock; nothing is spoken.
     * [summary] is everything the screen may draw, and [heardAt] is when the
     * sentence was heard, on the reducer's `now` clock.
     *
     * M4 adds [note]: a note heard while locked is kept exactly like an event
     * (M4 brief section 6), so a kept screen holds a [draft] or a [note], and a
     * question holds neither.
     */
    data class Queued(
        val draft: EventDraft?,
        val heardAt: Long,
        val summary: LockedSummary,
        val note: Note? = null,
        /**
         * An everyday-assistant sentence kept while locked, for M9. It joins
         * the queue exactly as a draft does and is handed to the answer
         * surface on unlock; nothing about it was read or asked while the
         * keyguard was up.
         */
        val command: AssistantCommand? = null,
    ) : FlowState

    /** There is nowhere writable to put the event. Commit is blocked, not deferred. */
    data class NoCalendar(val draft: EventDraft) : FlowState

    /**
     * The note card, M4 brief section 5.1. Shaped like [Preview] so the hold and
     * its haptic ladder are the same rule: [folder] is null while it is being
     * asked for, and a hold at 1 with no folder in hand resets rather than writes.
     */
    data class NotePreview(
        val note: Note,
        val folder: NotesFolder? = null,
        /** 0 to 1 across the same 600 ms hold as the event card. */
        val hold: Float = 0f,
        val holdSteps: Int = 0,
        val error: String? = null,
    ) : FlowState

    /**
     * There is no folder to put the note in, or the one there was has gone. The
     * note is held, and a folder arriving brings its card back. Shaped like
     * [NoCalendar].
     */
    data class NoFolder(val note: Note) : FlowState

    data class Committing(val draft: EventDraft, val target: CalendarTarget) : FlowState {
        val calendarId: Long get() = target.calendar.id
    }

    /** The append is on its way. [folder] is carried so the screen can say where. */
    data class Writing(val note: Note, val folder: NotesFolder) : FlowState

    data class Confirmed(
        val draft: EventDraft,
        /** The id the write returned. The only thing undo may ever delete. */
        val eventId: Long,
        /** [UNDO_WINDOW_MS] after the write completed, not after the hold began. */
        val undoDeadline: Long,
        val undo: UndoStatus = UndoStatus.Offered,
        val speaking: Boolean = false,
    ) : FlowState

    /**
     * The note is in the file [ref] names.
     *
     * M4 brief section 5.2 reuses [Confirmed] here. It is a state of its own
     * instead, because [Confirmed] carries an event draft and an event id that
     * M2's tests read, and a note has neither. There is no undo field, which is
     * the brief's "offers no undo" as a shape: deleting a line would be the
     * read-modify-write that section 3.1 forbids.
     */
    data class NoteConfirmed(val note: Note, val ref: NoteRef) : FlowState

    data class Fault(
        val reason: FaultReason,
        /** Kept verbatim. A fault never costs the user what they said. */
        val transcript: String,
        val draft: EventDraft? = null,
    ) : FlowState
}

/** First run's download, in bytes, because the screen shows byte counts and not a bar alone. */
data class Download(
    val file: String = "",
    val bytesDone: Long = 0,
    /** Null until the server has said how big the set is. */
    val bytesTotal: Long? = null,
    val running: Boolean = false,
    val error: String? = null,
)

enum class UndoStatus {
    Offered,
    /** The delete is in flight. */
    Undoing,
    Undone,
    /** The delete found nothing: the user or a sync removed it first. Never shown as an undo. */
    AlreadyGone,
    /** The delete threw. The event may still exist, so the screen must not claim otherwise. */
    Failed,
    /** The window closed. The affordance leaves and the event stays. */
    Expired,
}

sealed interface FaultReason {
    /** 4g: the sentence was an event but carried no date. See [noDateHeard]. */
    data object NoDateHeard : FaultReason

    /** The recogniser or the microphone failed while listening. */
    data class CaptureFailed(val message: String) : FaultReason

    /**
     * M3 brief section 4.2: five drafts are already waiting for an unlock, so
     * the sixth locked invocation is refused rather than heard. A pocket that
     * invokes itself all afternoon costs five drafts and then nothing.
     */
    data object QueueFull : FaultReason

    /** M4: the append failed for a reason other than the folder going away. */
    data class NoteWriteFailed(val message: String) : FaultReason

    /**
     * M4: the folder grant was revoked or the folder deleted. Declared with the
     * rest of interface I3. The reducer shows this case as [FlowState.NoFolder],
     * which keeps the note (criterion J12), so nothing raises a fault with it yet.
     */
    data object FolderGone : FaultReason
}
