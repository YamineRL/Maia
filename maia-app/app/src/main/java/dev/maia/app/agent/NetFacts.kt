package dev.maia.app.agent

import android.content.Context
import android.net.ConnectivityManager
import maiatunnel.Maiatunnel
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The network facts Go is not allowed to read for itself, pushed in before
 * anything tailcat runs.
 *
 * From SDK 30 Android blocks netlink route dumps for ordinary apps, so Go's
 * `net.Interfaces()` inside an APK fails with `route ip+net: netlinkrib:
 * permission denied` while the identical binary works from an adb shell.
 * `java.net.NetworkInterface` goes through bionic's `getifaddrs`, which is
 * permitted, and `ConnectivityManager` knows the default route. The Go side
 * takes both through `netmon.RegisterInterfaceGetter` and
 * `UpdateLastKnownDefaultRouteInterface`.
 *
 * Pushed once before anything tailcat runs, and again on every network event
 * the ConnectivityManager reports: the Go side cannot see a handover either,
 * and a snapshot taken at build time is wrong by the first move to cellular.
 * [push] compares the fresh read against what Go was last offered and says
 * whether it moved, because the caller tears the tunnel down on a true answer.
 *
 * This is a copy of `dev.maia.tunnel.NetworkInfo`, by the same rule that lets
 * `:transport` inherit that app's code by copy: the tunnel app is a finished
 * tool the user depends on daily and is not edited to share code with this
 * one, and the two APKs are separate processes with separate uids that could
 * not share a Go runtime anyway.
 *
 * **Nothing here logs.** An interface list names the user's networks.
 * Failures are swallowed for the same reason the tunnel app warns rather than
 * throws: a missing default route makes the dial slower, not impossible, and
 * a crash at the moment the user spoke is worse than a slow answer.
 */
internal object NetFacts {

    private val gate = FactsGate()

    /**
     * Pushes the current facts to Go and reports whether they moved since the
     * last snapshot Go was offered.
     *
     * The return exists for the ConnectivityManager callback in [AgentHost]:
     * it fires on every network event, most of which change nothing here, and
     * a true answer costs the tunnel its client. A capabilities flap that
     * leaves the interface list and the default route alone must not tear
     * down the stream a turn is on.
     */
    fun push(context: Context): Boolean {
        val interfaces = runCatching { interfacesJson() }.getOrNull()
        val route = runCatching { defaultRoute(context) }.getOrElse { "" to "" }
        if (!gate.changed(interfaces, route)) return false
        interfaces?.let { runCatching { Maiatunnel.setInterfacesJSON(it) } }
        runCatching { Maiatunnel.setDefaultRoute(route.first, route.second) }
        return true
    }

    private fun interfacesJson(): String {
        val all = JSONArray()
        for (face in NetworkInterface.getNetworkInterfaces()) {
            val addresses = JSONArray()
            for (bound in face.interfaceAddresses) {
                val address = bound.address ?: continue
                // hostAddress on a v6 link-local carries a %scope suffix that
                // ParseCIDR on the Go side will not accept.
                val host = address.hostAddress?.substringBefore('%') ?: continue
                addresses.put("$host/${bound.networkPrefixLength}")
            }
            all.put(
                JSONObject()
                    .put("name", face.name)
                    .put("index", face.index)
                    .put("mtu", runCatching { face.mtu }.getOrDefault(1500))
                    .put("up", runCatching { face.isUp }.getOrDefault(false))
                    .put("loopback", runCatching { face.isLoopback }.getOrDefault(false))
                    .put("pointToPoint", runCatching { face.isPointToPoint }.getOrDefault(false))
                    .put("multicast", runCatching { face.supportsMulticast() }.getOrDefault(false))
                    .put("addrs", addresses),
            )
        }
        return all.toString()
    }

    /** The interface name and gateway of the active default route, or two empty strings. */
    private fun defaultRoute(context: Context): Pair<String, String> {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return "" to ""
        val network = manager.activeNetwork ?: return "" to ""
        val properties = manager.getLinkProperties(network) ?: return "" to ""
        val gateway = properties.routes
            .firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway
            ?.hostAddress
            .orEmpty()
        return properties.interfaceName.orEmpty() to gateway
    }
}

/**
 * Whether a fresh snapshot differs from what Go was last offered.
 *
 * Separate from [NetFacts] because the decision is pure and testable while
 * the push itself needs a phone, a JVM and libgojni at once. The rule that
 * matters is that a network event which changes neither half is reported as
 * no change: the caller tears the tunnel down on a true answer, and a false
 * one is a turn killed for nothing.
 */
internal class FactsGate {

    private var interfaces: String? = null
    private var route: Pair<String, String>? = null

    /**
     * Records the snapshot and reports whether it moved.
     *
     * A null [newInterfaces] means enumeration failed, which counts as a
     * change on purpose: "could not read the interfaces" is not evidence they
     * are the same. The last good read is kept rather than overwritten by the
     * failure, so what comes back afterwards is still compared against what
     * Go actually holds.
     */
    @Synchronized
    fun changed(newInterfaces: String?, newRoute: Pair<String, String>): Boolean {
        val moved = newInterfaces == null || newInterfaces != interfaces || newRoute != route
        if (newInterfaces != null) interfaces = newInterfaces
        route = newRoute
        return moved
    }
}
