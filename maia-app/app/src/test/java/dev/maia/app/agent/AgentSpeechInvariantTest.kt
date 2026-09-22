package dev.maia.app.agent

import dev.maia.transport.AgentEvent
import dev.maia.transport.EventType
import dev.maia.transport.Json
import dev.maia.transport.ProjectEntry
import dev.maia.transport.ProjectState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Agent output is read, never spoken.**
 *
 * `docs/M8-copy.md` section 2 gives the reasoning and it is worth keeping in
 * front of whoever changes this file. Agent replies are long, they are full of
 * paths and shell and identifiers that a speech engine mangles, they arrive
 * minutes after the user spoke and often when the phone is in a pocket in a
 * room with other people, and their content is the contents of the user's
 * machine. The rule covers the turn ending, blocking and failing as well as
 * the reply text: "your agent is asking whether it may delete
 * src/main/kotlin" read aloud on a train is the same leak as the reply.
 *
 * A comment asking people to be careful is not a guard. **The guard is the
 * shape of the types**: the only effect that reaches a speaker is
 * [RunEffect.Say], whose payload is an [AgentAck] and two integers, so there
 * is no road along which an agent's words could travel to a speaker even if
 * someone wrote the code to try. These tests assert that shape, and then feed
 * a whole turn of distinctively-tokened agent text through the machine and
 * check that not one of those tokens appears anywhere in anything emitted.
 */
class AgentSpeechInvariantTest {

    private val maia = ProjectEntry(7, "maia", "/home/user/maia", ProjectState.ACTIVE)

    /** Tokens no phrase, path or project name would ever contain by accident. */
    private val tokens = listOf(
        "ZQXJ-REPLY-TEXT",
        "ZQXJ-TOOL-NAME",
        "ZQXJ-TOOL-TARGET",
        "ZQXJ-PERMISSION-BODY",
        "ZQXJ-QUESTION-BODY",
        "ZQXJ-ERROR-MESSAGE",
        "ZQXJ-CLOSE-REASON",
        "ZQXJ-SESSION-ID",
    )

    private fun event(type: String, data: String): RunEvent.Arrived =
        RunEvent.Arrived(AgentEvent("ZQXJ-EVENT-ID", type, Json.parse(data)))

    // ---------------------------------------------------------- the shape

    @Test
    fun `no member of AgentAck can carry a string`() {
        // An enum has no payload at all, which is the strongest form of this
        // available in Kotlin. If someone converts it to a sealed interface to
        // add a message, this is what fails.
        assertTrue(AgentAck::class.java.isEnum)
    }

    @Test
    fun `the speaking effect carries only an ack and integers`() {
        val fields = RunEffect.Say::class.java.declaredFields
            .filterNot { it.isSynthetic || it.name.startsWith("$") }
            .associate { it.name to it.type }
        assertEquals(setOf("ack", "number", "other"), fields.keys)
        assertEquals(AgentAck::class.java, fields["ack"])
        for (name in listOf("number", "other")) {
            assertEquals(Integer::class.java, fields[name])
        }
    }

    @Test
    fun `no other effect in the run machine reaches a speaker`() {
        val speaking = RunEffect::class.java.permittedSubclasses.orEmpty()
            .filter { it.simpleName.contains("Say") || it.simpleName.contains("Speak") }
            .map { it.simpleName }
        // Exactly one road, and this is it.
        assertEquals(listOf("Say"), speaking)
    }

    // ------------------------------------------------- the whole turn, twice

    @Test
    fun `a complete turn of agent text emits nothing containing any of it`() {
        val events = listOf(
            RunEvent.Send(maia, "run the tests"),
            RunEvent.Admitted(queued = false),
            event(EventType.SERVER_CONNECTED, """{"sessionID":"ZQXJ-SESSION-ID"}"""),
            event(EventType.TEXT_DELTA, """{"text":"ZQXJ-REPLY-TEXT"}"""),
            event(EventType.TOOL_CALLED, """{"tool":"ZQXJ-TOOL-NAME","path":"ZQXJ-TOOL-TARGET"}"""),
            event(EventType.TODO_UPDATED, """{"todos":[{"status":"completed"}]}"""),
            event(EventType.PERMISSION_ASKED, """{"requestID":"ZQXJ-PERMISSION-BODY"}"""),
            event(EventType.QUESTION_ASKED, """{"id":"ZQXJ-QUESTION-BODY"}"""),
            event(EventType.TEXT_DELTA, """{"text":"ZQXJ-REPLY-TEXT again"}"""),
            event(EventType.SESSION_IDLE, "{}"),
        )
        assertNothingSpokenAcross(events)
    }

    @Test
    fun `a turn that fails and a stream that dies emit nothing containing agent text`() {
        assertNothingSpokenAcross(
            listOf(
                RunEvent.Send(maia, "run the tests"),
                RunEvent.Admitted(queued = true),
                event(EventType.TEXT_DELTA, """{"text":"ZQXJ-REPLY-TEXT"}"""),
                event(EventType.SESSION_ERROR, """{"message":"ZQXJ-ERROR-MESSAGE"}"""),
            ),
        )
        assertNothingSpokenAcross(
            listOf(
                RunEvent.Send(maia, "run the tests"),
                RunEvent.Admitted(false),
                event(EventType.TEXT_DELTA, """{"text":"ZQXJ-REPLY-TEXT"}"""),
                RunEvent.StreamClosed("ZQXJ-CLOSE-REASON"),
                RunEvent.StreamClosed("ZQXJ-CLOSE-REASON"),
            ),
        )
        assertNothingSpokenAcross(
            listOf(
                RunEvent.Send(maia, "run the tests"),
                RunEvent.Admitted(false),
                event(EventType.STEP_FAILED, """{"error":"ZQXJ-ERROR-MESSAGE"}"""),
            ),
        )
    }

    @Test
    fun `the agent text really did arrive, so the test is not vacuous`() {
        var session = RunSession()
        for (e in listOf(
            RunEvent.Send(maia, "run the tests"),
            event(EventType.TEXT_DELTA, """{"text":"ZQXJ-REPLY-TEXT"}"""),
            event(EventType.TOOL_CALLED, """{"tool":"ZQXJ-TOOL-NAME","path":"ZQXJ-TOOL-TARGET"}"""),
        )) {
            session = reduceRun(session, e, 1_000).session
        }
        // Read, not spoken. It is on the screen, which is the other half of
        // the rule and the half a test that only checks for absence would let
        // someone satisfy by dropping the reply entirely.
        val drawn = session.state.toString()
        assertTrue(drawn.contains("ZQXJ-REPLY-TEXT"))
        assertTrue(drawn.contains("ZQXJ-TOOL-NAME"))
        assertTrue(drawn.contains("ZQXJ-TOOL-TARGET"))
    }

    @Test
    fun `the one agent-supplied string that travels is the resume cursor, and it cannot speak`() {
        val step = reduceRun(
            reduceRun(
                reduceRun(RunSession(), RunEvent.Send(maia, "go"), 1_000).session,
                RunEvent.Arrived(AgentEvent("ZQXJ-EVENT-ID", EventType.TEXT_DELTA, Json.parse("""{"text":"x"}"""))),
                2_000,
            ).session,
            RunEvent.StreamClosed(""),
            3_000,
        )
        // An SSE event id is agent-supplied and does reach an effect: it is
        // the `?after=` cursor the one reconnect resumes from, and a reconnect
        // that did not carry it would replay the whole turn. It is opaque, it
        // goes into a URL path and nowhere else, and it is carried by
        // Subscribe, which is not a speaking effect. Naming it here rather
        // than quietly excluding it is the point: this is the single
        // exception, and it is bounded by the type that holds it.
        val subscribe = step.effects.filterIsInstance<RunEffect.Subscribe>().single()
        assertEquals("ZQXJ-EVENT-ID", subscribe.after)
        assertTrue(step.effects.none { it is RunEffect.Say })
    }

    private fun assertNothingSpokenAcross(events: List<RunEvent>) {
        var session = RunSession()
        var now = 1_000L
        val emitted = mutableListOf<RunEffect>()
        for (e in events) {
            val step = reduceRun(session, e, now)
            session = step.session
            emitted += step.effects
            now += 1_000
        }
        assertTrue("nothing was emitted, so this proved nothing", emitted.isNotEmpty())
        for (effect in emitted) {
            // toString rather than a field walk, because it catches a string
            // smuggled inside a nested value as well as one held directly.
            val printed = effect.toString()
            for (token in tokens) {
                assertTrue(
                    "an agent-supplied string reached an effect: $printed",
                    !printed.contains(token),
                )
            }
        }
        // And the ones that actually speak, checked again by their own shape.
        for (say in emitted.filterIsInstance<RunEffect.Say>()) {
            assertTrue(say.number == null || say.number in 0..9999)
            assertTrue(say.other == null || say.other in 0..9999)
        }
    }
}
