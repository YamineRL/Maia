package dev.maia.audio

/**
 * A growable float array, because [java.util.ArrayList] would box every
 * sample.
 *
 * Internal to the module: this is the rescore pass's audio buffer and nothing
 * else. [add] is amortised O(1); [toArray] copies once, which is fine for a
 * thing that is about to cross JNI into 670 MB of graphs.
 *
 * Growth is by doubling, starting at one second of audio. The initial
 * capacity is a guess at a short sentence; the doubling is what keeps the
 * append cost noise next to the streaming decode that follows it.
 */
internal class FloatArrayList(initialCapacity: Int = AudioCapture.SAMPLE_RATE) {

    private var data = FloatArray(initialCapacity.coerceAtLeast(16))
    private var length = 0

    val size: Int get() = length

    fun addAll(values: FloatArray) {
        if (length + values.size > data.size) {
            var capacity = data.size
            while (capacity < length + values.size) capacity *= 2
            data = data.copyOf(capacity)
        }
        values.copyInto(data, length)
        length += values.size
    }

    fun toArray(): FloatArray = data.copyOf(length)
}
