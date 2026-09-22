package dev.maia.transport

/**
 * The project registry: the numbering the phone speaks in.
 *
 * PRD section 8 and principle B. A recogniser mangles `streamzFinal` and
 * `agentharnessfork` reliably and will keep doing so, so the spoken
 * primary key is a small integer and a name is an accelerator that is allowed
 * to fail. The devbox writes the file (`tunnel/scripts/maia-projects.sh`), the
 * phone reads it, and nothing here allocates or renumbers anything: a number
 * is decided once, on the box, and this side only looks numbers up.
 *
 * The schema, version 1:
 *
 * ```json
 * {
 *   "schema": "maia/projects",
 *   "version": 1,
 *   "generatedAt": "2026-09-18T08:32:41Z",
 *   "root": "/home/user/projects",
 *   "nextNumber": 16,
 *   "projects": [
 *     {"number": 7, "name": "maia", "path": "...", "state": "active",
 *      "firstSeen": "2026-09-18", "lastSeen": "2026-09-18"}
 *   ]
 * }
 * ```
 *
 * A retired entry carries `"state": "retired"` and a `retiredAt`. It is kept,
 * not dropped, and that is the point: a number pinned to a name that no longer
 * exists is what stops "send it to seven" from quietly meaning a different
 * repository after an archive.
 */
class Registry(
    val version: Int,
    val generatedAt: String?,
    val root: String?,
    val nextNumber: Int,
    val projects: List<ProjectEntry>,
) {

    /** What a numbered list on screen shows, in number order. */
    val active: List<ProjectEntry> = projects.filter { it.state == ProjectState.ACTIVE }

    private val byNumber: Map<Int, ProjectEntry> = projects.associateBy { it.number }

    /**
     * The primary path. A number is exact or it is nothing: there is no
     * lenient matching to do on an integer, which is exactly why it is the
     * primary key.
     */
    fun byNumber(number: Int): Lookup {
        val found = byNumber[number] ?: return Lookup.Miss(number.toString())
        return if (found.state == ProjectState.RETIRED) Lookup.Retired(found) else Lookup.Hit(found)
    }

    /**
     * The accelerator. Case folded, separators ignored, and deliberately
     * unwilling to guess.
     *
     * A name resolves to a [Lookup.Hit] only when exactly one project is
     * compatible with what was said. A prefix relationship in either
     * direction, including a name that is exactly one project's whole name
     * and also the start of another's, comes back as [Lookup.Ambiguous] even
     * when there is exactly one candidate, because PRD section 8 is explicit:
     * "A near miss shows the numbered list too: the phone never picks between
     * `openbrowser` and `openbrowser-ai` on its own." A single-candidate
     * [Lookup.Ambiguous] is therefore not a bug and not a rounding error, it
     * is the rule: the candidates shorten the list the user is shown, they do
     * not replace the asking.
     *
     * Saying "openbrowser" therefore shows a shortlist of two rather than
     * reaching project 10, which costs one utterance. The alternative costs an
     * agent with pre-approved tool permissions running in the wrong repository,
     * and the number always works. `:core-nlu` resolves spoken names by the
     * same rule and the two must not drift: see [RegistryTest] for the shared
     * table of cases.
     *
     * Retired entries are never name candidates. Their numbers still resolve,
     * so a user who says "nine" is told what nine was, but a retired name
     * cannot be reached by speaking it: there is nothing to run there.
     */
    fun byName(spoken: String): Lookup {
        val query = fold(spoken)
        if (query.isEmpty()) return Lookup.Miss(spoken)

        // Said in full and nothing else extends it: that is the project.
        val exact = active.filter { fold(it.name) == query }
        val extenders = active.filter { fold(it.name).startsWith(query) && fold(it.name) != query }
        if (exact.size == 1 && extenders.isEmpty()) return Lookup.Hit(exact.single())
        if (exact.size == 1) return Lookup.Ambiguous(exact + extenders)

        // Said in part. The list, always, even when only one thing is on it.
        val partial = active.filter {
            val name = fold(it.name)
            name.startsWith(query) || query.startsWith(name)
        }
        return if (partial.isEmpty()) Lookup.Miss(spoken) else Lookup.Ambiguous(partial)
    }

    companion object {
        /** Where the devbox script writes it. */
        const val DEFAULT_PATH = "/home/user/.config/maia/projects.json"

        /** The only schema version this build understands. */
        const val SCHEMA_VERSION = 1

        /**
         * Parses the registry document.
         *
         * Strict, unlike the event parsing in [AgentEvent]. An event union
         * will grow members and a client that throws on a new one is a client
         * that breaks on a server upgrade, but this file is written by a
         * script in this repository: an unreadable one means the script and
         * the phone disagree, and silently showing a short list of projects is
         * worse than saying so.
         */
        fun parse(text: String): Registry {
            val root = runCatching { Json.parse(text) }.getOrElse {
                throw RegistryException("not JSON: ${it.message}")
            }
            val schema = root.string("schema")
            if (schema != "maia/projects") {
                throw RegistryException("not a project registry: schema=$schema")
            }
            val version = root.long("version")?.toInt()
                ?: throw RegistryException("no version")
            if (version != SCHEMA_VERSION) {
                throw RegistryException("registry is version $version, this build reads $SCHEMA_VERSION")
            }
            val entries = root.list("projects")
                ?: throw RegistryException("no projects array")

            val projects = entries.map { entry ->
                val number = entry.long("number")?.toInt()
                    ?: throw RegistryException("a project has no number")
                val name = entry.string("name")
                    ?: throw RegistryException("project $number has no name")
                ProjectEntry(
                    number = number,
                    name = name,
                    path = entry.string("path") ?: "",
                    state = when (val s = entry.string("state")) {
                        "active" -> ProjectState.ACTIVE
                        "retired" -> ProjectState.RETIRED
                        else -> throw RegistryException("project $number has state=$s")
                    },
                    firstSeen = entry.string("firstSeen"),
                    lastSeen = entry.string("lastSeen"),
                    retiredAt = entry.string("retiredAt"),
                )
            }.sortedBy { it.number }

            val duplicate = projects.groupBy { it.number }.entries.firstOrNull { it.value.size > 1 }
            if (duplicate != null) {
                // A reused number is the one corruption that must never be
                // tolerated quietly: it is the failure principle B exists to
                // prevent, and it would send an instruction to the wrong repo.
                throw RegistryException("number ${duplicate.key} is used twice")
            }

            return Registry(
                version = version,
                generatedAt = root.string("generatedAt"),
                root = root.string("root"),
                nextNumber = root.long("nextNumber")?.toInt()
                    ?: ((projects.maxOfOrNull { it.number } ?: 0) + 1),
                projects = projects,
            )
        }

        /**
         * Case folded, separators ignored.
         *
         * Everything that is not a letter or a digit goes, which covers the
         * hyphen in `escha-amd-port`, the space a recogniser puts in "streamz
         * final", and the camel hump in `MemoryClip` by way of the case fold.
         */
        internal fun fold(s: String): String =
            s.filter { it.isLetterOrDigit() }.lowercase()
    }
}

/** One numbered project. [path] is absolute, and is what a request sends as `directory`. */
data class ProjectEntry(
    val number: Int,
    val name: String,
    val path: String,
    val state: ProjectState,
    val firstSeen: String? = null,
    val lastSeen: String? = null,
    val retiredAt: String? = null,
)

enum class ProjectState { ACTIVE, RETIRED }

/**
 * What a lookup can honestly answer.
 *
 * A sealed hierarchy rather than a nullable, because "not sure" and "not
 * there" want different words on screen and the caller must not be able to
 * collapse them into one null. Nothing here ever picks a winner.
 */
sealed class Lookup {
    /** Exactly one project, certainly. */
    data class Hit(val project: ProjectEntry) : Lookup()

    /**
     * Show the numbered list. [candidates] is the shortlist worth showing
     * first, and may hold a single entry: see [Registry.byName].
     */
    data class Ambiguous(val candidates: List<ProjectEntry>) : Lookup()

    /** Nothing matched. Show the whole numbered list. */
    data class Miss(val query: String) : Lookup()

    /**
     * That number was allocated, and its project is gone. Reached by number
     * only. Worth its own case so the phone can say what seven used to be
     * instead of implying seven never existed.
     */
    data class Retired(val project: ProjectEntry) : Lookup()
}

class RegistryException(message: String) : RuntimeException("registry: $message")


/** The outcome of asking for the registry. */
sealed class RegistryFetch {
    data class Loaded(val registry: Registry) : RegistryFetch()

    /**
     * No registry. [status] is an HTTP status where there was one and 0 where
     * the failure was local, and [detail] is the one line PRD section 9 wants
     * on screen: "One clear message naming the cause."
     */
    data class Unavailable(val status: Int, val detail: String) : RegistryFetch()
}

/**
 * Where a registry comes from.
 *
 * Narrow on purpose, and separate from [Registry] on purpose. The numbering
 * rules and the ambiguity rules above are settled; the transport under them is
 * not, for the reason recorded in [HttpRegistrySource]. Everything that reads
 * a registry depends on this interface, so the day the transport changes,
 * nothing above it moves.
 *
 * [load] never throws. A source that cannot answer returns
 * [RegistryFetch.Unavailable], because a caller that has to catch in order to
 * find out will eventually forget to, and the failure it forgets is a phone
 * showing an empty project list as though that were the truth.
 */
interface RegistrySource {
    fun load(): RegistryFetch
}

/**
 * A registry the app already holds as text: bundled at build time, pasted
 * during pairing, or cached from the last successful sync.
 *
 * Honest and stale. It is the working answer today, and it is also what any
 * network source wants behind it as a cache, since PRD section 9 says the
 * agent surface is absent with the tunnel down rather than broken.
 */
class TextRegistrySource(private val text: String) : RegistrySource {
    override fun load(): RegistryFetch =
        runCatching { RegistryFetch.Loaded(Registry.parse(text)) }
            .getOrElse { RegistryFetch.Unavailable(0, it.message ?: it.toString()) }
}

/**
 * The registry over the tunnel, as one GET returning the file as its body.
 *
 * **Not `GET /file/content`, and this is the finding of 2026-09-18.** PRD
 * section 8 says the phone fetches the registry through that route. Verified
 * against the live OpenAPI document at `GET /doc`, the contract is
 * `operationId: file.read`, query parameters `path` (required), `directory`
 * and `workspace` (optional), answering `FileContent`
 * (`{type: "text"|"binary", content: string}`, no `data` envelope). Verified
 * against the running server the same day, the route does not work at all:
 *
 * - a path that exists answers `500 UnknownError`
 * - a path that does not exist answers `200` with `content` empty, so the
 *   failure mode is inverted and an empty 200 must never be read as an empty
 *   file
 * - `GET /file`, `GET /find/file`, `GET /api/fs/read`, `GET /api/fs/list`
 *   and `GET /api/location` fail identically, with every parameter encoding
 * - the server log gives `TypeError: undefined is not an object (evaluating
 *   'a.name')` from a shared location resolver, thrown before any disk access,
 *   in opencode 1.18.31, first seen 2026-09-16
 *
 * It is a defect in the server build, not in our configuration, and the server
 * is in use and not being upgraded to chase it. So the file family is not a
 * transport this feature can stand on, and nothing here calls it.
 *
 * **The carrier, settled 2026-09-18.** A static read-only endpoint on the
 * devbox, on its own port, serving exactly one path: the registry file as the
 * response body, with no JSON envelope around it. It is
 * `tunnel/scripts/registry-serve.py`, run by
 * `tunnel/deploy/registry-web.service`, bound to 127.0.0.1:[DEFAULT_PORT] and
 * reached through the tailcat forward alongside 22 and 4096. It regenerates
 * the registry per request rather than serving a copy cached at boot, so a
 * project created this morning is in the answer.
 *
 * It carries no credential, and the asymmetry with the agent port is
 * deliberate rather than an omission. Port 4096 is an agent with every tool
 * pre-approved, so its passphrase is the whole lock; this port answers one
 * GET with a file that is already world readable on that disk, and its locks
 * are that it binds loopback only and that the tunnel pins one client by node
 * key. The 401 branch below is kept anyway, because a source that cannot say
 * "wrong passphrase" the day something does sit in front of it is a source
 * that reports a lie instead.
 *
 * The [channel] is the same [AgentChannel] seam as everything else, which
 * means the tunnel dials it and no loopback port is bound. It is not
 * necessarily the *same instance* as the agent's: a second port on the devbox
 * is a second `maiatunnel.Agent` in `:app`, and that is a construction detail
 * rather than anything this module decides.
 */
class HttpRegistrySource(
    private val channel: AgentChannel,
    private val path: String = DEFAULT_PATH,
) : RegistrySource {

    override fun load(): RegistryFetch {
        val reply = try {
            channel.request("GET", path)
        } catch (e: Exception) {
            // The tunnel is down, or the endpoint is not listening. One line,
            // no retry storm: section 9 again.
            return RegistryFetch.Unavailable(0, "cannot reach the registry: ${e.message ?: e}")
        }
        if (!reply.ok) {
            val detail = if (reply.status == 401) {
                "wrong or missing passphrase for the registry endpoint"
            } else {
                reply.body.take(300).ifBlank { "no body" }
            }
            return RegistryFetch.Unavailable(reply.status, detail)
        }
        if (reply.body.isBlank()) {
            // Never treated as an empty registry. An empty numbered list is
            // indistinguishable on screen from having lost every project.
            return RegistryFetch.Unavailable(reply.status, "the registry endpoint returned nothing")
        }
        return TextRegistrySource(reply.body).load()
    }

    companion object {
        /** The one path the static endpoint serves. */
        const val DEFAULT_PATH = "/projects.json"

        /**
         * The port that endpoint listens on, and the port the tunnel forwards.
         *
         * Adjacent to the agent's 4096 so the pair reads as one story, and
         * below the ephemeral floor of 32768 so the kernel can never hand it
         * out as a source port and take it before the unit starts. This
         * module binds nothing and dials nothing: the constant lives here so
         * that `:app`, which builds the channel, does not have to guess, and
         * so that changing it changes one number.
         */
        const val DEFAULT_PORT = 4097
    }
}
