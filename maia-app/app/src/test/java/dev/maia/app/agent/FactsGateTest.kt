package dev.maia.app.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision that keeps a network event cheap when nothing it reports
 * moved.
 *
 * [NetFacts.push] itself cannot run here: it needs a phone's interface list,
 * a ConnectivityManager and libgojni at once. What can run is the gate that
 * stands between a ConnectivityManager callback and the teardown of the Go
 * client, and its contract is the whole point: a true answer costs the
 * tunnel its client and any stream a turn is on, so a false one is a turn
 * killed for nothing, and a missed true is the bug this exists to fix.
 */
class FactsGateTest {

    private val ifaces = """[{"name":"wlan0","addrs":["10.0.0.2/24"]}]"""
    private val route = "wlan0" to "10.0.0.1"

    @Test
    fun `the first snapshot is always a change`() {
        // Nothing was pushed before, so Go holds whatever it read itself,
        // which inside an APK is nothing at all.
        assertTrue(FactsGate().changed(ifaces, route))
    }

    @Test
    fun `an identical snapshot is not a change`() {
        val gate = FactsGate()
        gate.changed(ifaces, route)
        // The shape of a capabilities flap: ConnectivityManager fires, the
        // pushed facts come out byte-identical, and the tunnel must not pay.
        assertFalse(gate.changed(ifaces, route))
        assertFalse(gate.changed(ifaces, route))
    }

    @Test
    fun `a moved interface list is a change`() {
        val gate = FactsGate()
        gate.changed(ifaces, route)
        assertTrue(gate.changed("""[{"name":"rmnet0","addrs":["10.1.0.2/24"]}]""", route))
    }

    @Test
    fun `a moved default route alone is a change`() {
        val gate = FactsGate()
        gate.changed(ifaces, route)
        // The interface list can survive a gateway move untouched, and the
        // dial path binds by the route half.
        assertTrue(gate.changed(ifaces, "wlan0" to "10.0.0.9"))
    }

    @Test
    fun `a lost default route is a change`() {
        val gate = FactsGate()
        gate.changed(ifaces, route)
        assertTrue(gate.changed(ifaces, "" to ""))
    }

    @Test
    fun `a failed read counts as a change and keeps the last good one`() {
        val gate = FactsGate()
        gate.changed(ifaces, route)
        // "Could not enumerate" is not evidence the list is unchanged, so it
        // is reported as a move. But the last good read is kept rather than
        // overwritten by the failure, so a recovery that reads back the same
        // facts Go already holds is not a second move.
        assertTrue(gate.changed(null, route))
        assertFalse(gate.changed(ifaces, route))
    }

    @Test
    fun `a recovery that differs from the last good read is a change`() {
        val gate = FactsGate()
        gate.changed(ifaces, route)
        gate.changed(null, route)
        // The handover that finished while enumeration was failing is still
        // seen: the comparison is against what Go holds, not against the gap.
        assertTrue(gate.changed("""[{"name":"rmnet0","addrs":["10.1.0.2/24"]}]""", route))
    }

    @Test
    fun `a route-only update during a failed read is still recorded`() {
        val gate = FactsGate()
        gate.changed(ifaces, route)
        gate.changed(null, "" to "")
        // The route half is recorded even when the interface half could not
        // be read, so the next snapshot is compared against it and not
        // against the older one.
        assertFalse(gate.changed(ifaces, "" to ""))
    }
}
