package dev.maia.audio

import java.io.Closeable

/**
 * One utterance in progress, seen from the outside.
 *
 * This exists so [Dictation] depends on the shape of a recogniser rather than
 * on sherpa-onnx itself. The real implementation is
 * `StreamingRecognizer.Session`, which cannot be constructed off a phone: it
 * needs a live `OnlineRecognizer`, which means 70 MB of ONNX graphs behind
 * arm64-v8a JNI, and the build machine is x86_64.
 *
 * Everything [Dictation] does with a recogniser is feed it frames and read
 * back what changed, so that is the whole interface. It is also what makes the
 * claim in the README true, that the same pipeline can be driven by a harness
 * feeding WAV files when the benchmark corpus in PRD section 7 exists.
 */
interface Utterance : Closeable {

    /**
     * Feeds one frame and returns what changed: a [Transcript.Final] on the
     * frame where the endpointer fires, a [Transcript.Partial] when the text
     * moved, or null when there is nothing new to draw.
     */
    fun accept(frame: FloatArray): Transcript?
}
