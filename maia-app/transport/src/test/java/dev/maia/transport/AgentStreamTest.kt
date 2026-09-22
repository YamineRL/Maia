package dev.maia.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable

/**
 * What a subscriber is told, with no network anywhere.
 *
 * The rule under test is PRD section 9's stall rule: "no frame of any kind for
 * 90 seconds, not even a heartbeat". That rule is only implementable through
 * this client if a heartbeat reaches an [EventListener], and until 2026-09-18
 * it did not: [AgentClient.subscribe] dropped comment lines and unparseable
 * frames on the floor. The cost was a second copy of the SSE parser in another
 * module, watching [SseAssembler.comments] directly over a raw
 * [AgentChannel]. These tests exist so that cannot come back quietly.
 */
class AgentStreamTest {

    /** Everything a listener heard, in order, as a readable script. */
    private class Recorder : EventListener {
        val log = mutableListOf<String>()
        override fun onEvent(event: AgentEvent) {
            log += "event:${event.type}"
        }
        override fun onAlive() {
            log += "alive"
        }
        override fun onClosed(reason: String) {
            log += "closed:$reason"
        }
    }

    /** Replays a fixed script of lines into whatever subscribes. */
    private class ScriptedChannel(private val lines: List<String>) : AgentChannel {
        var streamedPath: String? = null

        override fun request(method: String, path: String, body: String?) =
            throw UnsupportedOperationException("this test only streams")

        override fun stream(path: String, sink: LineSink): Closeable {
            streamedPath = path
            // Synchronous on purpose. The threading is LoopbackChannel's
            // concern and is exercised live; what is under test here is the
            // order of the callbacks, which a background thread would only
            // make harder to assert.
            lines.forEach { sink.onLine(it) }
            sink.onClosed("")
            return Closeable {}
        }

        override fun close() = Unit
    }

    /** Answers one canned [Reply] to `history`, and records what was asked. */
    private class HistoryChannel(private val reply: Reply) : AgentChannel {
        var requested: String? = null

        override fun request(method: String, path: String, body: String?): Reply {
            requested = "$method $path"
            return reply
        }

        override fun stream(path: String, sink: LineSink) =
            throw UnsupportedOperationException("this test only requests")

        override fun close() = Unit
    }

    private val session = Session("ses_1", "prj_1", "fusion", "/r/maia")

    private fun run(lines: List<String>): Recorder {
        val recorder = Recorder()
        AgentClient(ScriptedChannel(lines)).sessionEvents(session, recorder)
        return recorder
    }

    @Test
    fun `a heartbeat reaches the listener`() {
        // The whole point. Before this, a stream of nothing but heartbeats was
        // indistinguishable from a dead link to anyone using this client.
        val heard = run(listOf(": heartbeat", "", ": heartbeat", ""))
        assertEquals(listOf("alive", "alive", "closed:"), heard.log)
    }

    @Test
    fun `liveness arrives before the event it belongs to`() {
        val heard = run(
            listOf(
                """data: {"id":"evt_01","type":"server.connected","data":{}}""",
                "",
                ": heartbeat",
                "",
                """data: {"id":"evt_02","type":"session.idle","data":{"sessionID":"ses_1"}}""",
                "",
            )
        )
        assertEquals(
            listOf(
                "alive",
                "event:${EventType.SERVER_CONNECTED}",
                "alive",
                "alive",
                "event:${EventType.SESSION_IDLE}",
                "closed:",
            ),
            heard.log,
        )
    }

    @Test
    fun `a frame that does not parse is still proof the link is alive`() {
        // The subtle half of the defect. An event type this build has never
        // heard of, or a frame that is not JSON, must not look like silence:
        // that is how a working stream gets torn down by a stall timer.
        val heard = run(listOf("data: not json at all", "", """data: {"no":"type"}""", ""))
        assertEquals(listOf("alive", "alive", "closed:"), heard.log)
        assertTrue("nothing parseable was sent", heard.log.none { it.startsWith("event:") })
    }

    @Test
    fun `the blank line terminating a frame is not counted twice`() {
        val heard = run(listOf("""data: {"id":"e","type":"session.idle","data":{}}""", ""))
        assertEquals(
            "one event must produce exactly one liveness signal",
            1,
            heard.log.count { it == "alive" },
        )
    }

    @Test
    fun `onAlive is optional, so an existing listener still compiles and runs`() {
        // The default no-op is the compatibility promise. A listener written
        // before this callback existed must keep working untouched.
        val events = mutableListOf<String>()
        val old = object : EventListener {
            override fun onEvent(event: AgentEvent) { events += event.type }
            override fun onClosed(reason: String) {}
        }
        AgentClient(ScriptedChannel(listOf(": heartbeat", "", """data: {"type":"session.idle"}""", "")))
            .sessionEvents(session, old)
        assertEquals(listOf(EventType.SESSION_IDLE), events)
    }

    @Test
    fun `the resume cursor comes from the event, which is where an id exists`() {
        // Why onAlive carries no id: a heartbeat has none. A consumer tracks
        // the cursor in onEvent, and this is the shape of that.
        var cursor: String? = null
        val listener = object : EventListener {
            override fun onEvent(event: AgentEvent) { cursor = event.id ?: cursor }
            override fun onClosed(reason: String) {}
        }
        AgentClient(
            ScriptedChannel(
                listOf(
                    """data: {"id":"evt_01","type":"session.next.text.delta","data":{}}""",
                    "",
                    ": heartbeat",
                    "",
                )
            )
        ).sessionEvents(session, listener)
        assertEquals("a heartbeat must not clear the cursor", "evt_01", cursor)
    }

    @Test
    fun `the session stream is the global stream scoped to the session`() {
        // The per-session route hangs on the pinned server (opencode
        // 1.18.31): the connection opens and no byte ever arrives. Scoping is
        // client-side, so the path on the wire is the global stream.
        val channel = ScriptedChannel(emptyList())
        AgentClient(channel).sessionEvents(session, Recorder(), after = "evt_01")
        assertEquals("/api/event", channel.streamedPath)
    }

    @Test
    fun `another session's events never reach the listener`() {
        // A directory is not a session: two sessions can share one, so a
        // phone watching ses_1 must not see ses_2's deltas, idle or blocks.
        val heard = run(
            listOf(
                """data: {"id":"e1","type":"session.next.text.delta","data":{"sessionID":"ses_2","delta":"nope"}}""",
                "",
                """data: {"id":"e2","type":"session.idle","data":{"sessionID":"ses_2"}}""",
                "",
                """data: {"id":"e3","type":"session.next.text.delta","data":{"sessionID":"ses_1","delta":"yes"}}""",
                "",
                """data: {"id":"e4","type":"session.idle","data":{"sessionID":"ses_1"}}""",
                "",
            )
        )
        assertEquals(
            listOf(
                "alive",
                "alive",
                "alive",
                "event:${EventType.TEXT_DELTA}",
                "alive",
                "event:${EventType.SESSION_IDLE}",
                "closed:",
            ),
            heard.log,
        )
    }

    @Test
    fun `a sessionless event reaches every subscriber`() {
        // server.connected has an empty payload and so no sessionID. It is
        // the machine's reconcile trigger after a reconnect, and dropping it
        // for carrying no session would break that.
        val heard = run(listOf("""data: {"id":"e","type":"server.connected","data":{}}""", ""))
        assertEquals(
            listOf("alive", "event:${EventType.SERVER_CONNECTED}", "closed:"),
            heard.log,
        )
    }

    // ---- the event vocabulary -------------------------------------------

    @Test
    fun `a tool call is named, and is neither terminal nor blocking`() {
        assertEquals("session.next.tool.called", EventType.TOOL_CALLED)

        // Not terminal: a tool call is the middle of a turn. Ending the turn
        // here would cut off most of the output.
        assertTrue(
            "a tool call does not end a turn",
            EventType.TOOL_CALLED !in EventType.TERMINAL,
        )

        // Not blocking, which is the easier mistake: agent-web.sh pre-approves
        // every tool, so almost no tool call wants a human. The two holes left
        // open there arrive as permission.asked instead.
        assertTrue(
            "a tool call must not notify a phone on every file read",
            EventType.TOOL_CALLED !in EventType.BLOCKING,
        )
        assertTrue(EventType.PERMISSION_ASKED in EventType.BLOCKING)
    }

    @Test
    fun `a tool call parses off the wire and is delivered as an event`() {
        val heard = run(
            listOf(
                """data: {"id":"evt_07","type":"session.next.tool.called",""" +
                    """"data":{"sessionID":"ses_1","tool":"read"}}""",
                "",
            )
        )
        assertEquals(listOf("alive", "event:${EventType.TOOL_CALLED}", "closed:"), heard.log)
    }

    // ---- the backfill source ---------------------------------------------
    //
    // `GET /api/session/{id}/history` is what a reconnect recovers the gap
    // from, because the global stream takes no resume cursor. These pin the
    // wire shape: the `data` envelope, entries in order, and a bound on how
    // much of a long session one fetch may pull.

    @Test
    fun `history returns the session's events in order`() {
        val channel = HistoryChannel(
            Reply(
                200,
                """{"data":[""" +
                    """{"id":"evt_1","type":"session.next.text.delta","durable":{"seq":41},""" +
                    """"data":{"sessionID":"ses_1","text":"a"}},""" +
                    """{"id":"evt_2","type":"session.idle","durable":{"seq":42},""" +
                    """"data":{"sessionID":"ses_1"}}""" +
                    """]}""",
            )
        )
        val events = AgentClient(channel).history(session)

        // Bounded, always: history is the whole session and a reconnect only
        // ever wants the tail of it.
        assertEquals("GET /api/session/ses_1/history?limit=500", channel.requested)
        assertEquals(listOf("evt_1", "evt_2"), events.map { it.id })
        assertEquals(listOf(EventType.TEXT_DELTA, EventType.SESSION_IDLE), events.map { it.type })
        // The payload is the entry's `data`, read exactly as a live frame's.
        assertEquals("a", events[0].payload.string("text"))
        assertEquals("ses_1", events[0].sessionId)
    }

    @Test
    fun `a history row without a type is skipped, like a frame that does not parse`() {
        // The same rule the assembler applies to the live stream: the union
        // grows, and one row this build does not read must not cost the rest.
        val channel = HistoryChannel(
            Reply(
                200,
                """{"data":[""" +
                    """{"id":"evt_1","durable":{"seq":1},"data":{"sessionID":"ses_1"}},""" +
                    """{"id":"evt_2","type":"session.idle","durable":{"seq":2},""" +
                    """"data":{"sessionID":"ses_1"}}""" +
                    """]}""",
            )
        )
        val events = AgentClient(channel).history(session)
        assertEquals(listOf("evt_2"), events.map { it.id })
    }

    @Test
    fun `a refused history is the caller's problem, not this method's`() {
        // The driver swallows this and still opens the stream: recovery that
        // could fail the turn would be worse than the drop it was treating.
        // Here it simply has to surface as the status it was.
        val channel = HistoryChannel(Reply(503, "busy"))
        try {
            AgentClient(channel).history(session)
            throw AssertionError("expected AgentException")
        } catch (e: AgentException) {
            assertEquals(503, e.status)
        }
    }
}
