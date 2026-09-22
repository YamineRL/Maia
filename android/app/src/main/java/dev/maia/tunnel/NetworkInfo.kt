package dev.maia.tunnel

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import maiatunnel.Maiatunnel
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Supplies the Go side with the network facts it is not allowed to read itself.
 *
 * Android blocks netlink route dumps for ordinary apps, so Go's net.Interfaces()
 * fails inside an APK even though the identical code works from an adb shell.
 * java.net.NetworkInterface goes through bionic's getifaddrs instead, which is
 * permitted, and ConnectivityManager knows the default route. Push both before
 * connecting.
 */
object NetworkInfo {

    private const val TAG = "MaiaTunnel"

    fun push(context: Context) {
        runCatching { Maiatunnel.setInterfacesJSON(interfacesJson()) }
            .onFailure { Log.w(TAG, "interface push failed: ${it.message}") }
        runCatching {
            val (name, gateway) = defaultRoute(context)
            Maiatunnel.setDefaultRoute(name, gateway)
        }.onFailure { Log.w(TAG, "default route push failed: ${it.message}") }
    }

    private fun interfacesJson(): String {
        val arr = JSONArray()
        for (ni in NetworkInterface.getNetworkInterfaces()) {
            val addrs = JSONArray()
            for (ia in ni.interfaceAddresses) {
                val addr = ia.address ?: continue
                // hostAddress on a v6 link-local carries a %scope suffix that
                // ParseCIDR on the Go side will not accept.
                val host = addr.hostAddress?.substringBefore('%') ?: continue
                addrs.put("$host/${ia.networkPrefixLength}")
            }
            arr.put(
                JSONObject()
                    .put("name", ni.name)
                    .put("index", ni.index)
                    .put("mtu", runCatching { ni.mtu }.getOrDefault(1500))
                    .put("up", runCatching { ni.isUp }.getOrDefault(false))
                    .put("loopback", runCatching { ni.isLoopback }.getOrDefault(false))
                    .put("pointToPoint", runCatching { ni.isPointToPoint }.getOrDefault(false))
                    .put("multicast", runCatching { ni.supportsMulticast() }.getOrDefault(false))
                    .put("addrs", addrs)
            )
        }
        return arr.toString()
    }

    /** Returns the interface name and gateway of the active default route. */
    private fun defaultRoute(context: Context): Pair<String, String> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return "" to ""
        val props = cm.getLinkProperties(network) ?: return "" to ""

        val gateway = props.routes
            .firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway
            ?.hostAddress
            .orEmpty()

        return props.interfaceName.orEmpty() to gateway
    }
}
