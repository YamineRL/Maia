package dev.maia.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SseTest {

    /** Exactly what the server sent on 2026-09-17, byte for byte. */
    private val observed = listOf(
        """data: {"id":"evt_01","type":"server.connected","data":{}}""",
        "",
        ": heartbeat",
        "",
    )

    @Test
    fun `the observed opening frame parses`() {
        val a = SseAssembler()
        val frames = observed.mapNotNull { a.line(it) }
        assertEquals(1, frames.size)
        val event = AgentEvent.from(frames[0])
        assertNotNull(event)
        assertEquals(EventType.SERVER_CONNECTED, event!!.type)
        assertEquals("evt_01", event.id)
        assertEquals(1, a.comments)
    }

    @Test
    fun `there is no event line, so the type comes from the payload`() {
        // A client written against the earlier description, which expected an
        // "event:" line and a "properties" key, parses nothing at all. This
        // test is here to keep that from being re-introduced quietly.
        val a = SseAssembler()
        a.line("""data: {"id":"evt_02","type":"session.idle","data":{"sessionID":"ses_9"}}""")
        val frame = a.line("")!!
        assertNull(frame.event)
        val event = AgentEvent.from(frame)!!
        assertEquals(EventType.SESSION_IDLE, event.type)
        assertEquals("ses_9", event.sessionId)
        assertTrue(event.type in EventType.TERMINAL)
    }

    @Test
    fun `a heartbeat run produces no frames`() {
        val a = SseAssembler()
        repeat(5) {
            assertNull(a.line(": heartbeat"))
            assertNull(a.line(""))
        }
        assertEquals(5, a.comments)
    }

    @Test
    fun `multi line data is joined with newlines`() {
        val a = SseAssembler()
        a.line("data: one")
        a.line("data: two")
        assertEquals("one\ntwo", a.line("")!!.data)
    }

    @Test
    fun `the optional single space after the colon is stripped once`() {
        val a = SseAssembler()
        a.line("data:  leading")
        assertEquals(" leading", a.line("")!!.data)
    }

    @Test
    fun `a text delta carries the words the phone shows`() {
        val a = SseAssembler()
        a.line(
            """data: {"id":"evt_03","type":"session.next.text.delta",""" +
                """"data":{"sessionID":"ses_9","assistantMessageID":"msg_1","delta":"Hello"}}"""
        )
        val event = AgentEvent.from(a.line("")!!)!!
        assertEquals(EventType.TEXT_DELTA, event.type)
        assertEquals("Hello", event.payload.string("delta"))
    }

    @Test
    fun `a blocked agent is recognisable`() {
        val a = SseAssembler()
        a.line(
            """data: {"id":"evt_04","type":"permission.asked",""" +
                """"data":{"id":"per_7","sessionID":"ses_9","tool":"bash"}}"""
        )
        val event = AgentEvent.from(a.line("")!!)!!
        assertTrue(event.type in EventType.BLOCKING)
        assertEquals("per_7", event.payload.string("id"))
    }

    @Test
    fun `an unparseable or untyped frame is dropped, not thrown`() {
        val a = SseAssembler()
        a.line("data: not json at all")
        assertNull(AgentEvent.from(a.line("")!!))
        a.line("""data: {"id":"evt_05"}""")
        assertNull(AgentEvent.from(a.line("")!!))
    }
}
