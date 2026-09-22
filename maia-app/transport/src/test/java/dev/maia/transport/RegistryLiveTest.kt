package dev.maia.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The registry transport, against the real OpenCode server on the devbox.
 *
 * Skipped unless `MAIA_AGENT_PASSWORD` is set, exactly as [AgentLiveTest] is,
 * so an ordinary `gradle test` on a machine without the server passes rather
 * than failing for a reason nobody can act on.
 *
 * **The variable names do not match, and that is the trap.** The devbox holds
 * the secret as `OPENCODE_SERVER_PASSWORD` in
 * `~/.config/opencode/agent-web.env`, because that is the name OpenCode reads.
 * These tests look for `MAIA_AGENT_PASSWORD`. Sourcing the env file alone
 * therefore sets nothing these tests can see, every live test skips, and the
 * suite reports success having exercised no server at all. That is the worst
 * failure a live suite has, because it looks exactly like a pass. Map it:
 *
 * ```sh
 * set -a; . ~/.config/opencode/agent-web.env; set +a
 * export MAIA_AGENT_PASSWORD="$OPENCODE_SERVER_PASSWORD"
 * ./gradlew :transport:test --console=plain
 * ```
 *
 * Confirm by the count, never by the exit code: skipped must be 0. Gradle does
 * not treat an environment variable as a task input, so a run that skipped
 * everything is `UP-TO-DATE` on the next attempt even once the variable is
 * set. Pass `--rerun-tasks` after fixing the environment.
 *
 * These two exist because PRD section 8 names a transport that does not work,
 * and a finding that lives only in a report is a finding that gets
 * rediscovered. They send no prompt: they are two GETs.
 */
class RegistryLiveTest {

    private val host = System.getenv("MAIA_AGENT_HOST") ?: "127.0.0.1"
    private val port = System.getenv("MAIA_AGENT_PORT")?.toIntOrNull() ?: 4096
    private val user = System.getenv("MAIA_AGENT_USER")?.ifBlank { null } ?: "opencode"
    private val password = System.getenv("MAIA_AGENT_PASSWORD")?.ifBlank { null }

    private val maia = "/home/user/projects/maia"

    private fun channel() = LoopbackChannel(host, port, user, password!!)

    @org.junit.Before
    fun serverIsThere() {
        assumeTrue("MAIA_AGENT_PASSWORD is not set, skipping the live test", password != null)
    }

    /**
     * A tripwire, and it is meant to go red on good news.
     *
     * Verified 2026-09-18 against opencode 1.18.31: `GET /file/content`
     * answers 500 `UnknownError` for every path that exists, and 200 with an
     * empty `content` for every path that does not, so the failure mode is
     * inverted. The same defect takes out `GET /file`, `GET /find/file`,
     * `GET /api/fs/read`, `GET /api/fs/list` and `GET /api/location`. The
     * server log names it: `TypeError: undefined is not an object (evaluating
     * 'a.name')`, thrown from a shared location resolver before any disk
     * access, first seen 2026-09-16.
     *
     * If this test fails, the file family works again and PRD section 8's
     * original plan is back on the table. Read the rest of this file, decide,
     * and then delete this test.
     */
    @Test
    fun `the file route still cannot deliver the registry`() {
        channel().use { c ->
            val reply = c.request(
                "GET",
                "/file/content?path=" + java.net.URLEncoder.encode(Registry.DEFAULT_PATH, "UTF-8") +
                    "&directory=" + java.net.URLEncoder.encode(maia, "UTF-8"),
            )
            val content = runCatching { Json.parse(reply.body).string("content") }.getOrNull()
            val usable = reply.ok && content != null &&
                runCatching { Registry.parse(content) }.isSuccess
            assertTrue(
                "GET /file/content delivered the registry. The opencode defect this feature " +
                    "routed around is fixed: revisit PRD section 8 and delete this test. " +
                    "HTTP ${reply.status}",
                !usable,
            )
        }
    }

    /**
     * The static registry endpoint does not exist yet, and the client says so
     * in one line instead of throwing or inventing an empty project list.
     *
     * The agent port is the wrong port for it on purpose: OpenCode owns 4096
     * and serves its own SPA there. What this proves is the failure path, which
     * is the path that runs on a phone in a lift.
     */
    @Test
    fun `a missing registry endpoint is one line, not a crash and not an empty list`() {
        channel().use { c ->
            val result = HttpRegistrySource(c).load()
            assertTrue(
                "the agent port is not the registry endpoint, so this must be unavailable: $result",
                result is RegistryFetch.Unavailable,
            )
            assertTrue((result as RegistryFetch.Unavailable).detail.isNotBlank())
        }
    }

    /**
     * The registry, fetched the way the phone fetches it.
     *
     * This is the carrier that replaced `GET /file/content`: the static
     * endpoint on [HttpRegistrySource.DEFAULT_PORT], served by
     * `tunnel/scripts/registry-serve.py` and forwarded by
     * `tunnel/deploy/tailcat-phone.service`. The assertion is deliberately
     * end to end. It is not "the socket answered": it is that the bytes that
     * came back parse under the same strict parser the phone uses, and carry
     * the fifteen projects this box actually has.
     *
     * Skipped where the endpoint is not listening, which is every machine
     * that is not the devbox. The class-level gate on MAIA_AGENT_PASSWORD
     * applies here too and is the reason this still skips cleanly on a build
     * machine; the credential itself is not used against this port, which has
     * no auth, and [the registry endpoint carries no passphrase of its own]
     * is the test that pins that down.
     */
    @Test
    fun `the registry arrives through the static endpoint and holds fifteen projects`() {
        assumeTrue(
            "nothing is listening on ${HttpRegistrySource.DEFAULT_PORT}; " +
                "start tunnel/deploy/registry-web.service",
            endpointIsUp(),
        )
        registryChannel().use { c ->
            val fetched = HttpRegistrySource(c).load()
            assertTrue(
                "the registry endpoint must deliver a parsable registry, got $fetched",
                fetched is RegistryFetch.Loaded,
            )
            val registry = (fetched as RegistryFetch.Loaded).registry
            assertEquals(Registry.SCHEMA_VERSION, registry.version)
            assertEquals("this box has fifteen projects", 15, registry.active.size)
            assertTrue("every path is absolute", registry.projects.all { it.path.startsWith("/") })
            assertTrue(
                "nextNumber must be past every allocation",
                registry.nextNumber > registry.projects.maxOf { it.number },
            )
            // The numbering is the contract, not an implementation detail:
            // section 8 pins maia to 7 and the phone says "seven" out loud.
            assertEquals("maia is 7 in section 8", 7, hit(registry.byName("maia")).number)
        }
    }

    /**
     * The endpoint serves the registry and refuses to be a filesystem.
     *
     * One route, compared literally, so there is no directory to escape from.
     * Worth asserting from the client side rather than trusting the server's
     * own smoke test: this port is reachable from the phone, and the whole
     * reason it is allowed to carry no passphrase is that its vocabulary is
     * exactly one file.
     */
    @Test
    fun `the registry endpoint serves one path and refuses writes`() {
        assumeTrue("nothing is listening on ${HttpRegistrySource.DEFAULT_PORT}", endpointIsUp())
        registryChannel().use { c ->
            for (path in listOf("/", "/etc/passwd", "/../../etc/passwd", "/projects.json.bak")) {
                assertEquals("$path must not be served", 404, c.request("GET", path).status)
            }
            // A body on POST and PUT on purpose: refusing a write without
            // draining it desyncs the connection, and the GET below is what
            // catches that. DELETE goes without one, since HttpURLConnection
            // is not dependable about bodies on it.
            for ((method, body) in listOf("POST" to "{}", "PUT" to "{}", "DELETE" to null)) {
                assertEquals(
                    "$method must be refused: this endpoint has no write path",
                    405,
                    c.request(method, HttpRegistrySource.DEFAULT_PATH, body).status,
                )
            }
            // And it still works afterwards. A refused write that desynced the
            // connection would show up right here.
            assertEquals(200, c.request("GET", HttpRegistrySource.DEFAULT_PATH).status)
        }
    }

    /**
     * The registry endpoint has no credential, and that is a decision, not an
     * accident. If one ever grows in front of it, this test fails and the
     * comment in registry-web.service is the thing to re-read.
     */
    @Test
    fun `the registry endpoint carries no passphrase of its own`() {
        assumeTrue("nothing is listening on ${HttpRegistrySource.DEFAULT_PORT}", endpointIsUp())
        LoopbackChannel(host, HttpRegistrySource.DEFAULT_PORT, "nobody", "not-a-passphrase").use { c ->
            assertEquals(
                "a wrong credential must be ignored, not rejected: this port has no auth",
                200,
                c.request("GET", HttpRegistrySource.DEFAULT_PATH).status,
            )
        }
    }

    private fun endpointIsUp(): Boolean = runCatching {
        java.net.Socket().use {
            it.connect(java.net.InetSocketAddress(host, HttpRegistrySource.DEFAULT_PORT), 500)
        }
    }.isSuccess

    /** The credential is ignored by this endpoint; something must still be sent. */
    private fun registryChannel() =
        LoopbackChannel(host, HttpRegistrySource.DEFAULT_PORT, user, password ?: "no-passphrase-needed")

    private fun hit(lookup: Lookup): ProjectEntry {
        assertTrue("expected a hit, got $lookup", lookup is Lookup.Hit)
        return (lookup as Lookup.Hit).project
    }

    /**
     * A wrong passphrase on the AGENT port is reported as a wrong passphrase.
     *
     * This is about the client's error mapping, not about the registry
     * carrier: it runs against 4096, which does have auth. It stays because
     * [HttpRegistrySource] keeps its 401 branch for the day something sits in
     * front of the registry, and an unexercised error path is a guess.
     */
    @Test
    fun `a wrong passphrase is reported as a wrong passphrase`() {
        LoopbackChannel(host, port, user, "definitely-not-the-passphrase").use { c ->
            val result = HttpRegistrySource(c).load()
            assertTrue(result is RegistryFetch.Unavailable)
            assertEquals(401, (result as RegistryFetch.Unavailable).status)
            assertTrue(result.detail.contains("passphrase"))
        }
    }
}
