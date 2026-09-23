package dev.maia.app.agent

import dev.maia.app.feel.Pattern
import dev.maia.nlu.agent.ProjectRef
import dev.maia.transport.AgentChannel
import dev.maia.transport.AgentClient
import dev.maia.transport.LineSink
import dev.maia.transport.ProjectEntry
import dev.maia.transport.ProjectState
import dev.maia.transport.Reply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.Executor

/**
 * A live stream to a posted notification, wired exactly as [AgentHost] wires
 * it, against a fake at the [AgentChannel] seam.
 *
 * [RunAlertsTest] pins the decision and this pins the route: that the driver
 * renders before it performs, so the state [RunAlerts] reads is the state the
 * event produced rather than the one before it. Get that backwards and a
 * blocked notification is posted for a block the state does not have yet,
 * which is a notification with no kind and no body.
 *
 * Nothing here reaches a real agent. Prompting one runs the paid lead model,
 * and a test that costs money is a test people stop running.
 */
class AgentAlertRouteTest {

    private val maia = ProjectEntry(7, "maia", "/home/user/maia", ProjectState.ACTIVE)

    private class FakeChannel : AgentChannel {
        var sink: LineSink? = null
        var failWith: Exception? = null

        override fun request(method: String, path: String, body: String?): Reply {
            failWith?.let { throw it }
            return when {
                path == "/api/session" -> Reply(200, """{"data":{"id":"ses_1","projectID":"p1","agent":"fusion"}}""")
                path.endsWith("/prompt") ->
                    Reply(200, """{"data":{"id":"msg_1","sessionID":"ses_1","delivery":"steer"}}""")
                else -> Reply(204, "")
            }
        }

        override fun stream(path: String, sink: LineSink): Closeable {
            this.sink = sink
            return Closeable { }
        }

        override fun close() = Unit

        fun push(json: String) {
            sink!!.onLine("data: $json")
            sink!!.onLine("")
        }

        fun die(reason: String) = sink!!.onClosed(reason)
    }

    /** The driver, the sinks and the alert router, joined as `AgentHost.build` joins them. */
    private class Wiring(val channel: FakeChannel = FakeChannel()) {
        var state = RunState()
        val posted = mutableListOf<RunAlerts.Posting>()
        val hand = mutableListOf<Pattern>()
        val cleared = mutableListOf<RunAlert>()

        /** What `RunService.follow` reads, recorded at every render. */
        val serviceLive = mutableListOf<Boolean>()

        val alerts = RunAlerts(
            state = { state },
            hand = { hand += it },
            post = { posted += it },
            clear = { cleared += it },
        )

        var projects = listOf<ProjectEntry>()

        val driver = AgentDriver(
            client = AgentClient(channel),
            projects = { projects },
            render = { rendered ->
                state = rendered
                alerts.render(rendered)
                serviceLive += rendered.live
            },
            speak = { _, _, _ -> },
            feel = alerts::feel,
            feelByHand = alerts::feelByHand,
            clock = { 1_000L },
            work = Executor { it.run() },
            timer = { _, _ -> },
        )
    }

    private fun wiring(vararg entries: ProjectEntry) = Wiring().also { it.projects = entries.toList() }

    @Test
    fun `a turn that finishes posts one ended notification and no others`() {
        val w = wiring(maia)
        w.driver.instruct(ProjectRef.Numbered(7), "run the tests", "project seven run the tests")
        w.channel.push("""{"id":"e1","type":"session.next.text.delta","data":{"text":"Ran 14 tests"}}""")
        w.channel.push("""{"id":"e2","type":"session.idle","data":{}}""")

        val ended = w.posted.single()
        assertEquals(RunAlert.Ended, ended.alert)
        assertEquals(maia, ended.project)
        assertNull(ended.kind)
        assertNull(ended.loss)
        // Two instants and no duration: the words are chosen downstream, by
        // RunDuration, out of a closed vocabulary of fourteen strings.
        assertTrue(ended.endedAt >= ended.startedAt)
        // The sent buzz was the hand's and stayed there.
        assertEquals(1, w.hand.size)
        // And the service was asked to run and then to stop, in that order.
        assertTrue(w.serviceLive.first())
        assertFalse(w.serviceLive.last())
    }

    @Test
    fun `a block posts with the kind the event carried`() {
        val w = wiring(maia)
        w.driver.instruct(ProjectRef.Numbered(7), "deploy it", "project seven deploy it")
        w.channel.push(
            """{"id":"e1","type":"permission.asked","data":{"id":"req_4","type":"permission","title":"run rm -rf"}}""",
        )

        val posting = w.posted.single()
        assertEquals(RunAlert.Blocked, posting.alert)
        assertEquals(BlockKind.Permission, posting.kind)
        assertEquals(maia, posting.project)
        // The title of that permission request is agent text. It is nowhere
        // in the posting, because there is no field it could be in: an enum,
        // a project, two more enums and two longs. Every field here is either
        // the phone's own fact or a value from a closed set, and the list is
        // pinned so that adding a String to this class fails here first.
        assertEquals(
            setOf("alert", "project", "kind", "loss", "startedAt", "endedAt"),
            RunAlerts.Posting::class.java.declaredFields
                .filterNot { it.isSynthetic || it.name.startsWith("$") }
                .map { it.name }
                .toSet(),
        )
    }

    @Test
    fun `a block that resolves withdraws its notification before the next one is posted`() {
        val w = wiring(maia)
        w.driver.instruct(ProjectRef.Numbered(7), "deploy it", "project seven deploy it")
        w.channel.push(
            """{"id":"e1","type":"permission.asked","data":{"id":"req_4","type":"permission","title":"run rm -rf"}}""",
        )
        assertEquals(1, w.posted.size)

        // A delta while the agent is still waiting does not resolve anything:
        // the block stands until it is answered, and so does its notification.
        w.channel.push("""{"id":"e2","type":"session.next.text.delta","data":{"text":"still here"}}""")
        assertTrue(w.cleared.isEmpty())

        w.channel.push("""{"id":"e3","type":"session.idle","data":{}}""")
        assertEquals(listOf(RunAlert.Blocked), w.cleared)
        assertEquals(listOf(RunAlert.Blocked, RunAlert.Ended), w.posted.map { it.alert })
    }

    @Test
    fun `a stream that dies mid turn posts a failure`() {
        val w = wiring(maia)
        w.driver.instruct(ProjectRef.Numbered(7), "run the tests", "project seven run the tests")
        w.channel.push("""{"id":"e1","type":"session.next.text.delta","data":{"text":"half a"}}""")
        // One reconnect is all there is, and the second loss ends the turn.
        w.channel.die("connection reset")
        w.channel.die("connection reset")

        assertEquals(listOf(RunAlert.Failed), w.posted.map { it.alert })
        assertEquals(EndMarker.Cut, w.state.turn!!.end)
        assertFalse(w.serviceLive.last())
    }

    @Test
    fun `a tunnel that is down says so on the screen and posts nothing`() {
        val w = wiring(maia)
        w.channel.failWith = java.io.IOException("maiatunnel: tunnel dial: dial tcp: no route to host")
        w.driver.instruct(ProjectRef.Numbered(7), "run the tests", "project seven run the tests")

        assertEquals(RunFault.TunnelOff, w.state.fault)
        assertTrue("a fault before anything was sent posted a notification", w.posted.isEmpty())
        // The send went live for exactly one render and then stopped, so the
        // service is asked to stop in the same breath it was asked to start.
        // That is the honest reading of `live`, and `RunService.follow` is
        // wrapped for the case where the start itself is refused.
        assertFalse(w.serviceLive.last())
    }

    @Test
    fun `a number with no project posts nothing`() {
        val w = wiring(maia)
        w.driver.instruct(ProjectRef.Numbered(4), "run the tests", "project four run the tests")
        assertTrue(w.posted.isEmpty())
    }
}
