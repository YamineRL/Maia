package dev.maia.app.agent

import dev.maia.transport.LineSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The seam, driven without `libgojni.so`.
 *
 * A JVM unit test cannot load the gomobile binding, and that is the whole
 * reason [GoAgent] exists: every adaptation that could be wrong lives above it
 * and is exercised here. The one thing not covered is [BoundAgent], which
 * forwards five calls and decides nothing.
 */
class TunnelChannelTest {

    private class FakeGoAgent : GoAgent {
        var user: String? = null
        var password: String? = null
        var timeout: Long? = null
        var closed = false
        val requests = mutableListOf<Triple<String, String, String>>()
        val streamed = mutableListOf<String>()
        var reply = GoReply(200, "{}")
        var throwOnRequest: Exception? = null
        var lastStream: FakeStream? = null

        override fun setBasicAuth(user: String, password: String) {
            this.user = user
            this.password = password
        }

        override fun setTimeoutMillis(ms: Long) {
            timeout = ms
        }

        override fun request(method: String, path: String, body: String): GoReply {
            requests += Triple(method, path, body)
            throwOnRequest?.let { throw it }
            return reply
        }

        override fun stream(path: String, onLine: (String) -> Unit, onClosed: (String) -> Unit): GoStream {
            streamed += path
            return FakeStream(onLine, onClosed).also { lastStream = it }
        }

        var networkChanges = 0
        override fun networkChanged() {
            networkChanges++
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeStream(
        val onLine: (String) -> Unit,
        val onClosed: (String) -> Unit,
    ) : GoStream {
        var closeCalls = 0
        override fun close() {
            closeCalls++
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

    // ---- the three adaptations, each of which has been a bug somewhere ----

    @Test
    fun `a Go long status becomes an Int`() {
        val go = FakeGoAgent().apply { reply = GoReply(401L, "") }
        val reply = TunnelChannel(go).request("GET", "/api/session", null)
        // The hazard is not the width, it is that 401L == 401 is false in
        // Kotlin, so a client comparing against an Int silently never sees the
        // one status the whole auth screen hangs off.
        assertEquals(401, reply.status)
    }

    @Test
    fun `a null body goes down as empty and comes up as empty`() {
        val go = FakeGoAgent().apply { reply = GoReply(204L, "") }
        val reply = TunnelChannel(go).request("DELETE", "/api/session/abc", null)
        assertEquals("", go.requests.single().third)
        assertEquals("", reply.body)
    }

    @Test
    fun `any Go failure arrives as an IOException carrying its text`() {
        val go = FakeGoAgent().apply { throwOnRequest = RuntimeException("dial tcp: no route to host") }
        val thrown = runCatching { TunnelChannel(go).request("GET", "/api/session", null) }.exceptionOrNull()
        assertTrue(thrown is IOException)
        // Section 9 names "tunnel down" and "port silent" as different screens
        // and Go's error text is the only thing that distinguishes them, so it
        // is carried rather than flattened.
        assertTrue(thrown!!.message!!.contains("no route to host"))
    }

    @Test
    fun `an IOException from Go is not wrapped twice`() {
        val original = IOException("broken pipe")
        val go = FakeGoAgent().apply { throwOnRequest = original }
        val thrown = runCatching { TunnelChannel(go).request("GET", "/api/session", null) }.exceptionOrNull()
        assertSame(original, thrown)
    }

    // ---- the contract ----

    @Test
    fun `a path without a leading slash is refused before it is sent`() {
        val go = FakeGoAgent()
        assertThrowsIllegalArgument { TunnelChannel(go).request("GET", "api/session", null) }
        assertThrowsIllegalArgument { TunnelChannel(go).stream("event", Recorder()) }
        assertTrue(go.requests.isEmpty())
        assertTrue(go.streamed.isEmpty())
    }

    @Test
    fun `a closed channel refuses to send and refuses to stream`() {
        val go = FakeGoAgent()
        val channel = TunnelChannel(go)
        channel.close()
        assertTrue(runCatching { channel.request("GET", "/api/session", null) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { channel.stream("/event", Recorder()) }.exceptionOrNull() is IOException)
    }

    @Test
    fun `closing clears the credential before it closes the agent`() {
        val go = FakeGoAgent()
        val channel = TunnelChannel(go)
        channel.authorise("opencode", "six-word-thing-goes-right-here")
        channel.close()
        // A channel that is closed but still referenced must not be able to
        // send the only lock facing the phone.
        assertEquals("", go.password)
        assertTrue(go.closed)
    }

    @Test
    fun `closing twice closes the agent once`() {
        val go = FakeGoAgent()
        val channel = TunnelChannel(go)
        channel.close()
        go.closed = false
        channel.close()
        assertFalse(go.closed)
    }

    @Test
    fun `the passphrase never appears in a path or a body`() {
        val go = FakeGoAgent()
        val channel = TunnelChannel(go)
        val secret = "coral-anvil-tundra-quartz-melon-drift"
        channel.authorise("opencode", secret)
        channel.request("POST", "/api/session/s1/prompt", """{"parts":[{"text":"hello"}]}""")
        channel.stream("/event?directory=%2Fhome%2Fuser", Recorder())
        // OpenCode accepts `?auth_token=` at parity with the header, and a
        // credential in a URL lands in every log and every crash report. There
        // is no shape here that could put it there: the only road is
        // setBasicAuth.
        assertTrue(go.requests.none { it.second.contains(secret) || it.third.contains(secret) })
        assertTrue(go.streamed.none { it.contains(secret) })
        assertEquals(secret, go.password)
    }

    @Test
    fun `the timeout bounds a request and the open method sets it`() {
        val go = FakeGoAgent()
        TunnelChannel(go).timeoutMillis(TunnelChannel.REQUEST_TIMEOUT_MS)
        assertEquals(TunnelChannel.REQUEST_TIMEOUT_MS, go.timeout)
    }

    @Test
    fun `a network change reaches Go`() {
        val go = FakeGoAgent()
        val channel = TunnelChannel(go)
        channel.networkChanged()
        channel.networkChanged()
        // Forwarded each time rather than coalesced here: deciding whether a
        // change is real is NetFacts' job above and Go's below, and a channel
        // that filtered would hide a push its callers counted on.
        assertEquals(2, go.networkChanges)
    }

    // ---- the once-only close ----

    @Test
    fun `onClosed arrives exactly once when Go reports it twice`() {
        val go = FakeGoAgent()
        val sink = Recorder()
        TunnelChannel(go).stream("/event", sink)
        go.lastStream!!.onClosed("")
        go.lastStream!!.onClosed("unexpected EOF")
        // The run machine counts a close as the end of a turn. A second one
        // would mark an already finished turn as cut short, which is the one
        // thing rule 12 forbids: a false claim about how a turn ended.
        assertEquals(listOf(""), sink.closes)
    }

    @Test
    fun `closing a stream then hearing from Go still reports one close`() {
        val go = FakeGoAgent()
        val sink = Recorder()
        val handle = TunnelChannel(go).stream("/event", sink)
        handle.close()
        assertEquals(1, go.lastStream!!.closeCalls)
        go.lastStream!!.onClosed("context canceled")
        assertEquals(1, sink.closes.size)
    }

    @Test
    fun `lines after a close are dropped`() {
        val go = FakeGoAgent()
        val sink = Recorder()
        TunnelChannel(go).stream("/event", sink)
        go.lastStream!!.onLine("data: {}")
        go.lastStream!!.onClosed("")
        go.lastStream!!.onLine("data: {\"late\":true}")
        assertEquals(listOf("data: {}"), sink.lines)
    }

    @Test
    fun `tearing the channel down under a live stream still ends it`() {
        val go = FakeGoAgent()
        val sink = Recorder()
        val channel = TunnelChannel(go)
        val handle = channel.stream("/event", sink)
        channel.close()
        handle.close()
        // Go's own OnClosed follows a cancel, and this is the backstop for
        // when it cannot: nobody would otherwise hear the end of the turn.
        assertEquals(listOf(""), sink.closes)
    }

    @Test
    fun `nothing in the channel holds the passphrase`() {
        // There is no getter, by construction. This asserts the construction:
        // if a property is ever added that returns it, this fails.
        val holders = TunnelChannel::class.java.declaredFields.map { it.name }
        assertNull(holders.firstOrNull { it.contains("pass", ignoreCase = true) })
        assertNull(holders.firstOrNull { it.contains("secret", ignoreCase = true) })
    }

    private fun assertThrowsIllegalArgument(block: () -> Unit) {
        assertTrue(runCatching(block).exceptionOrNull() is IllegalArgumentException)
    }
}
