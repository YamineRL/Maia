package dev.maia.app.screens

import dev.maia.app.R
import dev.maia.app.agent.AgentIdentity
import dev.maia.app.agent.Pairing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pairing screen's decisions, section 5.15.
 *
 * Every one of them is here rather than in the composable, so every one of
 * them is checked. There is no Robolectric in this repo and no androidTest, so
 * the alternative was not a slower test, it was no test.
 */
class PairingCopyTest {

    private fun identity(addr: String, pass: String) =
        AgentIdentity(serverAddr = addr, passphrase = pass)

    @Test
    fun `nothing paired asks for the address and offers nothing else`() {
        val panel = PairingCopy.panel(null)
        assertEquals(Pairing.Step.Address, panel.step)
        assertEquals(R.string.m8_pair_address_caption, panel.addressCaption)
        assertTrue(panel.addressEntry)
        assertFalse("the passphrase is asked for after the address", panel.passphrase)
        assertFalse("the key is last, and it is work on another machine", panel.key)
        assertEquals(R.string.m8_pair_continue, panel.action)
        assertFalse("there is nothing stored to forget", panel.forget)
    }

    @Test
    fun `an address alone asks for the passphrase and stops showing the address`() {
        val panel = PairingCopy.panel(identity("tailcat://key", ""))
        assertEquals(Pairing.Step.Passphrase, panel.step)
        assertEquals(R.string.m8_pair_address_stored, panel.addressCaption)
        assertFalse("a stored credential is never shown again", panel.addressEntry)
        assertTrue(panel.passphrase)
        assertFalse(panel.key)
        assertEquals(R.string.m8_pair_continue, panel.action)
        assertTrue("the address is stored, so forgetting does something", panel.forget)
    }

    @Test
    fun `both stored shows the key block and the last action is Done`() {
        val panel = PairingCopy.panel(identity("tailcat://key", "six-words-with-a-few-more-here"))
        assertEquals(Pairing.Step.Ready, panel.step)
        assertEquals(R.string.m8_pair_address_stored, panel.addressCaption)
        assertFalse(panel.addressEntry)
        assertTrue(panel.passphrase)
        assertTrue(panel.key)
        assertEquals(R.string.m8_pair_done, panel.action)
        assertTrue(panel.forget)
    }

    /**
     * The whole point of the panel: it is integers and booleans, and there is
     * no field on it a credential could be carried in. Pinned by reflection so
     * that adding a `String` to it fails here first, in the same way
     * `RunAlerts.Posting` is pinned.
     */
    @Test
    fun `no field of the panel can carry text`() {
        val offenders = PairingCopy.Panel::class.java.declaredFields
            .filterNot { it.isSynthetic || it.name == "\$stable" }
            .filter { it.type == String::class.java || it.type == CharSequence::class.java }
            .map { it.name }
        assertEquals("a Panel field that could hold a secret", emptyList<String>(), offenders)
    }

    /**
     * `Copied.` below Android 13 and not above it. The boundary is the whole
     * behaviour, so it is tested at the boundary rather than at a level
     * somebody picked.
     */
    @Test
    fun `Copied is drawn below Android 13 and left to the platform from 13`() {
        assertTrue("Android 12L", PairingCopy.showCopied(32))
        assertFalse("Android 13, where the system shows its own", PairingCopy.showCopied(33))
        assertFalse("Android 14", PairingCopy.showCopied(34))
    }

    /**
     * Section 5.15's one condition on the `no_answer` failure. A key that was
     * never added to the allow list and a machine that stopped answering are
     * the same symptom, and the only thing that tells them apart is whether
     * anything has ever worked on this pairing.
     */
    @Test
    fun `the key hint is on the failed screen only until a run has succeeded`() {
        assertTrue(PairingCopy.keyHint(everSucceeded = false))
        assertFalse(PairingCopy.keyHint(everSucceeded = true))
    }

    /**
     * A typed address that has not been stored brings the passphrase field
     * out and nothing else. In particular the address keeps its warning
     * caption and its field: saying `Stored.` over a value that is still only
     * in memory would be the one false sentence on the screen, and hiding the
     * field would leave the user nothing to correct a mistyped paste in.
     */
    @Test
    fun `a drafted address advances the screen without claiming to be stored`() {
        val panel = PairingCopy.panel(Pairing.Step.Address, addressDraft = true)
        assertEquals(Pairing.Step.Passphrase, panel.step)
        assertEquals(R.string.m8_pair_address_caption, panel.addressCaption)
        assertTrue("a draft is still editable", panel.addressEntry)
        assertTrue(panel.passphrase)
        assertFalse("nothing is stored, so there is nothing to forget", panel.forget)
        assertEquals(R.string.m8_pair_continue, panel.action)
    }

    /** A draft cannot skip a step that storage has already passed. */
    @Test
    fun `a draft does not move a stored pairing`() {
        assertEquals(
            PairingCopy.panel(Pairing.Step.Ready),
            PairingCopy.panel(Pairing.Step.Ready, addressDraft = true),
        )
    }

    /**
     * The confirmation sheet, in the order it is read: title, body, then the
     * control that keeps the pairing, then the one that destroys it.
     *
     * The order is the whole design. It follows M3's `m3_locked_discard_action`
     * rule, where the destructive control is second and drawn quieter, so that
     * a thumb travelling to the obvious place lands on the one that changes
     * nothing. Pinned here because the composable lays these out in list order
     * and a reordering would otherwise be silent.
     */
    @Test
    fun `the forget sheet reads title, body, keep, forget`() {
        assertEquals(
            listOf(
                R.string.m8_pair_forget_title,
                R.string.m8_pair_forget_body,
                R.string.m8_pair_forget_cancel,
                R.string.m8_pair_forget_confirm,
            ),
            PairingCopy.forgetSheet,
        )
    }

    /**
     * After the sheet confirms, `m8_pair_forgotten` stands where
     * `m8_pair_address_stored` stood. Nothing is stored, so the screen is back
     * at its first field, and the caption is the only thing that says what
     * just happened.
     */
    @Test
    fun `forgetting leaves the confirmation where the stored caption was`() {
        val panel = PairingCopy.panel(Pairing.Step.Address, forgotten = true)
        assertEquals(Pairing.Step.Address, panel.step)
        assertEquals(R.string.m8_pair_forgotten, panel.addressCaption)
        assertTrue("the screen is back at its first field", panel.addressEntry)
        assertFalse("nothing is stored, so nothing can be forgotten again", panel.forget)
    }

    /**
     * "Appears once" means it goes when the user moves. The first character
     * typed into the address field takes the caption back to the warning,
     * which is the caption that matters when a credential is about to be
     * pasted.
     */
    @Test
    fun `typing an address clears the forgotten confirmation`() {
        val panel = PairingCopy.panel(Pairing.Step.Address, addressDraft = true, forgotten = true)
        assertEquals(R.string.m8_pair_address_caption, panel.addressCaption)
    }

    /**
     * A stored pairing is never described as forgotten. If the flag were ever
     * left set while an identity exists, the stored caption still wins, so the
     * screen cannot claim a machine is gone while it is reachable.
     */
    @Test
    fun `a stored pairing ignores a stale forgotten flag`() {
        assertEquals(
            R.string.m8_pair_address_stored,
            PairingCopy.panel(Pairing.Step.Ready, forgotten = true).addressCaption,
        )
    }

    /**
     * An identity is never half stored, so the panel never has to describe
     * half of one. `Pairing.identity` refuses to build one, and a blank
     * address with a passphrase behind it falls back to asking for the address
     * rather than skipping it.
     */
    @Test
    fun `a blank address asks for the address even with a passphrase behind it`() {
        val panel = PairingCopy.panel(identity("", "six-words-with-a-few-more-here"))
        assertEquals(Pairing.Step.Address, panel.step)
        assertTrue(panel.addressEntry)
        assertFalse(panel.forget)
    }

    /**
     * The M9 field: the gateway credential is asked for only once there is
     * an address for it to authenticate against. Before that it would be a
     * credential stored against nothing, so the field is absent rather than
     * disabled.
     */
    @Test
    fun `the assistant field appears once an address exists`() {
        assertFalse(
            "before the address there is nothing for it to unlock",
            PairingCopy.panel(Pairing.Step.Address).assistant,
        )
        assertTrue(PairingCopy.panel(Pairing.Step.Passphrase).assistant)
        assertTrue(PairingCopy.panel(Pairing.Step.Ready).assistant)
    }

    /**
     * A typed-but-unstored address counts: the field shows while the pair is
     * being completed, because the credential stores independently and a
     * refused pair keeps what was entered.
     */
    @Test
    fun `a drafted address brings the assistant field out too`() {
        assertTrue(PairingCopy.panel(Pairing.Step.Address, addressDraft = true).assistant)
    }

    /**
     * The stored flag is passed through, not decided: the screen draws the
     * stored caption and no entry when it is set, the same treatment the
     * address gets.
     */
    @Test
    fun `the assistant stored flag is carried verbatim`() {
        assertFalse(PairingCopy.panel(Pairing.Step.Ready).assistantStored)
        assertTrue(PairingCopy.panel(Pairing.Step.Ready, assistantStored = true).assistantStored)
    }
}
