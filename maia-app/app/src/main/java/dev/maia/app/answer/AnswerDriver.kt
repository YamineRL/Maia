package dev.maia.app.answer

import dev.maia.app.feel.Pattern
import dev.maia.app.flow.AssistantCommand
import dev.maia.audio.speech.Speaker
import dev.maia.nlu.assistant.RemotePlan
import dev.maia.nlu.assistant.WeatherAsk
import dev.maia.transport.AssistantClient
import dev.maia.transport.AssistantReply
import dev.maia.transport.Turn
import java.io.Closeable
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A spoken or typed sentence, all the way to an answer on the answer screen.
 *
 * The same shape as [dev.maia.app.agent.AgentDriver] and for the same
 * reason: [AnswerMachine.reduceAnswer] is where every decision about what
 * the screen says lives, and what is here is the part a pure function
 * cannot do. A read goes to [LocalReader], a handoff to the
 * [HandoffRunner], a remote question to [AssistantClient] over the tunnel,
 * a spoken answer to the [speaker], and every result comes back as an
 * [AnswerEvent] through the same machine.
 *
 * **The machine owns the conversation.** `AnswerEffect.AskRemote` carries
 * the history the `ConversationSession` kept, so none of it lives here:
 * the driver forwards the list verbatim and a `ClearSession` effect needs
 * nothing from this class at all.
 *
 * Every public method returns at once and does its work on [work]. The
 * suspending halves (a provider read, the gateway's answer) run on the
 * scope built over that same executor, so an event and an effect can never
 * race on two threads, and the machine is only ever touched from one.
 */
class AnswerDriver(
    private val reader: LocalReader,
    private val handoffs: HandoffRunner,
    /**
     * The devbox client, built on first ask and null while the phone cannot
     * make one: unpaired, or no assistant credential stored. A lambda so the
     * tunnel is only ever opened by a question that needs it.
     */
    private val assistantFor: () -> AssistantClient?,
    /**
     * The on-device model, built on the first ask the devbox cannot answer
     * and null while the phone cannot make one. Same lazy shape as
     * [assistantFor]: a 3 GB resident model is only ever paid for by a
     * question that needs it.
     */
    private val converserFor: () -> LocalConverser? = { null },
    private val render: (AnswerState) -> Unit,
    /**
     * The voice. An `AnswerSpeaker` in production, typed [Speaker] here: the
     * one thing this class may hand answer text to, and nothing else in the
     * class produces a string it could say.
     */
    private val speaker: Speaker,
    private val feel: (Pattern) -> Unit,
    private val clock: () -> Long,
    private val work: Executor,
    /**
     * Where the suspending work runs. Defaults to [work] as a dispatcher,
     * which keeps the single-thread guarantee the executor gives; a test
     * passes its own to stay on the calling thread.
     */
    scope: CoroutineScope? = null,
    /**
     * The pure machine. Injected so a test can pre-load a
     * [ConversationSession] and watch history and expiry from the outside.
     */
    private val machine: AnswerMachine = AnswerMachine(),
    /**
     * Where a devbox miss is written down: the fault and the client's
     * fixed-string cause, never a word of the question or the answer.
     * Without it a phone that quietly answers from its own model looks
     * exactly like a working pairing.
     */
    private val trace: (String) -> Unit = {},
    /** The city set in Settings, for a weather question that names none. */
    private val homeCity: () -> String? = { null },
) : Closeable {

    private val lock = Any()

    /** The screen's state, replaced whole by every step. */
    private var state: AnswerState = AnswerState()

    private val scope = scope ?: CoroutineScope(SupervisorJob() + work.asCoroutineDispatcher())

    /** The one in-flight remote ask. A new one replaces it. */
    private var remote: Job? = null

    /** The speech in flight, so `StopSpeaking` can find it. */
    private var speech: Job? = null

    /** The devbox client, once built. Closed with the driver. */
    private var assistant: AssistantClient? = null

    /** The on-device model, once built. Closed with the driver. */
    private var converser: LocalConverser? = null

    /**
     * Rises each time the remote ask moves on, so a reply that lands after a
     * cancel or a newer question is dropped rather than applied to a state
     * it no longer belongs to. The machine ignores a late event too; this
     * keeps it from ever being asked to.
     */
    private var remoteGeneration: Int = 0

    // ------------------------------------------------------------- in

    /**
     * One `AssistantCommand` from the flow, opaque here: the machine reads
     * its kind and decides which effect answers it.
     */
    fun handle(command: AssistantCommand) = work.execute { apply(AnswerEvent.Command(command)) }

    /** The previewed handoff, confirmed by the user's tap or hold. */
    fun confirm() = work.execute { apply(AnswerEvent.Confirmed) }

    /** A typed follow-up from the answer screen's field. Same road as speech. */
    fun typed(text: String) = work.execute { apply(AnswerEvent.Typed(text)) }

    /** "Try again" after a failure. The transcript survives; the ask repeats. */
    fun retry() = work.execute { apply(AnswerEvent.Retry) }

    /** "Clear this conversation". Local only; nothing crosses the tunnel. */
    fun clear() = work.execute { apply(AnswerEvent.ClearAsked) }

    /**
     * Stop, from the screen's control or a new invocation. What the machine
     * cancels arrives back here as `CancelRemote` and `StopSpeaking`, so
     * this method itself touches nothing.
     */
    fun stop() = work.execute { apply(AnswerEvent.StopAsked) }

    /**
     * The answer screen is in front of the user.
     *
     * A deliberate no-op: the M8 screen used this to dismiss a standing
     * notification, and the answer surface has none to dismiss. The method
     * exists so the screen's lifecycle has its hook the day a microphone or
     * a notification needs it.
     */
    fun seen() = Unit

    /**
     * The answer screen went away.
     *
     * Speech stops: a voice that keeps reading an answer nobody can see is
     * the pocket problem M8's `Hidden` event was written for, and the same
     * fix is applied here without a new event. `SpeechEnded` goes through
     * the machine so the state stops claiming speech it no longer has. The
     * conversation is untouched: leaving the screen does not clear it, or a
     * power-button follow-up would be impossible (PRD section 7).
     */
    fun hidden() = work.execute {
        if (speech != null) {
            speech?.cancel()
            speech = null
            apply(AnswerEvent.SpeechEnded)
        }
    }

    /** What the screen currently holds, for a surface that has just been created. */
    fun current(): AnswerState = synchronized(lock) { state }

    /**
     * Whether the session still holds an exchange a follow-up could attach to.
     *
     * Asked by the effect runner at parse time, off [work], so it takes
     * [lock] like [apply] does: the machine and the session it owns are only
     * ever touched under it. Expiry is the session's own read, which is why
     * the ten minutes counted here are the same ten the next ask would see.
     */
    fun conversationLive(): Boolean =
        synchronized(lock) { machine.conversation.history(clock()).isNotEmpty() }

    override fun close() {
        synchronized(lock) { remoteGeneration++ }
        remote?.cancel()
        speech?.cancel()
        // Children only: an injected scope belongs to its caller.
        scope.coroutineContext.cancelChildren()
        // The client closes its channel, and the channel clears the
        // credential on the Go side before it closes anything. The
        // converser gives the resident model back to the phone.
        runCatching { assistant?.close() }
        runCatching { converser?.close() }
    }

    // -------------------------------------------------------- the reduce

    /** Runs one event through the machine and then its effects, in order. */
    private fun apply(event: AnswerEvent) {
        val step = synchronized(lock) {
            val next = machine.reduceAnswer(state, event, clock())
            state = next.state
            next
        }
        render(step.state)
        for (effect in step.effects) perform(effect)
    }

    private fun perform(effect: AnswerEffect) {
        when (effect) {
            is AnswerEffect.RunRead -> runRead(effect.intent)
            is AnswerEffect.RunHandoff -> runHandoff(effect.intent)
            is AnswerEffect.AskRemote -> askRemote(effect.prompt, effect.history)
            AnswerEffect.CancelRemote -> cancelRemote()
            is AnswerEffect.Speak -> speak(effect.text)
            AnswerEffect.StopSpeaking -> stopSpeaking()
            is AnswerEffect.Feel -> feel(effect.pattern)
            // The session the machine just dropped is its own field; there
            // is nothing here to clear. The effect exists so the driver's
            // half of a clear (an in-flight ask about the old context)
            // could be stopped, which the machine emits separately as
            // CancelRemote.
            AnswerEffect.ClearSession -> Unit
        }
    }

    // ----------------------------------------------------------- a read

    private fun runRead(intent: dev.maia.nlu.Intent) = scope.launch {
        val event = try {
            // Provider and platform reads block, so they run on Default and
            // only the result comes back to the work thread.
            when (val read = withContext(Dispatchers.Default) { reader.read(intent) }) {
                is LocalRead.Done -> AnswerEvent.ReadDone(read.text, read.source)
                is LocalRead.Failed -> AnswerEvent.ReadFailed(read.fault)
            }
        } catch (e: CancellationException) {
            return@launch
        } catch (e: Exception) {
            // A collaborator that throws must not leave the screen in
            // Working forever: it is a failed read, reported like one.
            AnswerEvent.ReadFailed(AnswerFault.Unusable)
        }
        apply(event)
    }

    // -------------------------------------------------------- a handoff

    private fun runHandoff(intent: dev.maia.nlu.Intent) = scope.launch {
        val event = try {
            when (val outcome = withContext(Dispatchers.Default) { handoffs.execute(intent) }) {
                HandoffOutcome.Opened -> AnswerEvent.HandoffOpened
                HandoffOutcome.NoTarget -> AnswerEvent.HandoffNoTarget
                // The contract's one fault event carries it: a permission
                // the handoff needs is `AnswerFault.Permission` on the
                // state whichever road it arrived by. The grant was checked
                // before anything fired, which is what the spec's
                // `needsCamera` asks of this side.
                is HandoffOutcome.Permission -> AnswerEvent.ReadFailed(AnswerFault.Permission(outcome.which))
            }
        } catch (e: CancellationException) {
            return@launch
        } catch (e: Exception) {
            // A binder or service death mid-handoff is the same honest
            // answer as no target: nothing on the phone took the intent.
            AnswerEvent.HandoffNoTarget
        }
        apply(event)
    }

    // --------------------------------------------------------- the remote

    private fun askRemote(prompt: String, history: List<Turn>) {
        var buildFailed = false
        val client = assistant ?: runCatching { assistantFor() }
            .getOrElse {
                // Building the channel threw: identity load or the Go
                // constructor failed. The phone was set up to ask and
                // cannot, which is unreachable rather than not set up.
                buildFailed = true
                null
            }
            ?.also { assistant = it }
        val generation = ++remoteGeneration
        remote = scope.launch {
            val event = try {
                askRemoteThenLocal(client, buildFailed, prompt, history, generation)
            } catch (e: CancellationException) {
                // The user's own "stop", which the machine already knows.
                return@launch
            }
            if (generation == remoteGeneration) apply(event)
        }
    }

    /**
     * The devbox first, the phone's own model only when the devbox cannot
     * answer at all.
     *
     * Which endings fall through is decided by the fault each would have
     * produced, not by the transport detail behind it: `RemoteUnreachable`
     * (tunnel down, gateway down, the bound hit) and `RemoteNotSetUp`
     * (unpaired, no credential, a refused credential, or a client that
     * could not even be built) both mean "the devbox road does not exist
     * for this question", and the local model is the honest next thing.
     * `Busy` is deliberately not one of them: the devbox is reachable and
     * merely occupied, and a queued retry to the bigger model is worth more
     * than an instant smaller answer. `Unusable` is not either: the gateway
     * answered and its words failed checking, which a different model's
     * words would not fix.
     *
     * [generation] guards the `AnsweringLocally` note: a stop or a newer
     * question landing between the remote failure and the model's first
     * token must not redraw a screen that has moved on.
     */
    private suspend fun askRemoteThenLocal(
        client: AssistantClient?,
        buildFailed: Boolean,
        prompt: String,
        history: List<Turn>,
        generation: Int,
    ): AnswerEvent {
        // The cause, in the transport's own fixed words or an exception's
        // class name: never a word of the question or a reply body. Traced
        // for every outcome that is not a plain answer, so a phone that
        // quietly answers from its own model never looks like a working
        // pairing.
        var cause = "not paired"
        val remote = if (client == null) {
            // Unpaired, or no assistant credential, or the channel could
            // not be built: all three reach the fault without a tunnel
            // ever being opened.
            if (buildFailed) {
                cause = "the client could not be built"
                AnswerEvent.RemoteUnreachable
            } else {
                AnswerEvent.RemoteNotSetUp
            }
        } else {
            val reply = try {
                client.chat(
                    prompt,
                    history,
                    locale = Locale.getDefault().toLanguageTag(),
                    timezone = ZoneId.systemDefault().id,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A client that throws must not leave the screen working
                // forever: it is unreachable, reported like one.
                cause = "the client threw ${e.javaClass.simpleName}"
                AssistantReply.Unavailable("cannot reach the assistant")
            }
            // A reach failure's detail is the transport's own words; every
            // other cause may quote the gateway's body, so only its label.
            if (reply is AssistantReply.Unavailable) {
                val reach = reply.detail.startsWith("cannot reach") || reply.detail.startsWith("no answer after")
                cause = if (reach) reply.detail else reply.detail.substringBefore(':')
            }
            remoteEvent(reply)
        }
        when (remote) {
            AnswerEvent.RemoteUnreachable, AnswerEvent.RemoteNotSetUp ->
                trace("devbox could not answer: $cause")
            AnswerEvent.RemoteBusy -> trace("devbox busy")
            AnswerEvent.RemoteUnusable -> trace("devbox answered unusably: $cause")
            else -> {}
        }
        if (remote != AnswerEvent.RemoteUnreachable && remote != AnswerEvent.RemoteNotSetUp) {
            return remote
        }
        // The phone's model has no live data, so on weather it could only
        // say so. A forecast for the place named beats that, and needs no
        // model at all.
        WeatherAsk.of(prompt, runCatching(homeCity).getOrNull())?.let { return AnswerEvent.LocalAction(it) }
        val local = converser ?: runCatching { converserFor() }
            .getOrNull()
            ?.also { converser = it }
        if (local == null || !local.installed) return remote
        if (generation == remoteGeneration) apply(AnswerEvent.AnsweringLocally)
        val text = try {
            local.reply(prompt, history)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A model that throws mid-generation is the same honest ending
            // as one that was never installed: the remote fault stands.
            null
        }
        return if (text.isNullOrBlank()) remote else AnswerEvent.LocalAnswer(text)
    }

    private fun cancelRemote() {
        remoteGeneration++
        remote?.cancel()
        remote = null
        // A coroutine cancel cannot unblock a native generation call; this
        // is the verb that actually stops it.
        runCatching { converser?.cancel() }
    }

    // --------------------------------------------------------- the voice

    /**
     * Says the answer's spoken text.
     *
     * `Speak` is the only effect that reaches [speaker], which is the type
     * system's whole never-spoken rule in one line: nothing else in this
     * class could put a string in a voice. A completion, clean or thrown,
     * is `SpeechEnded`; a cancellation is not reported, because whoever
     * cancelled already told the machine.
     */
    private fun speak(text: String) {
        speech?.cancel()
        speech = scope.launch {
            try {
                // The envelope is the orb's Speaking pose: collecting it is
                // what plays, and cancelling the collection is the silence.
                speaker.speak(text).collect { }
            } catch (e: CancellationException) {
                return@launch
            } catch (e: Exception) {
                // A voice that fails is not a fault worth a screen: the
                // answer is already on it, and section 11's row for this is
                // "show the answer and remain silent".
            }
            apply(AnswerEvent.SpeechEnded)
        }
    }

    private fun stopSpeaking() {
        speech?.cancel()
        speech = null
    }
}

/**
 * The gateway's reply as the machine's next event.
 *
 * An `Action` payload is remote untrusted data: it goes through
 * [RemotePlan.validate], which is the whole of section 8.4's phone-side
 * checking, and only a validated intent becomes `RemoteAction`. A
 * validated `answer` shape inside an action reply is still just text.
 *
 * `Unavailable`'s [AssistantReply.Unavailable.detail] is one line of cause
 * the client composed from fixed strings, so classifying on those strings
 * is honest: a reach failure and a refused credential name different fixes.
 * A reply the gateway produced but could not be used (unparseable, empty,
 * unknown, or its own "unavailable") is `RemoteUnusable`; anything else,
 * timeouts and non-answer statuses included, is `RemoteUnreachable`, which
 * is what "the devbox cannot be reached" means to the user.
 */
internal fun remoteEvent(reply: AssistantReply): AnswerEvent = when (reply) {
    is AssistantReply.Answer -> AnswerEvent.RemoteAnswer(reply.text, reply.spoken)
    is AssistantReply.Action -> when (val plan = RemotePlan.validate(reply.payload)) {
        is RemotePlan.Answer -> AnswerEvent.RemoteAnswer(plan.text)
        is RemotePlan.Act -> AnswerEvent.RemoteAction(plan.intent)
        RemotePlan.Rejected -> AnswerEvent.RemoteUnusable
    }
    AssistantReply.Busy -> AnswerEvent.RemoteBusy
    is AssistantReply.Unavailable -> when {
        reply.detail.startsWith("wrong or missing credential") -> AnswerEvent.RemoteNotSetUp
        reply.detail.startsWith("unparseable") ||
            reply.detail.startsWith("an answer with no text") ||
            reply.detail.startsWith("an unknown reply") ||
            reply.detail.startsWith("the gateway said unavailable") -> AnswerEvent.RemoteUnusable
        else -> AnswerEvent.RemoteUnreachable
    }
}
