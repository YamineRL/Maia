package dev.maia.app.screens

import dev.maia.app.R
import dev.maia.app.agent.RunLoss
import dev.maia.app.agent.RunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `docs/M8-copy.md` section 5.2's ladder, and the three seams it was built
 * around.
 *
 * The seams are the whole test. The vocabulary is fourteen strings and the
 * buckets coarsen as they grow, which means every boundary is a place where
 * one rounding rule hands over to another and where a naive implementation
 * prints something the vocabulary does not say. The document names the three
 * that would be worst: "0 minutes", "60 minutes" and "1 hours". None of them
 * exists as a string, so the failure mode is not a wrong word but a word that
 * is arithmetically impossible, and the only way to be sure is to walk the
 * boundaries.
 */
class RunDurationTest {

    private fun at(ms: Long) = RunDuration.words(1_000L, 1_000L + ms)

    // ------------------------------------------------------------ the seams

    /**
     * Seam one, and the reason "0 minutes" cannot happen: the bottom of the
     * ladder is a constant with no number in it at all, and it catches
     * everything from a turn that ended in the same millisecond upwards.
     */
    @Test
    fun `nothing under a minute prints a number`() {
        for (ms in listOf(0L, 1L, 999L, 30_000L, 59_999L)) {
            assertEquals("$ms ms", RunDuration.Words(R.string.m8_duration_under_a_minute), at(ms))
        }
        assertEquals(RunDuration.Words(R.string.m8_duration_a_minute), at(60_000L))
    }

    /**
     * Seam two: 9 min 30 s, where the nearest whole minute would print "ten"
     * and the nearest five prints "10". They agree, so the handover from words
     * to digits is invisible and no value falls between them.
     */
    @Test
    fun `the word to digit seam at nine and a half minutes hands over cleanly`() {
        assertEquals(RunDuration.Words(R.string.m8_duration_nine_minutes), at(9 * 60_000L + 29_999L))
        assertEquals(RunDuration.Words(R.string.m8_duration_minutes, 10), at(9 * 60_000L + 30_000L))
    }

    /**
     * Seam three, and the reason "60 minutes" cannot happen: at 57 min 30 s
     * the nearest five minutes would be 60, which the vocabulary does not say,
     * so the band ends there and `an hour` begins.
     */
    @Test
    fun `fifty seven and a half minutes becomes an hour rather than sixty minutes`() {
        assertEquals(RunDuration.Words(R.string.m8_duration_minutes, 55), at(57 * 60_000L + 29_999L))
        assertEquals(RunDuration.Words(R.string.m8_duration_an_hour), at(57 * 60_000L + 30_000L))
    }

    /**
     * Seam four, and the reason "1 hours" cannot happen: `%1$d hours` starts
     * at 1 h 45 min, where the nearest whole hour is already 2.
     */
    @Test
    fun `the hours band starts at two and can never print one`() {
        assertEquals(RunDuration.Words(R.string.m8_duration_an_hour_and_a_half), at(104 * 60_000L + 59_999L))
        assertEquals(RunDuration.Words(R.string.m8_duration_hours, 2), at(105 * 60_000L))
    }

    // --------------------------------------------------------- the whole run

    /**
     * Every minute of the first six hours, checked against the three values
     * the vocabulary is not allowed to produce.
     *
     * This is the belt to the seams' braces: it does not care which string is
     * chosen, only that no chosen string ever arrives with a number that makes
     * it read as a lie.
     */
    @Test
    fun `no value in six hours prints zero, sixty or one`() {
        for (minute in 0..360) {
            for (offset in listOf(0L, 1L, 29_999L, 30_000L, 59_999L)) {
                val ms = minute * 60_000L + offset
                val words = at(ms) ?: error("$ms ms produced nothing")
                val count = words.count ?: continue
                when (words.res) {
                    R.string.m8_duration_minutes -> {
                        assertEquals("$ms ms is not a multiple of five", 0, count % 5)
                        if (count < 10 || count > 55) error("$ms ms printed $count minutes")
                    }
                    R.string.m8_duration_hours -> if (count < 2) error("$ms ms printed $count hours")
                    else -> error("$ms ms took a count on a constant string")
                }
            }
        }
    }

    /** The words are two to nine and nothing else takes a count. */
    @Test
    fun `two to nine are words and carry no number`() {
        val expected = listOf(
            2 to R.string.m8_duration_two_minutes,
            3 to R.string.m8_duration_three_minutes,
            4 to R.string.m8_duration_four_minutes,
            5 to R.string.m8_duration_five_minutes,
            6 to R.string.m8_duration_six_minutes,
            7 to R.string.m8_duration_seven_minutes,
            8 to R.string.m8_duration_eight_minutes,
            9 to R.string.m8_duration_nine_minutes,
        )
        expected.forEach { (minutes, res) ->
            assertEquals("$minutes minutes", RunDuration.Words(res), at(minutes * 60_000L))
        }
    }

    // ------------------------------------------------------ the unusable clock

    /**
     * Section 5.2's last rule: if the clock is unusable the body is omitted
     * and the notification posts with its title alone.
     *
     * Null is how that is said here, and it is said rather than guessed at:
     * a turn with no recorded start, and a device clock that moved backwards
     * under the run, are both a duration nobody can compute, and a fabricated
     * one is rule 12 in miniature.
     */
    @Test
    fun `an unusable clock produces no words at all`() {
        assertNull("no start time", RunDuration.words(0L, 60_000L))
        assertNull("a negative start time", RunDuration.words(-1L, 60_000L))
        assertNull("the clock moved backwards", RunDuration.words(120_000L, 60_000L))
        // And the boundary: ending in the same millisecond it started is a
        // real, if very short, run.
        assertNotNull(RunDuration.words(120_000L, 120_000L))
    }

    // ------------------------------------------------- the other new mappings

    /** Section 5.4's five, one apiece, with nothing shared and nothing missing. */
    @Test
    fun `each loss has its own reason string`() {
        val ids = RunLoss.entries.map { RunCopy.reason(it) }
        assertEquals(RunLoss.entries.size, ids.toSet().size)
        assertEquals(R.string.m8_notif_failed_reason_tunnel, RunCopy.reason(RunLoss.Tunnel))
        assertEquals(R.string.m8_notif_failed_reason_no_answer, RunCopy.reason(RunLoss.NoAnswer))
        assertEquals(R.string.m8_notif_failed_reason_refused, RunCopy.reason(RunLoss.Refused))
        assertEquals(R.string.m8_notif_failed_reason_lost, RunCopy.reason(RunLoss.Lost))
        assertEquals(R.string.m8_notif_failed_reason_agent_error, RunCopy.reason(RunLoss.AgentError))
    }

    /**
     * Section 5.14: three bodies for eight labels, split where the document
     * splits them. `Running.` covers the three that are one condition to
     * anyone not watching the screen, and the two that are not get their own.
     *
     * The four terminal labels are in here too, and they map to `Running.`
     * without that being a lie: the row is removed the moment the run is not
     * live, so no terminal status ever reaches a posted notification. What
     * this pins is that the mapping is total, so a new label cannot arrive
     * with nothing to say.
     */
    @Test
    fun `the ongoing body is three and splits where the document splits`() {
        assertEquals(R.string.m8_notif_open_body_queued, RunCopy.ongoingBody(RunStatus.Queued))
        assertEquals(R.string.m8_notif_open_body_waiting, RunCopy.ongoingBody(RunStatus.WaitingForYou))
        listOf(RunStatus.Sending, RunStatus.Sent, RunStatus.Working).forEach {
            assertEquals("$it", R.string.m8_notif_open_body_working, RunCopy.ongoingBody(it))
        }
        assertEquals(3, RunStatus.entries.map { RunCopy.ongoingBody(it) }.toSet().size)
    }
}
