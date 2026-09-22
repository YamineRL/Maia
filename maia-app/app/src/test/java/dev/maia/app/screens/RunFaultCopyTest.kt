package dev.maia.app.screens

import dev.maia.app.R
import dev.maia.app.agent.RunFault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four screens of sections 5.6 to 5.9, and the two faults that are not
 * screens.
 *
 * Every one of these decisions is here rather than in the composable, for the
 * reason [PairingCopyTest] gives: this repo has no Robolectric and no
 * androidTest, so the alternative to a decision object was no test at all.
 */
class RunFaultCopyTest {

    @Test
    fun `the tunnel screen says what is absent and offers the tunnel app`() {
        val panel = RunFaultCopy.panel(RunFault.TunnelOff)!!
        assertEquals(R.string.m8_fault_label_not_sent, panel.label)
        assertEquals(R.string.m8_tunnel_off_title, panel.title)
        assertEquals(R.string.m8_tunnel_off_body, panel.body)
        assertNull("there is nothing to type on this one", panel.data)
        assertEquals(R.string.m8_tunnel_off_action, panel.action)
        assertFalse(panel.passphrase)
    }

    /**
     * Two causes, both named, which is why this is its own screen and not a
     * shared "cannot connect": the two have different fixes and a merged
     * message would name neither. The command is printed because the audience
     * for this feature is the person whose machine it is.
     */
    @Test
    fun `the agent screen prints the command that starts it`() {
        val panel = RunFaultCopy.panel(RunFault.NoServer)!!
        assertEquals(R.string.m8_no_server_title, panel.title)
        assertEquals(R.string.m8_no_server_command, panel.data)
        assertEquals(R.string.m8_no_server_action, panel.action)
    }

    /**
     * Section 5.15's one condition, and the only place it applies. A key that
     * was never added and a machine that stopped answering are the same
     * symptom, and only the history of this pairing tells them apart.
     */
    @Test
    fun `the key hint is on the agent screen until a run has succeeded`() {
        assertTrue(RunFaultCopy.panel(RunFault.NoServer, everSucceeded = false)!!.keyHint)
        assertFalse(RunFaultCopy.panel(RunFault.NoServer, everSucceeded = true)!!.keyHint)
    }

    /** The hint belongs to one screen. A tunnel that is off says nothing about keys. */
    @Test
    fun `no other fault carries the key hint`() {
        for (fault in listOf(RunFault.TunnelOff, RunFault.Refused, RunFault.Retired)) {
            assertFalse("$fault", RunFaultCopy.panel(fault, everSucceeded = false)!!.keyHint)
        }
    }

    /**
     * "Was refused", never "is wrong". What the phone observed is a 401, and
     * the passphrase may be perfectly correct with the file at the other end
     * rotated. Only one of those is the user's fault, and the screen does not
     * pick.
     */
    @Test
    fun `the refused screen names the file and asks for the passphrase again`() {
        val panel = RunFaultCopy.panel(RunFault.Refused)!!
        assertEquals(R.string.m8_auth_title, panel.title)
        assertEquals(R.string.m8_auth_path, panel.data)
        assertEquals(R.string.m8_auth_action, panel.action)
        assertTrue("this is the one screen with a field on it", panel.passphrase)
    }

    /** The passphrase field is on exactly one of the four. */
    @Test
    fun `no other fault asks for a passphrase`() {
        for (fault in listOf(RunFault.TunnelOff, RunFault.NoServer, RunFault.Retired)) {
            assertFalse("$fault", RunFaultCopy.panel(fault)!!.passphrase)
        }
    }

    /**
     * The retired screen, and the copy gap in it.
     *
     * Section 5.9 ends "Action on all four: the project list (5.10)" and
     * defines no string for a control that opens the list. The three other
     * screens name their own action; this one has nothing to put on a button,
     * so it has none rather than an invented label. Pinned so that the day a
     * string is written this test fails and the button goes in.
     */
    @Test
    fun `the retired screen explains and has no action, which is the copy gap`() {
        val panel = RunFaultCopy.panel(RunFault.Retired)!!
        assertEquals(R.string.m8_retired_project_title, panel.title)
        assertEquals(R.string.m8_retired_project_body, panel.body)
        assertNull("no id is defined for a control that opens the list", panel.action)
        assertTrue("its two strings take arguments", RunFaultCopy.formatted(RunFault.Retired))
    }

    /**
     * Two faults are not screens, for two different reasons.
     *
     * A turn that died mid-run keeps the run screen and its partial reply,
     * because a user told something failed and offered nothing to look at
     * assumes everything was lost. A number that is not in the registry
     * arrives as a chooser and is a heading over the project list.
     */
    @Test
    fun `a failed turn and a missing number have no fault screen`() {
        assertNull(RunFaultCopy.panel(RunFault.TurnFailed))
        assertNull(RunFaultCopy.panel(RunFault.NoProject))
        assertNull(RunFaultCopy.panel(null))
    }

    /** `NOT SENT` is on all four, answering where the sentence went first. */
    @Test
    fun `every fault screen carries the same label`() {
        for (fault in RunFault.entries) {
            val panel = RunFaultCopy.panel(fault) ?: continue
            assertEquals("$fault", R.string.m8_fault_label_not_sent, panel.label)
        }
    }

    /**
     * The panel is integers and booleans, as [PairingCopy.Panel] is. A String
     * on it would be a field an agent's error text could travel in, and the
     * fault screens are exactly where a far-end error would be tempting to
     * pass through.
     */
    @Test
    fun `no field of the panel can carry text`() {
        val offenders = RunFaultCopy.Panel::class.java.declaredFields
            .filterNot { it.isSynthetic || it.name == "\$stable" }
            .filter { it.type == String::class.java || it.type == CharSequence::class.java }
            .map { it.name }
        assertEquals("a Panel field that could hold a secret", emptyList<String>(), offenders)
    }
}
