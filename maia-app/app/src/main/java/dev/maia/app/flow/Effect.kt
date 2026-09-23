package dev.maia.app.flow

import dev.maia.actions.notes.Note
import dev.maia.app.feel.Pattern
import dev.maia.nlu.Intent
import dev.maia.nlu.agent.ProjectRef
import dev.maia.nlu.EventDraft

/**
 * What the reducer asks the world to do. The view model runs these and reports
 * back with a [FlowEvent]; the reducer itself touches nothing.
 *
 * Kept as data so that a rule like "a cancel never speaks" is an assertion on a
 * list, not a mock verifying that a method was not called.
 */
sealed interface Effect {
    data object DownloadModels : Effect
    data object StartCapture : Effect

    /** [discardAudio] is true when the words must not be used: a cancel or a failure. */
    data class StopCapture(val discardAudio: Boolean) : Effect

    data class Haptic(val pattern: Pattern) : Effect
    /**
     * Parse the heard sentence. [surface] is which modal screen the
     * invocation came from, carried verbatim from the press: the runner
     * reads it to pick [dev.maia.nlu.Parser.parseAgentTurn] over the neutral
     * parse, which is the only thing the run screen changes about a
     * sentence.
     */
    data class Parse(val text: String, val surface: Surface = Surface.Neutral) : Effect

    /** Ask the provider where a commit would go. Answered by [FlowEvent.TargetLoaded]. */
    data object LoadTarget : Effect

    data class WriteEvent(val draft: EventDraft, val calendarId: Long) : Effect

    /** Only ever the id a write returned, never a title or a time. */
    data class DeleteEvent(val eventId: Long) : Effect

    /** Ask which notes folder is held. Answered by [FlowEvent.NotesFolderLoaded]. */
    data object LoadNotesFolder : Effect

    /**
     * Append one note to today's file. Answered by [FlowEvent.NoteWritten] or
     * [FlowEvent.NoteWriteFailed]. Emitted only from a hold on the note card,
     * which a locked session cannot reach (criterion J8).
     */
    data class WriteNote(val note: Note) : Effect

    /**
     * Say the confirmation. The draft rather than a sentence, because the
     * sentence is built by `:core-nlu` and the silence rule is applied by the
     * speaker; the reducer only decides that this is the moment.
     */
    data class Speak(val draft: EventDraft) : Effect

    /**
     * Say that a note was written: `m4_note_spoken_confirmation`, one word and
     * nothing from the note (`docs/M4-copy.md` section 4, U7). No payload on
     * purpose, so a note's words cannot reach a voice by this road. A separate
     * effect from [Speak] rather than a variant of it, because [Speak] carries
     * an event draft and every existing reader of it means an event. Emitted
     * only on [FlowEvent.NoteWritten] in [FlowState.Writing], which a locked
     * session cannot reach (criterion J16).
     */
    data object SpeakNote : Effect
    data object StopSpeaking : Effect

    /** Deliver [FlowEvent.Tick] at [atMs], on the same clock as `now`. */
    data class ScheduleTick(val atMs: Long) : Effect

    /**
     * Ask the host to dismiss the keyguard, which it does with
     * `KeyguardManager.requestDismissKeyguard`. Success arrives as
     * [FlowEvent.Unlocked]; a failure or a cancel arrives as nothing at all, so
     * the locked screen is left exactly as it was.
     */
    data object RequestUnlock : Effect

    /**
     * Ask the host to take its window down. Only the assistant session owns a
     * window that outlives the flow's interest in it; the other doors are
     * Activities that finish themselves.
     */
    data object HideSession : Effect

    /**
     * Take the window down and open the one that draws answers and runs.
     * Emitted in place of [HideSession] when an assistant session ends by
     * handing a sentence to one of those surfaces: they live only in
     * `MainActivity`, so a session that just hid would leave the reply
     * with nowhere to appear.
     */
    data object ShowSurface : Effect

    /**
     * Post, or update, the notification that says drafts are waiting for an
     * unlock (M3 brief section 4.5, open question U2, answered yes).
     *
     * [count] and nothing else, because the notification's public version is
     * what a lock screen shows and it may say only how many there are. There is
     * no field here for a title or a transcript, for the same reason
     * [LockedSummary] has no field for a calendar. A [count] of zero means
     * nothing is waiting any more and the notification is removed.
     */
    data class PostDraftWaiting(val count: Int) : Effect

    /**
     * Hand an agent sentence to the run surface. M8.
     *
     * One member rather than four, because the flow's job ends at the door:
     * every decision about projects, sessions, streams and what the screen
     * says lives in [dev.maia.app.agent.AgentDriver] and [dev.maia.app.agent.reduceRun],
     * and a flow that knew about sessions would be a flow with a four-minute
     * state in it.
     *
     * The payload is the user's own words and a [ProjectRef] the grammar
     * produced. Nothing that came off an agent stream can reach here: it is a
     * one-way door.
     */
    data class RunAgent(val command: AgentCommand) : Effect

    /**
     * Hand an everyday-assistant sentence to the answer surface. M9.
     *
     * Same shape as [RunAgent], for the same reason: the flow's job ends at
     * the door. Whether the answer is read off the calendar, computed, handed
     * to Android, or asked of the paired devbox is the answer surface's
     * decision, and its screen owns the seconds a remote answer can take.
     *
     * Nothing that comes back crosses this line the other way: what the
     * surface shows and says is its own, and a coding agent's words can never
     * arrive here at all, because the two surfaces share no channel.
     */
    data class Assist(val command: AssistantCommand) : Effect
}

/**
 * What the flow asks the answer surface to do. M9's three roads: something the
 * phone can read for itself, something Android can be handed, and something
 * only the devbox can answer.
 *
 * [Intent] is carried whole rather than unpacked here, because the grammar
 * that produced it already decided what it is; the surface reads the fields it
 * needs. [spoken] is the user's own sentence, for the screen's LAST HEARD line
 * and for nothing else.
 */
sealed interface AssistantCommand {

    /** The user's own sentence. Carried so a kept command can name itself. */
    val spoken: String

    /**
     * An answer the phone can produce alone: agenda, availability, a device
     * fact, a calculation. Never crosses the tunnel.
     */
    data class Read(val intent: Intent, override val spoken: String) : AssistantCommand

    /**
     * A user-visible Android handoff: timer, alarm, a settings panel, dial,
     * message compose, navigation, a search or site, an app, a media key.
     * The surface shows what it is about to open and opens it; nothing here
     * is silent.
     */
    data class Act(val intent: Intent, override val spoken: String) : AssistantCommand

    /**
     * A question the phone cannot answer, for the paired devbox assistant.
     * The prompt is the user's transcribed words and the recent conversation,
     * and that is everything that ever leaves.
     */
    data class Ask(val prompt: String, override val spoken: String) : AssistantCommand
}

/**
 * What the flow asks the agent surface to do, which is the whole of M8's four
 * intents and no more.
 */
sealed interface AgentCommand {

    /** [instruction] is what the user said to the agent, verbatim. */
    data class Instruct(val project: ProjectRef, val instruction: String, val spoken: String) : AgentCommand

    /** Move the stream. Nothing is sent, and the agent left behind keeps running. */
    data class Focus(val project: ProjectRef, val spoken: String) : AgentCommand

    /** `interrupt`. Not an undo. */
    data object Stop : AgentCommand

    /**
     * "What is it doing?" Answered from what the phone already has, because
     * the run screen is the answer and asking the server would be a second
     * source of truth about a stream this process is already holding.
     */
    data object Status : AgentCommand
}
