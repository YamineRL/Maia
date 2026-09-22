package dev.maia.app.answer

import dev.maia.app.feel.Pattern
import dev.maia.nlu.Intent
import dev.maia.transport.Turn

/**
 * What the answer screen draws, and nothing else. M9.
 *
 * The same shape as [dev.maia.app.agent.RunState] and for the same reason: a
 * screen never reaches past its state into a flag somewhere else. It is a
 * separate value from `FlowState` because its life is the conversation's and
 * not the one-sentence loop's: a follow-up asks again without the microphone
 * ever reopening the sentence that came before.
 *
 * **Nothing agent-shaped can live here.** There is no field that can carry a
 * `RunState`, an `AgentEvent`, or a stream line, and that is a deliberate
 * absence rather than an oversight: M9's spoken answers and M8's never-spoken
 * rule meet only in types, and the meeting is that they cannot.
 */
data class AnswerState(
    /** The user's own sentence, drawn under `YOU ASKED`. */
    val spoken: String = "",
    val status: AnswerStatus = AnswerStatus.Idle,
    /**
     * Where the answer came from. The screen's source row is this and only
     * this: it never says "AI" and it never implies a check it did not do.
     */
    val source: AnswerSource? = null,
    /** The whole answer, append-only once it starts. */
    val answer: String = "",
    /** What the voice is saying, when it differs from [answer]. */
    val spokenText: String? = null,
    /** The handoff being previewed or just opened, as a spec the screen can describe. */
    val handoff: HandoffSpec? = null,
    /**
     * One honest line under the status: "waiting behind other work" while a
     * devbox request queues, "nothing on that day" for an empty agenda. Never
     * an estimate and never a claim the state does not carry.
     */
    val note: String? = null,
    /** Why there is no answer, when there is none. */
    val fault: AnswerFault? = null,
    /**
     * Earlier exchanges in this conversation, oldest first, collapsed for the
     * screen. Bounded by `ConversationSession`'s cap; nothing older survives
     * and nothing here is written to disk.
     */
    val earlier: List<Exchange> = emptyList(),
) {
    /** The status a screen reader announces and the orb mirrors. */
    val live: Boolean get() = status == AnswerStatus.Working || status == AnswerStatus.Previewing

    /**
     * Something a spoken "stop" can still halt: a read or ask in flight, a
     * card waiting on a hand, or a voice mid-sentence ([spokenText] is set
     * while the speech plays and cleared by `SpeechEnded`). An answered card
     * left on screen claims nothing, so a later "stop" still reaches the
     * agent run it was meant for.
     */
    val claimsStop: Boolean get() = live || spokenText != null
}

/** One question and the answer it got, for the collapsed history rows. */
data class Exchange(val asked: String, val answered: String)

enum class AnswerStatus {
    /** Nothing asked yet. */
    Idle,

    /** A read, a remote ask, or a handoff is in flight. */
    Working,

    /**
     * A consequential handoff is on screen waiting for the user's tap or
     * hold (section 6: timers and alarms get a compact preview first).
     */
    Previewing,

    /** An answer is on screen, quiet. */
    Answered,

    /** Android accepted the handoff and its own UI is in front. */
    HandedOff,

    /** The ask ended without an answer. [AnswerState.fault] says why. */
    Failed,
}

/** The source row's three honest values (section 10). */
enum class AnswerSource {
    /** Read or computed on the phone: time, battery, a calculation. */
    Phone,

    /** Read from the calendar provider. */
    Calendar,

    /** Answered by the paired devbox. */
    Devbox,

    /**
     * Generated on the phone by the local model, reached only when the
     * devbox cannot answer. Distinct from [Phone]: a read is looked up, this
     * is written by a model, and the source row says so.
     */
    PhoneModel,
}

/** Why [AnswerStatus.Failed] was reached, which is which honest sentence shows. */
sealed interface AnswerFault {
    /** The devbox is not paired or has no assistant credential. */
    data object NotSetUp : AnswerFault

    /** The tunnel or the gateway could not be reached. */
    data object Unreachable : AnswerFault

    /** The gateway's one slot stayed busy past the bound. Retry is offered. */
    data object Busy : AnswerFault

    /** The reply could not be used even after the gateway's own retry. */
    data object Unusable : AnswerFault

    /** The action had no Android target: no app accepted the intent. */
    data object NoTarget : AnswerFault

    /** A runtime permission is missing; [which] names what it blocks. */
    data class Permission(val which: PermNeeded) : AnswerFault
}

enum class PermNeeded { Calendar, Contacts, Camera }

/**
 * What the answer machine asks the world to do. Pure data, like
 * [dev.maia.app.flow.Effect]: the driver runs these and reports back with an
 * [AnswerEvent], and "an agent's words were never spoken" stays an assertion
 * on a list rather than a hope about a call site.
 */
sealed interface AnswerEffect {

    /** A local read: agenda, availability, device fact, calculation. */
    data class RunRead(val intent: Intent) : AnswerEffect

    /**
     * Fire the Android handoff this intent describes. Only ever emitted after
     * the preview rule has had its say, which is the machine's job.
     */
    data class RunHandoff(val intent: Intent) : AnswerEffect

    /**
     * Ask the devbox. [history] is the conversation the session kept, which
     * is only ever text the user saw.
     */
    data class AskRemote(val prompt: String, val history: List<Turn>) : AnswerEffect

    /** Cancel the in-flight remote ask. The user's own "stop". */
    data object CancelRemote : AnswerEffect

    /**
     * Say a conversational answer. This is the only effect that can reach
     * `AnswerSpeaker`, and it carries answer text and nothing else: the
     * never-spoken boundary is that nothing else can be put here.
     */
    data class Speak(val text: String) : AnswerEffect

    data object StopSpeaking : AnswerEffect

    data class Feel(val pattern: Pattern) : AnswerEffect

    /** Wipe the conversation session. "Clear this conversation", and nothing crosses the tunnel. */
    data object ClearSession : AnswerEffect
}

/** Everything that can happen to the answer surface. */
sealed interface AnswerEvent {

    /**
     * A sentence arrived from the flow, as the `AssistantCommand` the
     * grammar decided it was. [command] is carried opaquely: the machine
     * reads its kind, and the driver reads its fields.
     */
    data class Command(val command: dev.maia.app.flow.AssistantCommand) : AnswerEvent

    /** A local read produced its text. */
    data class ReadDone(val text: String, val source: AnswerSource) : AnswerEvent

    /** The read could not happen. */
    data class ReadFailed(val fault: AnswerFault) : AnswerEvent

    /** The gateway answered. [spoken] is the optional short form. */
    data class RemoteAnswer(val text: String, val spoken: String? = null) : AnswerEvent

    /**
     * The phone's own model answered, after the devbox could not. No
     * [RemoteAnswer.spoken] half: the local model writes one text and the
     * machine's own cap decides what is said.
     */
    data class LocalAnswer(val text: String) : AnswerEvent

    /**
     * The remote road failed and the on-device model is being tried. Stays
     * [AnswerStatus.Working]; only the note changes, so the screen can say
     * the quiet part while a model that takes seconds to load is loading.
     */
    data object AnsweringLocally : AnswerEvent

    /** The gateway proposed an action, already validated into an intent. */
    data class RemoteAction(val intent: Intent) : AnswerEvent

    /**
     * The devbox could not answer and the phone knows a better move than its
     * own model's words: a weather question opens a forecast for the place
     * named. Fired like any handoff, credited to the phone.
     */
    data class LocalAction(val intent: Intent) : AnswerEvent

    data object RemoteBusy : AnswerEvent
    data object RemoteUnreachable : AnswerEvent
    data object RemoteNotSetUp : AnswerEvent
    data object RemoteUnusable : AnswerEvent

    /** The intent fired and an activity came up. */
    data object HandoffOpened : AnswerEvent

    /** Nothing on the phone would take the intent. */
    data object HandoffNoTarget : AnswerEvent

    /** The user confirmed the previewed handoff. */
    data object Confirmed : AnswerEvent

    data object SpeechEnded : AnswerEvent

    /** "Stop", "Stop speaking", or a new invocation: halt whatever is live. */
    data object StopAsked : AnswerEvent

    /** "Ask another" or "Try again" after a failure: keep the transcript, ask again. */
    data object Retry : AnswerEvent

    /** "Clear this conversation". */
    data object ClearAsked : AnswerEvent

    /**
     * The user typed rather than spoke, which the answer screen's follow-up
     * field produces. Same road as a spoken sentence from here.
     */
    data class Typed(val text: String) : AnswerEvent
}

/**
 * A handoff as the screen describes it, without an Android `Intent` in sight:
 * what it is called, what it will open, and whether it needs the preview tap
 * first. The driver builds the real intent from the same spec.
 */
data class HandoffSpec(
    /** One line for the card: "Timer for 12 minutes", "Call Sam". */
    val label: String,
    /** Where it goes, for the source row: "Clock", "Phone", "Messages", "Maps", a browser. */
    val target: String,
    /** True for timer and alarm (section 6's compact preview). */
    val needsConfirm: Boolean,
    /**
     * True for the torch alone: `CameraManager.setTorchMode` wants `CAMERA`,
     * a dangerous permission asked only after the first-use explanation
     * (sections 3.2 and 12). The driver reads this and asks first; the
     * machine does not preview for it.
     */
    val needsCamera: Boolean = false,
)
