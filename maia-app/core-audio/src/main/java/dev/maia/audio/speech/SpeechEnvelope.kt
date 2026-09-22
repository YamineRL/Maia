package dev.maia.audio.speech

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Synthesised audio to the 0..1 envelope a [Speaker] emits.
 *
 * sherpa-onnx hands audio back in chunks whose size it chooses, and for a VITS
 * voice a chunk can be a whole sentence. One level per chunk would leave the
 * aperture holding a single pose for the length of "Saved, dinner with Sam,
 * Thursday at eight." So each chunk is cut into [WINDOW_MS] windows, each
 * window is written to the track and then reported. This object is the pure
 * half of that, kept apart from the AudioTrack so the JVM can prove it.
 */
object SpeechEnvelope {

    /**
     * 50 ms, which is 20 levels a second: fast enough to follow syllables,
     * slow enough that the rim does not shimmer on every glottal pulse. The
     * springs in `:ui-orb` smooth whatever arrives, so this is a sampling
     * rate, not an animation curve.
     */
    const val WINDOW_MS = 50

    /** Quieter than this draws as closed. A gap between words. */
    const val FLOOR_DBFS = -50f

    /**
     * Louder than this draws as fully open.
     *
     * Higher than dictation's ceiling because synthesised speech is produced
     * close to full scale, where a microphone at arm's length is not. Both
     * ends are engineering's guess and belong on the Pixel checklist beside
     * the dictation constants.
     */
    const val CEILING_DBFS = -10f

    /** A slice of a chunk, by position, so nothing is copied to measure it. */
    data class Window(val offset: Int, val length: Int)

    /** Samples in one window at [sampleRate], never less than one. */
    fun windowSize(sampleRate: Int, windowMs: Int = WINDOW_MS): Int {
        require(sampleRate > 0) { "sampleRate must be positive, was $sampleRate" }
        require(windowMs > 0) { "windowMs must be positive, was $windowMs" }
        return maxOf(1, sampleRate * windowMs / 1000)
    }

    /**
     * Cuts [length] samples into consecutive windows of [size]. The last one
     * is shorter when the chunk does not divide evenly, rather than dropped,
     * because the tail of a sentence is still a sound the user hears.
     */
    fun windows(length: Int, size: Int): List<Window> {
        require(size > 0) { "size must be positive, was $size" }
        if (length <= 0) return emptyList()
        return (0 until length step size).map { Window(it, minOf(size, length - it)) }
    }

    /** Root mean square of one window. Zero for an empty one. */
    fun rms(samples: FloatArray, offset: Int = 0, length: Int = samples.size): Float {
        if (length <= 0) return 0f
        var sum = 0.0
        for (i in offset until offset + length) {
            val s = samples[i].toDouble()
            sum += s * s
        }
        return sqrt(sum / length).toFloat()
    }

    /**
     * RMS to 0..1 on a decibel scale, for the same reason dictation's level is:
     * loudness is heard in decibels, and a linear map would leave the rim
     * nearly still through everything but the loudest vowel. A NaN from a
     * misbehaving model draws as silence rather than reaching the shader.
     */
    fun level(rms: Float): Float {
        if (!(rms > 0f)) return 0f
        val dbfs = 20f * log10(rms)
        return ((dbfs - FLOOR_DBFS) / (CEILING_DBFS - FLOOR_DBFS)).coerceIn(0f, 1f)
    }

    /** The whole mapping for one chunk: one level per window, in order. */
    fun envelope(chunk: FloatArray, windowSize: Int): List<Float> =
        windows(chunk.size, windowSize).map { level(rms(chunk, it.offset, it.length)) }
}
