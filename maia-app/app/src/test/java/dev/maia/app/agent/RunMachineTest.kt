package dev.maia.app.agent

import dev.maia.app.feel.Schedule
import dev.maia.orb.ApertureState
import dev.maia.transport.AgentEvent
import dev.maia.transport.EventType
import dev.maia.transport.Json
import dev.maia.transport.ProjectEntry
import dev.maia.transport.ProjectState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The run screen as a pure function, driven by moving a long.
 *
 * Every rule about what happens after ninety seconds is a test here and not a
 * phone, which is the point of keeping the clock outside.
 */
class RunMachineTest {

    private val maia = ProjectEntry(7, "maia", "/home/user/projects/maia", ProjectState.ACTIVE)
    private val browser = ProjectEntry(3, "openbrowser", "/home/user/openbrowser", ProjectState.ACTIVE)

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

    private fun sent(): RunSession = run(
        RunEvent.Send(maia, "run the tests"),
        RunEvent.Admitted(queued = false),
    ).session

    // ------------------------------------------------------------ addressing

    @Test
    fun `an instruction subscribes before it prompts`() {
        val step = reduceRun(RunSession(), RunEvent.Send(maia, "run the tests"), 1_000)
        val kinds = step.effects.map { it::class.simpleName }
        // POST /prompt answers with a SessionInputAdmitted and says nothing
        // about the work, so a client that prompts and then subscribes can
        // miss the first deltas of its own reply.
        assertTrue(kinds.indexOf("Subscribe") < kinds.indexOf("Prompt"))
        assertEquals(RunStatus.Sending, step.session.state.status)
        assertEquals(7, step.session.state.project!!.number)
    }

    @Test
    fun `a new instruction resumes from the live edge and never from a stored id`() {
        val busy = run(
            RunEvent.Send(maia, "first"),
            RunEvent.Admitted(false),
            RunEvent.Arrived(event(EventType.TEXT_DELTA, """{"text":"a"}""", id = "evt_9")),
            RunEvent.Arrived(event(EventType.SESSION_IDLE)),
        ).session
        val step = reduceRun(busy, RunEvent.Send(maia, "second"), 9_000)
        // A resume replays, and a replayed delta appended to a turn that has
        // it is a reply printed twice.
        assertEquals(listOf<String?>(null), step.effects.filterIsInstance<RunEffect.Subscribe>().map { it.after })
    }

    @Test
    fun `naming another project moves the stream and does not stop the first agent`() {
        val step = reduceRun(sent(), RunEvent.Focus(browser, leftBehind = 7), 5_000)
        assertTrue(step.effects.contains(RunEffect.Unsubscribe))
        assertEquals(3, step.session.state.project!!.number)
        assertEquals(7, step.session.state.leftBehind)
        // The turn that was live is kept, and marked cut rather than done:
        // what is on screen is what got through.
        assertEquals(EndMarker.Cut, step.session.state.earlier.single().end)
    }

    @Test
    fun `moving after a finished turn keeps its DONE marker`() {
        val done = run(
            RunEvent.Send(maia, "first"),
            RunEvent.Admitted(false),
            RunEvent.Arrived(event(EventType.SESSION_IDLE)),
        ).session
        val step = reduceRun(done, RunEvent.Focus(browser), 9_000)
        assertEquals(EndMarker.Done, step.session.state.earlier.single().end)
    }

    // ----------------------------------------------------------- admission

    @Test
    fun `admission says sent, and moved says both numbers`() {
        val plain = reduceRun(
            reduceRun(RunSession(), RunEvent.Send(maia, "go"), 1_000).session,
            RunEvent.Admitted(false),
            1_100,
        )
        assertEquals(
            RunEffect.Say(AgentAck.Sent, 7),
            plain.effects.filterIsInstance<RunEffect.Say>().single(),
        )

        val moved = reduceRun(
            reduceRun(RunSession(), RunEvent.Send(browser, "go", leftBehind = 7), 1_000).session,
            RunEvent.Admitted(false),
            1_100,
        )
        assertEquals(
            RunEffect.Say(AgentAck.SentMoved, 3, 7),
            moved.effects.filterIsInstance<RunEffect.Say>().single(),
        )
    }

    @Test
    fun `a queue is a fact in words and not a pose`() {
        val step = reduceRun(
            reduceRun(RunSession(), RunEvent.Send(maia, "go"), 1_000).session,
            RunEvent.Admitted(queued = true),
            1_100,
        )
        assertEquals(RunStatus.Queued, step.session.state.status)
        assertEquals(AgentAck.Queued, step.effects.filterIsInstance<RunEffect.Say>().single().ack)
        assertEquals(Schedule.agentQueued, step.effects.filterIsInstance<RunEffect.Feel>().single().pattern)
    }

    // ------------------------------------------------------------ streaming

    @Test
    fun `deltas extend the tail rather than starting a new piece`() {
        val step = run(
            RunEvent.Send(maia, "go"),
            RunEvent.Admitted(false),
            RunEvent.Arrived(event(EventType.TEXT_DELTA, """{"text":"Ran "}""")),
            RunEvent.Arrived(event(EventType.TEXT_DELTA, """{"text":"14 tests"}""")),
        )
        // Appending, not replacing. Text already rendered is never re-laid-out,
        // because a re-layout moves a screen reader's focus mid-read.
        assertEquals(
            listOf(ReplyPiece.Prose("Ran 14 tests")),
            step.session.state.turn!!.pieces,
        )
        assertEquals(RunStatus.Working, step.session.state.status)
    }

    @Test
    fun `a delta whose field name is unknown renders nothing rather than something vaguer`() {
        val step = run(
            RunEvent.Send(maia, "go"),
            RunEvent.Arrived(event(EventType.TEXT_DELTA, """{"surprise":"x"}""")),
        )
        assertTrue(step.session.state.turn!!.empty)
    }

    @Test
    fun `a run of the same tool collapses and a different one does not`() {
        val step = run(
            RunEvent.Send(maia, "go"),
            RunEvent.Arrived(event(EventType.TOOL_CALLED, """{"tool":"read","path":"/a/b/Reduce.kt"}""")),
            RunEvent.Arrived(event(EventType.TOOL_CALLED, """{"tool":"read","path":"/a/b/Reduce.kt"}""")),
            RunEvent.Arrived(event(EventType.TOOL_CALLED, """{"tool":"read","path":"/a/b/Reduce.kt"}""")),
            RunEvent.Arrived(event(EventType.TOOL_CALLED, """{"tool":"bash"}""")),
        )
        assertEquals(
            listOf(
                ReplyPiece.Tool("read", "Reduce.kt", 3),
                ReplyPiece.Tool("bash", null, 1),
            ),
            step.session.state.turn!!.pieces,
        )
    }

    @Test
    fun `a plan without counts is omitted entirely`() {
        val step = run(
            RunEvent.Send(maia, "go"),
            RunEvent.Arrived(event(EventType.TODO_UPDATED, """{"note":"working"}""")),
        )
        assertNull(step.session.state.plan)

        val counted = run(
            RunEvent.Send(maia, "go"),
            RunEvent.Arrived(
                event(
                    EventType.TODO_UPDATED,
                    """{"todos":[{"status":"completed"},{"status":"completed"},{"status":"pending"}]}""",
                ),
            ),
        )
        assertEquals(Plan(2, 3), counted.session.state.plan)
    }

    @Test
    fun `a block waits for a human, and is felt and never spoken`() {
        val step = run(
            RunEvent.Send(maia, "go"),
            RunEvent.Admitted(false),
            RunEvent.Arrived(event(EventType.PERMISSION_ASKED, """{"requestID":"perm_1"}""")),
        )
        assertEquals(RunStatus.WaitingForYou, step.session.state.status)
        assertEquals(Block(BlockKind.Permission, "perm_1"), step.session.state.blocked)
        assertEquals(Schedule.agentBlocked, step.effects.filterIsInstance<RunEffect.Feel>().single().pattern)
        // A permission request read aloud is agent output read aloud.
        assertTrue(step.effects.none { it is RunEffect.Say })
    }

    @Test
    fun `an unknown event type is carried and not thrown on`() {
        val step = run(
            RunEvent.Send(maia, "go"),
            RunEvent.Arrived(event("session.next.something.new", """{"x":1}""", id = "evt_77")),
        )
        // The union will grow and a client that throws on a new member is a
        // client that breaks on a server upgrade.
        assertEquals("evt_77", step.session.stream.lastEventId)
    }

    // ------------------------------------------------------------- outcomes

    @Test
    fun `session idle ends the turn, unsubscribes and is not spoken`() {
        val step = run(
            RunEvent.Send(maia, "go"),
            RunEvent.Admitted(false),
            RunEvent.Arrived(event(EventType.TEXT_DELTA, """{"text":"done"}""")),
            RunEvent.Arrived(event(EventType.SESSION_IDLE)),
        )
        assertEquals(RunStatus.Finished, step.session.state.status)
        assertEquals(EndMarker.Done, step.session.state.turn!!.end)
        assertTrue(step.effects.contains(RunEffect.Unsubscribe))
        assertEquals(Schedule.agentEnded, step.effects.filterIsInstance<RunEffect.Feel>().single().pattern)
        assertTrue(step.effects.none { it is RunEffect.Say })
    }

    /**
     * `session.idle` is the contracted end of a turn, but the pinned server
     * never sends it: the last frame a turn emits is `session.next.step.ended`
     * with a `stop` finish. The machine therefore gives the stream
     * [STEP_END_GRACE_MS] to continue the turn and calls it finished only
     * when nothing does.
     */
    @Test
    fun `a stopped last step ends the turn once the grace window is out`() {
        var s = sent()
        s = reduceRun(s, RunEvent.Arrived(event(EventType.TEXT_DELTA, """{"text":"done"}""")), 10_000).session
        val ended = reduceRun(s, RunEvent.Arrived(event(EventType.STEP_ENDED, """{"finish":"stop"}""")), 20_000)
        // Not on the event itself: the turn could still be continued by
        // whatever the server emits next.
        assertEquals(RunStatus.Working, ended.session.state.status)
        assertTrue(ended.effects.any { it is RunEffect.ScheduleTick })

        val tick = reduceRun(ended.session, RunEvent.Tick, 20_000 + STEP_END_GRACE_MS)
        assertEquals(RunStatus.Finished, tick.session.state.status)
        assertEquals(EndMarker.Done, tick.session.state.turn!!.end)
        assertTrue(tick.effects.contains(RunEffect.Unsubscribe))
        assertEquals(Schedule.agentEnded, tick.effects.filterIsInstance<RunEffect.Feel>().single().pattern)
        assertTrue(tick.effects.none { it is RunEffect.Say })
    }

    @Test
    fun `work after a stopped step cancels the ending`() {
        var s = sent()
        s = reduceRun(s, RunEvent.Arrived(event(EventType.STEP_ENDED, """{"finish":"stop"}""")), 20_000).session
        s = reduceRun(s, RunEvent.Arrived(event("session.next.step.started")), 21_000).session
        val tick = reduceRun(s, RunEvent.Tick, 21_000 + STEP_END_GRACE_MS)
        assertNotEquals(RunStatus.Finished, tick.session.state.status)
        assertTrue(tick.effects.none { it is RunEffect.Unsubscribe })
    }

    @Test
    fun `a step that ended for a tool call never starts the ending`() {
        var s = sent()
        s = reduceRun(s, RunEvent.Arrived(event(EventType.STEP_ENDED, """{"finish":"tool_calls"}""")), 20_000).session
        val tick = reduceRun(s, RunEvent.Tick, 20_000 + STEP_END_GRACE_MS)
        assertNotEquals(RunStatus.Finished, tick.session.state.status)
        assertTrue(tick.effects.none { it is RunEffect.Unsubscribe })
    }

    @Test
    fun `a stopped step does not end a turn that is waiting on a human`() {
        var s = sent()
        s = reduceRun(s, RunEvent.Arrived(event(EventType.PERMISSION_ASKED, """{"requestID":"p1"}""")), 10_000).session
        s = reduceRun(s, RunEvent.Arrived(event(EventType.STEP_ENDED, """{"finish":"stop"}""")), 20_000).session
        val tick = reduceRun(s, RunEvent.Tick, 20_000 + STEP_END_GRACE_MS)
        assertEquals(RunStatus.WaitingForYou, tick.session.state.status)
        assertTrue(tick.effects.none { it is RunEffect.Unsubscribe })
    }

    @Test
    fun `a queued prompt does not end with the turn ahead of it`() {
        var s = run(
            RunEvent.Send(maia, "go"),
            RunEvent.Admitted(queued = true),
        ).session
        s = reduceRun(s, RunEvent.Arrived(event(EventType.STEP_ENDED, """{"finish":"stop"}""")), 20_000).session
        val tick = reduceRun(s, RunEvent.Tick, 20_000 + STEP_END_GRACE_MS)
        assertEquals(RunStatus.Queued, tick.session.state.status)
        assertTrue(tick.effects.none { it is RunEffect.Unsubscribe })
    }

    @Test
    fun `the grace window does not finish early`() {
        var s = sent()
        s = reduceRun(s, RunEvent.Arrived(event(EventType.STEP_ENDED, """{"finish":"stop"}""")), 20_000).session
        val tick = reduceRun(s, RunEvent.Tick, 20_000 + STEP_END_GRACE_MS - 1)
        assertEquals(RunStatus.Sent, tick.session.state.status)
        // And the clock keeps running, so the window is still checked.
        assertTrue(tick.effects.any { it is RunEffect.ScheduleTick })
    }

    @Test
    fun `a failed turn keeps what arrived and marks it cut`() {
        val step = run(
            RunEvent.Send(maia, "go"),
            RunEvent.Admitted(false),
            RunEvent.Arrived(event(EventType.TEXT_DELTA, """{"text":"partial"}""")),
            RunEvent.Arrived(event(EventType.STEP_FAILED)),
        )
        assertEquals(RunStatus.DidNotFinish, step.session.state.status)
        assertEquals(EndMarker.Cut, step.session.state.turn!!.end)
        // A user told something failed and offered nothing to look at assumes
        // everything was lost.
        assertEquals(listOf(ReplyPiece.Prose("partial")), step.session.state.turn!!.pieces)
    }

    @Test
    fun `a stop the user asked for is not a failure`() {
        val step = reduceRun(sent(), RunEvent.Interrupted, 5_000)
        assertEquals(RunStatus.Stopped, step.session.state.status)
        assertEquals(EndMarker.Stopped, step.session.state.turn!!.end)
        assertNull(step.session.state.fault)
        assertEquals(Schedule.agentStopped, step.effects.filterIsInstance<RunEffect.Feel>().single().pattern)
        assertEquals(AgentAck.Stopped, step.effects.filterIsInstance<RunEffect.Say>().single().ack)
    }

    @Test
    fun `the four faults that mean nothing left the phone are spoken`() {
        for ((fault, ack) in listOf(
            RunFault.TunnelOff to AgentAck.TunnelOff,
            RunFault.NoServer to AgentAck.NoAnswer,
            RunFault.Refused to AgentAck.Refused,
            RunFault.NoProject to AgentAck.NoProject,
        )) {
            val step = reduceRun(RunSession(), RunEvent.Failed(fault), 1_000)
            assertEquals(fault, step.session.state.fault)
            assertEquals(ack, step.effects.filterIsInstance<RunEffect.Say>().single().ack)
        }
    }

    @Test
    fun `a turn that failed after starting is felt and shown but never said`() {
        val step = reduceRun(sent(), RunEvent.Failed(RunFault.TurnFailed), 60_000)
        assertEquals(EndMarker.Cut, step.session.state.turn!!.end)
        // It arrives on a later clock, so rule 10's reason for speaking (the
        // user just spoke and is owed an answer in the same breath) is gone.
        assertTrue(step.effects.none { it is RunEffect.Say })
        assertEquals(Schedule.fault, step.effects.filterIsInstance<RunEffect.Feel>().single().pattern)
    }

    // -------------------------------------------- which run was actually lost

    /**
     * `docs/M8-copy.md` section 5.4, from the machine's side.
     *
     * [RunState.loss] answers a different question from [RunState.fault]:
     * not "what went wrong" but "was a run the user is waiting on lost". The
     * answer turns on where in the turn the failure arrived, because before
     * `Admitted` the instruction has not left the phone. Same fault, same
     * spoken line, and one of them is a notification saying a run did not
     * finish while the other would be naming a run that never started.
     */
    @Test
    fun `a fault before the instruction was admitted loses no run`() {
        val sending = run(RunEvent.Send(maia, "go")).session
        for (fault in RunFault.entries) {
            val step = reduceRun(sending, RunEvent.Failed(fault), 2_000)
            assertNull("$fault", step.session.state.loss)
            // And the turn keeps no end marker, because it never ran.
            assertNull("$fault", step.session.state.turn!!.end)
        }
    }

    @Test
    fun `the same fault after admission is a lost run, with its own sentence`() {
        for ((fault, loss) in listOf(
            RunFault.TunnelOff to RunLoss.Tunnel,
            RunFault.NoServer to RunLoss.NoAnswer,
            RunFault.Refused to RunLoss.Refused,
            RunFault.TurnFailed to RunLoss.AgentError,
        )) {
            val step = reduceRun(sent(), RunEvent.Failed(fault), 60_000)
            assertEquals("$fault", loss, step.session.state.loss)
            // Section 5.4: the tap opens the run screen scrolled to `STOPPED
            // HERE`, for all five reasons, so the marker goes on for all of
            // them and not only where the agent was the thing that broke.
            assertEquals("$fault", EndMarker.Cut, step.session.state.turn!!.end)
        }
    }

    @Test
    fun `a session error is the agent stopping and a second loss is silence`() {
        val error = reduceRun(sent(), RunEvent.Arrived(event(EventType.SESSION_ERROR)), 30_000)
        assertEquals(RunLoss.AgentError, error.session.state.loss)

        var s = sent()
        s = reduceRun(s, RunEvent.StreamClosed(""), 20_000).session
        val lost = reduceRun(s, RunEvent.StreamClosed("unexpected EOF"), 25_000)
        // It names silence and not a cause. The phone did not see a tunnel go
        // down; it saw frames stop arriving and one reconnect not fix it.
        assertEquals(RunLoss.Lost, lost.session.state.loss)
    }

    /**
     * Section 5.4's last bullet: two causes share no sentence, and if more
     * than one fires the earlier wins, because it is the one that explains the
     * other.
     */
    @Test
    fun `the earlier cause wins when a second arrives on top of it`() {
        val dropped = reduceRun(sent(), RunEvent.Failed(RunFault.TunnelOff), 60_000).session
        val then = reduceRun(dropped, RunEvent.StreamClosed("unexpected EOF"), 90_000)
        assertEquals(RunLoss.Tunnel, then.session.state.loss)
        val andAgain = reduceRun(then.session, RunEvent.Failed(RunFault.TurnFailed), 95_000)
        assertEquals(RunLoss.Tunnel, andAgain.session.state.loss)
    }

    @Test
    fun `a new instruction is not carrying the last one's loss`() {
        val lost = reduceRun(sent(), RunEvent.Failed(RunFault.TurnFailed), 60_000).session
        val again = run(
            RunEvent.Send(maia, "try that again"),
            RunEvent.Admitted(queued = false),
            start = lost,
        )
        assertNull(again.session.state.loss)
        assertNull(again.session.state.fault)
    }

    // ------------------------------------------------------- the silence rule

    @Test
    fun `a dropped stream reconnects once, resuming from the last id`() {
        val live = run(
            RunEvent.Send(maia, "go"),
            RunEvent.Admitted(false),
            RunEvent.Arrived(event(EventType.TEXT_DELTA, """{"text":"x"}""", id = "evt_42")),
        ).session
        val step = reduceRun(live, RunEvent.StreamClosed(""), 20_000)
        assertEquals("evt_42", step.effects.filterIsInstance<RunEffect.Subscribe>().single().after)
        assertTrue(step.session.stream.reconnected)
        // Still live: nothing is claimed about the ending yet.
        assertTrue(step.session.state.live)
        assertNull(step.session.state.turn!!.end)
    }

    @Test
    fun `a second loss is the end of the turn and marks it cut`() {
        var s = run(RunEvent.Send(maia, "go"), RunEvent.Admitted(false)).session
        s = reduceRun(s, RunEvent.StreamClosed(""), 20_000).session
        val step = reduceRun(s, RunEvent.StreamClosed("unexpected EOF"), 25_000)
        assertEquals(RunStatus.DidNotFinish, step.session.state.status)
        assertEquals(EndMarker.Cut, step.session.state.turn!!.end)
        assertEquals(RunFault.TurnFailed, step.session.state.fault)
    }

    @Test
    fun `a stream that ends after the turn is over is not a fault`() {
        val done = run(
            RunEvent.Send(maia, "go"),
            RunEvent.Admitted(false),
            RunEvent.Arrived(event(EventType.SESSION_IDLE)),
        ).session
        val step = reduceRun(done, RunEvent.StreamClosed(""), 30_000)
        assertEquals(RunStatus.Finished, step.session.state.status)
        assertEquals(EndMarker.Done, step.session.state.turn!!.end)
        assertTrue(step.effects.isEmpty())
    }

    @Test
    fun `eighty-nine seconds of silence is a slow agent and ninety is a dropped stream`() {
        val live = run(RunEvent.Send(maia, "go"), RunEvent.Admitted(false)).session
        val at = live.stream.lastFrameAt
        assertTrue(reduceRun(live, RunEvent.Tick, at + 89_000).effects.none { it is RunEffect.Subscribe })
        val dropped = reduceRun(live, RunEvent.Tick, at + SILENCE_MS)
        assertTrue(dropped.effects.contains(RunEffect.Unsubscribe))
        assertTrue(dropped.session.stream.reconnected)
    }

    @Test
    fun `a heartbeat is a frame and resets the silence clock`() {
        val live = run(RunEvent.Send(maia, "go"), RunEvent.Admitted(false)).session
        val beat = reduceRun(live, RunEvent.Heartbeat, live.stream.lastFrameAt + 80_000).session
        // Ninety seconds is nine missed heartbeats in a row, not a slow agent:
        // a four-minute tool call still heartbeats.
        val step = reduceRun(beat, RunEvent.Tick, live.stream.lastFrameAt + 120_000)
        assertTrue(step.effects.none { it is RunEffect.Subscribe })
        assertTrue(step.session.state.live)
    }

    @Test
    fun `a further thirty seconds after the reconnect is the fault`() {
        val live = run(RunEvent.Send(maia, "go"), RunEvent.Admitted(false)).session
        val at = live.stream.lastFrameAt
        val re = reduceRun(live, RunEvent.Tick, at + SILENCE_MS).session
        val still = reduceRun(re, RunEvent.Tick, at + SILENCE_MS + 29_000)
        assertEquals(RunStatus.Sent, still.session.state.status)
        val gone = reduceRun(re, RunEvent.Tick, at + SILENCE_MS + RECONNECT_GRACE_MS)
        assertEquals(RunStatus.DidNotFinish, gone.session.state.status)
        assertEquals(EndMarker.Cut, gone.session.state.turn!!.end)
    }

    @Test
    fun `nothing yet appears once after ten seconds and then stops changing`() {
        val live = run(RunEvent.Send(maia, "go"), RunEvent.Admitted(false)).session
        val early = reduceRun(live, RunEvent.Tick, live.state.turn!!.startedAt + 9_000)
        assertFalse(early.session.state.nothingYet)
        val late = reduceRun(live, RunEvent.Tick, live.state.turn!!.startedAt + NOTHING_YET_MS)
        assertTrue(late.session.state.nothingYet)
        // It does not count up and it does not estimate.
        assertEquals(late.session.state, reduceRun(late.session, RunEvent.Tick, live.state.turn!!.startedAt + 40_000).session.state)
    }

    @Test
    fun `the first delta clears nothing yet`() {
        var s = run(RunEvent.Send(maia, "go"), RunEvent.Admitted(false)).session
        s = reduceRun(s, RunEvent.Tick, s.state.turn!!.startedAt + NOTHING_YET_MS).session
        assertTrue(s.state.nothingYet)
        s = reduceRun(s, RunEvent.Arrived(event(EventType.TEXT_DELTA, """{"text":"hi"}""")), 40_000).session
        assertFalse(s.state.nothingYet)
    }

    @Test
    fun `a finished turn does not tick`() {
        val done = run(
            RunEvent.Send(maia, "go"),
            RunEvent.Arrived(event(EventType.SESSION_IDLE)),
        ).session
        assertTrue(reduceRun(done, RunEvent.Tick, 500_000).effects.isEmpty())
    }

    // -------------------------------------------------------------- chooser

    @Test
    fun `an ambiguous name lists and Maia does not choose`() {
        val chooser = Chooser(spoken = "open", matches = listOf(browser), all = listOf(browser, maia), held = "run the tests")
        val step = reduceRun(RunSession(), RunEvent.Choose(chooser, AgentAck.WhichOne), 1_000)
        assertEquals(chooser, step.session.state.chooser)
        assertEquals(AgentAck.WhichOne, step.effects.filterIsInstance<RunEffect.Say>().single().ack)
        assertTrue(step.effects.none { it is RunEffect.Prompt })
    }

    @Test
    fun `a number that does not exist is a fault with the number in it`() {
        val step = reduceRun(
            RunSession(),
            RunEvent.Choose(Chooser(spoken = "41", all = listOf(maia), badNumber = 41), AgentAck.NoProject),
            1_000,
        )
        assertEquals(RunFault.NoProject, step.session.state.fault)
        assertEquals(41, step.effects.filterIsInstance<RunEffect.Say>().single().number)
    }

    // ----------------------------------------------------------- the orb

    @Test
    fun `the orb is a function of the screen alone`() {
        assertEquals(ApertureState.Thinking, RunState(status = RunStatus.Sending).aperture)
        assertEquals(ApertureState.Working, RunState(status = RunStatus.Sent).aperture)
        // The queue is one more item in the same condition, not a sixth pose.
        assertEquals(ApertureState.Working, RunState(status = RunStatus.Queued).aperture)
        // Truthful rather than borrowed: the microphone really does open.
        assertEquals(ApertureState.Listening, RunState(status = RunStatus.WaitingForYou).aperture)
        assertEquals(ApertureState.Dormant, RunState(status = RunStatus.Finished).aperture)
        // A stop the user asked for is not a failure, and Fault here would be
        // the interface telling them off for using a feature.
        assertEquals(ApertureState.Dormant, RunState(status = RunStatus.Stopped).aperture)
        assertEquals(ApertureState.Fault, RunState(status = RunStatus.DidNotFinish).aperture)
    }
}
