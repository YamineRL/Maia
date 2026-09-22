package dev.maia.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The credential, and the ways it must refuse to be printed.
 *
 * PRD section 10 makes this load-bearing rather than defence in depth: tool
 * permissions on the devbox are pre-approved, so basic auth is the only lock
 * between any app on this phone and arbitrary code execution. A passphrase in
 * a log line is the whole of the security model, gone.
 */
class AgentIdentityTest {

    private val secret = "coral-anvil-tundra-quartz-melon-drift"
    private val addr = "tailcat://devbox?psk=abcdef0123456789"

    @Test
    fun `toString prints neither the passphrase nor the address`() {
        val printed = AgentIdentity(addr, secret).toString()
        assertFalse(printed.contains(secret))
        // The address embeds the pre-shared key, so it is lock one of three
        // and is redacted for the same reason.
        assertFalse(printed.contains("abcdef0123456789"))
        assertEquals("AgentIdentity(complete=true)", printed)
    }

    @Test
    fun `it is not a data class, so nothing generated can print it`() {
        val methods = AgentIdentity::class.java.declaredMethods.map { it.name }
        // copy, componentN and a generated toString would each be a road to a
        // log line. A data class here would reintroduce all three silently,
        // and the next person to add one would not be doing anything that
        // looks wrong.
        assertNull(methods.firstOrNull { it == "copy" })
        assertNull(methods.firstOrNull { it.startsWith("component") })
    }

    @Test
    fun `a pasted passphrase loses its whitespace and nothing else`() {
        // A trailing newline picked up from a terminal is the likeliest reason
        // for meeting the auth screen twice.
        assertEquals(secret, AgentIdentity.clean("  $secret\n"))
        assertEquals(secret, AgentIdentity.clean("$secret\r\n"))
    }

    @Test
    fun `cleaning does not fold case and does not repair hyphens`() {
        // A rotated passphrase may be any shape. Repairing one would be
        // guessing at the lock, and a guess that is wrong presents as a 401
        // the user cannot explain.
        assertEquals("Coral-Anvil", AgentIdentity.clean("Coral-Anvil"))
        assertEquals("coral_anvil", AgentIdentity.clean("coral_anvil"))
        assertEquals("cor.al", AgentIdentity.clean("cor.al"))
    }

    @Test
    fun `the generated shape is recognised and never used to refuse one`() {
        assertTrue(AgentIdentity.looksGenerated(secret))
        assertFalse(AgentIdentity.looksGenerated("five-words-only-right-here"))
        assertFalse(AgentIdentity.looksGenerated("hunter2"))
        // And an unrecognised shape is still a complete identity: the hint
        // wording is the only thing that reads this.
        assertTrue(AgentIdentity(addr, "hunter2").complete)
    }

    @Test
    fun `half an identity is not an identity`() {
        assertFalse(AgentIdentity("", secret).complete)
        assertFalse(AgentIdentity(addr, "").complete)
        assertFalse(AgentIdentity(addr, "   ").complete)
    }

    @Test
    fun `nothing paired means the agent surface is simply absent`() {
        assertNull(NoAgentSecrets.load())
    }
}
