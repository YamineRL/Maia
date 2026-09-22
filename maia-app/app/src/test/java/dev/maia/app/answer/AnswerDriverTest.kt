package dev.maia.app.answer

import dev.maia.actions.CalendarEvent
import dev.maia.actions.CalendarRepository
import dev.maia.actions.CalendarTarget
import dev.maia.actions.MaiaCalendar
import dev.maia.app.feel.Pattern
import dev.maia.app.flow.AssistantCommand
import dev.maia.audio.speech.ScriptedSpeaker
import dev.maia.nlu.EventDraft
import dev.maia.nlu.Intent
import dev.maia.transport.AssistantChannel
import dev.maia.transport.AssistantClient
import dev.maia.transport.AssistantReply
import dev.maia.transport.LineSink
import dev.maia.transport.Reply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executor

/**
 * A sentence to an answer, end to end, against fakes at the seams.
 *
 * The machine, the client and the session are real: the channel is a fake
 * [AssistantChannel] answering canned replies, the calendar is a fake
 * repository, the handoff is a lambda, and the voice is [ScriptedSpeaker]
 * so the spoken half is a list rather than a native library. What is
 * checked is the wiring between them, which is the only thing this class
 * owns.
 */
class AnswerDriverTest {

    // ------------------------------------------------------- the harness

    private class FakeCalendar(
        var events: List<CalendarEvent> = emptyList(),
    ) : CalendarRepository {
        override suspend fun calendars(): List<MaiaCalendar> = emptyList()
        override suspend fun defaultTarget(): CalendarTarget? = null
        override suspend fun chooseTarget(calendarId: Long) = Unit
        override suspend fun commit(draft: EventDraft, calendarId: Long): Long = 0
        override suspend fun delete(eventId: Long): Boolean = false
        override suspend fun eventsIn(range: ClosedRange<java.time.ZonedDateTime>) = events
    }

    /** Canned gateway replies, and a record of the credential pushed. */
    private class FakeChannel(
        var reply: Reply = Reply(200, """{"type":"answer","text":"because of chlorophyll","spoken":"short"}"""),
    ) : AssistantChannel {
        var credential: String? = null
        val bodies = mutableListOf<String>()
        var closed = false

        override fun authorise(credential: String) {
            this.credential = credential
        }

        override fun request(method: String, path: String, body: String?): Reply {
            bodies += body ?: ""
            return reply
        }

        override fun stream(path: String, sink: LineSink): Closeable = Closeable { }

        override fun close() {
            closed = true
        }
    }

    /**
     * The on-device model as a script: [answer] is what a reply returns, and
     * [gate] makes a test hold generation open long enough to stop it.
     * `installed` is the driver's only cheap question before it pays a load.
     */
    private class FakeConverser(
        override var installed: Boolean = true,
        var answer: String? = "answered on the phone",
        var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null,
    ) : LocalConverser {
        val asked = mutableListOf<Pair<String, List<dev.maia.transport.Turn>>>()
        var cancelled = false
        var closed = false

        override suspend fun reply(prompt: String, history: List<dev.maia.transport.Turn>): String? {
            asked += prompt to history
            gate?.await()
            return answer
        }

        override fun cancel() {
            cancelled = true
            gate?.complete(Unit)
        }

        override fun close() {
            closed = true
        }
    }

    private class Harness(
        channel: FakeChannel? = FakeChannel(),
        converser: FakeConverser? = null,
        battery: () -> Int? = { 64 },
        var handoff: HandoffOutcome = HandoffOutcome.Opened,
    ) {
        val states = mutableListOf<AnswerState>()
        val felt = mutableListOf<Pattern>()
        val speaker = ScriptedSpeaker(frameMillis = 1)
        var now = 1_000L
        val clock = Clock.fixed(Instant.parse("2026-09-22T10:30:00Z"), ZoneId.of("Europe/London"))

        /** Everything on the calling thread, so a test is a straight line. */
        private val here = Executor { it.run() }

        val driver = AnswerDriver(
            reader = LocalReader(FakeCalendar(), clock, battery),
            handoffs = HandoffRunner { handoff },
            assistantFor = { channel?.let { AssistantClient(it, "the-credential") } },
            converserFor = { converser },
            render = { states += it },
            speaker = speaker,
            feel = { felt += it },
            clock = { now },
            work = here,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

        val current: AnswerState get() = driver.current()

        /**
         * The blocking halves run on real worker dispatchers, so wait for
         * the observable end rather than a status: the state is swapped
         * before the step's effects run, and a status alone can settle a
         * frame before the read, the fire or the speech it asked for.
         */
        fun settle(until: () -> Boolean = { current.status != AnswerStatus.Working }) {
            val deadline = System.currentTimeMillis() + 5_000
            while (!until()) {
                if (System.currentTimeMillis() >= deadline) {
                    throw AssertionError("timed out waiting; last state is $current")
                }
                Thread.sleep(5)
            }
        }
    }

    // ----------------------------------------------------------- a read

    @Test
    fun `a read intent is answered locally and the short form is spoken`() {
        val h = Harness()
        h.driver.handle(AssistantCommand.Read(Intent.DeviceFact(Intent.DeviceFact.Kind.TIME), "what time is it"))
        h.settle { h.speaker.spoken.isNotEmpty() }
        assertEquals(AnswerStatus.Answered, h.current.status)
        assertEquals(AnswerSource.Phone, h.current.source)
        assertEquals("It's 11:30", h.current.answer)
        assertEquals("what time is it", h.current.spoken)
    }

    // -------------------------------------------------------- a handoff

    @Test
    fun `a timer previews first and fires only on confirm`() {
        var fired = 0
        val h = Harness(handoff = HandoffOutcome.Opened)
        val driver = AnswerDriver(
            reader = LocalReader(FakeCalendar(), h.clock, { 64 }),
            handoffs = HandoffRunner { fired++; HandoffOutcome.Opened },
            assistantFor = { null },
            render = { h.states += it },
            speaker = h.speaker,
            feel = { h.felt += it },
            clock = { h.now },
            work = Executor { it.run() },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        driver.handle(AssistantCommand.Act(Intent.SetTimer(12 * 60_000, "tea"), "timer for twelve minutes"))
        assertEquals(AnswerStatus.Previewing, driver.current().status)
        assertEquals("Timer for 12 minutes: tea", driver.current().handoff?.label)
        assertEquals(0, fired)
        driver.confirm()
        h.settle { fired == 1 }
        assertEquals(AnswerStatus.HandedOff, driver.current().status)
    }

    @Test
    fun `a handoff nobody takes is the honest fault`() {
        val h = Harness(handoff = HandoffOutcome.NoTarget)
        h.driver.handle(AssistantCommand.Act(Intent.OpenApp("spotify"), "open spotify"))
        h.settle()
        assertEquals(AnswerStatus.Failed, h.current.status)
        assertEquals(AnswerFault.NoTarget, h.current.fault)
    }

    @Test
    fun `a missing permission is a named fault, not a silent no`() {
        val h = Harness(handoff = HandoffOutcome.Permission(PermNeeded.Camera))
        h.driver.handle(AssistantCommand.Act(Intent.SetTorch(true), "torch on"))
        h.settle()
        assertEquals(AnswerStatus.Failed, h.current.status)
        assertEquals(AnswerFault.Permission(PermNeeded.Camera), h.current.fault)
    }

    // --------------------------------------------------------- the remote

    @Test
    fun `an ask goes to the gateway and the answer is shown and spoken`() {
        val channel = FakeChannel()
        val h = Harness(channel = channel)
        h.driver.handle(AssistantCommand.Ask("why do leaves change colour", "why do leaves change colour"))
        h.settle()
        assertEquals(AnswerStatus.Answered, h.current.status)
        assertEquals(AnswerSource.Devbox, h.current.source)
        assertEquals("because of chlorophyll", h.current.answer)
        // The credential was pushed to the channel, not placed in the body.
        assertEquals("the-credential", channel.credential)
        assertTrue(channel.bodies.single().contains("why do leaves change colour"))
        assertTrue(!channel.bodies.single().contains("the-credential"))
        h.settle { h.speaker.spoken.isNotEmpty() }
        assertTrue(h.speaker.spoken.contains("short"))
    }

    @Test
    fun `an unpaired phone reports not set up and opens no tunnel`() {
        val h = Harness(channel = null)
        h.driver.handle(AssistantCommand.Ask("anything", "anything"))
        h.settle()
        assertEquals(AnswerStatus.Failed, h.current.status)
        assertEquals(AnswerFault.NotSetUp, h.current.fault)
        assertTrue(h.felt.isNotEmpty())
    }

    @Test
    fun `busy keeps the question and says so`() {
        val h = Harness(channel = FakeChannel(Reply(503, "")))
        h.driver.handle(AssistantCommand.Ask("anything", "anything"))
        h.settle()
        assertEquals(AnswerStatus.Failed, h.current.status)
        assertEquals(AnswerFault.Busy, h.current.fault)
    }

    // --------------------------------------------------- the local model

    @Test
    fun `an unreachable devbox falls back to the phone's model`() {
        val channel = FakeChannel(Reply(500, ""))
        val conv = FakeConverser()
        val h = Harness(channel = channel, converser = conv)
        h.driver.handle(AssistantCommand.Ask("anything", "anything"))
        h.settle()
        assertEquals(AnswerStatus.Answered, h.current.status)
        assertEquals(AnswerSource.PhoneModel, h.current.source)
        assertEquals("answered on the phone", h.current.answer)
        assertEquals("anything", conv.asked.single().first)
        // The screen said the quiet part while the model loaded.
        assertTrue(h.states.any { it.note == LOCAL_NOTE })
        h.settle { h.speaker.spoken.isNotEmpty() }
        assertTrue(h.speaker.spoken.contains("answered on the phone"))
    }

    @Test
    fun `an unpaired phone still answers through the local model`() {
        val conv = FakeConverser()
        val h = Harness(channel = null, converser = conv)
        h.driver.handle(AssistantCommand.Ask("anything", "anything"))
        h.settle()
        assertEquals(AnswerStatus.Answered, h.current.status)
        assertEquals(AnswerSource.PhoneModel, h.current.source)
        assertEquals(1, conv.asked.size)
    }

    @Test
    fun `a refused credential falls back rather than dead-ending`() {
        val channel = FakeChannel(Reply(401, ""))
        val conv = FakeConverser()
        val h = Harness(channel = channel, converser = conv)
        h.driver.handle(AssistantCommand.Ask("anything", "anything"))
        h.settle()
        assertEquals(AnswerStatus.Answered, h.current.status)
        assertEquals(AnswerSource.PhoneModel, h.current.source)
    }

    @Test
    fun `busy never reaches the local model`() {
        val conv = FakeConverser()
        val h = Harness(channel = FakeChannel(Reply(503, "")), converser = conv)
        h.driver.handle(AssistantCommand.Ask("anything", "anything"))
        h.settle()
        assertEquals(AnswerStatus.Failed, h.current.status)
        assertEquals(AnswerFault.Busy, h.current.fault)
        assertTrue(conv.asked.isEmpty())
    }

    @Test
    fun `an unusable gateway reply never reaches the local model`() {
        val conv = FakeConverser()
        val h = Harness(channel = FakeChannel(Reply(200, "{")), converser = conv)
        h.driver.handle(AssistantCommand.Ask("anything", "anything"))
        h.settle()
        assertEquals(AnswerStatus.Failed, h.current.status)
        assertEquals(AnswerFault.Unusable, h.current.fault)
        assertTrue(conv.asked.isEmpty())
    }

    @Test
    fun `a phone with no local model keeps the remote fault`() {
        // Two ways to be without it: nothing to build, and nothing installed.
        for (conv in listOf(null, FakeConverser(installed = false))) {
            val h = Harness(channel = FakeChannel(Reply(500, "")), converser = conv)
            h.driver.handle(AssistantCommand.Ask("anything", "anything"))
            h.settle()
            assertEquals(AnswerStatus.Failed, h.current.status)
            assertEquals(AnswerFault.Unreachable, h.current.fault)
        }
    }

    @Test
    fun `a local model that cannot answer keeps the remote fault`() {
        val conv = FakeConverser(answer = null)
        val h = Harness(channel = FakeChannel(Reply(500, "")), converser = conv)
        h.driver.handle(AssistantCommand.Ask("anything", "anything"))
        h.settle()
        assertEquals(AnswerStatus.Failed, h.current.status)
        assertEquals(AnswerFault.Unreachable, h.current.fault)
        assertEquals(1, conv.asked.size)
    }

    @Test
    fun `a local answer becomes history the next ask carries`() {
        val channel = FakeChannel(Reply(500, ""))
        val conv = FakeConverser()
        val h = Harness(channel = channel, converser = conv)
        h.driver.handle(AssistantCommand.Ask("first question", "first question"))
        h.settle()
        // The devbox recovers: the follow-up goes remote carrying the
        // locally-answered exchange, because the user saw one thread.
        channel.reply = Reply(200, """{"type":"answer","text":"a devbox answer"}""")
        h.driver.handle(AssistantCommand.Ask("and then?", "and then?"))
        h.settle()
        assertEquals(AnswerSource.Devbox, h.current.source)
        // The remote request carried the locally-answered exchange, because
        // the user saw one thread.
        assertTrue(channel.bodies.last().contains("answered on the phone"))
        assertTrue(channel.bodies.last().contains("first question"))
    }

    @Test
    fun `stop reaches a generation in flight`() {
        val conv = FakeConverser(gate = kotlinx.coroutines.CompletableDeferred())
        val h = Harness(channel = FakeChannel(Reply(500, "")), converser = conv)
        h.driver.handle(AssistantCommand.Ask("anything", "anything"))
        h.settle { conv.asked.isNotEmpty() }
        h.driver.stop()
        h.settle { h.current.status == AnswerStatus.Idle }
        assertTrue(conv.cancelled)
    }

    @Test
    fun `a remote action is validated before it can fire`() {
        // The gateway's flat shape: type and fields on the reply itself.
        val channel = FakeChannel(
            Reply(200, """{"type":"action","action":"timer","duration_seconds":60}"""),
        )
        val h = Harness(channel = channel)
        h.driver.handle(AssistantCommand.Ask("set a timer for a minute", "set a timer for a minute"))
        h.settle()
        // A validated timer previews like a local one rather than firing.
        assertEquals(AnswerStatus.Previewing, h.current.status)
        assertEquals("Timer for 1 minute", h.current.handoff?.label)
        assertEquals(AnswerSource.Devbox, h.current.source)
    }

    // ------------------------------------------------------- the controls

    @Test
    fun `stop on a live ask ends it`() {
        val channel = FakeChannel(Reply(200, """{"type":"answer","text":"late answer"}"""))
        val h = Harness(channel = channel)
        h.driver.handle(AssistantCommand.Ask("anything", "anything"))
        h.driver.stop()
        assertEquals(AnswerStatus.Idle, h.current.status)
    }

    @Test
    fun `clear wipes the conversation and the transcript`() {
        val h = Harness()
        h.driver.handle(AssistantCommand.Read(Intent.DeviceFact(Intent.DeviceFact.Kind.TIME), "what time"))
        h.settle()
        h.driver.clear()
        assertEquals(AnswerState(), h.current)
    }

    @Test
    fun `a follow-up carries the history the session kept`() {
        val channel = FakeChannel()
        val h = Harness(channel = channel)
        h.driver.handle(AssistantCommand.Ask("why do leaves change colour", "why do leaves change colour"))
        h.settle()
        h.driver.handle(AssistantCommand.Ask("what about evergreens", "what about evergreens"))
        h.settle()
        // The second ask carried the first exchange: two turns, both halves
        // the user saw, and nothing else.
        val second = channel.bodies.last()
        assertTrue(second.contains("why do leaves change colour"))
        assertTrue(second.contains("because of chlorophyll"))
        assertTrue(second.contains("what about evergreens"))
    }

    // ------------------------------------------------- the reply mapping

    @Test
    fun `an answer carries its text and its spoken short form`() {
        assertEquals(
            AnswerEvent.RemoteAnswer("the whole answer", "the short answer"),
            remoteEvent(AssistantReply.Answer("the whole answer", "the short answer")),
        )
        assertEquals(
            AnswerEvent.RemoteAnswer("only the whole answer", null),
            remoteEvent(AssistantReply.Answer("only the whole answer")),
        )
    }

    @Test
    fun `a validated action becomes an intent and nothing else does`() {
        val timer = mapOf<String, Any?>(
            "type" to "action",
            "action" to "timer",
            "duration_seconds" to 720,
            "label" to "tea",
        )
        assertEquals(
            AnswerEvent.RemoteAction(Intent.SetTimer(720_000, "tea")),
            remoteEvent(AssistantReply.Action(timer)),
        )
        // A rejected payload is never an execution attempt.
        val nonsense = mapOf<String, Any?>("type" to "action", "action" to "delete_everything")
        assertEquals(AnswerEvent.RemoteUnusable, remoteEvent(AssistantReply.Action(nonsense)))
    }

    @Test
    fun `an answer shape inside an action reply is still just text`() {
        val payload = mapOf<String, Any?>("type" to "answer", "text" to "a plain answer")
        assertEquals(
            AnswerEvent.RemoteAnswer("a plain answer"),
            remoteEvent(AssistantReply.Action(payload)),
        )
    }

    @Test
    fun `busy stays busy`() {
        assertEquals(AnswerEvent.RemoteBusy, remoteEvent(AssistantReply.Busy))
    }

    @Test
    fun `unavailable is classified by the client's own cause line`() {
        // The tunnel or the gateway being down.
        assertEquals(
            AnswerEvent.RemoteUnreachable,
            remoteEvent(AssistantReply.Unavailable("cannot reach the assistant: dial tcp")),
        )
        assertEquals(
            AnswerEvent.RemoteUnreachable,
            remoteEvent(AssistantReply.Unavailable("no answer after 75 seconds")),
        )
        assertEquals(
            AnswerEvent.RemoteUnreachable,
            remoteEvent(AssistantReply.Unavailable("HTTP 500: no body")),
        )
        // A refused credential has the same fix as a missing one.
        assertEquals(
            AnswerEvent.RemoteNotSetUp,
            remoteEvent(AssistantReply.Unavailable("wrong or missing credential for the assistant")),
        )
        // A reply the gateway produced but could not be used.
        assertEquals(
            AnswerEvent.RemoteUnusable,
            remoteEvent(AssistantReply.Unavailable("unparseable reply: {")),
        )
        assertEquals(
            AnswerEvent.RemoteUnusable,
            remoteEvent(AssistantReply.Unavailable("the gateway said unavailable")),
        )
        assertEquals(
            AnswerEvent.RemoteUnusable,
            remoteEvent(AssistantReply.Unavailable("an unknown reply: {}")),
        )
    }
}
