package dev.maia.app.answer

import android.content.Context
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import dev.maia.actions.ProviderCalendars
import dev.maia.app.agent.AgentHost
import dev.maia.app.agent.AgentSecrets
import dev.maia.app.agent.PrefsAgentSecrets
import dev.maia.app.feel.Haptics
import dev.maia.app.settings.MaiaPrefs
import dev.maia.audio.speech.AnswerSpeaker
import dev.maia.audio.speech.Speaker
import dev.maia.transport.AssistantClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import maiatunnel.Identity
import maiatunnel.Maiatunnel
import java.io.Closeable
import java.io.File
import java.time.Clock
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicReference

/**
 * Where the process keeps its one [AnswerDriver].
 *
 * The same shape as [dev.maia.app.agent.AgentHost] and for the same reason:
 * the driver is a plain class a test can build, and this is the part that
 * cannot be tested and therefore contains nothing but wiring. Which
 * repository, which executor, which speaker, which channel.
 *
 * **It always builds.** Section 11's first row: local reads, handoffs and
 * media owe nothing to a pairing, so [driver] returns a driver on a phone
 * that has never seen the devbox. The tunnel is the lazy part: the
 * assistant client is only built on the first `AskRemote`, and only when
 * [AgentSecrets.assistantComplete] can be true. Until then the driver's own
 * answer for a remote question is `RemoteNotSetUp`, which is the honest
 * screen rather than a missing surface.
 *
 * **One network watcher, one process.** The interface facts push and the
 * ConnectivityManager callback are guarded process-wide in
 * [AgentHost.ensureNetFacts], which the lazy assistant build calls before
 * opening its channel: a process that only ever answers questions never
 * builds an AgentHost, and Go cannot read the interface list inside an
 * APK without them. This host's channel, a second `maiatunnel.Agent`,
 * hears real changes through `AgentHost.onNetworksChanged`, a hook this
 * file sets when its wiring is built rather than a second registered
 * callback.
 *
 * **The assistant credential is never held.** It is read out of
 * [AgentSecrets] inside the lazy build, handed to the `AssistantClient`,
 * and from there pushed into Go by [AssistantTunnelChannel.authorise].
 * Nothing in this file prints, logs or returns it.
 */
object AnswerHost {

    private const val TAG = "maia-answer"

    private val lock = Any()

    // Volatile because the AgentHost network hook reads it from the
    // connectivity thread, outside [lock].
    @Volatile
    private var wiring: Wiring? = null

    private val _state = MutableStateFlow(AnswerState())

    /** What the answer screen draws. Empty until the first command. */
    val state: StateFlow<AnswerState> = _state.asStateFlow()

    /**
     * The driver, built on first use and kept for the life of the process.
     * Never null: an unpaired phone still answers everything it can answer
     * alone.
     *
     * @param speaker the voice answers go through. Wrapped in
     *   [AnswerSpeaker] here, so the long form of an answer arrives as
     *   sentence-sized pieces and the ringer policy of the delegate the
     *   caller passed is untouched.
     */
    fun driver(context: Context, speaker: Speaker): AnswerDriver {
        wiring?.let { return it.driver }
        val app = context.applicationContext
        return synchronized(lock) {
            wiring?.driver ?: build(app, PrefsAgentSecrets(app), speaker).also { wiring = it }.driver
        }
    }

    /**
     * The driver if one has already been built, and never building one.
     *
     * For a surface that has an answer on screen and wants to act on it: by
     * the time anything is drawn the driver exists.
     */
    fun current(): AnswerDriver? = wiring?.driver

    /**
     * Whether an assistant request could be made: a paired address and the
     * gateway credential are both on this phone. Reads the store without
     * building anything, for a surface drawn cold.
     */
    fun assistantReady(context: Context): Boolean =
        PrefsAgentSecrets(context.applicationContext).assistantComplete()

    /**
     * Stores the assistant gateway's credential and drops the wiring.
     *
     * The one road by which it enters this process: in, into
     * [AgentSecrets], and from there into Go on the first ask. The wiring
     * is dropped rather than re-authorised for the same reason the agent's
     * is: no channel outlives the credential it was opened with, and a lazy
     * channel that was never opened costs a rebuild nothing.
     */
    fun setAssistantPassphrase(context: Context, phrase: String) {
        val app = context.applicationContext
        PrefsAgentSecrets(app).storeAssistantPassphrase(phrase)
        rebuild()
    }

    /**
     * Drops the wiring so the next [driver] call builds it again.
     *
     * Any in-flight ask or speech is cancelled by the driver's close, and
     * the channel, if one was ever opened, clears its credential on the Go
     * side before it closes.
     */
    fun rebuild() {
        val old = synchronized(lock) { wiring.also { wiring = null } } ?: return
        old.close()
        _state.value = AnswerState()
    }

    /**
     * Forgets the assistant credential and nothing else: the pairing is the
     * agent's and outlives it. Called by a pairing surface that wants the
     * gateway credential re-entered without unpairing the phone.
     */
    fun forgetAssistant(context: Context) {
        val app = context.applicationContext
        PrefsAgentSecrets(app).storeAssistantPassphrase("")
        rebuild()
    }

    /**
     * The pushed network facts moved; the channel, if one is open, is told
     * so the next dial rebuilds against what is true now.
     *
     * Called from `AgentHost.onNetworksChanged`, the hook this host sets
     * when its wiring is built. Registering a second ConnectivityManager
     * callback here would double the pushes for no new information: the
     * facts are process-wide and AgentHost already guards them.
     */
    fun networkChanged() {
        wiring?.networkChanged()
    }

    private fun build(app: Context, secrets: AgentSecrets, speaker: Speaker): Wiring {
        val work = Executors.newSingleThreadExecutor(named("maia-answer"))
        val haptics = Haptics(app)

        // The lazy half. Nothing is dialled until a question needs the
        // devbox, and a phone that cannot answer remotely gets null, which
        // the driver reports as `RemoteNotSetUp` rather than throwing.
        val channel = AtomicReference<AssistantTunnelChannel?>(null)
        val client = AtomicReference<AssistantClient?>(null)

        val driver = AnswerDriver(
            reader = LocalReader(
                calendar = ProviderCalendars(app),
                clock = Clock.systemDefaultZone(),
                battery = { batteryPercent(app) },
            ),
            handoffs = HandoffExecutor(app),
            assistantFor = {
                client.get() ?: buildAssistant(app, secrets)?.also { (built, chan) ->
                    client.set(built)
                    channel.set(chan)
                }?.first
            },
            converserFor = {
                // Built for every ask the devbox cannot take; the object is
                // cheap, the model it opens on first reply is not, and
                // `installed` keeps an unprovisioned phone from ever paying it.
                LiteRtConverser(
                    modelDir = File(app.filesDir, LiteRtConverser.MODEL_DIR_NAME),
                    cacheDir = File(app.cacheDir, "litert"),
                    dispatchLibDir = { TensorDispatch.install(app) },
                )
            },
            render = { _state.value = it },
            speaker = AnswerSpeaker(speaker),
            feel = haptics::play,
            clock = System::currentTimeMillis,
            work = work,
            homeCity = { MaiaPrefs(app).homeCity },
            trace = { Log.w(TAG, it) },
        )

        // One process-wide watcher lives in AgentHost; this host's channel
        // hears real changes through the hook rather than a second
        // registration. Set here and not replaced: [networkChanged] reads
        // [wiring], so a rebuilt host's channel is the one reached.
        AgentHost.onNetworksChanged = { networkChanged() }

        return Wiring(driver, channel, work)
    }

    /**
     * The devbox client, or null when this phone cannot ask: unpaired, or
     * no assistant credential stored.
     *
     * The node identity is the same one [AgentHost] uses: loaded from
     * [AgentSecrets] or generated and stored there, because its public half
     * is what the devbox allow list names and a second identity would be a
     * second phone to the tunnel.
     */
    private fun buildAssistant(
        app: Context,
        secrets: AgentSecrets,
    ): Pair<AssistantClient, AssistantTunnelChannel>? {
        val addr = secrets.load()?.serverAddr?.takeIf { it.isNotBlank() } ?: return null
        val credential = secrets.assistantPassphrase()?.takeIf { it.isNotBlank() } ?: return null
        // Must happen before this host's first tailcat call, and cannot be
        // left to AgentHost: a process that only ever answers questions
        // never builds one, and without the pushed facts the Go side cannot
        // read the interface list inside an APK, so every dial fails.
        AgentHost.ensureNetFacts(app)
        val node = runCatching { identity(secrets) }.getOrNull()
        val chan = AssistantTunnelChannel.open(node, addr)
        return AssistantClient(chan, credential) to chan
    }

    private fun identity(secrets: AgentSecrets): Identity {
        secrets.node()?.let { saved ->
            runCatching { return Maiatunnel.loadIdentity(saved) }
            // A stored key that cannot be read is replaced below, which
            // changes the public key the devbox allow list pins. Loudly,
            // because every ask then fails at the tunnel and nothing else
            // says why.
            Log.w(TAG, "stored node key could not be loaded; rotating identity")
        }
        val fresh = Maiatunnel.newIdentity()
        secrets.storeNode(fresh.privateText())
        return fresh
    }

    /**
     * The battery percentage, or null when the platform will not say.
     *
     * The sticky `ACTION_BATTERY_CHANGED` broadcast needs no receiver and
     * no permission; an absent or malformed one is an honest "cannot read"
     * rather than a guessed level.
     */
    private fun batteryPercent(app: Context): Int? {
        val intent = runCatching {
            app.registerReceiver(null, IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return level * 100 / scale
    }

    private fun named(name: String) = ThreadFactory { runnable ->
        // Daemon: this thread should not hold the process up once
        // everything else has gone.
        Thread(runnable, name).apply { isDaemon = true }
    }

    /** Everything built together, so it can be closed together. */
    private class Wiring(
        val driver: AnswerDriver,
        private val channel: AtomicReference<AssistantTunnelChannel?>,
        private val work: java.util.concurrent.ExecutorService,
    ) : Closeable {

        /** Tells the channel, if one was ever opened, that the pushed facts moved. */
        fun networkChanged() {
            runCatching { channel.get()?.networkChanged() }
        }

        override fun close() {
            // The driver closes the client, and the client closes the
            // channel, whose own close clears the credential on the Go
            // side before anything else goes.
            runCatching { driver.close() }
            work.shutdownNow()
        }
    }
}
