package dev.maia.audio

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.Closeable

/**
 * The sherpa-onnx streaming recogniser, with the endpointer configured to the
 * shape PRD section 7 asks for.
 *
 * Construction loads 70 MB of ONNX graphs and takes seconds, so there should
 * be exactly one of these for the life of the process. A [Session] is the
 * cheap per-utterance object.
 */
class StreamingRecognizer(
    paths: ModelPaths,
    hotwordsScore: Float = DEFAULT_HOTWORDS_SCORE,
    threads: Int = 2,
) : Closeable {

    private val recognizer = OnlineRecognizer(
        config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = AudioCapture.SAMPLE_RATE,
                featureDim = 80,
            ),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = paths.encoder,
                    decoder = paths.decoder,
                    joiner = paths.joiner,
                ),
                tokens = paths.tokens,
                modelType = "zipformer2",
                // No modelingUnit or bpeVocab. Both are required for hotwords
                // to mean anything on a BPE model, but setting them aborts the
                // process on the Pixel as the load finishes ("pthread_mutex_lock
                // called on a destroyed mutex", four out of four on 2026-09-13,
                // on hardened_malloc and on scudo), which is upstream
                // k2-fsa/sherpa-onnx#3065. The hotword list is empty until
                // contact biasing lands, so nothing is lost today. Put both
                // back with that work, once #3065 is fixed: paths.bpeVocab is
                // still downloaded for it.
                numThreads = threads,
            ),
            endpointConfig = ENDPOINTING,
            enableEndpoint = true,
            // greedy_search is faster and ignores hotwords entirely. Contact
            // biasing is PRD section 8, so the beam search is not optional.
            decodingMethod = "modified_beam_search",
            maxActivePaths = 4,
            hotwordsScore = hotwordsScore,
        ),
    )

    /**
     * One utterance in progress.
     *
     * [hotwords] is applied per stream, which is the good news: contact names
     * can change between utterances without rebuilding the recogniser. One
     * phrase per line, and sherpa tokenises each with the bpe model above.
     */
    inner class Session(hotwords: List<String> = emptyList()) : Utterance {
        private val stream: OnlineStream =
            recognizer.createStream(hotwords.joinToString("\n"))

        private var closed = false

        /**
         * Feeds one frame and returns what changed.
         *
         * Returns a [Transcript.Final] on the frame where the endpointer fires
         * and a [Transcript.Partial] otherwise, or null when the text has not
         * moved since the last frame and there is nothing to redraw.
         */
        override fun accept(frame: FloatArray): Transcript? {
            check(!closed) { "session is closed" }
            stream.acceptWaveform(frame, AudioCapture.SAMPLE_RATE)
            while (recognizer.isReady(stream)) recognizer.decode(stream)

            val result = recognizer.getResult(stream)
            val text = result.text
            if (recognizer.isEndpoint(stream)) {
                recognizer.reset(stream)
                // An endpoint on silence alone carries no words. Reporting it
                // would clear the screen every 1.2 seconds while nobody talks.
                return if (text.isNotBlank()) {
                    Transcript.Final(text = text, utteranceMs = 0, silenceToFinalMs = 0)
                } else {
                    null
                }
            }
            if (text.isBlank()) return null
            // Guarded because the scores cross JNI, and whether this build
            // fills ysProbs for a streaming transducer is M2 criterion 17,
            // which only the phone can answer. Without them the words are
            // simply absent and the screen shows everything as confirmed.
            val words = runCatching { Word.fromTokens(result.tokens, result.ysProbs) }
                .getOrDefault(emptyList())
            return Transcript.Partial(text, words)
        }

        override fun close() {
            if (!closed) {
                closed = true
                stream.release()
            }
        }
    }

    /**
     * Opens an utterance. Callers take the [Utterance] interface rather than
     * [Session], which is what keeps [Dictation] free of sherpa-onnx.
     */
    fun open(hotwords: List<String> = emptyList()): Utterance = Session(hotwords)

    override fun close() = recognizer.release()

    companion object {
        /**
         * Rule 2 is the one that matters: 1.2 seconds of trailing silence after
         * something was actually said, which is the figure in PRD section 7.
         *
         * Rule 1 covers the user who opens the mic and says nothing. Rule 3 is
         * a hard ceiling so a noisy room cannot hold a stream open forever.
         *
         * Silero VAD is deliberately not wired in as a second stop authority.
         * Two independent deciders racing to close the same utterance produce
         * a bug that only shows up under load and is miserable to reproduce.
         */
        private val ENDPOINTING = EndpointConfig(
            rule1 = EndpointRule(false, 2.4f, 0f),
            rule2 = EndpointRule(true, 1.2f, 0f),
            rule3 = EndpointRule(false, 0f, 20f),
        )

        /**
         * How hard to push a hotword. Upstream defaults to 1.5. Higher rescues
         * more unusual names and starts inventing them in speech that never
         * contained one, so this wants tuning against the section 7 corpus
         * rather than guessing once.
         */
        const val DEFAULT_HOTWORDS_SCORE = 1.5f
    }
}
