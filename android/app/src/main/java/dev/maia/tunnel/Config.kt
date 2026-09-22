package dev.maia.tunnel

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One local port mapped to a port on the devbox. */
data class ForwardSpec(
    val name: String,
    val localPort: Int,
    val remotePort: Int,
)

/**
 * Everything the app remembers between launches.
 *
 * Two of these fields are secret material: [address] embeds a WireGuard
 * pre-shared key, so the whole string is a credential rather than a hostname,
 * and [identityPrivate] is this device's node key. Both live in the app's
 * private preferences, which file-based encryption already covers, and neither
 * is ever written to a log line or shown in full in the UI.
 */
class Config(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("maia-tunnel", Context.MODE_PRIVATE)

    var address: String
        get() = prefs.getString(KEY_ADDRESS, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_ADDRESS, value.trim()).apply()

    var identityPrivate: String
        get() = prefs.getString(KEY_IDENTITY, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_IDENTITY, value).apply()

    var debug: Boolean
        get() = prefs.getBoolean(KEY_DEBUG, false)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG, value).apply()

    /**
     * Whether the tunnel was on when the app was last closed. Recorded but not
     * yet acted on: reconnecting after a reboot needs a BOOT_COMPLETED receiver,
     * which is a decision about battery rather than a missing line of code.
     */
    var wasConnected: Boolean
        get() = prefs.getBoolean(KEY_WAS_CONNECTED, false)
        set(value) = prefs.edit().putBoolean(KEY_WAS_CONNECTED, value).apply()

    var forwards: List<ForwardSpec>
        get() {
            val raw = prefs.getString(KEY_FORWARDS, null) ?: return DEFAULT_FORWARDS
            return runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    ForwardSpec(o.getString("name"), o.getInt("local"), o.getInt("remote"))
                }
            }.getOrDefault(DEFAULT_FORWARDS)
        }
        set(value) {
            val arr = JSONArray()
            value.forEach { f ->
                arr.put(
                    JSONObject()
                        .put("name", f.name)
                        .put("local", f.localPort)
                        .put("remote", f.remotePort)
                )
            }
            prefs.edit().putString(KEY_FORWARDS, arr.toString()).apply()
        }

    val isPaired: Boolean get() = address.isNotEmpty()

    private companion object {
        const val KEY_ADDRESS = "address"
        const val KEY_IDENTITY = "identity"
        const val KEY_FORWARDS = "forwards"
        const val KEY_DEBUG = "debug"
        const val KEY_WAS_CONNECTED = "was_connected"

        // 2222 rather than 22, because binding below 1024 needs root and
        // Termius is perfectly happy being told a port.
        val DEFAULT_FORWARDS = listOf(ForwardSpec("SSH", 2222, 22))
    }
}
