package dev.maia.app.ui

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * The first-run screen's one line of numbers, `32.4 / 70.0 MB · ≈ 40 s left`,
 * set in mono by the caller.
 *
 * Megabytes here are 1024 × 1024 bytes. That is the unit behind "a 70 MB
 * download" everywhere else in the product's copy, and a counter that ends at
 * 73.8 while the sentence above it says 70 reads as a lie.
 */
object DownloadCopy {

    private const val MEGABYTE = 1024.0 * 1024.0

    /** `32.4 / 70.0 MB`, or `32.4 MB` while the total is unknown. Never more done than total. */
    fun bytes(done: Long, total: Long): String {
        val d = done.coerceAtLeast(0) / MEGABYTE
        if (total <= 0) return "%.1f MB".format(Locale.ROOT, d)
        val t = total / MEGABYTE
        return "%.1f / %.1f MB".format(Locale.ROOT, d.coerceAtMost(t), t)
    }

    /**
     * `≈ 40 s left`, or null when there is no honest estimate yet.
     *
     * Deliberately coarse, a proposal in the absence of a rule in the
     * handoff: seconds in fives under a minute, whole minutes rounded up under
     * an hour, then hours and minutes. An ETA that ticks every second invites
     * watching it, and a precise one over a phone connection is never true.
     */
    fun eta(remainingBytes: Long, bytesPerSecond: Double): String? {
        if (bytesPerSecond <= 0.0 || remainingBytes < 0) return null
        val seconds = ceil(remainingBytes / bytesPerSecond).toLong()
        return when {
            seconds < 60 -> "≈ ${((seconds / 5.0).roundToLong() * 5).coerceAtLeast(5)} s left"
            seconds < 3_600 -> "≈ ${ceil(seconds / 60.0).toLong()} min left"
            else -> {
                val minutes = ceil(seconds / 60.0).toLong()
                val rest = minutes % 60
                if (rest == 0L) "≈ ${minutes / 60} h left" else "≈ ${minutes / 60} h ${rest} min left"
            }
        }
    }

    /** The whole line: bytes, then the ETA when there is one. */
    fun line(done: Long, total: Long, bytesPerSecond: Double): String {
        val remaining = if (total > 0) (total - done).coerceAtLeast(0) else -1
        return listOfNotNull(bytes(done, total), eta(remaining, bytesPerSecond)).joinToString(" · ")
    }
}
