package dev.maia.app.agent

import dev.maia.app.feel.Schedule
import dev.maia.transport.AgentEvent
import dev.maia.transport.EventType
import dev.maia.transport.Json
import dev.maia.transport.PermissionReply
import dev.maia.transport.ProjectEntry
import dev.maia.transport.ProjectState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Answering a blocked agent, as a pure function. `docs/M8-copy.md` sections
 * 5.3, 5.12 and 5.17.
 *
 * No agent is prompted anywhere in this file and none can be: [reduceRun] is
 * a function of a state and an event, and every event here is constructed.
 *
 * The three outcomes are three tests on purpose. `ACCEPTED`, `GONE` and a
 * transport failure are three different screens, and the one thing that would
 * quietly ruin this feature is an implementation that treats the third as one
 * of the first two.
 */
class RunAnswerTest {

    private val maia = ProjectEntry(7, "maia", "/home/user/projects/maia", ProjectState.ACTIVE)

    private fun event(type: String, data: String = "{}", id: String? = "evt_1"): AgentEvent =
        AgentEvent(id = id, type = type, payload = Json.parse(data))

    private fun run(vararg events: RunEvent, start: RunSession = RunSession()): RunStep {
        var session = start
        var step = RunStep(start)
        var now = 1_000L
        for (e in events) {
            step = reduceRun(session, e, now)
            session = step.session
            now += 100
        }
        return step
    }

    /** A live turn with a permission request standing on it. */
    private fun blocked(patterns: String = """["bash"]"""): RunSession = run(
        RunEvent.Send(maia, "run the tests"),
        RunEvent.Admitted(queued = false),
        RunEvent.Arrived(
            event(
                EventType.PERMISSION_ASKED,
                """{"requestID":"req_1","always":$patterns}""",
            ),
        ),
    ).session

    // ------------------------------------------------------------ the block

    @Test
    fun `a permission carries what would be remembered, and only its count is read`() {
        val state = blocked().state
        assertEquals(RunStatus.WaitingForYou, state.status)
        assertEquals("req_1", state.blocked!!.requestId)
        assertEquals(listOf("bash"), state.blocked!!.patterns)
        // Section 5.3: with nothing to remember the session control is not
        // drawn at all, and nothing marks its absence.
        assertTrue(dev.maia.app.screens.RunCopy.sessionControl(state.blocked))
        assertFalse(dev.maia.app.screens.RunCopy.sessionControl(blocked("[]").state.blocked))
    }

    @Test
    fun `the row goes up with the block and is not a second alert`() {
        assertEquals(BlockedNotice.Asking, blocked().state.blockedRow)
    }

    // ---------------------------------------------------------- the pressing

    @Test
    fun `pressing an answer moves nothing on the screen`() {
        val before = blocked()
        val step = reduceRun(before, RunEvent.Answer(PermissionReply.ONCE), 5_000)
        // Section 5.3: no label promises the press is the end of it. On a
        // locked phone the press is followed by a keyguard and only then by
        // the send, and what reports is the outcome.
        assertEquals(RunStatus.WaitingForYou, step.session.state.status)
        assertEquals(before.state.blocked, step.session.state.blocked)
        assertEquals(
            listOf(RunEffect.Answer(PermissionReply.ONCE, null)),
            step.effects,
        )
    }

    @Test
    fun `the request id never travels on an effect`() {
        val step = reduceRun(blocked(), RunEvent.Answer(PermissionReply.ONCE), 5_000)
        // The id arrived on the event stream. Rule 12's structural half is
        // that nothing off the stream becomes an argument to an effect, and
        // the driver reads the id off the state it already holds.
        assertFalse(step.effects.toString().contains("req_1"))
    }

    // --------------------------------------------------------- the outcomes

    @Test
    fun `an allow that landed goes back to working and marks the reply inline`() {
        val step = reduceRun(blocked(), RunEvent.Answered(PermissionReply.ONCE, null), 5_000)
        val state = step.session.state
        assertEquals(RunStatus.Working, state.status)
        assertNull(state.blocked)
        assertEquals(BlockedNotice.Allowed, state.blockedRow)
        assertEquals(ReplyPiece.Answered(AnswerMark.Allowed), state.turn!!.pieces.last())
        // Inline, so the reply carries straight on past it and no end marker
        // is claimed.
        assertNull(state.turn!!.end)
        assertEquals(listOf(RunEffect.FeelByHand(Schedule.agentSent)), step.effects)
    }

    @Test
    fun `a refusal closes the reply and keeps the stream open`() {
        val step = reduceRun(
            blocked(),
            RunEvent.Answered(PermissionReply.REJECT, "use the repo"),
            5_000,
        )
        val state = step.session.state
        assertEquals(RunStatus.Stopped, state.status)
        assertEquals(EndMarker.Refused, state.turn!!.end)
        assertEquals("use the repo", state.turn!!.refusal)
        assertEquals(BlockedNotice.Refused, state.blockedRow)
        // The cancel pattern, not the sent one: a refusal ends the turn, so it
        // is the same class of event as `Stop` landing (section 4.6).
        assertEquals(
            listOf(
                RunEffect.FeelByHand(Schedule.agentStopped),
                RunEffect.ScheduleTick(5_000 + NOTHING_YET_MS),
            ),
            step.effects,
        )
        // `m8_notif_blocked_answered_refuse` promises Maia says when the run
        // has ended, and only the stream can produce that.
        assertFalse(step.effects.contains(RunEffect.Unsubscribe))
        // And the service stays up for it: the turn is over for the user and
        // not for the wire, which is what `holding` and `live` differ on.
        assertEquals(5_000L, state.settlingSince)
        assertTrue(state.awaitingEnd)
        assertTrue(state.holding)
        assertFalse(state.live)
    }

    @Test
    fun `a refusal marker survives the session going idle afterwards`() {
        val refused = reduceRun(
            blocked(),
            RunEvent.Answered(PermissionReply.REJECT, null),
            5_000,
        ).session
        val step = reduceRun(refused, RunEvent.Arrived(event(EventType.SESSION_IDLE)), 6_000)
        // Set once. The far end reporting the turn as idle afterwards does not
        // unmark what the user did.
        assertEquals(EndMarker.Refused, step.session.state.turn!!.end)
        assertNull(step.session.state.blockedRow)
    }

    @Test
    fun `an answer that was too late is felt, and one nobody pressed is not`() {
        val late = reduceRun(blocked(), RunEvent.AnswerTooLate, 5_000)
        assertEquals(RunAnnounce.TooLate, late.session.state.announce)
        assertEquals(BlockedNotice.Gone, late.session.state.blockedRow)
        assertEquals(listOf(RunEffect.FeelByHand(Schedule.fault)), late.effects)

        val stale = reduceRun(blocked(), RunEvent.Withdrawn, 5_000)
        assertEquals(RunAnnounce.Withdrawn, stale.session.state.announce)
        assertEquals(BlockedNotice.Stale, stale.session.state.blockedRow)
        // Nothing was pressed, so nothing is felt and nothing sounds. It is a
        // correction, not an alert.
        assertEquals(emptyList<RunEffect>(), stale.effects)

        // One marker for both arrivals; only the announcement differs.
        assertEquals(
            ReplyPiece.Answered(AnswerMark.StoppedWaiting),
            late.session.state.turn!!.pieces.last(),
        )
        assertEquals(
            late.session.state.turn!!.pieces.last(),
            stale.session.state.turn!!.pieces.last(),
        )
    }

    @Test
    fun `an answer that never left the phone keeps the block and its controls`() {
        val step = reduceRun(blocked(), RunEvent.AnswerFailed(RunLoss.Tunnel), 5_000)
        val state = step.session.state
        assertEquals(RunLoss.Tunnel, state.answerFailed)
        assertEquals(BlockedNotice.Undelivered, state.blockedRow)
        // The run is exactly where it was.
        assertEquals(RunStatus.WaitingForYou, state.status)
        assertEquals("req_1", state.blocked!!.requestId)
        assertNull(state.turn!!.end)
        assertEquals(listOf(RunEffect.FeelByHand(Schedule.fault)), step.effects)
    }

    @Test
    fun `pressing again clears the last failure before it sends`() {
        val failed = reduceRun(blocked(), RunEvent.AnswerFailed(RunLoss.Tunnel), 5_000).session
        val step = reduceRun(failed, RunEvent.Answer(PermissionReply.ONCE), 6_000)
        assertNull(step.session.state.answerFailed)
    }

    // ------------------------------------------------------------- stopping

    @Test
    fun `a stop that did not go through does not end the run`() {
        val step = reduceRun(blocked(), RunEvent.StopFailed(RunLoss.NoAnswer), 5_000)
        val state = step.session.state
        assertEquals(RunLoss.NoAnswer, state.stopFailed)
        assertNull(state.turn!!.end)
        assertEquals(RunStatus.WaitingForYou, state.status)
        assertEquals(
            listOf(RunEffect.FeelByHand(Schedule.fault), RunEffect.Say(AgentAck.StopNotSent)),
            step.effects,
        )
    }

    @Test
    fun `a stop that was too late says so and changes nothing`() {
        val before = blocked()
        val step = reduceRun(before, RunEvent.StopTooLate, 5_000)
        assertEquals(before.state, step.session.state)
        assertEquals(
            listOf(RunEffect.FeelByHand(Schedule.fault), RunEffect.Say(AgentAck.StopTooLate)),
            step.effects,
        )
    }

    @Test
    fun `an interrupt clears a failed stop rather than leaving its caption up`() {
        val failed = reduceRun(blocked(), RunEvent.StopFailed(RunLoss.NoAnswer), 5_000).session
        val step = reduceRun(failed, RunEvent.Interrupted, 6_000)
        assertNull(step.session.state.stopFailed)
        assertEquals(EndMarker.Stopped, step.session.state.turn!!.end)
        assertNull(step.session.state.blockedRow)
    }

    // ------------------------------------------------- the wait for the ending

    @Test
    fun `the service stays up through the refusal and the screen does not`() {
        val refused = reduceRun(blocked(), RunEvent.Answered(PermissionReply.REJECT, null), 5_000)
            .session
        // The footer is already `Ask again` and the wire is not finished.
        assertFalse(refused.state.live)
        assertTrue(refused.state.holding)
        assertTrue(refused.stream.open)
    }

    @Test
    fun `the ending arriving ends the wait`() {
        val refused = reduceRun(blocked(), RunEvent.Answered(PermissionReply.REJECT, null), 5_000)
            .session
        val idle = reduceRun(refused, RunEvent.Arrived(event(EventType.SESSION_IDLE)), 6_000)
        assertEquals(0L, idle.session.state.settlingSince)
        assertFalse(idle.session.state.holding)
        // And the marker the user earned is still the one on the reply.
        assertEquals(EndMarker.Refused, idle.session.state.turn!!.end)
    }

    @Test
    fun `the stream closing ends the wait`() {
        val refused = reduceRun(blocked(), RunEvent.Answered(PermissionReply.REJECT, null), 5_000)
            .session
        val closed = reduceRun(refused, RunEvent.StreamClosed(""), 6_000)
        assertEquals(0L, closed.session.state.settlingSince)
        assertFalse(closed.session.state.holding)
    }

    @Test
    fun `an ending that never comes is not waited for forever`() {
        val refused = reduceRun(blocked(), RunEvent.Answered(PermissionReply.REJECT, null), 5_000)
            .session
        // Ticks keep coming through the wait, and each one reschedules.
        val early = reduceRun(refused, RunEvent.Tick, 5_000 + SETTLE_MS - 1)
        assertTrue(early.session.state.holding)
        assertTrue(early.effects.any { it is RunEffect.ScheduleTick })
        val out = reduceRun(refused, RunEvent.Tick, 5_000 + SETTLE_MS)
        assertFalse(out.session.state.holding)
        assertEquals(listOf(RunEffect.Unsubscribe), out.effects)
        // Nothing is invented at the end of it: no fault, no second marker,
        // no vibration and nothing said.
        assertNull(out.session.state.fault)
        assertNull(out.session.state.loss)
        assertEquals(RunStatus.Stopped, out.session.state.status)
        assertEquals(EndMarker.Refused, out.session.state.turn!!.end)
    }

    @Test
    fun `a turn that simply ended is not waited for at all`() {
        val done = reduceRun(
            blocked(),
            RunEvent.Arrived(event(EventType.SESSION_IDLE)),
            5_000,
        ).session
        assertFalse(done.state.holding)
        assertEquals(RunStep(done), reduceRun(done, RunEvent.Tick, 9_000_000))
    }

    // -------------------------------------------------- a human being present

    @Test
    fun `opening the run screen takes the row down and leaves the block alone`() {
        val before = blocked()
        assertEquals(BlockedNotice.Asking, before.state.blockedRow)
        val step = reduceRun(before, RunEvent.Seen, 5_000)
        assertNull(step.session.state.blockedRow)
        // The agent is still waiting and the two controls are on the screen
        // the user is now looking at.
        assertEquals("req_1", step.session.state.blocked!!.requestId)
        assertEquals(RunStatus.WaitingForYou, step.session.state.status)
        assertTrue(step.effects.isEmpty())
    }

    @Test
    fun `opening the run screen with no row up changes nothing but the presence`() {
        val live = run(RunEvent.Send(maia, "go"), RunEvent.Admitted(false)).session
        val step = reduceRun(live, RunEvent.Seen, 5_000)
        // One bit moves, and it is not part of the turn: section 5.18 needs
        // to know the window is in front before it can open a microphone,
        // because Android hands a background capture silence.
        assertTrue(step.session.state.onScreen)
        assertEquals(RunStep(live), step.copy(session = step.session.copy(state = step.session.state.copy(onScreen = false))))
    }

    // ---------------------------------------------------------- reconciling

    @Test
    fun `a reconnect while blocked asks whether the request is still pending`() {
        val step = reduceRun(
            blocked(),
            RunEvent.Arrived(event(EventType.SERVER_CONNECTED)),
            5_000,
        )
        // There is no withdrawal event anywhere in the server's event types,
        // so a phone that was away can only find out by asking.
        assertTrue(step.effects.contains(RunEffect.Reconcile))
        assertEquals(5_000L, step.session.stream.lastReconcileAt)
    }

    @Test
    fun `a reconnect with nothing blocked asks nothing`() {
        val live = run(RunEvent.Send(maia, "go"), RunEvent.Admitted(false)).session
        val step = reduceRun(live, RunEvent.Arrived(event(EventType.SERVER_CONNECTED)), 5_000)
        assertFalse(step.effects.contains(RunEffect.Reconcile))
    }

    @Test
    fun `a standing block is rechecked on the tick, and not oftener`() {
        val start = blocked()
        val soon = reduceRun(start, RunEvent.Tick, start.stream.lastReconcileAt + 1_000)
        assertFalse(soon.effects.contains(RunEffect.Reconcile))
        val later = reduceRun(start, RunEvent.Tick, start.stream.lastReconcileAt + RECONCILE_MS)
        assertTrue(later.effects.contains(RunEffect.Reconcile))
    }
}
