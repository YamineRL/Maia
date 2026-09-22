package dev.maia.app.screens

import dev.maia.app.R
import dev.maia.app.agent.Block
import dev.maia.app.agent.BlockKind
import dev.maia.app.agent.EndMarker
import dev.maia.app.agent.ReplyPiece
import dev.maia.app.agent.RunState
import dev.maia.app.agent.RunStatus
import dev.maia.app.agent.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.time.ZoneId

/**
 * [RunCopy], which is every word the run screen shows and every word it says.
 *
 * The mappings are pinned row by row because a wrong row is a silent bug: a
 * screen that says `FINISHED` over a turn that was cut is worse than one that
 * says nothing, and nothing about that is visible in a build log.
 *
 * The last two tests are the never-spoken rule seen from the accessibility
 * side. `M8CopyTest` proves the strings hold no agent text; these prove no
 * agent text can reach them.
 */
class RunCopyTest {

    @Test
    fun `the eight status labels, in order`() {
        assertEquals(R.string.m8_run_status_sending, RunCopy.status(RunStatus.Sending))
        assertEquals(R.string.m8_run_status_sent, RunCopy.status(RunStatus.Sent))
        assertEquals(R.string.m8_run_status_working, RunCopy.status(RunStatus.Working))
        assertEquals(R.string.m8_run_status_queued, RunCopy.status(RunStatus.Queued))
        assertEquals(R.string.m8_run_status_waiting_for_you, RunCopy.status(RunStatus.WaitingForYou))
        assertEquals(R.string.m8_run_status_finished, RunCopy.status(RunStatus.Finished))
        assertEquals(R.string.m8_run_status_stopped, RunCopy.status(RunStatus.Stopped))
        assertEquals(R.string.m8_run_status_did_not_finish, RunCopy.status(RunStatus.DidNotFinish))
    }

    /** Every status has a label, so no state of the machine can draw a blank one. */
    @Test
    fun `every status has its own label`() {
        val ids = RunStatus.entries.map { RunCopy.status(it) }
        assertEquals(RunStatus.entries.size, ids.toSet().size)
        assertTrue(ids.none { it == 0 })
    }

    /**
     * Rule 12, at the one place it is drawn: while the turn is live there is
     * no marker at all, because an ending that has not happened is not
     * implied by a hairline sitting under the last line.
     */
    @Test
    fun `there is no end marker until the turn has ended`() {
        assertNull(RunCopy.endMarker(null))
        assertEquals(R.string.m8_run_end_marker_done, RunCopy.endMarker(EndMarker.Done))
        assertEquals(R.string.m8_run_end_marker_stopped, RunCopy.endMarker(EndMarker.Stopped))
        assertEquals(R.string.m8_run_end_marker_cut, RunCopy.endMarker(EndMarker.Cut))
    }

    /** The cut end is the dashed one, and it is the only dashed one. */
    @Test
    fun `only a cut turn is dashed`() {
        assertEquals(setOf(EndMarker.Cut), EndMarker.entries.filter { RunCopy.endMarkerDashed(it) }.toSet())
        assertTrue(!RunCopy.endMarkerDashed(null))
    }

    /**
     * Section 5.12: one control, never both. The footer follows `live`, which
     * is the state's own word for it, so a status added later cannot leave the
     * footer showing `Stop` over a turn nothing is running.
     */
    @Test
    fun `the footer is Stop while live and Ask again once it is not`() {
        RunStatus.entries.forEach { status ->
            val state = RunState(status = status)
            val expected =
                if (state.live) R.string.m8_run_stop_action else R.string.m8_run_ask_again_action
            assertEquals(status.name, expected, RunCopy.footerAction(state))
            assertEquals(
                status.name,
                if (state.live) R.string.m8_run_stop_cd else null,
                RunCopy.footerDescription(state),
            )
        }
    }

    /** Section 1.4's caption waits for the ten seconds and for an empty reply. */
    @Test
    fun `the nothing yet caption needs both the wait and an empty reply`() {
        val waited = RunState(nothingYet = true, turn = Turn(instruction = "x"))
        assertEquals(R.string.m8_run_nothing_yet, RunCopy.nothingYet(waited))
        assertNull(RunCopy.nothingYet(waited.copy(nothingYet = false)))
        val arrived = waited.copy(turn = Turn(instruction = "x", pieces = listOf(ReplyPiece.Prose("a"))))
        assertNull(RunCopy.nothingYet(arrived))
    }

    @Test
    fun `the tool line names a target only when there is one`() {
        assertEquals(R.string.m8_run_tool_line, RunCopy.toolLine(ReplyPiece.Tool("read", "a.kt")))
        assertEquals(R.string.m8_run_tool_line_bare, RunCopy.toolLine(ReplyPiece.Tool("bash")))
        assertEquals(R.string.m8_run_tool_repeat, RunCopy.toolLine(ReplyPiece.Tool("read", "a.kt", count = 3)))
    }

    @Test
    fun `a code block counts its lines`() {
        assertEquals(1, RunCopy.codeBlockLines(ReplyPiece.Code("one")))
        assertEquals(3, RunCopy.codeBlockLines(ReplyPiece.Code("one\ntwo\nthree")))
    }

    @Test
    fun `an earlier turn carries the user's own clock and no date`() {
        val turn = Turn(instruction = "x", startedAt = 1_700_000_000_000)
        val text = RunCopy.earlierTime(turn, ZoneId.of("UTC"))
        assertTrue(text, text.none { it.isLetter() && it !in "AMPamp" })
        assertTrue(text, !text.contains("2023"))
    }

    // ------------------------------------------- the rule, from the other side

    /**
     * Section 6.1: one announcement, at the end, and a stop the user asked for
     * is not one of them. They pressed the control, heard the confirmation,
     * and the status label has already gone `STOPPED`.
     */
    @Test
    fun `the announcement is made once at the end and never for a stop`() {
        assertEquals(R.string.m8_run_done_announce, RunCopy.announcement(EndMarker.Done, blocked = false))
        assertEquals(R.string.m8_run_cut_announce, RunCopy.announcement(EndMarker.Cut, blocked = false))
        assertNull(RunCopy.announcement(EndMarker.Stopped, blocked = false))
        assertNull(RunCopy.announcement(null, blocked = false))
        // Blocked outranks an end marker: it is the one state the user has to
        // act on, and it is announced while the turn is still open.
        assertEquals(R.string.m8_run_blocked_announce, RunCopy.announcement(null, blocked = true))
        assertEquals(R.string.m8_run_blocked_announce, RunCopy.announcement(EndMarker.Done, blocked = true))
    }

    /**
     * The announcement never varies with the reply.
     *
     * `announceForAccessibility` is speech, and section 2.1 covers every
     * speech path rather than only the one called speak. A screen reader
     * reading an agent's answer out loud because Maia handed it the text would
     * be the never-spoken rule broken from the accessibility side, which is
     * exactly the side nobody is watching. So the announcement is a function
     * of two facts about the turn and of nothing in it, and the reply is fed
     * through here in bulk to say so.
     */
    @Test
    fun `no reply text can change what is announced`() {
        val replies = listOf(
            emptyList(),
            listOf(ReplyPiece.Prose("the deploy key is in ~/.ssh")),
            listOf(ReplyPiece.Code("rm -rf /"), ReplyPiece.Tool("bash", "rm")),
        )
        EndMarker.entries.plus(null).forEach { end ->
            listOf(true, false).forEach { blocked ->
                val expected = RunCopy.announcement(end, blocked)
                replies.forEach { pieces ->
                    val state = RunState(
                        status = RunStatus.Working,
                        turn = Turn(instruction = "anything at all", pieces = pieces, end = end),
                        blocked = if (blocked) Block(BlockKind.Permission, "r1") else null,
                    )
                    assertEquals(
                        "$end / $blocked / ${pieces.size} pieces",
                        expected,
                        RunCopy.announcement(state.turn?.end, state.blocked != null),
                    )
                }
            }
        }
    }

    /**
     * The shape, not the behaviour.
     *
     * Every function here returns a resource id, a number, a flag or the
     * turns themselves. The one that returns a [String] is [RunCopy.earlierTime],
     * which formats a clock and touches no text, and it is named rather than
     * excused so that a second one cannot be added quietly: a new function
     * returning a string is the exact shape a leak would take, and it fails
     * here on the day it is written.
     */
    @Test
    fun `nothing in RunCopy returns a string built from the reply`() {
        // Plain Java reflection: `kotlin-reflect` is not a dependency of this
        // module and one test is not a reason to add a megabyte to the APK.
        val stringy = RunCopy::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .filter { it.returnType == String::class.java }
            .map { it.name }
            .toSet()
        assertEquals(setOf("earlierTime"), stringy)
    }
}
