package dev.maia.app

import android.content.Context
import android.media.AudioManager
import android.util.Log
import android.os.SystemClock
import dev.maia.actions.CalendarRepository
import dev.maia.actions.CalendarWriteDenied
import dev.maia.actions.ProviderCalendars
import dev.maia.actions.notes.FakeNotes
import dev.maia.actions.notes.NoteRepository
import dev.maia.actions.notes.SafNotes
import dev.maia.app.agent.AgentAck
import dev.maia.app.agent.AgentDriver
import dev.maia.app.agent.AgentHost
import dev.maia.app.feel.Haptics
import dev.maia.app.feel.Schedule
import dev.maia.app.flow.AgentCommand
import dev.maia.app.flow.Effect
import dev.maia.app.flow.EffectRunner
import dev.maia.app.flow.FlowEvent
import dev.maia.app.flow.Origin
import dev.maia.app.flow.loadNotesFolder
import dev.maia.app.flow.writeNote
import dev.maia.app.screens.RunCopy
import dev.maia.audio.Dictation
import dev.maia.audio.Transcript
import dev.maia.audio.speech.DeferredSpeaker
import dev.maia.audio.speech.RingerAwareSpeaker
import dev.maia.audio.speech.SilentSpeaker
import dev.maia.audio.speech.Speaker
import dev.maia.nlu.Parser
import dev.maia.nlu.speech.Confirmation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.ZonedDateTime

/**
 * Every effect the reducer can ask for, done for real.
 *
 * This is the only class in the flow that knows there is a microphone, a
 * calendar provider, a motor or a voice. Everything above it is pure, and that
 * is the whole arrangement: the rules live where a JVM test can reach them and
 * the Android lives in one file that cannot be tested on this box and therefore
 * must not contain a rule.
 *
 * Three things it deliberately does not do. It does not decide anything, so
 * there is no `when` here on a [dev.maia.app.flow.FlowState]. It does not hold
 * the flow, so nothing here reads back what the machine is doing. And it never
 * sees the three host effects, which the controller answers, so a window's
 * business cannot leak in here.
 *
 * Every collaborator is a constructor parameter rather than a lookup, because
 * the alternative is a class that can only be exercised by installing the app.
 *
 * @param scope where the long-running halves live. The controller's scope,
 *   which outlives every window: a write must not be cancelled because the user
 *   put the phone in their pocket.
 * @param hotwords the per-stream contact biasing M0 built. Empty until the
 *   contacts decision (out of bounds at M3, `READ_CONTACTS`), and a lambda so
 *   the day it is not empty nothing here changes.
 * @param notes where notes go. [FakeNotes] with no folder by default, so a
 *   caller that says nothing writes nothing; [forApp] passes the real
 *   [SafNotes]. Either way a note reaches the no-folder screen until a folder
 *   has been picked, and nothing is written or pretended to be.
 */
private const val TAG = "MaiaFlow"

class AndroidEffects(
    private val context: Context,
    private val scope: CoroutineScope,
    private val haptics: Haptics,
    private val repository: CalendarRepository,
    private val parser: Parser,
    private val speaker: Speaker,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val hotwords: () -> List<String> = { emptyList() },
    private val notes: NoteRepository = FakeNotes(folder = null),
    /**
     * Null until a passphrase has been paired. M8 PRD section 9's answer to an
     * unpaired phone is that the agent surface is simply absent, so an agent
     * sentence with no driver does nothing rather than opening a screen that
     * explains a feature the user has not set up.
     *
     * A lambda rather than a value because pairing happens while this object
     * is alive. Held as a field it would be null for the life of the process
     * for anyone who pairs after the first sentence, which is everyone.
     */
    private val agent: () -> AgentDriver? = { null },
    /**
     * The answer surface, M9. A lambda for the same reason [agent] is one:
     * the driver is built on first use, which is after the sentence, not at
     * startup. Unlike the agent it is never null for pairing reasons: local
     * reads and handoffs work on a phone that has never seen the devbox.
     */
    private val answer: () -> dev.maia.app.answer.AnswerDriver? = { null },
) : EffectRunner {

    private val _micLevel = MutableStateFlow(0f)
    override val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    private val _speech = MutableStateFlow(0f)
    override val speech: StateFlow<Float> = _speech.asStateFlow()

    /**
     * One of each, because there is one of each thing they drive. A second
     * capture job would be a second claim on the microphone, which is the exact
     * failure the process-scoped controller exists to prevent, so the field is
     * the enforcement rather than a comment asking callers to be careful.
     */
    private var capture: Job? = null
    private var speaking: Job? = null
    private var tick: Job? = null
    private var warming: Job? = null

    override suspend fun run(effect: Effect, origin: Origin?, send: (FlowEvent) -> Unit) {
        when (effect) {
            Effect.DownloadModels -> download(send)
            Effect.StartCapture -> startCapture(send)
            is Effect.StopCapture -> stopCapture()
            is Effect.Haptic -> haptic(effect, origin)
            // Stamped here, on the same clock the parser resolves against, so a
            // note is filed under the day it was said and not the day it lands.
            //
            // Wrapped since the phone sitting turned up an Understanding hang
            // (2026-09-14, open item A) with nothing in logcat; this is the
            // one place that dropped Parsed answer could originate, so a
            // throw here is now logged and degrades to Unparsed rather than
            // silently vanishing. [Reduce.UNDERSTAND_TIMEOUT_MS] is the
            // backstop if this still is not the whole story.
            is Effect.Parse -> {
                val intent = try {
                    parser.parse(effect.text)
                } catch (e: Exception) {
                    Log.e(TAG, "parser threw on a final transcript, falling back to unparsed", e)
                    dev.maia.nlu.Intent.Unparsed(
                        dev.maia.nlu.EventDraft(
                            title = dev.maia.nlu.Field(effect.text, dev.maia.nlu.Provenance.Heard),
                            start = dev.maia.nlu.Field(ZonedDateTime.now(clock), dev.maia.nlu.Provenance.Inferred),
                            duration = dev.maia.nlu.Field(java.time.Duration.ofHours(1), dev.maia.nlu.Provenance.Inferred),
                            allDay = false,
                            transcript = effect.text,
                        ),
                    )
                }
                send(FlowEvent.Parsed(intent, ZonedDateTime.now(clock)))
            }
            // The date is passed so the storage layer can resolve today's file
            // now, while the card is being read, rather than under the hold:
            // the spike's G6 put the append at 15 ms and the children query
            // that finds the file at 345 ms and up. Same clock as the parse,
            // so the day warmed is the day the note will be filed under.
            Effect.LoadNotesFolder ->
                scope.launch { send(loadNotesFolder(notes, ZonedDateTime.now(clock).toLocalDate())) }
            is Effect.WriteNote -> scope.launch { send(writeNote(notes, effect.note)) }
            Effect.LoadTarget -> loadTarget(send)
            // Off the main-immediate dispatcher effects run on, and suspended
            // rather than launched, so the step's effect order is unchanged.
            // The hop exists for the first call: `agent()` builds the driver
            // when none exists, and that build is the 19 MB libgojni load,
            // the Go runtime starting, [dev.maia.app.agent.NetFacts.push] and
            // two tunnel channels. Seconds of work, under the finger that
            // just finished speaking. Once the driver exists the hop buys
            // nothing, because every method on it dispatches to the driver's
            // own executor and owns the minutes that follow, which is why
            // nothing here is launched into [scope]: a four-minute agent turn
            // is not an effect's lifetime.
            is Effect.RunAgent -> withContext(Dispatchers.Default) {
                when (val command = effect.command) {
                    is AgentCommand.Instruct ->
                        agent()?.instruct(command.project, command.instruction, command.spoken)
                    is AgentCommand.Focus -> agent()?.focus(command.project, command.spoken)
                    // A bare "stop" belongs to whichever surface has
                    // something live to halt. The grammar owns the word
                    // for the agent, but a remote ask in flight, a
                    // preview card, or a voice mid-sentence is what the
                    // user is looking at; `claimsStop` is false on a
                    // settled card so the run surface still hears it.
                    // `current()` rather than `answer()`: a stop aimed at
                    // the run must not build the answer driver to ask it.
                    AgentCommand.Stop -> dev.maia.app.answer.AnswerHost.current()
                        ?.takeIf { it.current().claimsStop }
                        ?.stop()
                        ?: agent()?.stop()
                    // Answered from what the phone already holds. Asking the
                    // server would be a second source of truth about a stream
                    // this process is holding open.
                    AgentCommand.Status -> Unit
                }
            }
            // The same door, one surface over. The command is opaque to this
            // class: what the grammar decided is executed by the answer
            // driver, and what comes back is drawn on the answer screen.
            is Effect.Assist -> withContext(Dispatchers.Default) {
                answer()?.handle(effect.command)
            }
            is Effect.WriteEvent -> write(effect, send)
            is Effect.DeleteEvent -> delete(effect, send)
            is Effect.Speak -> speak(send) { Confirmation.sentence(effect.draft, ZonedDateTime.now(clock)) }
            // One word and nothing from the note (M4 D4, U7). Through the same
            // speaker, so the ringer and silent rules are the event's.
            Effect.SpeakNote -> speak(send) { context.getString(R.string.m4_note_spoken_confirmation) }
            Effect.StopSpeaking -> {
                speaking?.cancel()
                speaking = null
                _speech.value = 0f
            }
            is Effect.ScheduleTick -> scheduleTick(effect, send)

            // The controller answers these three. Reaching one here means a
            // window's effect was routed to the process, which is a wiring
            // mistake and not something to paper over with a no-op.
            Effect.RequestUnlock, Effect.HideSession, Effect.ShowSurface, is Effect.PostDraftWaiting ->
                error("host effect reached the runner: $effect")
        }
    }

    /**
     * The 70 MB model set, and the load that follows it.
     *
     * [EngineHolder] already owns both and already reports progress, so this
     * subscribes rather than downloading a second time. The subscription lives
     * for as long as the download does and then ends, so a first run that fails
     * and is retried gets a fresh one instead of two.
     */
    private fun download(send: (FlowEvent) -> Unit) {
        if (warming?.isActive == true) return
        warming = scope.launch {
            val mirror = launch {
                EngineHolder.warmth.collect { warmth ->
                    when (warmth) {
                        is Warmth.Downloading -> send(
                            FlowEvent.DownloadProgress(warmth.file, warmth.bytes, warmth.totalBytes.takeIf { it >= 0 }),
                        )
                        is Warmth.Failed -> send(FlowEvent.DownloadFailed(warmth.message))
                        Warmth.Ready -> send(FlowEvent.ModelsReady)
                        Warmth.Cold, Warmth.Loading -> Unit
                    }
                }
            }
            try {
                EngineHolder.engine(context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                send(FlowEvent.DownloadFailed(e.message ?: e.toString()))
            } finally {
                mirror.cancel()
            }
        }
    }

    /**
     * Open the microphone and report what is heard.
     *
     * The engine is awaited rather than peeked at, so an invocation that lands a
     * few hundred milliseconds before a load finishes waits for it instead of
     * failing. A load that has already finished returns at once, which is what
     * the warmth policy in [dev.maia.app.flow.warmthFor] is buying.
     *
     * `EngineHolder.capture` and not a new one: the warmth path reserves an
     * unstarted recorder on that instance, and a fresh [dev.maia.audio.AudioCapture]
     * here would throw the reservation away and pay for the microphone twice.
     */
    private fun startCapture(send: (FlowEvent) -> Unit) {
        // M9 PRD section 9: a new invocation interrupts the answer voice
        // before the microphone opens. `stop` is a no-op on a driver with
        // nothing live, and calling the factory here also pre-warms the
        // driver so the sentence about to be heard finds it built.
        answer()?.stop()
        val previous = capture
        capture = scope.launch {
            // A cancel is asynchronous and the microphone is released in the old
            // job's finally block. Starting before that finishes finds the
            // recorder still held and reports "another app may hold it", which
            // is a false diagnosis: the other app is this one.
            previous?.join()
            try {
                val engine = EngineHolder.engine(context)
                // Kicks off the 670 MB second-pass download on first-ever
                // capture. No-op once loaded; readiness is checked at the
                // endpoint, so a model loaded during speech can rescore it.
                EngineHolder.warmRescorer(context)
                val dictation = Dictation(
                    recognizer = engine,
                    capture = EngineHolder.capture,
                    rescorer = EngineHolder::rescorerOrNull,
                )
                val levels = launch { dictation.level.collect { _micLevel.value = it } }
                send(FlowEvent.CaptureStarted)
                try {
                    dictation.run(hotwords()).collect { event ->
                        when (val transcript = event.transcript) {
                            is Transcript.Partial ->
                                send(FlowEvent.PartialHeard(transcript.text, transcript.words))

                            is Transcript.Final -> send(FlowEvent.FinalHeard(transcript.text))
                        }
                    }
                } finally {
                    levels.cancel()
                    _micLevel.value = 0f
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                send(FlowEvent.CaptureFailed(e.message ?: e.toString()))
            }
        }
    }

    /**
     * Close the microphone.
     *
     * [Effect.StopCapture.discardAudio] is not read, and that is not an
     * oversight: whether the words are used is decided by the reducer, which
     * either kept them in a state or did not. Acting on it here would be a
     * second place deciding the same thing.
     *
     * The unreserve is the release half of the warmth policy (brief section 3.2
     * and research R2). The role keeps this process alive for as long as it is
     * held, so a reservation nobody starts would otherwise live until the phone
     * reboots.
     */
    private fun stopCapture() {
        capture?.cancel()
        capture = null
        _micLevel.value = 0f
        EngineHolder.capture.unreserve()
    }

    /**
     * Decision D3: the assistant's invoke pulse is suppressed.
     *
     * A long-press of the power key has already made the phone buzz by the time
     * `onShow` arrives, and a second pulse on top of the system's reads as a
     * stutter rather than as an acknowledgement. Every other door is silent
     * until Maia says something, so every other door keeps its pulse. Only the
     * invoke pattern is affected: a fault or a commit still has to be felt no
     * matter which door was used.
     */
    private fun haptic(effect: Effect.Haptic, origin: Origin?) {
        if (origin == Origin.Assistant && effect.pattern == Schedule.invoke) return
        haptics.play(effect.pattern)
    }

    private fun loadTarget(send: (FlowEvent) -> Unit) {
        scope.launch {
            runCatching { repository.defaultTarget() }
                .onSuccess { send(FlowEvent.TargetLoaded(it)) }
                .onFailure {
                    send(FlowEvent.TargetUnreadable(it.message ?: "the calendar could not be read"))
                }
        }
    }

    private fun write(effect: Effect.WriteEvent, send: (FlowEvent) -> Unit) {
        scope.launch {
            runCatching { repository.commit(effect.draft, effect.calendarId) }
                .onSuccess { send(FlowEvent.WriteSucceeded(it)) }
                .onFailure {
                    send(FlowEvent.WriteFailed(denied = it is CalendarWriteDenied, message = it.message))
                }
        }
    }

    private fun delete(effect: Effect.DeleteEvent, send: (FlowEvent) -> Unit) {
        scope.launch {
            runCatching { repository.delete(effect.eventId) }
                .onSuccess { send(FlowEvent.Deleted(effect.eventId, it)) }
                .onFailure { send(FlowEvent.DeleteFailed(effect.eventId, it.message)) }
        }
    }

    /**
     * Say the confirmation, if anything is going to say it.
     *
     * The sentence is built here and not by the reducer, because it is language
     * and a clock; the reducer only decided that this is the moment. A speaker
     * that stays silent, which is every phone without the optional voice model
     * and every phone whose ringer is off, still reports start and end, so the
     * orb's Speaking pose lasts no time at all rather than hanging on a flow
     * that never emits.
     */
    private fun speak(send: (FlowEvent) -> Unit, sentenceFor: () -> String) {
        speaking?.cancel()
        speaking = scope.launch {
            val sentence = sentenceFor()
            send(FlowEvent.SpeechStarted)
            try {
                speaker.speak(sentence).collect { _speech.value = it }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A voice that failed is not a fault the user needs to see: the
                // event or the note is already written and the screen says so.
            } finally {
                _speech.value = 0f
                send(FlowEvent.SpeechEnded)
            }
        }
    }

    /**
     * One timer, replaced rather than stacked.
     *
     * The reducer schedules a tick for the understanding floor and again for the
     * undo deadline, and the two never overlap: the second is asked for after
     * the first has come due. A tick that arrives late into a state that has
     * moved on is ignored by the reducer, so a replaced timer costs nothing and
     * a leaked one would cost a wake-up.
     */
    private fun scheduleTick(effect: Effect.ScheduleTick, send: (FlowEvent) -> Unit) {
        tick?.cancel()
        tick = scope.launch {
            delay((effect.atMs - now()).coerceAtLeast(0))
            send(FlowEvent.Tick)
        }
    }

    companion object {
        /**
         * The production wiring.
         *
         * The voice is [SilentSpeaker] until the optional Piper model is on
         * the phone, which is M9's "refusing the download leaves everything
         * working" made structural. [DeferredSpeaker] asks the store on every
         * sentence rather than once at wiring time, so a voice downloaded
         * mid-process from the answer screen's offer speaks on the next
         * answer without a restart. The ringer policy wraps whichever voice
         * exists, so silent mode is silent whether or not the model was
         * fetched.
         */
        fun forApp(context: Context, scope: CoroutineScope): AndroidEffects {
            val app = context.applicationContext
            val audio = app.getSystemService(AudioManager::class.java)
            val voice = DeferredSpeaker.sherpa(java.io.File(app.filesDir, "voice"))
            val speaker = RingerAwareSpeaker(
                voice,
                ringerMode = { audio?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL },
            )
            return AndroidEffects(
                context = app,
                scope = scope,
                haptics = Haptics(app),
                repository = ProviderCalendars(app),
                parser = Parser(Clock.systemDefaultZone()),
                speaker = speaker,
                // The real storage. It writes nothing until a folder has
                // been picked, and a note lands on the no-folder screen while
                // `getPersistedUriPermissions()` holds no grant. Picking is
                // not done here: `FolderPicker` (row 7), hosted by
                // `MainActivity` and `SettingsActivity`, is the one caller of
                // `useFolder`, with the picker's Uri, and this instance reads
                // the same stored tree on its next `LoadNotesFolder`.
                notes = SafNotes(app),
                // Built on first use and null until the phone is paired. The
                // spoken half goes through the same speaker as everything
                // else, so there is one voice and one ringer policy, and it
                // receives an ack and two project numbers: there is no
                // parameter here an agent's own words could travel in.
                agent = {
                    val say: (AgentAck, Int?, Int?) -> Unit = { ack, number, other ->
                        val line = app.getString(
                            RunCopy.spoken(ack),
                            *RunCopy.spokenArgs(ack, number, other),
                        )
                        // A voice that fails is not a fault worth a screen: the
                        // same words are already on the run screen.
                        scope.launch { runCatching { speaker.speak(line).collect { } } }
                    }
                    // An unpaired phone still answers. Until copy section 5.15
                    // there were no words for this and the sentence fell on the
                    // floor, which the user hears as not having been heard: they
                    // say it again, louder, and it fails the same way. Nothing
                    // is built and no tunnel is opened, so the surface is still
                    // absent; what changes is that Maia says so and puts the way
                    // in on the screen.
                    AgentHost.driver(app, say) ?: run {
                        say(AgentAck.NotSetUp, null, null)
                        AgentHost.askToPair()
                        null
                    }
                },
                // Always builds: timers, reads and handoffs owe nothing to a
                // pairing. The devbox is asked only when a question needs it,
                // and the driver's own answer for an unpaired phone is the
                // honest "not set up" state rather than a missing surface.
                answer = { dev.maia.app.answer.AnswerHost.driver(app, speaker) },
            )
        }
    }
}
