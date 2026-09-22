package dev.maia.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pairing, which is three separate things that fail in three separate ways. */
class PairingTest {

    @Test
    fun `the address is asked for first and the passphrase second`() {
        assertEquals(Pairing.Step.Address, Pairing.next(null))
        assertEquals(
            Pairing.Step.Address,
            Pairing.next(AgentIdentity(serverAddr = "", passphrase = "six-words-with-hyphens-in-it")),
        )
        assertEquals(
            Pairing.Step.Passphrase,
            Pairing.next(AgentIdentity(serverAddr = "devbox.example:9999/abc", passphrase = "")),
        )
        assertEquals(
            Pairing.Step.Ready,
            Pairing.next(AgentIdentity(serverAddr = "devbox.example:9999/abc", passphrase = "a-b-c-d-e-f")),
        )
    }

    @Test
    fun `a pasted address loses its whitespace and nothing else`() {
        assertEquals("devbox.example:9999/AbC", Pairing.cleanAddress("  devbox.example:9999/AbC\n"))
        // Case is not folded and no scheme is added: the string carries a key.
        assertEquals("AbC", Pairing.cleanAddress("A b C"))
    }

    @Test
    fun `half a pairing cannot be stored`() {
        assertNull(Pairing.identity("", "a-b-c-d-e-f"))
        assertNull(Pairing.identity("devbox.example:9999/abc", "   "))
        assertNull(Pairing.identity("", ""))
    }

    @Test
    fun `both halves are cleaned on the way in`() {
        val identity = Pairing.identity(" devbox.example:9999/abc ", "one-two-three-four-five-six\n")!!
        assertEquals("devbox.example:9999/abc", identity.serverAddr)
        assertEquals("one-two-three-four-five-six", identity.passphrase)
        assertEquals(TunnelChannel.USER, identity.user)
    }

    @Test
    fun `a paired identity still refuses to print itself`() {
        val identity = Pairing.identity("devbox.example:9999/abc", "one-two-three-four-five-six")!!
        val printed = identity.toString()
        assertEquals("AgentIdentity(complete=true)", printed)
    }
}
