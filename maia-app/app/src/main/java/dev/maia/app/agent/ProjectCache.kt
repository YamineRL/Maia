package dev.maia.app.agent

import dev.maia.transport.ProjectEntry
import dev.maia.transport.Registry
import dev.maia.transport.RegistryFetch
import dev.maia.transport.RegistrySource

/**
 * The registry, fetched over the tunnel and remembered between turns.
 *
 * `AgentDriver` re-reads the registry every turn on purpose: a project can be
 * added on the devbox while the app is running, and a number the user has just
 * been told about must work. What it cannot do is pay for an HTTP round trip
 * through a cold tunnel between the user finishing a sentence and the
 * instruction being sent, every single time. So this holds the last good
 * answer and refreshes it when it is older than [ttlMs].
 *
 * **An unavailable registry is never an empty registry.** `RegistryFetch`
 * separates those two for a reason: an empty list would make every project
 * name unknown and put the user in front of the list screen with nothing on
 * it, which reads as "your projects are gone". Holding the last good list
 * means a tunnel that drops mid-session still resolves the numbers the user
 * has been saying all afternoon, and the failure surfaces on the send instead,
 * where section 5.6's fault screens name it properly.
 *
 * Every method is called on `AgentDriver`'s single work thread, and the fields
 * are guarded anyway because [refresh] may be called from elsewhere later.
 */
class ProjectCache(
    private val source: RegistrySource,
    private val clock: () -> Long,
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {

    private val lock = Any()
    private var held: Registry? = null
    private var fetchedAt = Long.MIN_VALUE
    private var lastFetch: RegistryFetch? = null

    /** The active list, oldest good answer if the registry cannot be reached now. */
    fun list(): List<ProjectEntry> {
        val stale = synchronized(lock) { held == null || clock() - fetchedAt >= ttlMs }
        if (stale) refresh()
        return synchronized(lock) { held?.projects.orEmpty() }
    }

    /** Fetches now, whatever the age. Keeps what it has if the fetch fails. */
    fun refresh() {
        val fetch = runCatching { source.load() }.getOrNull()
        synchronized(lock) {
            lastFetch = fetch ?: RegistryFetch.Unavailable(0, "load threw")
            if (fetch is RegistryFetch.Loaded) {
                held = fetch.registry
                fetchedAt = clock()
            } else if (held == null) {
                // Nothing good has ever arrived. The clock still moves, so a
                // dead registry is retried on the next turn and not on every
                // call in a tight loop.
                fetchedAt = clock()
            }
        }
    }

    /** Whether anything has ever been fetched. Not the same as an empty registry. */
    val known: Boolean get() = synchronized(lock) { held != null }

    /**
     * Which of section 5.16's situations the list screen is in.
     *
     * The three that all look like an empty list are kept apart here because
     * this is the last place on the phone that still knows the difference.
     * `RegistryFetch.Unavailable` carries the status, and a 503 from the
     * registry port is not a network failure: it is the machine answering and
     * saying it has nothing, which `registry-serve.py` is written to say
     * rather than serve an empty list. Collapsing it back into "could not
     * reach your machine" would throw away the one case where Maia knows more
     * than that.
     *
     * A 503 arriving while a list is already held is its own value,
     * [ListReach.StaleNoRegistry], and neither [ListReach.Stale] nor
     * [ListReach.NoRegistry]. The user has a list and it is the last one Maia
     * had, so it is not the no-registry screen; but `m8_list_stale_note`
     * promises implicitly that a refresh will fix it, and on a 503 that
     * promise is false, because the far end keeps answering 503 until somebody
     * runs the script on the devbox. `m8_list_stale_no_registry_note` is the
     * sentence that says both halves, and this is the value that picks it.
     */
    val reach: ListReach get() = synchronized(lock) {
        val fetch = lastFetch
        when {
            held != null && fetch is RegistryFetch.Loaded -> ListReach.Fresh
            held != null &&
                fetch is RegistryFetch.Unavailable &&
                fetch.status == NO_REGISTRY -> ListReach.StaleNoRegistry
            held != null -> ListReach.Stale
            fetch == null -> ListReach.Fetching
            fetch is RegistryFetch.Unavailable && fetch.status == NO_REGISTRY -> ListReach.NoRegistry
            else -> ListReach.Unreachable
        }
    }

    companion object {
        /**
         * Long enough that a conversation of several instructions pays for one
         * fetch, short enough that a project added on the devbox works on the
         * user's second try rather than after a restart.
         */
        const val DEFAULT_TTL_MS = 60_000L

        /**
         * `registry-serve.py` answers 503 with the cause named when it has no
         * registry file at all, which is a statement about the devbox and not
         * about the network. See `tunnel/deploy/registry-web.service`: "A
         * stale registry is a fine answer; an empty one never is."
         */
        const val NO_REGISTRY = 503
    }
}

/**
 * How well the phone can see the project list, section 5.16.
 *
 * An enum and not a boolean, for the reason the whole section exists: "no
 * list" is several different facts and the user needs a different sentence for
 * each. It is also an enum and not a class, so there is no field on it the
 * registry's own words could be carried into and no way for a devbox error
 * string to reach a screen.
 */
enum class ListReach {
    /** Nothing has been asked for yet, or the first ask is still out. */
    Fetching,

    /** A list arrived and it is current. */
    Fresh,

    /** A list is held and the last refresh failed. It is the last one Maia had. */
    Stale,

    /**
     * A list is held and the last refresh was answered with a 503.
     *
     * Apart from [Stale] because the difference is what the user should do
     * next. A stale list came from a refresh that failed to arrive, so trying
     * again is worth something. A 503 is the machine answering and saying it
     * has nothing, and it will keep saying that until the registry is built at
     * that end, so "try again" is advice that cannot work.
     */
    StaleNoRegistry,

    /** Nothing has ever arrived and the last try did not reach the machine. */
    Unreachable,

    /** The machine answered, and said it has no project list. */
    NoRegistry,
}
