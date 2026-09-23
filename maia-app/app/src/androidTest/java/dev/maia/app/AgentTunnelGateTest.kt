package dev.maia.app

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.maia.app.agent.AgentHost
import dev.maia.app.agent.PrefsAgentSecrets
import dev.maia.app.agent.TunnelChannel
import maiatunnel.Maiatunnel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * The agent tunnel path, end to end, on the phone's own Go runtime.
 *
 * Exists because "the tunnel is off" is the driver's catch-all for any
 * request that fails without an HTTP status, and it says nothing about
 * which layer failed. This test dials the devbox exactly the way
 * [AgentHost] does — pushed interface facts, the stored node identity,
 * port 4096, the stored passphrase — and names the stage that fails.
 *
 * Skips on an unpaired phone: pairing state is a legal absence, and the
 * check that still runs reports it.
 */
@RunWith(AndroidJUnit4::class)
class AgentTunnelGateTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @Test
    fun agentChannel_dialsDevbox_getsAnHttpAnswer() {
        val secrets = PrefsAgentSecrets(context)
        val identity = secrets.load()
        assumeTrue("phone is not paired", identity?.complete == true)
        identity ?: return

        // The order AgentHost keeps: facts before any tailcat call.
        AgentHost.ensureNetFacts(context)

        val saved = secrets.node()
        assertNotNull("no node identity stored", saved)
        val node = try {
            Maiatunnel.loadIdentity(saved)
        } catch (e: Exception) {
            fail("stored node key does not load: ${e.message}")
            throw AssertionError("unreachable")
        }

        val channel = try {
            TunnelChannel.open(node, identity.serverAddr)
        } catch (e: Exception) {
            fail("channel did not open: ${e.message}")
            throw AssertionError("unreachable")
        }
        try {
            channel.authorise(TunnelChannel.USER, identity.passphrase)
            val reply = try {
                channel.request("GET", "/session?limit=1", null)
            } catch (e: IOException) {
                // The text is the transport's own: dial, handshake or the
                // exchange. It is the line TunnelOff was invented from.
                fail("tunnel request failed: ${e.message}")
                throw AssertionError("unreachable")
            }
            Log.i(TAG, "agent channel answered: HTTP ${reply.status}")
            assertEquals(200, reply.status)
        } finally {
            channel.close()
        }
    }
}

private const val TAG = "AgentTunnelGate"
