package dev.maia.transport

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.IOException

/**
 * The conversational gateway's wire contract and outcome mapping, with no
 * network anywhere.
 *
 * What is pinned here is the shape the devbox `assistant-web` service answers
 * to: one POST to `/assistant/chat`, a bearer credential carried by the
 * channel rather than by the request, client-side caps applied before
 * sending, and a reply union of exactly four meanings. The fake is a
 * [StubChannel], the same seam every other test in this module uses, because
 * the channel is where the credential lives and where the exchange happens.
 */
class AssistantClientTest {

    /**
     * Records everything it was asked, then answers one canned [Reply], fails
     * the exchange, or sits on the request to act as a slow gateway.
     */
    private class StubChannel(
        private val reply: Reply = Reply(200, "{}"),
        private val fail: String? = null,
        private val latencyMillis: Long = 0,
    ) : AssistantChannel {

        /** The calls, in order, so a test can see auth came before the ask. */
        val calls = mutableListOf<String>()
        var credential: String? = null
            private set
        var method: String? = null
            private set
        var path: String? = null
            private set
        var body: String? = null
            private set

        override fun authorise(credential: String) {
            calls += "authorise"
            this.credential = credential
        }

        override fun request(method: String, path: String, body: String?): Reply {
            calls += "request"
            this.method = method
            this.path = path
            this.body = body
            if (latencyMillis > 0) Thread.sleep(latencyMillis)
            if (fail != null) throw IOException(fail)
            return reply
        }

        override fun stream(path: String, sink: LineSink): Closeable =
            throw UnsupportedOperationException("the assistant is one POST, not a stream")

        override fun close() = Unit
    }

    private fun client(
        channel: StubChannel,
        timeoutMillis: Long = AssistantClient.TIMEOUT_MS,
    ) = AssistantClient(channel, "the-secret", timeoutMillis)

    // ---- the reply union --------------------------------------------------

    @Test
    fun `an answer comes back as text`() = runBlocking {
        val reply = client(StubChannel(Reply(200, """{"type":"answer","text":"Forty two."}""")))
            .chat("what is the answer")
        assertEquals(AssistantReply.Answer("Forty two."), reply)
    }

    @Test
    fun `the live gateway wraps the reply under an ok envelope`() = runBlocking {
        // assistant-web.py answers {"ok":true,"reply":{...}}; the device gate
        // caught a build that read `type` at the root and saw every real
        // answer as an unknown reply.
        val reply = client(
            StubChannel(Reply(200, """{"ok":true,"reply":{"type":"answer","text":"Paris.","spoken":"Paris."}}"""))
        ).chat("capital of France")
        assertEquals(AssistantReply.Answer("Paris.", "Paris."), reply)
    }

    @Test
    fun `an action hands over its payload raw`() = runBlocking {
        // Strict validation of the proposal belongs above this module. What
        // is asserted here is only that the map arrives whole.
        val reply = client(
            StubChannel(Reply(200, """{"type":"action","action":{"type":"set_timer","seconds":720}}"""))
        ).chat("timer for twelve minutes")
        assertTrue("expected Action, got $reply", reply is AssistantReply.Action)
        val payload = (reply as AssistantReply.Action).payload
        assertEquals("set_timer", payload["type"])
        assertEquals(720.0, payload["seconds"])
    }

    @Test
    fun `busy is one outcome whether it arrives as a status or a body`() = runBlocking {
        assertEquals(AssistantReply.Busy, client(StubChannel(Reply(503, "queue full"))).chat("hi"))
        assertEquals(AssistantReply.Busy, client(StubChannel(Reply(200, """{"error":"busy"}"""))).chat("hi"))
    }

    @Test
    fun `every expected failure is Unavailable, and none of them throw`() = runBlocking {
        // The contract is that a caller can show the result without a catch:
        // auth refused, server error, dead tunnel, unreadable body, an answer
        // with nothing in it, a type this build does not know, and the
        // gateway's own "unavailable" all land on the same value.
        val cases = listOf(
            StubChannel(Reply(401, "")),
            StubChannel(Reply(403, "")),
            StubChannel(Reply(500, "boom")),
            StubChannel(Reply(200, "not json")),
            StubChannel(Reply(200, "")),
            StubChannel(Reply(200, """{"type":"answer"}""")),
            StubChannel(Reply(200, """{"type":"answer","text":"   "}""")),
            StubChannel(Reply(200, """{"type":"mystery"}""")),
            StubChannel(Reply(200, """{"type":"unavailable","reason":"current_data_required"}""")),
            StubChannel(Reply(0, ""), fail = "connection refused"),
        )
        for (channel in cases) {
            val reply = client(channel).chat("hi")
            assertTrue("expected Unavailable, got $reply", reply is AssistantReply.Unavailable)
        }
        // And the detail is worth having: a 401 says so rather than blaming
        // the tunnel, which is the one distinction pairing can act on.
        val refused = client(StubChannel(Reply(401, ""))).chat("hi")
        assertTrue(
            (refused as AssistantReply.Unavailable).detail.contains("credential"),
        )
    }

    @Test
    fun `a slow gateway is Unavailable rather than a wait forever`() = runBlocking {
        val channel = StubChannel(
            Reply(200, """{"type":"answer","text":"too late"}"""),
            latencyMillis = 400,
        )
        val reply = AssistantClient(channel, "the-secret", timeoutMillis = 60).chat("hi")
        assertTrue("expected Unavailable, got $reply", reply is AssistantReply.Unavailable)
    }

    // ---- the request ------------------------------------------------------

    @Test
    fun `the credential is pushed to the channel before the request`() = runBlocking {
        // The bearer lives on the channel, exactly like the agent's basic
        // pair on TunnelChannel: the request itself carries no auth, and the
        // push must happen first or the ask goes out anonymous.
        val channel = StubChannel(Reply(200, """{"type":"answer","text":"hi"}"""))
        AssistantClient(channel, "s3cret").chat("hi")
        assertEquals(listOf("authorise", "request"), channel.calls)
        assertEquals("s3cret", channel.credential)
    }

    @Test
    fun `the request is one POST to the chat route with the wire body`() = runBlocking {
        val channel = StubChannel(Reply(200, """{"type":"answer","text":"ok"}"""))
        client(channel).chat(
            "what about evergreens",
            listOf(
                Turn(Role.USER, "why do leaves change colour"),
                Turn(Role.ASSISTANT, "the answer already shown"),
            ),
        )
        assertEquals("POST", channel.method)
        assertEquals("/assistant/chat", channel.path)

        val sent = Json.parse(channel.body!!)
        assertEquals("what about evergreens", sent.string("utterance"))
        val history = sent.list("history")!!
        assertEquals(2, history.size)
        assertEquals("user", history[0].string("role"))
        assertEquals("why do leaves change colour", history[0].string("text"))
        assertEquals("assistant", history[1].string("role"))
        assertEquals("the answer already shown", history[1].string("text"))
    }

    @Test
    fun `no history sends an empty list, not a missing key`() = runBlocking {
        val channel = StubChannel(Reply(200, """{"type":"answer","text":"ok"}"""))
        client(channel).chat("hi")
        val sent = Json.parse(channel.body!!)
        assertEquals(emptyList<Any?>(), sent.list("history"))
    }

    // ---- the caps ---------------------------------------------------------

    @Test
    fun `oversized input is truncated before it is sent`() = runBlocking {
        val channel = StubChannel(Reply(200, """{"type":"answer","text":"ok"}"""))
        val long = "x".repeat(900)
        // Nine pairs in, and the cap is six: the newest ones are kept, which
        // is the same order the session itself ages them out in.
        val history = (1..9).map { Turn(Role.USER, "turn $it") }
        client(channel).chat(long, history)

        val sent = Json.parse(channel.body!!)
        assertEquals(AssistantClient.MAX_TEXT, sent.string("utterance")!!.length)
        val kept = sent.list("history")!!
        assertEquals(AssistantClient.MAX_HISTORY, kept.size)
        assertEquals("turn 4", kept.first().string("text"))
        assertEquals("turn 9", kept.last().string("text"))
    }

    @Test
    fun `an oversized history entry is cut at the same cap`() = runBlocking {
        val channel = StubChannel(Reply(200, """{"type":"answer","text":"ok"}"""))
        client(channel).chat("hi", listOf(Turn(Role.USER, "x".repeat(900))))
        val sent = Json.parse(channel.body!!)
        assertEquals(AssistantClient.MAX_TEXT, sent.list("history")!![0].string("text")!!.length)
    }

    @Test
    fun `a blank utterance is refused, not sent`() = runBlocking {
        val channel = StubChannel()
        val failure = runCatching { client(channel).chat("   ") }.exceptionOrNull()
        assertTrue("expected IllegalArgumentException, got $failure", failure is IllegalArgumentException)
        assertNull("nothing may go on the wire", channel.path)
    }
}
