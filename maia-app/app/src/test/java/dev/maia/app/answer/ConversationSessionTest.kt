package dev.maia.app.answer

import dev.maia.transport.Role
import dev.maia.transport.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Section 7's bounds as tests: six pairs, ten minutes, only shown text, and
 * an id that is random per session. The clock is an argument, so the window
 * is moved by hand rather than waited out.
 */
class ConversationSessionTest {

    private fun session() = ConversationSession(id = "test-session")

    @Test
    fun `a recorded exchange comes back as a user turn and an assistant turn`() {
        val s = session()
        s.record("why is the sky blue", "because of scattering", now = 1_000)
        assertEquals(
            listOf(
                Turn(Role.USER, "why is the sky blue"),
                Turn(Role.ASSISTANT, "because of scattering"),
            ),
            s.history(2_000),
        )
    }

    @Test
    fun `the seventh exchange drops the oldest pair`() {
        val s = session()
        repeat(7) { i -> s.record("q$i", "a$i", now = i * 1_000L) }
        assertEquals(ConversationSession.MAX_PAIRS, s.exchanges.size)
        assertEquals(Exchange("q1", "a1"), s.exchanges.first())
        assertEquals(Exchange("q6", "a6"), s.exchanges.last())
    }

    @Test
    fun `ten minutes of quiet empties the history`() {
        val s = session()
        s.record("q", "a", now = 1_000)
        // A second before the bound the conversation is still there.
        assertEquals(2, s.history(1_000 + ConversationSession.EXPIRY_MS - 1).size)
        // At it, the session is empty and stays empty: the reset is real.
        assertTrue(s.history(1_000 + ConversationSession.EXPIRY_MS).isEmpty())
        assertTrue(s.exchanges.isEmpty())
    }

    @Test
    fun `a record after expiry does not resurrect the old pairs`() {
        val s = session()
        s.record("q0", "a0", now = 1_000)
        s.record("q1", "a1", now = 1_000 + ConversationSession.EXPIRY_MS)
        assertEquals(listOf(Exchange("q1", "a1")), s.exchanges)
    }

    @Test
    fun `clear removes everything immediately`() {
        val s = session()
        s.record("q", "a", now = 1_000)
        s.clear()
        assertTrue(s.exchanges.isEmpty())
        assertTrue(s.history(2_000).isEmpty())
    }

    @Test
    fun `only a whole exchange is kept`() {
        // "Only shown text enters it" cannot be expressed narrower than this:
        // an exchange missing either half would put words in a mouth that
        // never said them.
        val s = session()
        s.record("", "a", now = 1_000)
        s.record("q", "", now = 2_000)
        assertTrue(s.exchanges.isEmpty())
    }

    @Test
    fun `the id is injected and random by default`() {
        assertEquals("test-session", session().id)
        assertNotEquals(ConversationSession().id, ConversationSession().id)
    }
}
