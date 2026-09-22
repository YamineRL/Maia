package dev.maia.audio

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transform
import kotlin.math.log10
import kotlin.math.sqrt

/** One observation from the pipeline, with the timing that produced it. */
data class DictationEvent(
    val transcript: Transcript,
    /**
     * Time from speech onset in the current utterance to its first partial.
     * Null until that partial arrives. PRD section 13 budgets 400 ms with an
     * 800 ms ceiling, and measures from onset rather than from the button, so
     * this does too. See [Dictation] on how onset is decided.
     */
    val firstPartialMs: Long?,
    /** Decode time over audio time for the session so far. Under 1.0 keeps up. */
    val realTimeFactor: Float,
)

/**
 * Microphone to text, which is the whole of M0.
 *
 * Everything here runs off the main thread: the microphone reads on the IO
 * dispatcher and the decode on Default. Nothing in this class touches the UI
 * or sherpa-onnx, which is what lets the same pipeline be driven by a test
 * harness feeding WAV files instead of a live mic.
 *
 * **On measuring from speech onset.** The acceptance criterion for M0 is time
 * from *speech onset* to the first partial, not from the stream opening. Those
 * differ by however long you sat there before talking, so timing from the
 * first frame reports a 2.4 second failure when the recogniser did nothing
 * wrong. Onset is taken here as the first frame whose RMS crosses
 * [ONSET_RMS], with the arrival of text as a backstop in case a quiet speaker
 * never crosses it.
 *
 * This is a marker and explicitly **not** a stop authority. The recogniser's
 * own endpointer remains the only thing that decides an utterance is over, for
 * the reason in [StreamingRecognizer]: two independent deciders racing to
 * close the same utterance is a bug that only appears under load.
 *
 * **On the second pass.** [rescorer] is looked up once per utterance, at the
 * moment the streaming engine closes it, and null is the normal state for a
 * first run before the rescore model has been downloaded: the utterance then
 * ships exactly as it did before this pass existed. The lookup is a function
 * rather than a field so the engine can finish loading mid-capture and the
 * next utterance picks it up; a mid-capture load is also why the lookup
 * happens on the utterance boundary and not once at construction, when the
 * answer would be "not ready" forever on a slow first run.
 *
 * The audio of one utterance is accumulated for that pass. [AudioCapture]
 * emits a fresh array per frame, so holding references to 100 ms slices is
 * holding the whole utterance and nothing else; a 20 minute monologue would
 * hold 16 kHz × 4 bytes × 1200 s = 76 MB, which is the same order as the
 * models themselves. The cap exists so the failure is bounded: at
 * [MAX_UTTERANCE_SAMPLES] the buffer stops growing and a longer utterance
 * falls back to the streaming draft rather than being rescored on its head
 * alone. 30 seconds covers a note or an event sentence many times over.
 */
class Dictation(
    private val frames: Flow<FloatArray>,
    private val open: (List<String>) -> Utterance,
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val rescorer: () -> Rescorer? = { null },
) {

    /** The production wiring: a real microphone and a real recogniser. */
    constructor(
        recognizer: StreamingRecognizer,
        capture: AudioCapture = AudioCapture(),
        rescorer: () -> Rescorer? = { null },
    ) : this(
        frames = capture.frames(),
        open = { recognizer.open(it) },
        rescorer = rescorer,
    )

    private val _level = MutableStateFlow(0f)

    /**
     * How loud the microphone is, 0 to 1, updated on every frame while [run]
     * is being collected and back to 0 when it stops. The listening aperture
     * writes it into the rim.
     *
     * A StateFlow beside the events rather than a field on them, because
     * events arrive only when the text changes and the rim has to move on
     * every frame, including the silent ones before the first word.
     */
    val level: StateFlow<Float> = _level.asStateFlow()

    fun run(hotwords: List<String> = emptyList()): Flow<DictationEvent> = flow {
        val session = open(hotwords)
        try {
            // Utterance-scoped, reset every time the endpointer fires.
            var utteranceStart = UNSET
            var speechStart = UNSET
            var firstPartialAt = UNSET
            var lastGrowthAt = UNSET
            var lastText = ""
            var audio = FloatArrayList()
            var audioCapped = false

            // Session-scoped, deliberately not reset: the real time factor is a
            // property of the machine, not of one sentence.
            var decodeNanos = 0L
            var audioNanos = 0L

            emitAll(
                frames.transform { frame ->
                    val startedAt = now()
                    if (utteranceStart == UNSET) utteranceStart = startedAt
                    val frameRms = rms(frame)
                    _level.value = loudness(frameRms)
                    if (speechStart == UNSET && frameRms >= ONSET_RMS) speechStart = startedAt

                    // The streaming engine owns every decision about live
                    // text; the buffer only records what it heard so the
                    // rescore pass can hear it too. It is not on the latency
                    // path: appending 1600 floats to a growing array is noise
                    // next to the decode that follows.
                    if (audio.size + frame.size <= MAX_UTTERANCE_SAMPLES) {
                        audio.addAll(frame)
                    } else {
                        audioCapped = true
                    }

                    val before = System.nanoTime()
                    val result = session.accept(frame)
                    decodeNanos += System.nanoTime() - before
                    audioNanos += frame.size.toLong() * NANOS_PER_SECOND / AudioCapture.SAMPLE_RATE
                    val rtf = if (audioNanos == 0L) 0f else decodeNanos.toFloat() / audioNanos

                    val at = now()
                    when (result) {
                        null -> Unit

                        is Transcript.Partial -> {
                            // Text is itself evidence of speech, so it backstops
                            // a speaker too quiet to trip the RMS gate.
                            if (speechStart == UNSET) speechStart = at
                            if (firstPartialAt == UNSET) firstPartialAt = at
                            // Growth is the only signal this engine gives that
                            // the user is still going. It reports how long the
                            // tail took; it never decides when to stop.
                            if (result.text != lastText) {
                                lastText = result.text
                                lastGrowthAt = at
                            }
                            emit(
                                DictationEvent(
                                    transcript = result,
                                    firstPartialMs = firstPartialAt - speechStart,
                                    realTimeFactor = rtf,
                                ),
                            )
                        }

                        is Transcript.Final -> {
                            // The endpointer has closed the utterance. The
                            // streaming draft is complete and this is the one
                            // moment the rescore pass may run: the live screen
                            // is already showing the final draft and nothing
                            // downstream has seen a word yet.
                            //
                            // The guard here, not inside a rescorer, is what
                            // makes the [Rescorer] contract real: any
                            // implementation is free to return null or blank
                            // or throw, and the utterance still ships. A null
                            // or failed pass keeps the draft exactly as it
                            // is; see [Rescorer] for why failure is not a
                            // fault.
                            val text = if (audioCapped) {
                                result.text
                            } else {
                                currentCoroutineContext().ensureActive()
                                val rescored = try {
                                    rescorer()?.rescore(audio.toArray())
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    null
                                }
                                // Native decode cannot be interrupted, but a cancellation
                                // during it must not publish text when it returns.
                                currentCoroutineContext().ensureActive()
                                rescored?.takeIf { it.isNotBlank() } ?: result.text
                            }
                            val final = result.copy(
                                text = text,
                                utteranceMs = at - utteranceStart,
                                silenceToFinalMs =
                                    if (lastGrowthAt == UNSET) 0 else at - lastGrowthAt,
                            )
                            val firstPartial =
                                if (firstPartialAt == UNSET) {
                                    null
                                } else {
                                    firstPartialAt - speechStart
                                }
                            emit(
                                DictationEvent(
                                    transcript = final,
                                    firstPartialMs = firstPartial,
                                    realTimeFactor = rtf,
                                ),
                            )
                            utteranceStart = UNSET
                            speechStart = UNSET
                            firstPartialAt = UNSET
                            lastGrowthAt = UNSET
                            lastText = ""
                            audio = FloatArrayList()
                            audioCapped = false
                        }
                    }
                },
            )
        } finally {
            _level.value = 0f
            session.close()
        }
    }.flowOn(Dispatchers.Default)

    private fun rms(frame: FloatArray): Float {
        if (frame.isEmpty()) return 0f
        var sum = 0.0
        for (sample in frame) sum += sample.toDouble() * sample
        return sqrt(sum / frame.size).toFloat()
    }

    internal companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L

        /**
         * A sentinel rather than 0, because an injected clock is free to start
         * at 0 and the real one is only non-zero by luck.
         */
        const val UNSET = Long.MIN_VALUE

        /**
         * Thirty seconds of audio at the capture rate, the cap on what one
         * utterance's rescore buffer may hold.
         */
        const val MAX_UTTERANCE_SAMPLES = AudioCapture.SAMPLE_RATE * 30

        /**
         * Roughly -40 dBFS. Quiet rooms sit an order of magnitude below this
         * and ordinary speech an order of magnitude above, so it separates the
         * two with room to spare. It wants checking against the section 7
         * corpus rather than trusting, which is why it is one constant.
         */
        const val ONSET_RMS = 0.01f

        /** Quieter than this draws as silence. About a quiet room. */
        const val FLOOR_DBFS = -60f

        /** Louder than this draws as full. Close speech, short of clipping. */
        const val CEILING_DBFS = -12f

        /**
         * RMS to 0..1 on a decibel scale, because loudness is heard in
         * decibels and a linear map would leave the rim still for everything
         * but shouting. The handoff says the level is written into the rim and
         * gives no calibration, so the two ends are engineering's guess and
         * belong on the phone's checklist next to [ONSET_RMS].
         */
        fun loudness(rms: Float): Float {
            if (rms <= 0f) return 0f
            val dbfs = 20f * log10(rms)
            return ((dbfs - FLOOR_DBFS) / (CEILING_DBFS - FLOOR_DBFS)).coerceIn(0f, 1f)
        }
    }
}
