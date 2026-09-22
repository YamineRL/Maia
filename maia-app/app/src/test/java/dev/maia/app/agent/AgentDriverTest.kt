package dev.maia.app.agent

import dev.maia.app.feel.Pattern
import dev.maia.nlu.agent.ProjectRef
import dev.maia.transport.AgentChannel
import dev.maia.transport.AgentClient
import dev.maia.transport.AgentException
import dev.maia.transport.LineSink
import dev.maia.transport.ProjectEntry
import dev.maia.transport.ProjectState
import dev.maia.transport.Reply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.Executor

/**
 * A spoken sentence to text on a screen, against a fake at the
 * [AgentChannel] seam.
 *
 * Nothing here touches a real agent, deliberately: prompting one runs the paid
 * lead model, and a test that costs money every time it runs is a test people
 * stop running. The seam exists exactly so the wiring above it can be driven
 * with the SSE bytes written out by hand.
 */
class AgentDriverTest {

    private val maia = ProjectEntry(7, "maia", "/home/user/maia", ProjectState.ACTIVE)
    private val browser = ProjectEntry(3, "openbrowser", "/home/user/openbrowser", ProjectState.ACTIVE)
    private val gone = ProjectEntry(4, "oldthing", "/home/user/oldthing", ProjectState.RETIRED)

    /** Everything on the calling thread, so a test is a straight line. */
    private val here = Executor { it.run() }

    private class FakeChannel : AgentChannel {
        val requests = mutableListOf<Pair<String, String>>()
        /** Requests and streams in one order, for "history before resubscribe". */
        val log = mutableListOf<String>()
        var sink: LineSink? = null
        var streamPaths = mutableListOf<String>()
        var streamClosed = 0
        var failWith: Exception? = null
        var delivery = "queue"
        /** What `GET .../history` answers, and a way to make it fail instead. */
        var historyReply: Reply = Reply(200, """{"data":[]}""")
        var historyFail: Exception? = null

        override fun request(method: String, path: String, body: String?): Reply {
            requests += method to path
            log += "req:$path"
            failWith?.let { throw it }
            return when {
                path.contains("/history") -> {
                    historyFail?.let { throw it }
                    historyReply
                }
                path == "/api/session" -> Reply(200, """{"data":{"id":"ses_1","projectID":"p1","agent":"fusion"}}""")
                path.endsWith("/prompt") ->
                    Reply(200, """{"data":{"id":"msg_1","sessionID":"ses_1","delivery":"$delivery"}}""")
                else -> Reply(204, "")
            }
        }

        override fun stream(path: String, sink: LineSink): Closeable {
            streamPaths += path
            log += "stream:$path"
            this.sink = sink
            return Closeable { streamClosed++ }
        }

        override fun close() = Unit

        /** One SSE frame, exactly as the server writes it: no `event:` line. */
        fun push(json: String) {
            sink!!.onLine("data: $json")
            sink!!.onLine("")
        }

        fun heartbeat() = sink!!.onLine(": heartbeat")
    }

    private class Harness(val channel: FakeChannel = FakeChannel()) {
        val states = mutableListOf<RunState>()
        val spoken = mutableListOf<Triple<AgentAck, Int?, Int?>>()
        val felt = mutableListOf<Pattern>()
        val timers = mutableListOf<Pair<Long, () -> Unit>>()
        var now = 1_000L
        var projects = listOf<ProjectEntry>()

        val driver = AgentDriver(
            client = AgentClient(channel),
            projects = { projects },
            render = { states += it },
            speak = { ack, n, o -> spoken += Triple(ack, n, o) },
            feel = { felt += it },
            // Both sinks into the same list. What this test cares about is
            // that a pattern was felt at all; which usage carries it is
            // `AgentAlertRouteTest`'s question.
            feelByHand = { felt += it },
            clock = { now },
            work = Executor { it.run() },
            timer = { delay, block -> timers += delay to block },
        )

        val last: RunState get() = states.last()

        /** Fires the most recently armed tick, which is the only live one. */
        fun fire() = timers.last().second()
    }

    private fun harness(vararg entries: ProjectEntry): Harness =
        Harness().also { it.projects = entries.toList() }

    // ------------------------------------------------ the end to end path

    @Test
    fun `a numbered instruction subscribes, prompts, streams and ends`() {
        val h = harness(maia, browser)
        h.channel.delivery = "queue"
        h.driver.instruct(ProjectRef.Numbered(7), "run the tests", "project seven run the tests")

        // Subscribed before prompting, because a prompt says nothing about the
        // work and its first deltas would be missed otherwise.
        assertEquals(
            listOf("POST" to "/api/session", "POST" to "/api/session/ses_1/prompt"),
            h.channel.requests,
        )
        // The global v2 stream, filtered to the session client-side: the
        // per-session route accepts the connection and never writes a byte
        // on the pinned server.
        assertEquals(listOf("/api/event"), h.channel.streamPaths)
        assertEquals(RunStatus.Queued, h.last.status)

        h.channel.push("""{"id":"e1","type":"session.next.text.delta","data":{"text":"Ran 14 tests"}}""")
        h.channel.push("""{"id":"e2","type":"session.next.tool.called","data":{"tool":"bash","command":"./gradlew test"}}""")
        h.channel.push("""{"id":"e3","type":"session.idle","data":{}}""")

        assertEquals(RunStatus.Finished, h.last.status)
        assertEquals(EndMarker.Done, h.last.turn!!.end)
        assertEquals(
            listOf(ReplyPiece.Prose("Ran 14 tests"), ReplyPiece.Tool("bash", "./gradlew test", 1)),
            h.last.turn!!.pieces,
        )
        assertEquals(1, h.channel.streamClosed)
        // Queued was spoken once, and nothing else was.
        assertEquals(listOf(Triple(AgentAck.Queued, 7, null)), h.spoken)
    }

    @Test
    fun `an unaddressed sentence goes to the project that is already streaming`() {
        val h = harness(maia, browser)
        h.driver.instruct(ProjectRef.Numbered(7), "first", "project seven first")
        h.channel.requests.clear()
        h.driver.instruct(ProjectRef.Current, "and again", "and again")
        // Same session, no second create: section 13 answer 3, verbatim to the
        // current session.
        assertEquals(listOf("POST" to "/api/session/ses_1/prompt"), h.channel.requests)
        assertEquals(7, h.last.project!!.number)
    }

    @Test
    fun `an unaddressed sentence with nothing streaming lists rather than guesses`() {
        val h = harness(maia, browser)
        h.driver.instruct(ProjectRef.Current, "and again", "and again")
        assertEquals(listOf(3, 7), h.last.chooser!!.all.map { it.number })
        assertEquals("and again", h.last.chooser!!.held)
        assertTrue(h.channel.requests.isEmpty())
        assertEquals(AgentAck.WhichProject, h.spoken.single().first)
    }

    @Test
    fun `focus moves the stream and does not stop the agent left behind`() {
        val h = harness(maia, browser)
        h.driver.instruct(ProjectRef.Numbered(7), "run the tests", "seven run the tests")
        h.channel.requests.clear()
        h.driver.focus(ProjectRef.Named("openbrowser", 3), "openbrowser")

        assertEquals(1, h.channel.streamClosed)
        assertEquals(3, h.last.project!!.number)
        assertEquals(7, h.last.leftBehind)
        // Nothing was sent to either agent, and nothing interrupted the first.
        assertTrue(h.channel.requests.none { it.second.endsWith("/interrupt") })
        assertTrue(h.channel.requests.none { it.second.endsWith("/prompt") })
    }

    @Test
    fun `stop interrupts the live turn and says so`() {
        val h = harness(maia)
        h.driver.instruct(ProjectRef.Numbered(7), "run the tests", "seven run the tests")
        h.channel.push("""{"id":"e1","type":"session.next.text.delta","data":{"text":"working"}}""")
        h.channel.requests.clear()

        h.driver.stop()

        assertEquals(listOf("POST" to "/api/session/ses_1/interrupt"), h.channel.requests)
        assertEquals(RunStatus.Stopped, h.last.status)
        assertEquals(EndMarker.Stopped, h.last.turn!!.end)
        // What arrived is still on screen. Stop is not an undo.
        assertEquals(listOf(ReplyPiece.Prose("working")), h.last.turn!!.pieces)
        assertEquals(AgentAck.Stopped, h.spoken.last().first)
    }

    @Test
    fun `stop with nothing running sends nothing and says nothing`() {
        val h = harness(maia)
        h.driver.stop()
        assertTrue(h.channel.requests.isEmpty())
        // Asking twice is not a fault.
        assertTrue(h.spoken.isEmpty())
    }

    // ------------------------------------------------------- not sending

    @Test
    fun `a number that is not in the registry is its own answer`() {
        val h = harness(maia)
        h.driver.instruct(ProjectRef.Numbered(41), "go", "project forty one go")
        assertEquals(41, h.last.chooser!!.badNumber)
        assertEquals(Triple(AgentAck.NoProject, 41, null), h.spoken.single())
        assertTrue(h.channel.requests.isEmpty())
    }

    @Test
    fun `a retired number says which project is gone rather than showing a list`() {
        val h = harness(maia, gone)
        h.driver.instruct(ProjectRef.Numbered(4), "go", "project four go")
        assertEquals(RunFault.Retired, h.last.fault)
        assertNull(h.last.chooser)
        assertTrue(h.channel.requests.isEmpty())
        // The screen says what the number was, which is the only place the
        // user ever sees the promise that numbers are never reused being
        // kept. The name comes off the registry row the phone already holds.
        assertEquals("oldthing", h.last.retired?.name)
        assertEquals(4, h.last.retired?.number)
    }

    /**
     * `Discard` on the project list, section 5.10: nothing is sent and the
     * words are forgotten. The held instruction lives on the chooser and
     * nowhere else, so dropping the chooser is the whole of forgetting it.
     */
    @Test
    fun `discarding the list forgets the held instruction`() {
        val h = harness(maia, browser)
        h.driver.instruct(ProjectRef.Unknown("something"), "run the tests", "something run the tests")
        assertEquals("run the tests", h.last.chooser?.held)
        h.driver.dismiss()
        assertNull(h.last.chooser)
        assertNull(h.last.fault)
        assertTrue("nothing was sent, and nothing is sent by leaving", h.channel.requests.isEmpty())
    }

    /** Leaving a fault screen clears the fault and the project behind it. */
    @Test
    fun `dismissing a fault screen clears what it was describing`() {
        val h = harness(maia, gone)
        h.driver.instruct(ProjectRef.Numbered(4), "go", "project four go")
        assertEquals(RunFault.Retired, h.last.fault)
        h.driver.dismiss()
        assertNull(h.last.fault)
        assertNull(h.last.retired)
    }

    @Test
    fun `an ambiguous name shows the shortlist and every active project`() {
        val h = harness(maia, browser, gone)
        h.driver.instruct(ProjectRef.Ambiguous("open", listOf(3, 7)), "go", "open go")
        val chooser = h.last.chooser!!
        assertEquals(listOf(3, 7), chooser.matches.map { it.number })
        // Retired projects are not on the list of things you can address now.
        assertEquals(listOf(3, 7), chooser.all.map { it.number })
        assertEquals(AgentAck.WhichOne, h.spoken.single().first)
        assertTrue(h.channel.requests.isEmpty())
    }

    @Test
    fun `a 401 is refused and not reported as a dead tunnel`() {
        val h = harness(maia)
        h.channel.failWith = AgentException(401, "unauthorized")
        h.driver.instruct(ProjectRef.Numbered(7), "go", "seven go")
        // The three faults have three different fixes, and one "something went
        // wrong" makes the user try all of them.
        assertEquals(RunFault.Refused, h.last.fault)
        assertEquals(AgentAck.Refused, h.spoken.single().first)
    }

    @Test
    fun `no route to the machine is the tunnel fault`() {
        val h = harness(maia)
        h.channel.failWith = IOException("agent request failed: dial tcp: no route to host")
        h.driver.instruct(ProjectRef.Numbered(7), "go", "seven go")
        assertEquals(RunFault.TunnelOff, h.last.fault)
        assertEquals(AgentAck.TunnelOff, h.spoken.single().first)
    }

    @Test
    fun `a silent agent port is not the same fault as a dead tunnel`() {
        val h = harness(maia)
        h.channel.failWith = IOException("agent request failed: dial tcp 127.0.0.1:4096: connection refused")
        h.driver.instruct(ProjectRef.Numbered(7), "go", "seven go")
        assertEquals(RunFault.NoServer, h.last.fault)
        assertEquals(AgentAck.NoAnswer, h.spoken.single().first)
    }

    // ----------------------------------------------------- the silence rule

    @Test
    fun `a heartbeat keeps a long turn alive`() {
        val h = harness(maia)
        h.driver.instruct(ProjectRef.Numbered(7), "run the tests", "seven run the tests")
        // Four minutes of tool calls, heartbeating every ten seconds, is a
        // healthy stream and must not reconnect.
        repeat(24) {
            h.now += 10_000
            h.channel.heartbeat()
            h.fire()
        }
        assertEquals(1, h.channel.streamPaths.size)
        assertTrue(h.last.live)
    }

    @Test
    fun `ninety seconds of nothing reconnects once`() {
        val h = harness(maia)
        h.driver.instruct(ProjectRef.Numbered(7), "run the tests", "seven run the tests")
        h.channel.push("""{"id":"e9","type":"session.next.text.delta","data":{"text":"half a "}}""")

        h.now += SILENCE_MS
        h.fire()
        // The global stream takes no resume cursor, so the reconnect is the
        // same subscribe again and reconciliation recovers whatever was missed.
        assertEquals(
            listOf("/api/event", "/api/event"),
            h.channel.streamPaths,
        )
        assertTrue(h.last.live)

        // And a further thirty seconds is the end of the turn, marked at the
        // point it stopped and never invented past it.
        h.now += RECONNECT_GRACE_MS
        h.fire()
        assertEquals(RunStatus.DidNotFinish, h.last.status)
        assertEquals(EndMarker.Cut, h.last.turn!!.end)
        assertEquals(listOf(ReplyPiece.Prose("half a ")), h.last.turn!!.pieces)
    }

    @Test
    fun `a dropped stream reconnects and the turn can still finish`() {
        val h = harness(maia)
        h.driver.instruct(ProjectRef.Numbered(7), "run the tests", "seven run the tests")
        h.channel.push("""{"id":"e1","type":"session.next.text.delta","data":{"text":"one "}}""")
        h.channel.sink!!.onClosed("unexpected EOF")

        h.channel.push("""{"id":"e2","type":"session.next.text.delta","data":{"text":"two"}}""")
        h.channel.push("""{"id":"e3","type":"session.idle","data":{}}""")
        assertEquals(RunStatus.Finished, h.last.status)
        assertEquals(listOf(ReplyPiece.Prose("one two")), h.last.turn!!.pieces)
    }

    @Test
    fun `nothing at all for ten seconds says so, once`() {
        val h = harness(maia)
        h.driver.instruct(ProjectRef.Numbered(7), "run the tests", "seven run the tests")
        h.now += NOTHING_YET_MS
        h.fire()
        assertTrue(h.last.nothingYet)
        val before = h.states.size
        h.now += NOTHING_YET_MS
        h.fire()
        // It appears once and then nothing further changes: no counting up and
        // no estimating.
        assertEquals(h.states[before - 1], h.last)
    }

    @Test
    fun `a stale tick from a finished turn cannot reconnect the next one`() {
        val h = harness(maia)
        h.driver.instruct(ProjectRef.Numbered(7), "first", "seven first")
        val stale = h.timers.last().second
        h.channel.push("""{"id":"e1","type":"session.idle","data":{}}""")

        h.now += SILENCE_MS * 2
        stale()
        assertEquals(RunStatus.Finished, h.last.status)
        assertEquals(1, h.channel.streamPaths.size)
    }

    // --------------------------------------------------------- the backfill
    //
    // The reconnect path: the global stream takes no resume cursor, so the
    // driver asks `GET /api/session/{id}/history` what it missed, replays it
    // through the same listener the new stream is about to get, and only then
    // opens the stream. History here is exactly what the server returns:
    // entries in order, each with an `id`, a `type`, a `durable.seq` the
    // client does not read, and the `data` a live frame would carry.

    /** History for ses_1, the way the server writes it. */
    private fun historyOf(vararg entries: String): Reply =
        Reply(200, """{"data":[${entries.joinToString(",")}]}""")

    private fun row(id: String, type: String, seq: Int, data: String): String =
        """{"id":"$id","type":"$type","durable":{"seq":$seq},"data":$data}"""

    @Test
    fun `a dropped stream backfills what it missed before resubscribing`() {
        val h = harness(maia)
        h.driver.instruct(ProjectRef.Numbered(7), "run the tests", "seven run the tests")
        h.channel.push("""{"id":"e1","type":"session.next.text.delta","data":{"text":"one "}}""")
        // Armed before the drop: the reconnect asks for it synchronously.
        h.channel.historyReply = historyOf(
            row("e1", "session.next.text.delta", 1, """{"sessionID":"ses_1","text":"one "}"""),
            row("e2", "session.next.text.delta", 2, """{"sessionID":"ses_1","text":"two "}"""),
            row("e3", "session.next.tool.called", 3, """{"sessionID":"ses_1","tool":"bash","command":"./gradlew test"}"""),
        )
        h.channel.sink!!.onClosed("unexpected EOF")

        // History is asked after the drop and before the new stream opens.
        val history = "req:/api/session/ses_1/history?limit=500"
        val firstStream = h.channel.log.indexOf("stream:/api/event")
        assertTrue("history was fetched", h.channel.log.contains(history))
        assertTrue(h.channel.log.indexOf(history) > firstStream)
        assertEquals("stream:/api/event", h.channel.log.last())

        // The gap is on screen exactly as if it had streamed live.
        assertEquals(
            listOf(ReplyPiece.Prose("one two "), ReplyPiece.Tool("bash", "./gradlew test", 1)),
            h.last.turn!!.pieces,
        )
        assertTrue(h.last.live)

        // And the new stream continues from there.
        h.channel.push("""{"id":"e4","type":"session.next.text.delta","data":{"text":"three"}}""")
        h.channel.push("""{"id":"e5","type":"session.idle","data":{}}""")
        assertEquals(RunStatus.Finished, h.last.status)
        assertEquals(
            listOf(
                ReplyPiece.Prose("one two "),
                ReplyPiece.Tool("bash", "./gradlew test", 1),
                ReplyPiece.Prose("three"),
            ),
            h.last.turn!!.pieces,
        )
    }

    @Test
    fun `an event in both the history and the new stream is delivered once`() {
        // The server does not promise the two sources are disjoint: an event
        // emitted inside the gap can sit in the snapshot and still come down
        // the new stream. Appended deltas make a duplicate a printed-twice
        // reply, so the second arrival is dropped on its id.
        val h = harness(maia)
        h.driver.instruct(ProjectRef.Numbered(7), "run the tests", "seven run the tests")
        h.channel.push("""{"id":"e1","type":"session.next.text.delta","data":{"text":"one "}}""")
        h.channel.historyReply = historyOf(
            row("e1", "session.next.text.delta", 1, """{"sessionID":"ses_1","text":"one "}"""),
            row("e2", "session.next.text.delta", 2, """{"sessionID":"ses_1","text":"two"}"""),
        )
        h.channel.sink!!.onClosed("unexpected EOF")

        h.channel.push("""{"id":"e2","type":"session.next.text.delta","data":{"text":"two"}}""")
        h.channel.push("""{"id":"e3","type":"session.idle","data":{}}""")

        assertEquals(RunStatus.Finished, h.last.status)
        assertEquals(listOf(ReplyPiece.Prose("one two")), h.last.turn!!.pieces)
    }

    @Test
    fun `a last id the history does not contain replays nothing`() {
        // An anchor that is not there cannot be told apart from a window that
        // does not reach back far enough, and guessing at a position risks a
        // printed-twice delta. The right amount to replay is none.
        val h = harness(maia)
        h.driver.instruct(ProjectRef.Numbered(7), "run the tests", "seven run the tests")
        h.channel.push("""{"id":"e1","type":"session.next.text.delta","data":{"text":"one "}}""")
        h.channel.historyReply = historyOf(
            row("x1", "session.next.text.delta", 1, """{"sessionID":"ses_1","text":"not it"}"""),
            row("x2", "session.next.text.delta", 2, """{"sessionID":"ses_1","text":"not it either"}"""),
        )
        h.channel.sink!!.onClosed("unexpected EOF")

        assertEquals(listOf(ReplyPiece.Prose("one ")), h.last.turn!!.pieces)
        // The stream still opened: the cursor failing to match is not a
        // failure, it is the same place a reconnect stood before this existed.
        assertEquals(2, h.channel.streamPaths.size)
        assertTrue(h.last.live)

        h.channel.push("""{"id":"e2","type":"session.next.text.delta","data":{"text":"two"}}""")
        assertEquals(listOf(ReplyPiece.Prose("one two")), h.last.turn!!.pieces)
    }

    @Test
    fun `a history fetch that fails still opens the stream`() {
        // Recovery must never fail the turn it was treating: an unreachable
        // history endpoint leaves the reconnect exactly where it was without
        // this feature, and the machine hears nothing about it.
        val h = harness(maia)
        h.driver.instruct(ProjectRef.Numbered(7), "run the tests", "seven run the tests")
        h.channel.push("""{"id":"e1","type":"session.next.text.delta","data":{"text":"one "}}""")
        h.channel.historyFail = IOException("agent request failed: unexpected EOF")
        h.channel.sink!!.onClosed("unexpected EOF")

        assertEquals(2, h.channel.streamPaths.size)
        assertTrue(h.last.live)
        assertNull(h.last.fault)

        h.channel.push("""{"id":"e2","type":"session.next.text.delta","data":{"text":"two"}}""")
        h.channel.push("""{"id":"e3","type":"session.idle","data":{}}""")
        assertEquals(RunStatus.Finished, h.last.status)
        assertEquals(listOf(ReplyPiece.Prose("one two")), h.last.turn!!.pieces)
    }

    @Test
    fun `a backfill that ends the turn does not open a stream`() {
        // If the replay carried the turn's own ending, subscribing anyway
        // would leave a stream heartbeating forever on a turn that never arms
        // another clock check.
        val h = harness(maia)
        h.driver.instruct(ProjectRef.Numbered(7), "run the tests", "seven run the tests")
        h.channel.push("""{"id":"e1","type":"session.next.text.delta","data":{"text":"one "}}""")
        h.channel.historyReply = historyOf(
            row("e1", "session.next.text.delta", 1, """{"sessionID":"ses_1","text":"one "}"""),
            row("e2", "session.next.text.delta", 2, """{"sessionID":"ses_1","text":"two"}"""),
            row("e3", "session.idle", 3, """{"sessionID":"ses_1"}"""),
        )
        h.channel.sink!!.onClosed("unexpected EOF")

        assertEquals(RunStatus.Finished, h.last.status)
        assertEquals(EndMarker.Done, h.last.turn!!.end)
        assertEquals(listOf(ReplyPiece.Prose("one two")), h.last.turn!!.pieces)
        assertEquals(1, h.channel.streamPaths.size)
    }
}
