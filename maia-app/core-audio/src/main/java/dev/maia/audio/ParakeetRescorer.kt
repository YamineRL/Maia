package dev.maia.audio

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.Closeable

/**
 * The rescore pass: NVIDIA's Parakeet TDT 0.6B v3, csukuangfj's int8 ONNX
 * export, run through sherpa-onnx's offline NeMo transducer path
 * (`model_type=nemo_transducer` in the upstream CLI; the TDT duration
 * predictor is handled inside that path, per the sherpa-onnx pre-trained
 * NeMo transducer models page, checked 2026-09-14).
 *
 * This is the second engine from `docs/M0-brief.md` section 1 and open item
 * B in `docs/M4-status.md`: the streaming zipformer keeps the live partials
 * inside the PRD's 800 ms budget, and this model, which cannot stream, gets
 * the whole utterance once the endpointer has closed it and returns the text
 * that goes to NLU. On 2026-09-14 the user accepted the accuracy cost of the
 * zipformer's draft no longer being good enough ("LUNCH WITH SOME TO MORROW
 * AT NOON" for "lunch with Sam tomorrow at noon"), and this is the answer.
 *
 * **The on-call call.** Choosing Parakeet v3 over Whisper large-v3-turbo
 * without the measured comparison M4-status proposed was a decision taken to
 * unblock the user's live testing quickly; it is revisitable, and the numbers
 * to revisit it with (WER on the user's sentences, delay to final text, RAM,
 * download size) are exactly what item B asked for. What tipped it, all
 * verifiable from what already ships: the same JNI library and the same
 * process already run sherpa-onnx, and the same download engine already
 * fetches the zipformer, so Parakeet is one more model directory and nothing
 * else; its int8 ONNX export is pre-converted by the same publisher
 * (csukuangfj) with no NeMo toolchain step in this repo; and the vendored
 * AAR (1.13.8) ships the offline NeMo transducer decode path it needs. No
 * accuracy comparison against Whisper turbo has been run; that remains item
 * B's open measurement.
 *
 * Construction loads ~670 MB of int8 graphs (encoder 652 MB, decoder 12 MB,
 * joiner 6 MB, tokens under 1 MB. HTTP HEAD against
 * `csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8`, 2026-09-14) and
 * takes seconds, so like [StreamingRecognizer] there should be exactly one
 * of these for the life of the process, owned by whatever owns that one.
 * Kept loaded rather than loaded per utterance, because a per-utterance load
 * would spend seconds of disk and parse on every sentence for no benefit;
 * the RAM it holds resident is a number for maia-privacy and the user, not a
 * decision for this class.
 */
class ParakeetRescorer(
    paths: OfflineModelPaths,
    threads: Int = 4,
) : Rescorer, Closeable {

    private val recognizer = OfflineRecognizer(
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = AudioCapture.SAMPLE_RATE,
                featureDim = 80,
            ),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = paths.encoder,
                    decoder = paths.decoder,
                    joiner = paths.joiner,
                ),
                tokens = paths.tokens,
                numThreads = threads,
                modelType = "nemo_transducer",
            ),
            // The NeMo TDT decode path in sherpa-onnx is greedy search; there
            // is no beam to configure and no hotwords to bias with.
            decodingMethod = "greedy_search",
        ),
    )

    override fun rescore(audio: FloatArray): String? {
        if (audio.isEmpty()) return null
        val stream: OfflineStream = recognizer.createStream()
        try {
            stream.acceptWaveform(audio, AudioCapture.SAMPLE_RATE)
            recognizer.decode(stream)
            // Parakeet's ONNX text is joined tokens and often carries a
            // leading space; the parser downstream has never seen one from
            // the zipformer and should not start.
            val text = recognizer.getResult(stream).text.trim()
            return text.takeIf { it.isNotBlank() }
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()

}
