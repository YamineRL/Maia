package dev.maia.app.answer

import dev.maia.app.agent.GoAgent
import dev.maia.app.agent.GoReply
import dev.maia.app.agent.GoStream
import dev.maia.app.agent.TunnelChannel
import dev.maia.transport.LineSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable

/**
 * The assistant credential's one road into Go, driven without `libgojni.so`.
 *
 * The seam is the same one `TunnelChannelTest` works: [GoAgent] is the
 * gomobile boundary, and everything above it is exercised here. What this
 * class adds over the agent's channel is a single adaptation worth a test:
 * `authorise(credential)` becomes `setBasicAuth("maia", credential)`,
 * because `maiatunnel` can only send basic and the gateway accepts the
 * password half.
 */
class AssistantTunnelChannelTest {

    private class FakeGoAgent : GoAgent {
        var user: String? = null
        var password: String? = null
        val requests = mutableListOf<Triple<String, String, String>>()
        val streamed = mutableListOf<String>()
        var reply = GoReply(200, "{}")
        var networkChanges = 0
        var closed = false

        override fun setBasicAuth(user: String, password: String) {
            this.user = user
            this.password = password
        }

        override fun setTimeoutMillis(ms: Long) = Unit

        override fun request(method: String, path: String, body: String): GoReply {
            requests += Triple(method, path, body)
            return reply
        }

        override fun stream(path: String, onLine: (String) -> Unit, onClosed: (String) -> Unit): GoStream {
            streamed += path
            return object : GoStream {
                override fun close() = Unit
            }
        }

        override fun networkChanged() {
            networkChanges++
        }

        override fun close() {
            closed = true
        }
    }

    private class Recorder : LineSink {
        val lines = mutableListOf<String>()
        val closes = mutableListOf<String>()
        override fun onLine(line: String) {
            lines += line
        }

        override fun onClosed(reason: String) {
            closes += reason
        }
    }

    private fun channel(go: FakeGoAgent) = AssistantTunnelChannel(TunnelChannel(go))

    @Test
    fun `authorise pushes the credential as the password half of basic`() {
        val go = FakeGoAgent()
        channel(go).authorise("the-gateway-token")
        // "maia" is a label; the credential is the password. The gateway
        // accepts the pair's second half, which is all maiatunnel can send.
        assertEquals("maia", go.user)
        assertEquals("the-gateway-token", go.password)
    }

    @Test
    fun `request and stream pass through to the tunnel`() {
        val go = FakeGoAgent().apply { reply = GoReply(200L, """{"type":"answer","text":"hi"}""") }
        val channel = channel(go)
        val reply = channel.request("POST", "/assistant/chat", """{"utterance":"hi"}""")
        assertEquals(200, reply.status)
        assertEquals(Triple("POST", "/assistant/chat", """{"utterance":"hi"}"""), go.requests.single())
        val sink = Recorder()
        val handle: Closeable = channel.stream("/assistant/stream", sink)
        assertEquals(listOf("/assistant/stream"), go.streamed)
        handle.close()
    }

    @Test
    fun `the credential never enters a path or a body`() {
        val go = FakeGoAgent()
        val secret = "correct horse battery staple"
        val channel = channel(go)
        channel.authorise(secret)
        channel.request("POST", "/assistant/chat", """{"utterance":"why do leaves change colour"}""")
        channel.stream("/assistant/stream", Recorder())
        assertTrue(go.requests.none { it.second.contains(secret) || it.third.contains(secret) })
        assertTrue(go.streamed.none { it.contains(secret) })
        assertEquals(secret, go.password)
    }

    @Test
    fun `closing clears the credential before it closes the agent`() {
        val go = FakeGoAgent()
        val channel = channel(go)
        channel.authorise("a-token")
        channel.close()
        // A channel that is closed but still referenced must not be able to
        // send the only lock this port has.
        assertEquals("", go.password)
        assertTrue(go.closed)
    }

    @Test
    fun `a network change reaches Go`() {
        val go = FakeGoAgent()
        val channel = channel(go)
        channel.networkChanged()
        assertEquals(1, go.networkChanges)
    }
}
