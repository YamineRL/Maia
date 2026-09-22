package dev.maia.audio

/**
 * A second, stronger pass over a finished utterance.
 *
 * This exists for the same reason [Utterance] does: [Dictation] depends on the
 * shape of a recogniser, not on sherpa-onnx, so the whole pipeline stays
 * drivable by a test harness with no ONNX graphs behind arm64 JNI. The real
 * implementation is [ParakeetRescorer].
 *
 * The contract is deliberately narrow. It runs once per utterance, after the
 * streaming engine's endpointer has fired, on audio that has already been
 * heard once; it is not on the partial-words path and nothing about the live
 * screen may wait on it. It returns null or blank or throws to say "no
 * improvement", and the caller keeps the streaming draft: a second model that
 * fails is a sentence that arrives as it would have yesterday, never a fault.
 *
 * There is no hotwords parameter. The streaming engine takes contextual
 * biasing; this pass is bigger and slower and runs after the contact-biasing
 * moment has passed. PRD section 8's contact names are the first engine's
 * subject.
 */
interface Rescorer {

    /**
     * Re-transcribes one utterance, given exactly the audio the streaming
     * engine heard for it, at [AudioCapture.SAMPLE_RATE].
     *
     * @return the replacement text, or null when this pass has nothing better
     *   than the draft. Blank counts as null.
     */
    fun rescore(audio: FloatArray): String?
}
