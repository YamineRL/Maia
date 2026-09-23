package dev.maia.app.agent

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import dev.maia.app.feel.Haptics
import dev.maia.transport.AgentClient
import dev.maia.transport.HttpRegistrySource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import maiatunnel.Identity
import maiatunnel.Maiatunnel
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Where the process keeps its one [AgentDriver], if it has one.
 *
 * The same shape as [dev.maia.app.MaiaFlow] and for the same reason: the
 * driver is a plain class a test can build four of, and this is the part that
 * cannot be tested and therefore contains nothing but wiring. Which channels,
 * which executors, which sinks.
 *
 * **It is null until the phone is paired.** [driver] returns null and builds
 * nothing, which is still M8 PRD section 9's answer: an unpaired phone has no
 * agent surface, no tunnel and no channels.
 *
 * What changed on 2026-09-20 is what the user is told. Section 9 was read as
 * "an agent sentence does nothing", and doing nothing is the one answer a user
 * cannot tell apart from not having been heard, so they say it again and it
 * fails the same way. Copy section 5.15 now defines the words:
 * `m8_spoken_not_set_up` out loud and the `m8_pair_needed_*` screen, reached
 * through [needsPairing]. No tunnel is opened and no driver is built, so the
 * surface really is absent; only the silence is gone.
 *
 * **Two channels, not one.** 4096 is the agent, and it carries the passphrase
 * on every request. 4097 is `registry-web.service`, which serves
 * `GET /projects.json` and has no credential of its own. They are separate
 * `maiatunnel.Agent` instances because they are separate ports, and keeping
 * the registry channel unauthorised means the one credential that matters is
 * sent to exactly one place.
 *
 * **The passphrase is never held here.** It is read out of [AgentSecrets],
 * handed to [TunnelChannel.authorise], and from there written into Go. There
 * is no field, no local that outlives [build], and nothing in this file
 * prints, logs or returns it.
 */
object AgentHost {

    private const val TAG = "maia-agent"

    private val lock = Any()

    // Volatile because the ConnectivityManager callback reads it from the
    // connectivity thread, outside [lock], as do [driver] and [refreshList].
    @Volatile
    private var wiring: Wiring? = null

    private val _state = MutableStateFlow(RunState())

    /** What the run screen draws. Empty until the first instruction. */
    val state: StateFlow<RunState> = _state.asStateFlow()

    private val _needsPairing = MutableStateFlow(false)

    /**
     * An agent sentence arrived and this phone has never been paired.
     *
     * A flag and not a [RunFault], because there is no run: nothing was sent,
     * no project was resolved and no tunnel was opened. Putting it on
     * [RunState] would make the run screen describe a run that does not exist,
     * and `m8_pair_needed_body` is careful not to claim the tunnel is down for
     * the same reason. Maia has not tried anything. It has nowhere to try.
     */
    val needsPairing: StateFlow<Boolean> = _needsPairing.asStateFlow()

    private val _everSucceeded = MutableStateFlow(false)

    /**
     * Whether anything has ever got through on the pairing now stored.
     *
     * Copy section 5.15's one condition, held as a flow so the fault screen
     * redraws the moment the first run is admitted. The durable copy is in
     * [AgentSecrets.everSucceeded]; this is that value, read when the driver
     * is built and raised once when a turn is admitted.
     */
    val everSucceeded: StateFlow<Boolean> = _everSucceeded.asStateFlow()

    /** Reads the stored flag without building anything, for a surface drawn cold. */
    fun everSucceeded(context: Context): Boolean =
        _everSucceeded.value ||
            PrefsAgentSecrets(context.applicationContext).everSucceeded().also {
                if (it) _everSucceeded.value = true
            }

    private val _listReach = MutableStateFlow(ListReach.Fetching)

    /**
     * How well the phone can see the project list, section 5.16.
     *
     * Read off [ProjectCache] every time the driver asks it for projects, and
     * again after [refreshList], so the note over a stale list is never older
     * than the list under it. `Fetching` before a driver exists, which is the
     * honest answer: nothing has been asked for yet.
     */
    val listReach: StateFlow<ListReach> = _listReach.asStateFlow()

    /**
     * `m8_list_sync_action`. Asks the registry again, off the main thread.
     *
     * On the driver's own executor rather than a new one, so a refresh and an
     * instruction cannot be resolving against the cache at the same moment.
     * Does nothing when the phone is unpaired: there is nowhere to ask.
     */
    fun refreshList() {
        wiring?.refresh { _listReach.value = it }
    }

    /** Raises the `m8_pair_needed_*` screen. Opens nothing and builds nothing. */
    fun askToPair() { _needsPairing.value = true }

    /** The user has seen it, or has gone to the pairing screen from it. */
    fun pairingSeen() { _needsPairing.value = false }

    /**
     * The driver, built on first use and kept for the life of the process.
     *
     * @param speak says one [AgentAck] out loud. A resource id and two
     *   integers away from the agent's own words, which is the invariant this
     *   parameter's type exists to hold.
     */
    fun driver(context: Context, speak: (AgentAck, Int?, Int?) -> Unit): AgentDriver? {
        wiring?.let { return it.driver }
        val app = context.applicationContext
        return synchronized(lock) {
            wiring?.driver ?: build(app, PrefsAgentSecrets(app), speak)?.also { wiring = it }?.driver
        }
    }

    /**
     * The driver if one has already been built, and never building one.
     *
     * For a surface that has a run on screen and wants to act on it: by the
     * time anything is drawn the driver exists, and a screen is not a reason
     * to open a tunnel.
     */
    fun current(): AgentDriver? = wiring?.driver

    /** Whether an address and a passphrase are both on this phone. */
    fun paired(context: Context): Boolean =
        PrefsAgentSecrets(context.applicationContext).load()?.complete == true

    /**
     * Drops the wiring so the next [driver] call builds it again.
     *
     * The one caller is the surface that stores a passphrase: a user who has
     * just paired, or who has just re-entered a refused passphrase, should not
     * have to restart the app. Any live stream is closed, which is correct in
     * both cases: the credential it was using is the one being replaced.
     */
    fun rebuild() {
        val old = synchronized(lock) { wiring.also { wiring = null } } ?: return
        old.close()
        _state.value = RunState()
    }

    /**
     * Stores a pairing and builds the driver against it.
     *
     * The one road by which a passphrase enters this process: in, into
     * [AgentSecrets], and from there into Go. Nothing is returned, nothing is
     * logged, and the existing wiring is dropped rather than re-authorised so
     * that no stream outlives the credential it was opened with.
     *
     * Returns false when either half is missing, which is [Pairing.identity]'s
     * decision and not this method's.
     */
    fun pair(context: Context, address: String, passphrase: String): Boolean {
        val app = context.applicationContext
        val identity = Pairing.identity(address, passphrase) ?: return false
        PrefsAgentSecrets(app).store(identity)
        rebuild()
        _needsPairing.value = false
        return true
    }

    /**
     * A new passphrase against the address already stored, for `m8_auth_action`.
     *
     * Separate from [pair] so that the surface asking for a passphrase never
     * has to hold the address to send it back in: the address embeds the
     * pre-shared key, and the fewer places it can be is a real property rather
     * than a style. The screen types one field, this reads the other half out
     * of storage, and the two are put together here.
     *
     * Returns false when nothing is stored to repair, which is a phone that
     * should be on the pairing screen instead.
     */
    fun repair(context: Context, passphrase: String): Boolean {
        val app = context.applicationContext
        val stored = PrefsAgentSecrets(app).load() ?: return false
        return pair(app, stored.serverAddr, passphrase)
    }

    /** Forgets the address and the passphrase. The node key stays, per [AgentSecrets.node]. */
    fun unpair(context: Context) {
        PrefsAgentSecrets(context.applicationContext).clear()
        rebuild()
    }

    /** What the pairing surface should ask for next. */
    fun step(context: Context): Pairing.Step =
        Pairing.next(PrefsAgentSecrets(context.applicationContext).load())

    /**
     * This phone's tailcat node identity, generated on first ask and kept.
     *
     * Generating it before any connection is deliberate: the public half has
     * to exist before the devbox operator can put it in the `--allow` list,
     * so it must be showable on a pairing screen that has never connected to
     * anything. The private half goes straight back into [AgentSecrets] and
     * is never returned.
     */
    fun publicKey(context: Context): String? {
        val secrets = PrefsAgentSecrets(context.applicationContext)
        return runCatching { identity(secrets).publicKey() }.getOrNull()
    }

    private fun identity(secrets: AgentSecrets): Identity {
        secrets.node()?.let { saved ->
            runCatching { return Maiatunnel.loadIdentity(saved) }
            // A stored key that cannot be read is replaced below, which
            // changes the public key the devbox allow list pins. Loudly,
            // because every request then fails at the tunnel and nothing
            // else says why.
            Log.w(TAG, "stored node key could not be loaded; rotating identity")
        }
        val fresh = Maiatunnel.newIdentity()
        secrets.storeNode(fresh.privateText())
        return fresh
    }

    private fun build(
        app: Context,
        secrets: AgentSecrets,
        speak: (AgentAck, Int?, Int?) -> Unit,
    ): Wiring? {
        val identity = secrets.load()?.takeIf { it.complete } ?: return null

        // Read once here rather than on every render: this is a disk read and
        // `render` is called for every delta of a four-minute reply.
        var succeeded = secrets.everSucceeded()
        _everSucceeded.value = succeeded

        // Must happen before any tailscale code runs: netmon reads the
        // interface list as it starts and cannot get it for itself inside an
        // APK.
        ensureNetFacts(app)

        val node = runCatching { identity(secrets) }.getOrNull()
        val agent = TunnelChannel.open(node, identity.serverAddr, TunnelChannel.PORT)
        agent.authorise(identity.user, identity.passphrase)
        val registry = TunnelChannel.open(node, identity.serverAddr, HttpRegistrySource.DEFAULT_PORT)

        val notifier = AgentNotifier(app, Haptics(app))
        val work = Executors.newSingleThreadExecutor(named("maia-agent"))
        val ticks = Executors.newSingleThreadScheduledExecutor(named("maia-agent-tick"))
        // Section 4.2's one repeat, held so it can be cancelled. The same
        // scheduler the run machine's ticks use: it is one delayed runnable on
        // a thread that already exists, and a second executor for a single
        // twenty second wait would be a thread per blocked agent.
        val knocking = java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ScheduledFuture<*>?>()
        val alerts = RunAlerts(
            state = { _state.value },
            hand = notifier::feel,
            post = { posting ->
                // Every body comes out of the copy document, and each of the
                // three comes out of it differently. A block names its kind
                // and is one string. A finished run is two, joined: the
                // sentence and the rounded duration, and null when the clock
                // is unusable, which posts the title alone. A lost run is two
                // as well: the sentence and one of the five reasons, chosen
                // from an enum the run machine set. Nothing on any of these
                // three paths can carry a word an agent produced.
                notifier.alert(
                    posting.alert,
                    posting.project,
                    body = posting.kind?.let(notifier::blockedBody),
                    text = when (posting.alert) {
                        RunAlert.Ended -> notifier.doneBody(posting.startedAt, posting.endedAt)
                        RunAlert.Failed -> posting.loss?.let(notifier::failedBody)
                        RunAlert.Blocked -> null
                    },
                    kind = posting.kind,
                )
            },
            // Section 5.17's rewrite of the same row, in place, silently. It
            // is a separate sink and not a fourth alert because it posts to
            // the id that is already there: nothing new arrives in the shade,
            // and what is there stops being wrong.
            rewrite = notifier::blockedRow,
            // Schedule on a [RunAlerts.Knock] and cancel on null, which is
            // every path that moves the row off `Asking`. The state is read
            // again inside the runnable because twenty seconds is long enough
            // for an answer to land in the gap between the cancel being
            // decided and this thread waking up, and a knock for a block that
            // is over would put the row back up.
            knock = { pending ->
                knocking.getAndSet(
                    pending?.let { k ->
                        ticks.schedule(
                            {
                                if (_state.value.blockedRow == BlockedNotice.Asking) {
                                    notifier.knock(k.project, k.kind)
                                }
                            },
                            AgentAlerts.REPEAT_MS,
                            TimeUnit.MILLISECONDS,
                        )
                    },
                )?.cancel(false)
            },
            clear = notifier::clear,
            clock = System::currentTimeMillis,
        )

        val cache = ProjectCache(
            source = HttpRegistrySource(registry),
            clock = System::currentTimeMillis,
        )

        // Built before the driver because the driver takes its sink, and
        // handed the driver straight after because it has words to give back.
        val mic = AnswerMic(app)

        val driver = AgentDriver(
            client = AgentClient(agent),
            // The reach is read on the way back out rather than polled: every
            // road to the project list goes through here, so the screen's note
            // about the list and the list itself are always the same age.
            projects = { cache.list().also { _listReach.value = cache.reach } },
            render = { state ->
                _state.value = state
                // The first status past `Sending` is the first proof that this
                // phone's node key is on the devbox allow list: the request
                // went through the tunnel and the agent accepted it. Nothing
                // else on the phone can establish that, which is why section
                // 5.15's hint is a function of this one bit.
                if (!succeeded && state.status in ADMITTED) {
                    succeeded = true
                    _everSucceeded.value = true
                    secrets.markSucceeded()
                }
                alerts.render(state)
                // The service follows the run rather than the screen: a turn
                // that outlives the Activity is the case this exists for.
                RunService.follow(app, state)
            },
            speak = speak,
            feel = alerts::feel,
            // Section 4.6's three events, which are all the hand's: an answer
            // or a stop that did not land is felt a second after the thumb
            // that caused it, so `Schedule.fault` goes to `USAGE_TOUCH` here
            // and to the channel on the other sink.
            feelByHand = alerts::feelByHand,
            listen = mic::set,
            clock = System::currentTimeMillis,
            work = work,
            timer = { delay, block -> ticks.schedule(block, delay, TimeUnit.MILLISECONDS) },
        )
        mic.driver = driver
        return Wiring(driver, cache, agent, registry, mic, work, ticks)
    }

    /**
     * One other surface told when the pushed facts move, set by that surface.
     *
     * The answer surface's assistant channel is a second `maiatunnel.Agent`
     * built after this host's watcher was already registered (M9). A second
     * ConnectivityManager callback would double the pushes for no new
     * information, so `AnswerHost` sets this hook when its wiring is built
     * and [networksChanged] forwards to it after this host's own channels.
     */
    @Volatile
    var onNetworksChanged: (() -> Unit)? = null

    private val watchingNetworks = AtomicBoolean(false)

    /**
     * The interface facts push and the one network watcher, owed by whichever
     * host opens a tunnel channel first. This host's build calls it, and so
     * does AnswerHost's before its assistant channel: a process that only
     * ever answers questions never builds an AgentHost, and without the push
     * the Go side cannot read the interface list inside an APK, so every ask
     * failed at the dial.
     */
    internal fun ensureNetFacts(app: Context) {
        NetFacts.push(app)
        watchNetworks(app)
    }

    /**
     * Registers the one ConnectivityManager callback for the process.
     *
     * [NetFacts.push] in [build] fixes the network facts at a moment in time,
     * but a phone moves: a Wi-Fi handover leaves the Go tunnel bound to an
     * interface that no longer answers, and every dial fails until the
     * process is restarted. Each event re-reads the facts, and only when
     * they moved are the channels told to drop the client built on the old
     * ones so the next dial rebuilds against what is true now.
     *
     * Registered once and never unregistered. It guards process-wide Go
     * state rather than any one [Wiring], so it must outlive [rebuild], and
     * between builds its cost is a comparison inside [NetFacts.push].
     */
    private fun watchNetworks(app: Context) {
        if (!watchingNetworks.compareAndSet(false, true)) return
        val manager = app.getSystemService(ConnectivityManager::class.java)
        if (manager == null) {
            watchingNetworks.set(false)
            return
        }
        runCatching {
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                // All four report through the same path on purpose: none of
                // them is asked to decide what changed, because each fires
                // for reasons the pushed facts do not always reflect.
                // NetFacts compares snapshots and only a real difference
                // costs the tunnel its client.
                override fun onAvailable(network: Network) = networksChanged(app)

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) = networksChanged(app)

                override fun onLinkPropertiesChanged(
                    network: Network,
                    properties: LinkProperties,
                ) = networksChanged(app)

                override fun onLost(network: Network) = networksChanged(app)
            })
        }.onFailure { watchingNetworks.set(false) }
    }

    /** One network event: re-push the facts, and only a real change reaches the channels. */
    private fun networksChanged(app: Context) {
        if (!NetFacts.push(app)) return
        wiring?.networkChanged()
        onNetworksChanged?.invoke()
    }

    /**
     * The statuses that can only be reached after the server admitted an
     * instruction. [RunStatus.DidNotFinish] is not among them: a turn can
     * reach it without anything having been sent, which is the exact case the
     * hint exists for.
     */
    private val ADMITTED = setOf(
        RunStatus.Sent,
        RunStatus.Working,
        RunStatus.Queued,
        RunStatus.WaitingForYou,
        RunStatus.Finished,
    )

    private fun named(name: String) = ThreadFactory { runnable ->
        // Daemon: neither of these should hold the process up once everything
        // else has gone, and a stream that is still open when that happens has
        // nothing left to render to.
        Thread(runnable, name).apply { isDaemon = true }
    }

    /** Everything built together, so it can be closed together. */
    private class Wiring(
        val driver: AgentDriver,
        private val cache: ProjectCache,
        private val agent: TunnelChannel,
        private val registry: TunnelChannel,
        private val mic: AnswerMic,
        private val work: java.util.concurrent.ExecutorService,
        private val ticks: ScheduledExecutorService,
    ) : Closeable {

        /** A refresh on the driver's thread, reporting where it got to. */
        fun refresh(report: (ListReach) -> Unit) = work.execute {
            cache.refresh()
            report(cache.reach)
        }

        /**
         * Tells both channels the pushed facts moved so each drops its Go
         * client. The next request pays the cold bring-up again instead of
         * failing against a dead interface until the process restarts.
         */
        fun networkChanged() {
            runCatching { agent.networkChanged() }
            runCatching { registry.networkChanged() }
        }

        override fun close() {
            runCatching { driver.close() }
            // Before the channels, because the one thing that must not
            // outlive this is an open microphone.
            runCatching { mic.close() }
            // The credential first: TunnelChannel.close clears the basic auth
            // on the Go side before it closes anything.
            runCatching { agent.close() }
            runCatching { registry.close() }
            work.shutdownNow()
            ticks.shutdownNow()
        }
    }
}
