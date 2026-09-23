package dev.maia.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.IOException
import org.junit.Test

/**
 * Answering a blocked agent, with no network anywhere.
 *
 * Everything asserted here was read out of the running server rather than
 * remembered: the route and body come from `GET /doc` on opencode 1.18.31, and
 * the 404 shape was confirmed live against `POST /permission/{id}/reply` with
 * an id that has never existed, which costs nothing and starts no agent.
 *
 * The distinction these tests protect is the one the notification screen is
 * built on. "The machine never heard us" and "the machine had already moved
 * on" are two different sentences to a person holding a phone, and they stay
 * two different sentences only as long as a 404 and a dead socket keep landing
 * in different places in this client.
 */
class AgentPermissionTest {

    /** Records the one request made, and answers with whatever was set up. */
    private class RecordingChannel(private val answer: Reply) : AgentChannel {
        var method: String? = null
        var path: String? = null
        var body: String? = null

        override fun request(method: String, path: String, body: String?): Reply {
            this.method = method
            this.path = path
            this.body = body
            return answer
        }

        override fun stream(path: String, sink: LineSink) =
            throw UnsupportedOperationException("this test only makes requests")

        override fun close() = Unit
    }

    /** A channel that cannot complete the exchange at all. */
    private class DeadChannel : AgentChannel {
        override fun request(method: String, path: String, body: String?): Reply =
            throw IOException("tunnel closed")

        override fun stream(path: String, sink: LineSink) =
            throw UnsupportedOperationException("this test only makes requests")

        override fun close() = Unit
    }

    private val session = Session("ses_1", "prj_1", "fusion", "/r/maia")

    private fun reply(
        answer: Reply,
        what: PermissionReply = PermissionReply.ONCE,
        message: String? = null,
    ): Pair<RecordingChannel, ReplyOutcome> {
        val channel = RecordingChannel(answer)
        val outcome = AgentClient(channel).replyPermission(session, "per_7", what, message)
        return channel to outcome
    }

    // ---- the route -------------------------------------------------------

    @Test
    fun `the reply goes to the v2 session route, carrying no directory`() {
        // Verified live: a real external_directory ask listed through
        // GET /api/session/{id}/permission, took {"reply":"once"} on this
        // route, and the suspended tool call completed. The session id does
        // the work ?directory= did on the v1 route, and the v1 route is blind
        // to v2 asks: GET /permission?directory= answered [] for the same
        // pending request.
        val (channel, _) = reply(Reply(200, "true"))
        assertEquals("POST", channel.method)
        assertEquals("/api/session/ses_1/permission/per_7/reply", channel.path)
    }

    @Test
    fun `the body names the reply and nothing else when there is no message`() {
        val (channel, _) = reply(Reply(200, "true"))
        assertEquals("""{"reply":"once"}""", channel.body)
    }

    @Test
    fun `a refusal can carry feedback for the agent`() {
        val (channel, _) = reply(
            Reply(200, "true"),
            PermissionReply.REJECT,
            "not that directory",
        )
        assertEquals("""{"reply":"reject","message":"not that directory"}""", channel.body)
    }

    @Test
    fun `the three wire words are the server's own enum`() {
        // once, always, reject. The server's schema is a closed enum and a
        // fourth spelling is a 400, so this is pinned rather than trusted.
        assertEquals("once", PermissionReply.ONCE.wire)
        assertEquals("always", PermissionReply.ALWAYS.wire)
        assertEquals("reject", PermissionReply.REJECT.wire)
        assertEquals(3, PermissionReply.entries.size)
    }

    @Test
    fun `allow always is a second value of one field, not a third action`() {
        // The design seat's question, answered in code. Allow once and allow
        // always are the same POST with a different word, so the copy can say
        // "Allow once" and "Allow always" without a third button appearing
        // anywhere in this client.
        val (once, _) = reply(Reply(200, "true"), PermissionReply.ONCE)
        val (always, _) = reply(Reply(200, "true"), PermissionReply.ALWAYS)
        assertEquals(once.path, always.path)
        assertEquals("""{"reply":"always"}""", always.body)
    }

    // ---- the late answer -------------------------------------------------

    @Test
    fun `a reply the agent still wants comes back accepted`() {
        val (_, outcome) = reply(Reply(200, "true"))
        assertEquals(ReplyOutcome.ACCEPTED, outcome)
    }

    @Test
    fun `a reply to a request that is no longer pending comes back gone`() {
        // Verified live: this is the exact body the server sends for an id it
        // does not hold. It is the same answer whether the agent moved on, the
        // turn ended, the id was already answered or the server restarted, and
        // the phone treats all four the same way.
        val (_, outcome) = reply(
            Reply(
                404,
                """{"_tag":"PermissionNotFoundError","requestID":"per_7",""" +
                    """"message":"Permission request not found: per_7"}""",
            )
        )
        assertEquals(ReplyOutcome.GONE, outcome)
    }

    @Test
    fun `a reply that never arrived is not an outcome at all`() {
        // The whole reason ReplyOutcome has two members and not three. A
        // transport failure must not be able to masquerade as "too late", so
        // it never becomes a value: it leaves through the channel as an
        // IOException and the screen that reports it is a different screen.
        try {
            AgentClient(DeadChannel()).replyPermission(session, "per_7", PermissionReply.ONCE)
            fail("a dead tunnel must not return an outcome")
        } catch (e: IOException) {
            assertEquals("tunnel closed", e.message)
        }
    }

    @Test
    fun `a wrong passphrase is ours to fix and still throws`() {
        // 401 is not "too late" and must not be shown as it. Only 404 is.
        try {
            reply(Reply(401, ""))
            fail("401 must not be reported as an outcome")
        } catch (e: AgentException) {
            assertEquals(401, e.status)
            assertTrue(e.message!!.contains("passphrase"))
        }
    }

    // ---- reconciling after being offline ---------------------------------

    @Test
    fun `pending permissions are read from the session-scoped v2 list`() {
        val channel = RecordingChannel(Reply(200, """{"data":[]}"""))
        AgentClient(channel).pendingPermissions(session)
        assertEquals("GET", channel.method)
        assertEquals("/api/session/ses_1/permission", channel.path)
    }

    @Test
    fun `a pending request parses into what a notification needs`() {
        // The v2 row: action is the rule, resources is what was asked for,
        // save is what "always" would remember.
        val channel = RecordingChannel(
            Reply(
                200,
                """{"data":[{"id":"per_7","sessionID":"ses_1","action":"external_directory",""" +
                    """"resources":["/etc/*"],"save":["/etc/*"]}]}""",
            )
        )
        val pending = AgentClient(channel).pendingPermissions(session)
        assertEquals(1, pending.size)
        assertEquals(
            PendingPermission(
                id = "per_7",
                sessionId = "ses_1",
                permission = "external_directory",
                patterns = listOf("/etc/*"),
                always = listOf("/etc/*"),
            ),
            pending.single(),
        )
    }

    @Test
    fun `an empty save is the signal that allow always would remember nothing`() {
        // Both holes agent-web.sh leaves open do send one, so this is the
        // defensive case rather than the expected one: if a request ever
        // arrives without it, the screen must not offer to remember a choice
        // the server will discard.
        val channel = RecordingChannel(
            Reply(200, """{"data":[{"id":"per_8","sessionID":"ses_1","action":"doom_loop"}]}"""),
        )
        val pending = AgentClient(channel).pendingPermissions(session).single()
        assertTrue("nothing to remember", pending.always.isEmpty())
        assertEquals("doom_loop", pending.permission)
    }

    @Test
    fun `a row without an id is dropped rather than half parsed`() {
        val channel = RecordingChannel(Reply(200, """{"data":[{"sessionID":"ses_1"},"nonsense"]}"""))
        assertTrue(AgentClient(channel).pendingPermissions(session).isEmpty())
    }

    @Test
    fun `the list is read out of the data envelope`() {
        // The /api routes answer with {data: [...]}. A bare list is the v1
        // shape, and returning it as rows would silently mix the two APIs,
        // so it throws rather than half parse.
        val channel = RecordingChannel(Reply(200, """[{"id":"per_7"}]"""))
        try {
            AgentClient(channel).pendingPermissions(session)
            fail("a bare list where an envelope belongs must not parse")
        } catch (e: AgentException) {
            assertTrue(e.message!!.contains("data envelope"))
        }
    }

    // ---- the stream's half of the contract -------------------------------

    @Test
    fun `permission asked is what a phone notifies on`() {
        assertEquals("permission.asked", EventType.PERMISSION_ASKED)
        assertTrue(EventType.PERMISSION_ASKED in EventType.BLOCKING)
        assertEquals("permission.v2.asked", EventType.PERMISSION_V2_ASKED)
        assertTrue(EventType.PERMISSION_V2_ASKED in EventType.BLOCKING)
    }

    @Test
    fun `an abandoned request leaves no trace on the stream, which is why the list exists`() {
        // The server publishes permission.asked and permission.replied and
        // nothing else: there is no permission.expired, withdrawn or
        // cancelled anywhere in its event vocabulary. So a request abandoned
        // by an interrupt or by the end of a turn simply stops being pending,
        // silently. A phone that was offline cannot infer that from the
        // stream and has to ask.
        assertTrue(
            "a withdrawal event would make pendingPermissions unnecessary",
            EventType.BLOCKING.none { it.contains("expired") || it.contains("cancel") },
        )
    }
}
