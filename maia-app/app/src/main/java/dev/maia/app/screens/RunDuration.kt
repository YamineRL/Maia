package dev.maia.app.screens

import dev.maia.app.R
import kotlin.math.roundToLong

/**
 * "Ran for four minutes." `docs/M8-copy.md` section 5.2.
 *
 * A closed vocabulary of fourteen strings and one ladder taken from the top,
 * first match wins. It is here rather than in a `DateUtils` call because the
 * document specifies prose, and prose in a prose slot: `m8_notif_done_body`
 * reads "Ran for %1$s. Tap to read the reply.", and a mono timestamp in that
 * sentence is a different register and a precision nobody asked for.
 *
 * **It is pure, and returns an id rather than a string**, which is the same
 * discipline as [RunCopy]: the only value that ever travels with it is a
 * number the phone computed from its own clock. There is no path by which text
 * reaches a notification through here.
 */
object RunDuration {

    /** A resource id and the one integer it takes, or none. */
    data class Words(val res: Int, val count: Int? = null)

    private const val SECOND = 1_000L
    private const val MINUTE = 60 * SECOND
    private const val HOUR = 60 * MINUTE

    /**
     * The ladder.
     *
     * Null means the clock is unusable and the caller omits the body
     * entirely, posting the notification with its title alone. That is the
     * document's own instruction and it is rule 12 in miniature: a title
     * saying the run finished is complete without a duration, and a fabricated
     * duration is worse than no duration. Unusable means the turn has no start
     * time, or the elapsed time is negative, which is what a device clock that
     * moved under the run looks like from here.
     */
    fun words(startedAt: Long, endedAt: Long): Words? {
        if (startedAt <= 0L || endedAt < startedAt) return null
        val ms = endedAt - startedAt
        return when {
            // A turn that ends in under a second is still "under a minute".
            // Never zero, never "0 minutes", never a negative.
            ms < MINUTE -> Words(R.string.m8_duration_under_a_minute)
            ms < 90 * SECOND -> Words(R.string.m8_duration_a_minute)
            // Two to nine, as words. The nearest whole minute, which at the
            // bottom of this band is 90 s rounding up to two and at the top is
            // 9 min 29 s rounding down to nine.
            ms < 9 * MINUTE + 30 * SECOND -> word(round(ms, MINUTE).toInt())
            // Ten and up, as digits, to the nearest five minutes. The seam is
            // invisible: at 9 min 30 s the nearest whole minute is ten and the
            // nearest five is 10, and they agree.
            ms < 57 * MINUTE + 30 * SECOND ->
                Words(R.string.m8_duration_minutes, (round(ms, 5 * MINUTE) * 5).toInt())
            // 57 min 30 s is where the nearest five minutes would be 60, which
            // is not a thing this vocabulary says, so it becomes an hour.
            ms < HOUR + 15 * MINUTE -> Words(R.string.m8_duration_an_hour)
            ms < HOUR + 45 * MINUTE -> Words(R.string.m8_duration_an_hour_and_a_half)
            // 1 h 45 min is where the nearest half hour is two hours, so this
            // starts at 2 and can never print "1 hours".
            else -> Words(R.string.m8_duration_hours, round(ms, HOUR).toInt())
        }
    }

    /** Two to nine, as the words section 5.2 writes them. */
    private fun word(minutes: Int): Words = Words(
        when (minutes) {
            2 -> R.string.m8_duration_two_minutes
            3 -> R.string.m8_duration_three_minutes
            4 -> R.string.m8_duration_four_minutes
            5 -> R.string.m8_duration_five_minutes
            6 -> R.string.m8_duration_six_minutes
            7 -> R.string.m8_duration_seven_minutes
            8 -> R.string.m8_duration_eight_minutes
            // Nine is the last word and the band above it starts at 9 min
            // 30 s, so nothing else can arrive here. `else` rather than `9`
            // because a total `when` on an Int needs one, and it lands on the
            // value the band's own edges guarantee.
            else -> R.string.m8_duration_nine_minutes
        },
    )

    private fun round(ms: Long, unit: Long): Long = (ms.toDouble() / unit).roundToLong()
}
