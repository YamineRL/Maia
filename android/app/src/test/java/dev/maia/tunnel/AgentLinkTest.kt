package dev.maia.tunnel

import org.junit.Assert.*
import org.junit.Test

class AgentLinkTest {
    @Test fun `preset does not replace ssh or collide with a local port`() {
        assertTrue(AgentLink.canAdd(listOf(ForwardSpec("SSH", 2222, 22))))
        assertFalse(AgentLink.canAdd(listOf(ForwardSpec("Other", 4096, 80))))
        assertFalse(AgentLink.canAdd(listOf(AgentLink.preset())))
        assertEquals(4096, AgentLink.preset().remotePort)
    }

    @Test fun `opening requires a matching live forward without errors`() {
        val spec = AgentLink.preset()
        val live = ForwardStatus(spec.name, spec.localPort, spec.remotePort, 0, 0, 0, 0, "")
        val on = TunnelState(link = LinkState.ON, forwards = listOf(live))
        assertEquals("http://127.0.0.1:4096/", AgentLink.url(on, spec))
        assertNull(AgentLink.url(on.copy(link = LinkState.OFF), spec))
        assertNull(AgentLink.url(on.copy(forwards = emptyList()), spec))
        assertNull(AgentLink.url(on.copy(forwards = listOf(live.copy(remotePort = 8080))), spec))
        assertNull(AgentLink.url(on.copy(forwards = listOf(live.copy(lastError = "refused"))), spec))
        assertNull(AgentLink.url(on, null))
    }
}
