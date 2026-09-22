package dev.maia.audio

/**
 * Throughput over the last few seconds, for the first-run screen's ETA.
 *
 * A rolling window rather than bytes over elapsed time, because a phone that
 * walks from wifi onto mobile data mid-download is the normal case, and an
 * average over the whole transfer would keep promising the wifi speed for
 * minutes after it stopped being true.
 */
class RollingRate(private val nanoTime: () -> Long = System::nanoTime) {

    private val times = LongArray(CAPACITY)
    private val sizes = LongArray(CAPACITY)
    private var head = 0
    private var count = 0

    fun add(bytes: Long) {
        val at = nanoTime()
        val slot = (head + count) % CAPACITY
        times[slot] = at
        sizes[slot] = bytes
        if (count < CAPACITY) count++ else head = (head + 1) % CAPACITY
        while (count > 1 && at - times[head] > WINDOW_NANOS) {
            head = (head + 1) % CAPACITY
            count--
        }
    }

    /**
     * Bytes per second across the window, or 0 when the window spans less
     * than [MIN_SPAN_NANOS]: two reads a microsecond apart are not a speed.
     * The oldest sample's bytes are excluded, since they arrived before the
     * span being measured began.
     */
    fun bytesPerSecond(): Double {
        if (count < 2) return 0.0
        val first = times[head]
        val last = times[(head + count - 1) % CAPACITY]
        val span = last - first
        if (span < MIN_SPAN_NANOS) return 0.0
        var bytes = 0L
        for (i in 1 until count) bytes += sizes[(head + i) % CAPACITY]
        return bytes * 1e9 / span
    }

    companion object {
        const val WINDOW_NANOS = 5_000_000_000L
        const val MIN_SPAN_NANOS = 500_000_000L
        private const val CAPACITY = 512

        /** Whole seconds left, or null when there is no rate to divide by. */
        fun etaSeconds(remainingBytes: Long, bytesPerSecond: Double): Long? =
            if (bytesPerSecond <= 0.0 || remainingBytes < 0) null
            else kotlin.math.ceil(remainingBytes / bytesPerSecond).toLong()
    }
}
