package dev.maia.app.agent

import dev.maia.app.feel.Pattern
import dev.maia.app.feel.Schedule
import dev.maia.transport.ProjectEntry
import dev.maia.transport.ProjectState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which unprompted events become a notification, decided with no `Context`.
 *
 * The rule under test that is easiest to get wrong, and would be worst to get
 * wrong, is the last one: a fault that happened before anything was sent is
 * already on the screen the user is holding and must not also arrive as
 * "%1$d %2$s did not finish", which would name a run that never started.
 */
class RunAlertsTest {

    private val maia = ProjectEntry(7, "maia", "/home/user/maia", ProjectState.ACTIVE)

    private val running = RunState(
        project = maia,
        status = RunStatus.Working,
        turn = Turn("run the tests"),
    )

    private class Sink(clock: () -> Long = { 0L }) {
        val posted = mutableListOf<RunAlerts.Posting>()
        val rewritten = mutableListOf<RunAlerts.BlockedRow>()
        val knocks = mutableListOf<RunAlerts.Knock?>()
        val hand = mutableListOf<Pattern>()
        val cleared = mutableListOf<RunAlert>()
        var state = RunState()

        val alerts = RunAlerts(
            state = { state },
            hand = { hand += it },
            post = { posted += it },
            rewrite = { rewritten += it },
            knock = { knocks += it },
            clear = { cleared += it },
            clock = clock,
        )

        /** Raises a block the way a run does: render, then the pattern. */
        fun block(): RunState {
            val blocked = RunState(
                project = ProjectEntry(7, "maia", "/home/user/maia", ProjectState.ACTIVE),
                status = RunStatus.WaitingForYou,
                turn = Turn("run the tests"),
                blocked = Block(BlockKind.Permission, "req_1", listOf("bash")),
                blockedRow = BlockedNotice.Asking,
            )
            state = blocked
            alerts.render(blocked)
            alerts.feel(Schedule.agentBlocked)
            return blocked
        }
    }

    // ------------------------------------------------ section 4.2's one repeat

    @Test
    fun `the one repeating pattern is scheduled when the row goes up`() {
        val sink = Sink()
        val blocked = sink.block()
        assertEquals(
            listOf(RunAlerts.Knock(blocked.project!!, BlockKind.Permission)),
            sink.knocks,
        )
    }

    @Test
    fun `an answer stops the repeat and rewrites the row it is repeating for`() {
        val sink = Sink()
        val blocked = sink.block()
        val answered = blocked.copy(blocked = null, blockedRow = BlockedNotice.Allowed)
        sink.state = answered
        sink.alerts.render(answered)
        // Null is the cancel, and it arrives in the same breath as the rewrite.
        assertNull(sink.knocks.last())
        assertEquals(BlockedNotice.Allowed, sink.rewritten.single().notice)
    }

    @Test
    fun `the run screen being opened stops the repeat and takes the row down`() {
        val sink = Sink()
        val blocked = sink.block()
        // What `RunEvent.Seen` does to the state: the row goes, the block
        // stays, because the two controls are on the screen being looked at.
        val seen = blocked.copy(blockedRow = null)
        sink.state = seen
        sink.alerts.render(seen)
        assertNull(sink.knocks.last())
        assertEquals(listOf(RunAlert.Blocked), sink.cleared)
    }

    @Test
    fun `a request reconciled away stops the repeat`() {
        val sink = Sink()
        val blocked = sink.block()
        val stale = blocked.copy(blocked = null, blockedRow = BlockedNotice.Stale)
        sink.state = stale
        sink.alerts.render(stale)
        assertNull(sink.knocks.last())
    }

    @Test
    fun `nothing but a block ever repeats`() {
        val sink = Sink()
        sink.state = running.copy(status = RunStatus.Finished, loss = RunLoss.Lost)
        sink.alerts.feel(Schedule.agentEnded)
        sink.alerts.feel(Schedule.fault)
        assertTrue(sink.knocks.isEmpty())
    }

    @Test
    fun `a row that was never posted is not rewritten into existence`() {
        val sink = Sink()
        // No block was ever raised on this run, so nothing is in the shade.
        // An answer arriving must not put a notification there saying so.
        val answered = running.copy(blockedRow = BlockedNotice.Allowed)
        sink.state = answered
        sink.alerts.render(answered)
        assertTrue(sink.rewritten.isEmpty())
    }

    // -------------------------------------------------- the two haptic halves

    @Test
    fun `sent, queued and stopped are the hand's and never a notification`() {
        val sink = Sink()
        sink.state = running
        for (pattern in listOf(Schedule.agentSent, Schedule.agentQueued, Schedule.agentStopped)) {
            sink.alerts.feel(pattern)
        }
        assertEquals(listOf(Schedule.agentSent, Schedule.agentQueued, Schedule.agentStopped), sink.hand)
        assertTrue(sink.posted.isEmpty())
    }

    @Test
    fun `ended, blocked and failed never reach the vibrator directly`() {
        val sink = Sink()
        sink.state = running.copy(
            status = RunStatus.Finished,
            turn = running.turn?.copy(end = EndMarker.Done),
        )
        sink.alerts.feel(Schedule.agentEnded)
        sink.state = running.copy(
            status = RunStatus.WaitingForYou,
            blocked = Block(BlockKind.Permission, "req_1"),
        )
        sink.alerts.feel(Schedule.agentBlocked)
        sink.state = running.copy(
            status = RunStatus.DidNotFinish,
            fault = RunFault.TurnFailed,
            loss = RunLoss.AgentError,
            turn = running.turn?.copy(end = EndMarker.Cut),
        )
        sink.alerts.feel(Schedule.fault)

        assertTrue("a channel pattern went to the hand", sink.hand.isEmpty())
        assertEquals(
            listOf(RunAlert.Ended, RunAlert.Blocked, RunAlert.Failed),
            sink.posted.map { it.alert },
        )
    }

    // -------------------------------------------------------- what is posted

    @Test
    fun `a block carries its kind and a project and nothing else`() {
        val sink = Sink()
        sink.state = running.copy(blocked = Block(BlockKind.Question, "req_9"))
        sink.alerts.feel(Schedule.agentBlocked)
        assertEquals(
            RunAlerts.Posting(RunAlert.Blocked, maia, kind = BlockKind.Question),
            sink.posted.single(),
        )
    }

    @Test
    fun `an ended run carries no kind, because there is no body to put one in`() {
        val sink = Sink()
        sink.state = running.copy(turn = running.turn?.copy(end = EndMarker.Done))
        sink.alerts.feel(Schedule.agentEnded)
        assertNull(sink.posted.single().kind)
    }

    @Test
    fun `nothing is posted before a project has been chosen`() {
        val sink = Sink()
        sink.state = RunState(chooser = Chooser("maia"))
        sink.alerts.feel(Schedule.fault)
        sink.alerts.feel(Schedule.agentEnded)
        assertTrue(sink.posted.isEmpty())
    }

    // ------------------------------------------------- the fault gate itself

    @Test
    fun `a fault before anything was sent is not a run that did not finish`() {
        for (fault in listOf(RunFault.TunnelOff, RunFault.NoServer, RunFault.Refused, RunFault.NoProject, RunFault.Retired)) {
            val sink = Sink()
            // Exactly the shape `RunEvent.Failed` leaves behind for these: a
            // fault, no turn, and `Schedule.fault` felt.
            sink.state = RunState(project = maia, status = RunStatus.DidNotFinish, fault = fault)
            sink.alerts.feel(Schedule.fault)
            assertTrue("$fault posted a notification", sink.posted.isEmpty())
        }
    }

    /**
     * The five sentences of section 5.4, from the notification's side.
     *
     * [RunState.loss] is the machine's record that a turn was admitted and
     * then stopped, and it is the only thing that lets a `did not finish`
     * notification be posted at all. Each of the five reaches the posting
     * unchanged, so the body is chosen from an enum and never assembled.
     */
    @Test
    fun `each of the five losses reaches the posting as itself`() {
        for (loss in RunLoss.entries) {
            val sink = Sink()
            sink.state = running.copy(
                status = RunStatus.DidNotFinish,
                fault = RunFault.TurnFailed,
                loss = loss,
                turn = running.turn?.copy(end = EndMarker.Cut),
            )
            sink.alerts.feel(Schedule.fault)
            assertEquals(loss, sink.posted.single().loss)
        }
    }

    /**
     * A finished run carries two longs and no duration.
     *
     * The formatting is [dev.maia.app.screens.RunDuration]'s and the clock is
     * the host's, so what this pins is that the posting is the pair of
     * instants and not a rendered string: there is nowhere on it for a word.
     */
    @Test
    fun `an ended run carries the turn's start and the clock's now`() {
        val sink = Sink(clock = { 240_000L })
        sink.state = running.copy(
            status = RunStatus.Finished,
            turn = running.turn?.copy(end = EndMarker.Done, startedAt = 1_000L),
        )
        sink.alerts.feel(Schedule.agentEnded)
        val posted = sink.posted.single()
        assertEquals(1_000L, posted.startedAt)
        assertEquals(240_000L, posted.endedAt)
        assertNull(posted.loss)
    }

    @Test
    fun `a turn that failed mid stream is a run that did not finish`() {
        val sink = Sink()
        sink.state = running.copy(
            status = RunStatus.DidNotFinish,
            fault = RunFault.TurnFailed,
            loss = RunLoss.AgentError,
            turn = running.turn?.copy(end = EndMarker.Cut),
        )
        sink.alerts.feel(Schedule.fault)
        assertEquals(RunAlert.Failed, sink.posted.single().alert)
    }

    // ------------------------------------------------------------ withdrawal

    @Test
    fun `a blocked notification is withdrawn when the block is answered`() {
        val sink = Sink()
        sink.state = running.copy(blocked = Block(BlockKind.Permission, "req_1"))
        sink.alerts.render(sink.state)
        sink.alerts.feel(Schedule.agentBlocked)
        assertTrue("cleared the notification it had just posted", sink.cleared.isEmpty())

        sink.state = running
        sink.alerts.render(sink.state)
        assertEquals(listOf(RunAlert.Blocked), sink.cleared)

        // And only once: a screen that keeps rendering does not keep
        // cancelling a notification that is already gone.
        sink.alerts.render(sink.state)
        assertEquals(1, sink.cleared.size)
    }

    @Test
    fun `an ended notification is withdrawn when the next turn goes live`() {
        val sink = Sink()
        sink.state = running.copy(
            status = RunStatus.Finished,
            turn = running.turn?.copy(end = EndMarker.Done),
        )
        sink.alerts.render(sink.state)
        sink.alerts.feel(Schedule.agentEnded)

        sink.state = running.copy(status = RunStatus.Sending)
        sink.alerts.render(sink.state)
        assertEquals(listOf(RunAlert.Ended), sink.cleared)
    }

    @Test
    fun `nothing that was never posted is ever withdrawn`() {
        val sink = Sink()
        sink.alerts.render(RunState(project = maia, status = RunStatus.Working))
        sink.alerts.render(RunState(project = maia, status = RunStatus.Finished))
        assertTrue(sink.cleared.isEmpty())
    }
}
