package dev.maia.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The client against the real OpenCode server on the devbox.
 *
 * Skipped unless `MAIA_AGENT_PASSWORD` is set, so an ordinary `gradle test`
 * on a machine without the server passes rather than failing for a reason
 * nobody can act on. Run it with the credentials in the environment:
 *
 * ```sh
 * export MAIA_AGENT_PASSWORD="$(grep OPENCODE_SERVER_PASSWORD ~/.config/opencode/agent-web.env | cut -d= -f2-)"
 * ./gradlew :transport:test --tests '*AgentLiveTest'
 * ```
 *
 * It never sends a prompt. A prompt runs the paid lead model, and a test suite
 * that spends money every time it runs is a test suite people stop running.
 * What it proves is everything up to that point: that the passphrase is the
 * lock, that one server really does serve many projects by request, that a
 * session can be created and named a harness, and that the event stream opens
 * and frames the way [SseTest] says it does.
 */
class AgentLiveTest {

    private val host = System.getenv("MAIA_AGENT_HOST") ?: "127.0.0.1"
    private val port = System.getenv("MAIA_AGENT_PORT")?.toIntOrNull() ?: 4096
    // Blank counts as absent: an empty user is not the default user, it is
    // a credential the server refuses, and it took a red test to notice.
    private val user = System.getenv("MAIA_AGENT_USER")?.ifBlank { null } ?: "opencode"
    private val password = System.getenv("MAIA_AGENT_PASSWORD")?.ifBlank { null }

    private val maia = "/home/user/projects/maia"
    private val fusion = "/home/user/projects/agentharnessfork"

    private fun channel(pass: String = password!!) = LoopbackChannel(host, port, user, pass)

    @org.junit.Before
    fun serverIsThere() {
        assumeTrue("MAIA_AGENT_PASSWORD is not set, skipping the live test", password != null)
    }

    @Test
    fun `the passphrase is the whole lock`() {
        // With tool permissions open this is the only thing between any app on
        // the phone and arbitrary code execution on this box, so it is worth a
        // test that fails loudly the day someone starts the server without it.
        channel("definitely-not-the-passphrase").use { wrong ->
            val reply = wrong.request("GET", "/project/current?directory=$maia")
            assertEquals("a wrong passphrase must be refused", 401, reply.status)
        }
        channel().use { right ->
            assertTrue(right.request("GET", "/project/current?directory=$maia").ok)
        }
    }

    @Test
    fun `one server, many projects, resolved per request`() {
        channel().use { c ->
            val client = AgentClient(c)
            val a = client.currentProject(maia)
            val b = client.currentProject(fusion)
            assertEquals(maia, a.worktree)
            assertEquals(fusion, b.worktree)
            assertNotEquals("two directories must not resolve to one project", a.id, b.id)
        }
    }

    @Test
    fun `a session is created with the harness named`() {
        channel().use { c ->
            val client = AgentClient(c)
            val session = client.createSession(maia)
            var deleted = Reply(-1, "the finally block did not run")
            try {
                assertTrue("session id: ${session.id}", session.id.startsWith("ses"))
                assertEquals("fusion", session.agent)
                assertEquals(client.currentProject(maia).id, session.projectId)
            } finally {
                // Throwaway sessions are visible in the web UI forever
                // otherwise, and a test that litters the user's session list
                // every run is a test that gets deleted instead of the
                // sessions. The delete is on the older route, which wants the
                // directory like every other per-request call.
                deleted = c.request("DELETE", "/session/${session.id}?directory=$maia")
            }
            assertTrue("left ${session.id} behind: HTTP ${deleted.status}", deleted.ok)
        }
    }

    @Test
    fun `the event stream opens and frames as expected`() {
        channel().use { c ->
            val client = AgentClient(c)
            val connected = CountDownLatch(1)
            val types = mutableListOf<String>()
            val stream = client.events(maia, object : EventListener {
                override fun onEvent(event: AgentEvent) {
                    synchronized(types) { types.add(event.type) }
                    if (event.type == EventType.SERVER_CONNECTED) connected.countDown()
                }

                override fun onClosed(reason: String) = Unit
            })
            stream.use {
                assertTrue(
                    "no server.connected within 10s, saw $types",
                    connected.await(10, TimeUnit.SECONDS),
                )
            }
        }
    }
}
