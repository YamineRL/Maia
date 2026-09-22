package dev.maia.tunnel

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import maiatunnel.Identity
import maiatunnel.Maiatunnel
import maiatunnel.Peer
import org.json.JSONObject

private const val TAG = "MaiaTunnel"

enum class LinkState { OFF, CONNECTING, ON }

/** Live counters for one forward, straight out of the Go side's StatusJSON. */
data class ForwardStatus(
    val name: String,
    val localPort: Int,
    val remotePort: Int,
    val openConns: Long,
    val totalConns: Long,
    val bytesIn: Long,
    val bytesOut: Long,
    val lastError: String,
)

data class TunnelState(
    val link: LinkState = LinkState.OFF,
    val pingMs: Long = 0,
    val error: String = "",
    val forwards: List<ForwardStatus> = emptyList(),
    val publicKey: String = "",
)

/**
 * The single owner of the Go [Peer] for the whole process.
 *
 * It is a singleton rather than a field on the service because the UI and the
 * foreground service need the same tunnel, and the tunnel must outlive the
 * activity: the entire point is that you switch to Termius and the forward is
 * still there. The service keeps the process alive; this object holds the state.
 */
object Tunnel {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(TunnelState())
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    private var peer: Peer? = null
    private var pollJob: Job? = null

    /** Loads the saved identity, generating and persisting one on first run. */
    private fun identity(config: Config): Identity {
        val saved = config.identityPrivate
        if (saved.isNotEmpty()) {
            runCatching { return Maiatunnel.loadIdentity(saved) }
                .onFailure { Log.w(TAG, "saved identity unusable, generating a new one") }
        }
        val fresh = Maiatunnel.newIdentity()
        config.identityPrivate = fresh.privateText()
        return fresh
    }

    /**
     * Reports the device's public node key without connecting, so it can be
     * shown and copied before the devbox has ever been paired. Generating it
     * here is deliberate: the key must exist before the server operator can put
     * it in an --allow list.
     */
    suspend fun publicKey(context: Context): String = withContext(Dispatchers.IO) {
        val key = identity(Config(context)).publicKey()
        _state.value = _state.value.copy(publicKey = key)
        key
    }

    fun connect(context: Context) {
        val app = context.applicationContext
        scope.launch {
            if (_state.value.link != LinkState.OFF) return@launch
            val config = Config(app)
            if (!config.isPaired) {
                _state.value = _state.value.copy(error = "No devbox address saved yet")
                return@launch
            }
            _state.value = _state.value.copy(link = LinkState.CONNECTING, error = "")

            // Must happen before any tailscale code runs: netmon reads the
            // interface list as it starts, and inside an APK it cannot get it
            // for itself.
            NetworkInfo.push(app)

            val result = runCatching {
                val id = identity(config)
                val p = Maiatunnel.newPeer(id, config.address)
                p.setDebug(config.debug)
                p.clearForwards()
                config.forwards.forEach { f ->
                    p.addForward(f.name, f.localPort.toLong(), f.remotePort.toLong())
                }
                // Start only binds loopback listeners, so it cannot fail slowly.
                p.start()
                peer = p
                _state.value = _state.value.copy(publicKey = id.publicKey())

                // Pay the 1.2 to 1.8 s bring-up here rather than making the
                // first SSH keystroke wait for it. A failure at this point is
                // reported but does not tear the forwards down: the link may
                // well come up on the next attempt, and a bound port that
                // refuses is a clearer signal than no port at all.
                runCatching { p.pingMillis(20_000) }
                    .onSuccess { _state.value = _state.value.copy(pingMs = it) }
                    .onFailure { _state.value = _state.value.copy(error = shortError(it)) }
            }

            result.onFailure { e ->
                Log.w(TAG, "connect failed: ${e.message}")
                runCatching { peer?.stop() }
                peer = null
                _state.value = TunnelState(
                    link = LinkState.OFF,
                    error = shortError(e),
                    publicKey = _state.value.publicKey,
                )
                return@launch
            }

            config.wasConnected = true
            _state.value = _state.value.copy(link = LinkState.ON)
            startPolling()
        }
    }

    fun disconnect(context: Context) {
        val app = context.applicationContext
        scope.launch {
            pollJob?.cancel()
            pollJob = null
            runCatching { peer?.stop() }
                .onFailure { Log.w(TAG, "stop: ${it.message}") }
            peer = null
            Config(app).wasConnected = false
            _state.value = TunnelState(publicKey = _state.value.publicKey)
        }
    }

    /** Re-measures round trip time on demand, from the UI's refresh control. */
    fun ping() {
        scope.launch {
            val p = peer ?: return@launch
            runCatching { p.pingMillis(10_000) }
                .onSuccess { _state.value = _state.value.copy(pingMs = it, error = "") }
                .onFailure { _state.value = _state.value.copy(error = shortError(it)) }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                peer?.let { p ->
                    runCatching { parseStatus(p.statusJSON()) }
                        .onSuccess { s -> _state.value = _state.value.copy(forwards = s) }
                }
                delay(1_000)
            }
        }
    }

    private fun parseStatus(json: String): List<ForwardStatus> {
        val root = JSONObject(json)
        val arr = root.optJSONArray("forwards") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ForwardStatus(
                name = o.optString("name"),
                localPort = o.optInt("localPort"),
                remotePort = o.optInt("remotePort"),
                openConns = o.optLong("openConns"),
                totalConns = o.optLong("totalConns"),
                bytesIn = o.optLong("bytesIn"),
                bytesOut = o.optLong("bytesOut"),
                lastError = o.optString("lastError"),
            )
        }
    }

    /**
     * Trims Go's wrapped error chains to something a notification can hold.
     * The full text goes to logcat, not to the user.
     */
    private fun shortError(e: Throwable): String {
        val msg = e.message.orEmpty().ifEmpty { e.javaClass.simpleName }
        return msg.removePrefix("maiatunnel: ").take(140)
    }
}
