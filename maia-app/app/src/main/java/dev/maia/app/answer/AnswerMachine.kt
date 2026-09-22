package dev.maia.app.answer

import dev.maia.app.feel.Schedule
import dev.maia.app.flow.AssistantCommand
import dev.maia.nlu.Intent

/**
 * The most of an answer the voice will say, and the most sentences of it.
 *
 * Section 3.5 bounds the spoken form at two sentences and 400 characters. The
 * bound belongs to the response schema, but a local answer is not checked by
 * that schema and a remote one is untrusted until the phone has measured it,
 * so the cap is applied here again at the only place the spoken string is
 * chosen.
 */
const val SPEECH_CAP_SENTENCES = 2
const val SPEECH_CAP_CHARS = 400

/**
 * The one honest line under the status while a devbox ask is in flight.
 *
 * A literal and not a resource key, because that is what the codebase's copy
 * convention is: `LockedCopy` and `FlowCopy` carry decided English as named
 * constants for exactly the same reason, and section 10's queue copy says
 * "waiting behind other work" without ever estimating.
 */
const val QUEUED_NOTE = "waiting behind other work"

/**
 * The note while the on-device model is being tried instead of the devbox.
 * Named for the same reason as [QUEUED_NOTE]: one honest line, decided here,
 * never an estimate. Loading a 3 GB model takes seconds, so there is a real
 * stretch of Working where this is the true thing to say.
 */
const val LOCAL_NOTE = "the devbox can't be reached; answering on this phone"

data class AnswerStep(val state: AnswerState, val effects: List<AnswerEffect> = emptyList())

/**
 * The answer surface as a pure function of [AnswerState], [AnswerEvent] and
 * `now`. No Android, no clock of its own, no channel: every consequence is
 * an [AnswerEffect] the driver runs and reports back on, exactly as the flow
 * and run machines are arranged.
 *
 * It is a class where its siblings are top-level functions, for one reason:
 * the two things it must remember are not drawable. `RunMachine` puts the
 * stream's health beside the state in `RunSession` because it is not drawn;
 * here the same job is done by fields, because [AnswerStep] carries only the
 * [AnswerState] the screen draws and the effects. What the machine remembers:
 *
 *  - [conversation], the process-scoped session (PRD section 7). Injected,
 *   so a test can pre-load history and watch it expire by moving `now`.
 *  - [lastCommand], so `Try again` on a failure can re-issue the exact
 *   command rather than a paraphrase of it.
 *  - [pendingHandoff], the intent sitting in a preview. [AnswerState] carries
 *   the spec the screen describes; the intent is what fires on confirm, and
 *   it is not the screen's to hold.
 *
 * An event that does not apply in a state returns the state unchanged. That
 * is the codebase's late-result rule: a remote answer landing after a stop,
 * or a read finishing after the user asked something else, lands on a state
 * that does not listen for it.
 */
class AnswerMachine(
    val conversation: ConversationSession = ConversationSession(),
) {

    /** The command last issued, so [AnswerEvent.Retry] can send it again. */
    private var lastCommand: AssistantCommand? = null

    /** The intent a [AnswerStatus.Previewing] card is holding for [AnswerEvent.Confirmed]. */
    private var pendingHandoff: Intent? = null

    fun reduceAnswer(state: AnswerState, event: AnswerEvent, now: Long): AnswerStep = when (event) {
        is AnswerEvent.Command -> command(state, event.command, now)
        // A typed sentence is already decided: it takes the ask road whole.
        // The deterministic parse belongs to the flow, which is where the
        // microphone's transcripts are decided too.
        is AnswerEvent.Typed -> command(state, AssistantCommand.Ask(event.text, event.text), now)

        is AnswerEvent.ReadDone ->
            if (state.status == AnswerStatus.Working) readDone(state, event, now) else stay(state)
        is AnswerEvent.ReadFailed ->
            if (state.status == AnswerStatus.Working) failed(state, event.fault) else stay(state)

        is AnswerEvent.RemoteAnswer ->
            if (state.status == AnswerStatus.Working) remoteAnswer(state, event, now) else stay(state)
        is AnswerEvent.LocalAnswer ->
            if (state.status == AnswerStatus.Working) {
                // The local model sends no short form of its own, so the
                // spoken cap is applied here as it is for a local read.
                answered(state, event.text, shortForSpeech(event.text), AnswerSource.PhoneModel, now)
            } else {
                stay(state)
            }
        AnswerEvent.AnsweringLocally ->
            if (state.status == AnswerStatus.Working) {
                AnswerStep(state.copy(note = LOCAL_NOTE))
            } else {
                stay(state)
            }
        // A remote proposal is validated data, not a consequence (section 5):
        // it walks the same road a locally parsed Act walks, preview included.
        is AnswerEvent.RemoteAction ->
            if (state.status == AnswerStatus.Working) {
                act(state, event.intent, AnswerSource.Devbox)
            } else {
                stay(state)
            }
        is AnswerEvent.LocalAction ->
            if (state.status == AnswerStatus.Working) {
                act(state, event.intent, AnswerSource.Phone)
            } else {
                stay(state)
            }
        AnswerEvent.RemoteBusy ->
            if (state.status == AnswerStatus.Working) failed(state, AnswerFault.Busy) else stay(state)
        AnswerEvent.RemoteUnreachable ->
            if (state.status == AnswerStatus.Working) failed(state, AnswerFault.Unreachable) else stay(state)
        AnswerEvent.RemoteNotSetUp ->
            if (state.status == AnswerStatus.Working) failed(state, AnswerFault.NotSetUp) else stay(state)
        AnswerEvent.RemoteUnusable ->
            if (state.status == AnswerStatus.Working) failed(state, AnswerFault.Unusable) else stay(state)

        AnswerEvent.HandoffOpened ->
            if (state.status == AnswerStatus.Working && state.handoff != null) {
                AnswerStep(state.copy(status = AnswerStatus.HandedOff, note = null))
            } else {
                stay(state)
            }
        // Section 11: the populated action stays on screen; only the claim
        // changes. [state.handoff] is deliberately not cleared.
        AnswerEvent.HandoffNoTarget ->
            if (state.status == AnswerStatus.Working && state.handoff != null) {
                failed(state, AnswerFault.NoTarget)
            } else {
                stay(state)
            }

        AnswerEvent.Confirmed -> confirmed(state)
        AnswerEvent.SpeechEnded -> AnswerStep(state.copy(spokenText = null))
        AnswerEvent.StopAsked -> stop(state)
        AnswerEvent.Retry -> retry(state, now)
        AnswerEvent.ClearAsked -> clear(state)
    }

    /**
     * A new sentence, whatever it interrupts.
     *
     * The finished exchange, if there is one, collapses into [AnswerState.earlier]
     * first: this is the moment an exchange stops being current, and folding
     * here rather than at answer time is what keeps the just-answered exchange
     * off its own history row.
     */
    private fun command(state: AnswerState, command: AssistantCommand, now: Long): AnswerStep {
        lastCommand = command
        pendingHandoff = null
        val base = foldCompleted(state).copy(
            spoken = command.spoken,
            answer = "",
            spokenText = null,
            source = null,
            handoff = null,
            note = null,
            fault = null,
        )
        val prefix = interrupting(state)
        return when (command) {
            is AssistantCommand.Read -> AnswerStep(
                base.copy(status = AnswerStatus.Working),
                prefix + AnswerEffect.RunRead(command.intent),
            )
            is AssistantCommand.Act -> act(base, command.intent, source = null, prefix = prefix)
            is AssistantCommand.Ask -> AnswerStep(
                base.copy(status = AnswerStatus.Working, note = QUEUED_NOTE),
                prefix + AnswerEffect.AskRemote(command.prompt, conversation.history(now)),
            )
        }
    }

    /**
     * The handoff road, shared by [AssistantCommand.Act] and
     * [AnswerEvent.RemoteAction].
     *
     * Section 6: timers and alarms preview first and fire only on confirm;
     * every other handoff fires at once. A read intent arriving by this road
     * (a remote action can propose one, section 8.3) is still a read: it
     * emits [AnswerEffect.RunRead], because asking Android to open an agenda
     * would land on the honest "no app accepted it" for a thing that was
     * never an app.
     */
    private fun act(
        state: AnswerState,
        intent: Intent,
        source: AnswerSource?,
        prefix: List<AnswerEffect> = emptyList(),
    ): AnswerStep = when (intent) {
        is Intent.SetTimer, is Intent.SetAlarm -> {
            pendingHandoff = intent
            AnswerStep(
                state.copy(
                    status = AnswerStatus.Previewing,
                    handoff = specFor(intent),
                    source = source,
                    note = null,
                    fault = null,
                ),
                prefix,
            )
        }
        is Intent.Agenda, is Intent.Availability, is Intent.Calculate, is Intent.DeviceFact ->
            AnswerStep(
                state.copy(status = AnswerStatus.Working, source = source, note = null, fault = null),
                prefix + AnswerEffect.RunRead(intent),
            )
        else -> AnswerStep(
            state.copy(
                status = AnswerStatus.Working,
                handoff = specFor(intent),
                source = source,
                note = null,
                fault = null,
            ),
            prefix + AnswerEffect.RunHandoff(intent),
        )
    }

    /**
     * The preview's one verb: fire what is on the card.
     *
     * The intent is the machine's, not the state's: [AnswerState.handoff] is
     * the description, and a state that could re-fire an action from a
     * description would be one more place the two could disagree.
     */
    private fun confirmed(state: AnswerState): AnswerStep {
        if (state.status != AnswerStatus.Previewing) return stay(state)
        val intent = pendingHandoff ?: return stay(state)
        pendingHandoff = null
        return AnswerStep(state.copy(status = AnswerStatus.Working), listOf(AnswerEffect.RunHandoff(intent)))
    }

    /**
     * A local read produced its text. The whole text is shown; the first
     * breath of it is said.
     */
    private fun readDone(state: AnswerState, event: AnswerEvent.ReadDone, now: Long): AnswerStep {
        conversation.record(state.spoken, event.text, now)
        val short = shortForSpeech(event.text)
        return AnswerStep(
            state.copy(
                status = AnswerStatus.Answered,
                source = event.source,
                answer = event.text,
                spokenText = short,
                note = null,
                fault = null,
            ),
            listOf(AnswerEffect.Speak(short)),
        )
    }

    /**
     * The devbox answered in words. The gateway's own short form is used when
     * it sent one; the display text is spoken whole otherwise, which is the
     * reply contract's own rule (`AssistantReply.Answer.spoken` is null when
     * there is nothing to shorten).
     */
    private fun remoteAnswer(state: AnswerState, event: AnswerEvent.RemoteAnswer, now: Long): AnswerStep =
        answered(state, event.text, event.spoken ?: event.text, AnswerSource.Devbox, now)

    /**
     * A text answer landed, from whichever side produced it. Records the
     * exchange either way: a local answer joins the same conversation the
     * next remote ask would carry, because the user saw it as one thread.
     * [say] is the spoken form already chosen by the caller: the gateway's
     * own short form, or the machine's cap for a source that has none.
     */
    private fun answered(
        state: AnswerState,
        text: String,
        say: String,
        source: AnswerSource,
        now: Long,
    ): AnswerStep {
        conversation.record(state.spoken, text, now)
        return AnswerStep(
            state.copy(
                status = AnswerStatus.Answered,
                source = source,
                answer = text,
                spokenText = say,
                note = null,
                fault = null,
            ),
            listOf(AnswerEffect.Speak(say)),
        )
    }

    /**
     * An ending without an answer. The transcript stays: section 11 keeps the
     * question on screen for every failure, and [AnswerState.spoken] is that
     * keeping. The fault, not a new status, says which honest sentence shows.
     */
    private fun failed(state: AnswerState, fault: AnswerFault): AnswerStep = AnswerStep(
        state.copy(
            status = AnswerStatus.Failed,
            fault = fault,
            note = null,
            spokenText = null,
        ),
        listOf(AnswerEffect.Feel(Schedule.fault)),
    )

    /**
     * "Stop", "stop speaking", or a new invocation landing on something live.
     *
     * Working halts both the wire and the voice, because either could be the
     * thing in flight and the effects are cheap when wrong. Previewing simply
     * drops the card: nothing has fired, so there is nothing to cancel.
     * Answered and HandedOff release the voice. Failed only leaves the fault,
     * keeping the transcript, because the question is still the thing the
     * retry control needs.
     */
    private fun stop(state: AnswerState): AnswerStep = when (state.status) {
        AnswerStatus.Working -> {
            pendingHandoff = null
            AnswerStep(
                state.copy(
                    status = AnswerStatus.Idle,
                    handoff = null,
                    note = null,
                    spokenText = null,
                ),
                listOf(AnswerEffect.CancelRemote, AnswerEffect.StopSpeaking),
            )
        }
        AnswerStatus.Previewing -> {
            pendingHandoff = null
            AnswerStep(
                state.copy(status = AnswerStatus.Idle, handoff = null, note = null),
            )
        }
        AnswerStatus.Answered, AnswerStatus.HandedOff -> AnswerStep(
            state.copy(status = AnswerStatus.Idle, spokenText = null),
            listOf(AnswerEffect.StopSpeaking),
        )
        AnswerStatus.Failed -> AnswerStep(state.copy(status = AnswerStatus.Idle))
        AnswerStatus.Idle -> stay(state)
    }

    /**
     * "Try again" after a failure: the same command, sent again. The failed
     * exchange was never recorded and never folded, so the session and the
     * history row see one exchange, not a question and a half.
     */
    private fun retry(state: AnswerState, now: Long): AnswerStep {
        if (state.status != AnswerStatus.Failed) return stay(state)
        val command = lastCommand ?: return stay(state)
        return command(state, command, now)
    }

    /**
     * "Clear this conversation". The wipe is total and local: the session,
     * the history rows, the transcript and the remembered command all go,
     * and [AnswerEffect.ClearSession] tells the driver the same wipe reached
     * anything it was keeping on its own side.
     */
    private fun clear(state: AnswerState): AnswerStep {
        conversation.clear()
        lastCommand = null
        pendingHandoff = null
        val effects = buildList {
            add(AnswerEffect.ClearSession)
            if (state.status == AnswerStatus.Working) add(AnswerEffect.CancelRemote)
            if (state.spokenText != null) add(AnswerEffect.StopSpeaking)
        }
        return AnswerStep(AnswerState(), effects)
    }

    /**
     * What a new command must halt before it starts.
     *
     * [AnswerEffect.CancelRemote] when anything is in flight, because the
     * driver no-ops it when nothing remote was running and a stale remote
     * answer must not land mid-question. [AnswerEffect.StopSpeaking] when the
     * state says the voice is live: section 3.1's interrupt stops output
     * immediately and keeps everything that was shown.
     */
    private fun interrupting(state: AnswerState): List<AnswerEffect> = buildList {
        if (state.status == AnswerStatus.Working) add(AnswerEffect.CancelRemote)
        if (state.spokenText != null) add(AnswerEffect.StopSpeaking)
    }

    /**
     * Push the finished exchange onto the collapsed rows.
     *
     * "Finished" is [AnswerState.answer] non-blank: a handoff or a failure
     * leaves no shown answer, so nothing is folded for it, which is also why
     * a failed question's words are not half-kept in a history row. Capped at
     * the session's cap so the screen's history and the wire's history age
     * out together.
     */
    private fun foldCompleted(state: AnswerState): AnswerState =
        if (state.answer.isBlank()) {
            state
        } else {
            state.copy(
                earlier = (state.earlier + Exchange(state.spoken, state.answer))
                    .takeLast(ConversationSession.MAX_PAIRS),
            )
        }

    private fun stay(state: AnswerState): AnswerStep = AnswerStep(state)
}

/**
 * The spoken form of a shown answer: the first one or two sentences, inside
 * [SPEECH_CAP_CHARS] characters.
 *
 * A sentence ends on a terminator followed by whitespace or the end of the
 * text, so "3.14" and "e.g." do not read as endings. When the character cap
 * binds mid-sentence the cut lands on the last space inside it rather than
 * mid-word; only a run with no spaces is cut hard, and 400 characters of
 * unbroken text is a string nobody meant to say anyway.
 *
 * Written here rather than borrowed from `core-audio`'s `SpeechChunks`: that
 * splitter exists to feed a voice model fixed-size pieces, while this is the
 * product bound itself, and the answer package carries no dependency on the
 * audio module.
 */
fun shortForSpeech(text: String): String {
    val trimmed = text.trim()
    var sentences = 0
    var i = 0
    var end = trimmed.length
    while (i < trimmed.length) {
        if (trimmed[i] in SENTENCE_ENDS) {
            var j = i + 1
            // Closing quotes and brackets belong to the sentence they close.
            while (j < trimmed.length && trimmed[j] in SENTENCE_CLOSERS) j++
            if (j >= trimmed.length || trimmed[j].isWhitespace()) {
                sentences++
                if (sentences >= SPEECH_CAP_SENTENCES) {
                    end = j
                    break
                }
                i = j
                continue
            }
        }
        i++
    }
    val within = trimmed.substring(0, end)
    if (within.length <= SPEECH_CAP_CHARS) return within
    val window = within.substring(0, SPEECH_CAP_CHARS)
    val cut = window.lastIndexOf(' ').takeIf { it > 0 } ?: SPEECH_CAP_CHARS
    return window.substring(0, cut).trimEnd()
}

private const val SENTENCE_ENDS = ".!?"
private const val SENTENCE_CLOSERS = "\"')]}”’"
