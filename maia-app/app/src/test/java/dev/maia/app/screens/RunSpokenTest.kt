package dev.maia.app.screens

import dev.maia.app.agent.AgentAck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The spoken acknowledgements, section 6.3's second table.
 *
 * The arity tests are the ones that earn their place: a missing argument for
 * `m8_spoken_sent_moved` is a `MissingFormatArgumentException` thrown at the
 * exact moment the user has just finished speaking, which is the worst
 * possible time for Maia to crash.
 */
class RunSpokenTest {

    @Test
    fun `every ack has its own line`() {
        val ids = AgentAck.entries.map { RunCopy.spoken(it) }
        assertEquals(AgentAck.entries.size, ids.toSet().size)
        for (id in ids) assertNotEquals(0, id)
    }

    @Test
    fun `the lines that name one project get one number`() {
        for (ack in listOf(AgentAck.Sent, AgentAck.Queued, AgentAck.NoProject)) {
            assertEquals(listOf<Any>(7), RunCopy.spokenArgs(ack, 7, null).toList())
        }
    }

    @Test
    fun `the line that names two projects gets both, in the order it says them`() {
        assertEquals(listOf<Any>(7, 3), RunCopy.spokenArgs(AgentAck.SentMoved, 7, 3).toList())
    }

    @Test
    fun `the lines with no placeholder get no arguments`() {
        for (ack in listOf(
            AgentAck.Stopped,
            AgentAck.WhichProject,
            AgentAck.WhichOne,
            AgentAck.TunnelOff,
            AgentAck.NoAnswer,
            AgentAck.Refused,
        )) {
            assertTrue("$ack was given arguments", RunCopy.spokenArgs(ack, 7, 3).isEmpty())
        }
    }

    @Test
    fun `a missing number is a number and never a crash`() {
        // The run machine always has one by the time it says these, but a
        // formatter that throws on the null path would only ever be found by
        // the user, and it would be found mid-sentence.
        for (ack in AgentAck.entries) {
            val args = RunCopy.spokenArgs(ack, null, null)
            assertTrue(args.all { it is Int })
        }
    }

    @Test
    fun `nothing in the spoken path carries a string`() {
        // The whole never-spoken rule in one assertion: the only inputs are an
        // enum and two integers, and the only output is a resource id.
        val method = RunCopy::class.java.getMethod("spoken", AgentAck::class.java)
        assertEquals(Int::class.javaPrimitiveType, method.returnType)
    }
}
