package dev.maia.app.flow

import dev.maia.actions.CalendarTarget
import dev.maia.actions.notes.NoteRef
import dev.maia.actions.notes.NotesFolder
import dev.maia.audio.Word
import dev.maia.nlu.Edit
import dev.maia.nlu.Intent
import java.time.ZonedDateTime

/**
 * Everything that can happen to the flow: a finger, the recogniser, the parser,
 * the provider, the voice, or the clock.
 *
 * The results of effects come back as events too, so the reducer never waits on
 * anything and a test can deliver them in any order, including orders a phone
 * would only produce once a month.
 */
sealed interface FlowEvent {
    data object DownloadRequested : FlowEvent
    data class DownloadProgress(val file: String, val bytesDone: Long, val bytesTotal: Long?) : FlowEvent
    data class DownloadFailed(val message: String) : FlowEvent
    data object ModelsReady : FlowEvent

    /** `ACTION_DOWN` on the invoke control. The haptic is on the press, not the release. */
    data object Press : FlowEvent

    /**
     * M3 brief section 2.2: the same press, from a door that knows which door it
     * is and whether the keyguard was up when it was used.
     *
     * [locked] is read once, at invocation, from `KeyguardManager` by the host,
     * and from then on it is carried in state. The reducer never asks Android
     * what the keyguard is doing, which is what makes the locked rules provable
     * here rather than only on the phone.
     *
     * [family] is M4 brief section 5.4's hint: which kind of capture the door
     * the user reached for was labelled with. A defaulted field rather than a
     * second return type for `route`, so every M2 and M3 call site keeps
     * meaning exactly what it meant and the hint stays inside the event the
     * locked invariant already searches over. It is carried and not yet acted
     * on: see [CaptureFamily].
     */
    data class Invoke(
        val origin: Origin,
        val locked: Boolean,
        val family: CaptureFamily = CaptureFamily.Event,
    ) : FlowEvent

    /**
     * The keyguard is gone: either the user dismissed it themselves, or
     * [Effect.RequestUnlock] succeeded. A failed or cancelled unlock sends
     * nothing, so the locked screen stays exactly as it was.
     */
    data object Unlocked : FlowEvent

    /**
     * The host's window went away: the session was dismissed, or the screen
     * went off. Distinct from [Cancel], which is the user saying no. See
     * `hidden` in Session.kt for what does and does not react to it.
     */
    data object Hidden : FlowEvent

    /** The Unlock action on the locked screen. Answered by [Effect.RequestUnlock]. */
    data object UnlockRequested : FlowEvent
    data object CaptureStarted : FlowEvent
    data class PartialHeard(val text: String, val words: List<Word> = emptyList()) : FlowEvent
    data class FinalHeard(val text: String) : FlowEvent
    data class CaptureFailed(val message: String) : FlowEvent

    /** The user backs out, or leaves the screen. Both mean the same thing to the flow. */
    data object Cancel : FlowEvent

    /**
     * The parser's answer. [at] is the wall clock when the sentence was parsed,
     * stamped by the runner, because a note is filed under the moment it was
     * captured and the reducer's `now` is not a wall clock. Null only in a
     * hand-built test event; a note parsed without it is not filed (see `route`).
     */
    data class Parsed(val intent: Intent, val at: ZonedDateTime? = null) : FlowEvent

    /** A [Effect.ScheduleTick] coming due. Carries nothing: `now` is the time. */
    data object Tick : FlowEvent

    /** The provider's answer to [Effect.LoadTarget], or a choice made in the chooser. */
    data class TargetLoaded(val target: CalendarTarget?) : FlowEvent
    data class TargetUnreadable(val message: String) : FlowEvent

    data class Edited(val edit: Edit) : FlowEvent

    /** A chip or the picker on 4g. */
    data class DatePicked(val start: ZonedDateTime) : FlowEvent

    /** The hold's progress, 0 to 1. Reaching 1 is the commit; nothing else is. */
    data class Hold(val fraction: Float) : FlowEvent

    data class WriteSucceeded(val eventId: Long) : FlowEvent
    data class WriteFailed(val denied: Boolean, val message: String? = null) : FlowEvent

    data object Undo : FlowEvent

    /** The result of [Effect.DeleteEvent]: [existed] is what `delete` returned. */
    data class Deleted(val eventId: Long, val existed: Boolean) : FlowEvent
    data class DeleteFailed(val eventId: Long, val message: String? = null) : FlowEvent

    /** The answer to [Effect.LoadNotesFolder]. Null means no folder is held, which is a screen. */
    data class NotesFolderLoaded(val folder: NotesFolder?) : FlowEvent

    /** The result of [Effect.WriteNote]. */
    data class NoteWritten(val ref: NoteRef) : FlowEvent

    /**
     * [Effect.WriteNote] did not land. [folderGone] is true when the folder
     * itself went away, which keeps the note on the no-folder screen rather
     * than faulting (criterion J12).
     */
    data class NoteWriteFailed(val message: String, val folderGone: Boolean = false) : FlowEvent

    /**
     * "Try again" on the note write fault (`docs/M4-copy.md` section 2). Reopens
     * the note card with the words intact and never re-appends by itself, so the
     * hold is still the only thing that writes (privacy V3). The session reducer
     * answers it, because the failed note is kept there ([FlowSession.retryNote])
     * and not in [FlowState.Fault], whose shape the M4 row 5 tests pin.
     */
    data object RetryNote : FlowEvent

    /**
     * Decision U2, amended: "Keep as a note instead" on the event card. Opens
     * the note card for the same words and writes nothing (M4 row 8).
     *
     * [at] is the moment the note is filed under, stamped by whoever sends the
     * event, for the same reason [Parsed.at] is: the reducer has no wall clock,
     * only [now]. The event card does not remember when its sentence was
     * parsed, so the tap's own time is used, which is also the day whose file
     * the card goes on to name.
     */
    data class KeepAsNote(val at: ZonedDateTime) : FlowEvent

    /**
     * The note card's body row, changed by hand (M4 row 8, D1: "same edit
     * behaviour" as the event card). The body becomes
     * [dev.maia.nlu.Provenance.Corrected]. A blank [text] is ignored rather
     * than written, because an empty note is a write the storage layer refuses.
     */
    data class NoteBodyEdited(val text: String) : FlowEvent

    data object SpeechStarted : FlowEvent
    data object SpeechEnded : FlowEvent
}

/**
 * Which door was used. M3 gives Maia four of them and they all reach one flow.
 *
 * The origin is not a mode: every door starts the same capture. It is kept
 * because the hosts differ in what they own (only the assistant holds a system
 * window that has to be told to go away, which is [Effect.HideSession]) and
 * because P3's latency budget is measured per door.
 */
enum class Origin { Assistant, Tile, Shortcut, Launcher }

/**
 * Which kind of capture the door was labelled with. M4 brief section 5.4.
 *
 * The Quick note shortcut says the user reached for a note. That is a hint
 * about the door and **not** a claim about the sentence: brief section 5.4 is
 * explicit that "the origin does not change what a sentence means", so a
 * sentence spoken through the Quick note door that parses as an event still
 * opens the event card and is not forced into a file.
 *
 * Which is why nothing reads this yet, and why that is the correct amount of
 * code for M4 row 9. The moment the family were allowed to decide that a bare
 * sentence parses as a note rather than as an event, it would stop being a
 * hint carried on an event and become a second grammar, reaching through
 * [FlowState.Listening] and [FlowState.Understanding] into `:core-nlu`. That
 * is a larger change than row 9, it was stopped at this line deliberately, and
 * `docs/M4-status.md` says so.
 */
enum class CaptureFamily { Event, Note }

/**
 * M2's [FlowEvent.Press] read as what it always was: a launcher invocation on
 * an unlocked phone.
 *
 * M2's screens send `Press` and keep sending it. Rather than a second code path
 * for the same thing, everything that decides what an invocation does asks this
 * question, and `Press` answers it identically to `Invoke(Launcher, false)`.
 * Null for every event that is not an invocation.
 */
fun FlowEvent.asInvoke(): FlowEvent.Invoke? = when (this) {
    is FlowEvent.Invoke -> this
    FlowEvent.Press -> PRESS_AS_INVOKE
    else -> null
}

private val PRESS_AS_INVOKE = FlowEvent.Invoke(Origin.Launcher, locked = false)
