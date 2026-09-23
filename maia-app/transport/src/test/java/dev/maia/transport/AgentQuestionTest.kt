package dev.maia.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.IOException
import org.junit.Test

/**
 * Answering a question, with no network anywhere.
 *
 * The sibling of [AgentPermissionTest], and written the same way: the route,
 * the body and the schemas come from `GET /doc` on opencode 1.18.31 and from
 * the server's own bundled source, and the 404 and 400 shapes were confirmed
 * live against `POST /api/session/{id}/question/{id}/reply`,
 * which costs nothing and starts no agent. No prompt was sent to get any of it.
 *
 * What these tests mostly protect is the thing that surprised us. A question
 * is not a free-text prompt: it is a list of questions, each with a list of
 * options, answered by naming labels. Free text is legal only because
 * [AskedQuestion.custom] defaults to true. A client that assumed one question
 * and one sentence would work on most requests and line the wrong answer up
 * against the wrong question on the rest.
 */
class AgentQuestionTest {

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
        answers: List<List<String>> = listOf(listOf("Yes")),
    ): Pair<RecordingChannel, ReplyOutcome> {
        val channel = RecordingChannel(answer)
        val outcome = AgentClient(channel).replyQuestion(session, "que_7", answers)
        return channel to outcome
    }

    // ---- the route -------------------------------------------------------

    @Test
    fun `the reply goes to the v2 session route, carrying no directory`() {
        // The session id does the work ?directory= did on the v1 route: the
        // pending map belongs to one project instance, and the session scopes
        // the lookup to the instance that holds it.
        val (channel, _) = reply(Reply(200, "true"))
        assertEquals("POST", channel.method)
        assertEquals("/api/session/ses_1/question/que_7/reply", channel.path)
    }

    @Test
    fun `the body is answers, an array per question, of labels`() {
        val (channel, _) = reply(Reply(200, "true"))
        assertEquals("""{"answers":[["Yes"]]}""", channel.body)
    }

    @Test
    fun `a dictated sentence is a legal answer and goes verbatim`() {
        // Section 5.18's whole premise. The server does not check an answer
        // against the option labels: it hands the strings to the model as
        // typed. So a sentence off the recogniser needs no encoding and no
        // matching against anything.
        val (channel, _) = reply(
            Reply(200, "true"),
            listOf(listOf("use the staging database, not the live one")),
        )
        assertEquals(
            """{"answers":[["use the staging database, not the live one"]]}""",
            channel.body,
        )
    }

    @Test
    fun `several questions keep their order, and a multiple keeps its several`() {
        // The failure this prevents is silent: a request with two questions
        // answered as if it had one puts the answer to the first against the
        // second. Order is the only thing tying an answer to its question.
        val (channel, _) = reply(
            Reply(200, "true"),
            listOf(listOf("Postgres"), listOf("Linux", "macOS")),
        )
        assertEquals(
            """{"answers":[["Postgres"],["Linux","macOS"]]}""",
            channel.body,
        )
    }

    @Test
    fun `an unanswered question is an empty list, not a blank string`() {
        // Verified in the server's own source: the tool renders an empty or
        // missing entry as `Unanswered` and the turn carries on. A blank
        // string would instead reach the model as an answer of "", which is a
        // different and worse thing, so it is refused before any request.
        val (channel, _) = reply(Reply(200, "true"), listOf(emptyList()))
        assertEquals("""{"answers":[[]]}""", channel.body)

        try {
            reply(Reply(200, "true"), listOf(listOf(" ")))
            fail("a blank answer must not reach the server")
        } catch (e: IllegalArgumentException) {
            assertEquals("blank answer", e.message)
        }
    }

    // ---- the late answer -------------------------------------------------

    @Test
    fun `a reply the agent still wants comes back accepted`() {
        val (_, outcome) = reply(Reply(200, "true"))
        assertEquals(ReplyOutcome.ACCEPTED, outcome)
    }

    @Test
    fun `a reply to a question that is no longer pending comes back gone`() {
        // Verified live: this is the exact body the server sends for an id it
        // does not hold, and the log line beside it is "reply for unknown
        // request", which is how we know the v1 service saw it at all.
        //
        // Observed again on 2026-09-21, twice over: once by replaying a reply
        // the server had already consumed, and once by sending a good reply to
        // a genuinely pending question with ?directory= left off. The second is
        // why the test above pins the query string. The server cannot tell a
        // caller that lost the directory from a question that expired, so the
        // client must never be the one that loses it.
        val (_, outcome) = reply(
            Reply(
                404,
                """{"_tag":"QuestionNotFoundError","requestID":"que_7",""" +
                    """"message":"Question request not found: que_7"}""",
            )
        )
        assertEquals(ReplyOutcome.GONE, outcome)
    }

    @Test
    fun `a reply that never arrived is not an outcome at all`() {
        // ReplyOutcome stays at two members for the question path as well. A
        // question reply has no third thing to report: the server answers 200
        // or 404 and a 400 is ours to fix.
        try {
            AgentClient(DeadChannel()).replyQuestion(session, "que_7", listOf(listOf("Yes")))
            fail("a dead tunnel must not return an outcome")
        } catch (e: IOException) {
            assertEquals("tunnel closed", e.message)
        }
    }

    @Test
    fun `a wrong passphrase is ours to fix and still throws`() {
        try {
            reply(Reply(401, ""))
            fail("401 must not be reported as an outcome")
        } catch (e: AgentException) {
            assertEquals(401, e.status)
            assertTrue(e.message!!.contains("passphrase"))
        }
    }

    // ---- dismissing without answering ------------------------------------

    @Test
    fun `reject is its own route and carries no body`() {
        val channel = RecordingChannel(Reply(200, "true"))
        val outcome = AgentClient(channel).rejectQuestion(session, "que_7")
        assertEquals("POST", channel.method)
        assertEquals("/api/session/ses_1/question/que_7/reject", channel.path)
        assertEquals(null, channel.body)
        assertEquals(ReplyOutcome.ACCEPTED, outcome)
    }

    // ---- reconciling after being offline ---------------------------------

    @Test
    fun `pending questions are read from the session-scoped v2 list`() {
        val channel = RecordingChannel(Reply(200, """{"data":[]}"""))
        AgentClient(channel).pendingQuestions(session)
        assertEquals("GET", channel.method)
        assertEquals("/api/session/ses_1/question", channel.path)
    }

    @Test
    fun `a pending question parses into what a screen needs`() {
        val channel = RecordingChannel(Reply(200, """{"data":[$ASKED]}"""))
        val pending = AgentClient(channel).pendingQuestions(session).single()
        assertEquals("que_7", pending.id)
        assertEquals("ses_1", pending.sessionId)
        val asked = pending.questions.single()
        assertEquals("Which database should the migration run against?", asked.question)
        assertEquals("Database", asked.header)
        assertEquals(
            listOf(
                AnswerOption("Staging (Recommended)", "The copy, so a mistake costs nothing"),
                AnswerOption("Production", "The live one"),
            ),
            asked.options,
        )
        assertFalse(asked.multiple)
        assertTrue("absent custom means free text is invited", asked.custom)
    }

    @Test
    fun `the event payload parses with the same parser as the list row`() {
        // question.asked carries a QuestionRequest under `data`, the same
        // object the list returns. That is what lets a phone draw the question
        // off the stream without asking the server for it again.
        val frame = SseFrame(
            id = null,
            event = null,
            data = """{"id":"evt_1","type":"question.asked","data":$ASKED}""",
        )
        val event = AgentEvent.from(frame)!!
        assertEquals(EventType.QUESTION_ASKED, event.type)
        val parsed = PendingQuestion.from(event.payload)!!
        assertEquals("que_7", parsed.id)
        assertEquals("Database", parsed.questions.single().header)
    }

    @Test
    fun `custom false is the one case where free text is not offered`() {
        // plan_exit in the server's own source sends custom:false with Yes and
        // No. A screen that opened the microphone on that one would collect a
        // sentence the agent has no branch for.
        val asked = AskedQuestion.from(
            Json.parse(
                """{"question":"Switch to build?","header":"Build Agent","custom":false,""" +
                    """"options":[{"label":"Yes","description":"go"},""" +
                    """{"label":"No","description":"stay"}]}""",
            )
        )!!
        assertFalse(asked.custom)
        assertEquals(listOf("Yes", "No"), asked.options.map { it.label })
    }

    @Test
    fun `multiple is what says more than one label may go back`() {
        val asked = AskedQuestion.from(
            Json.parse(
                """{"question":"Which platforms?","header":"Platforms","multiple":true,""" +
                    """"options":[{"label":"Linux","description":"x"}]}""",
            )
        )!!
        assertTrue(asked.multiple)
    }

    @Test
    fun `a row without questions is dropped rather than half parsed`() {
        val channel = RecordingChannel(Reply(200, """{"data":[{"id":"que_7"},"nonsense"]}"""))
        assertTrue(AgentClient(channel).pendingQuestions(session).isEmpty())
    }

    @Test
    fun `the list is read out of the data envelope`() {
        val channel = RecordingChannel(Reply(200, "[$ASKED]"))
        try {
            AgentClient(channel).pendingQuestions(session)
            fail("a bare list where an envelope belongs must not parse")
        } catch (e: AgentException) {
            assertTrue(e.message!!.contains("data envelope"))
        }
    }

    // ---- the stream's half of the contract -------------------------------

    @Test
    fun `question asked is what a phone notifies on`() {
        assertEquals("question.asked", EventType.QUESTION_ASKED)
        assertTrue(EventType.QUESTION_ASKED in EventType.BLOCKING)
        assertEquals("question.v2.asked", EventType.QUESTION_V2_ASKED)
        assertTrue(EventType.QUESTION_V2_ASKED in EventType.BLOCKING)
    }

    @Test
    fun `the frame observed on the wire carries neither multiple nor custom`() {
        // Captured verbatim from opencode 1.18.31 on 2026-09-21, from a model
        // that had been told in the prompt to allow a custom free text answer.
        // It still sent neither key. Absent is the ordinary case, so the two
        // defaults in AskedQuestion.from are what decide whether section 5.18's
        // microphone ever opens. Keep this fixture byte for byte: the moment
        // someone "tidies" it by adding the keys, the defaults stop being
        // tested and the bug it guards against comes back silently.
        val parsed = PendingQuestion.from(
            Json.parse(
                """{"id":"que_0c34d7c9f0012lBOmThxtujeGi",""" +
                    """"sessionID":"ses_f3cb33b4cffe2xClFn25diH5OA","questions":[{""" +
                    """"question":"Which colour should the orb use while listening?",""" +
                    """"header":"Orb colour","options":[""" +
                    """{"label":"Amber","description":"A warm, inviting glow that """ +
                    """signals the orb is attentively listening."},""" +
                    """{"label":"Teal","description":"A calm, cool tone that conveys """ +
                    """a relaxed, ready-to-receive state."},""" +
                    """{"label":"Violet","description":"A distinctive, energetic hue """ +
                    """that clearly marks an active listening mode."}]}],""" +
                    """"tool":{"messageID":"msg_0c34cf61d001yXm2EyCGe2kfVf",""" +
                    """"callID":"RTxVFAFEOX4VNbssQXr1sybU673HwCMn"}}"""
            )
        )!!
        assertEquals("que_0c34d7c9f0012lBOmThxtujeGi", parsed.id)
        assertEquals("ses_f3cb33b4cffe2xClFn25diH5OA", parsed.sessionId)
        val asked = parsed.questions.single()
        assertEquals("Orb colour", asked.header)
        assertEquals(listOf("Amber", "Teal", "Violet"), asked.options.map { it.label })
        assertFalse("absent multiple is one label, not several", asked.multiple)
        assertTrue("absent custom is what lets the user dictate", asked.custom)
    }

    private companion object {
        /** One question request, in the shape both the list and the event use. */
        const val ASKED =
            """{"id":"que_7","sessionID":"ses_1","questions":[{""" +
                """"question":"Which database should the migration run against?",""" +
                """"header":"Database","options":[""" +
                """{"label":"Staging (Recommended)",""" +
                """"description":"The copy, so a mistake costs nothing"},""" +
                """{"label":"Production","description":"The live one"}]}],""" +
                """"tool":{"messageID":"msg_1","callID":"call_1"}}"""
    }
}
