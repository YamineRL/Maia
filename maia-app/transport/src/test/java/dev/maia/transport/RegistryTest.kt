package dev.maia.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The numbering and the lookup rules, with no network anywhere.
 *
 * The rules under test are PRD section 8 and principle B: a number is exact
 * and primary, a name is lenient and secondary, and a near miss is never
 * resolved by guessing.
 */
class RegistryTest {

    private val sample = """
        {
          "schema": "maia/projects",
          "version": 1,
          "generatedAt": "2026-09-18T08:32:41Z",
          "root": "/home/user/projects",
          "nextNumber": 16,
          "projects": [
            {"number": 3, "name": "escha-amd-port", "path": "/r/escha-amd-port", "state": "active"},
            {"number": 7, "name": "maia", "path": "/r/maia", "state": "active"},
            {"number": 8, "name": "MemoryClip", "path": "/r/MemoryClip", "state": "active"},
            {"number": 9, "name": "nomad-workspace", "path": "/r/nomad-workspace",
             "state": "retired", "retiredAt": "2026-09-18"},
            {"number": 10, "name": "openbrowser", "path": "/r/openbrowser", "state": "active"},
            {"number": 11, "name": "openbrowser-ai", "path": "/r/openbrowser-ai", "state": "active"},
            {"number": 14, "name": "streamzFinal", "path": "/r/streamzFinal", "state": "active"}
          ]
        }
    """.trimIndent()

    private val registry = Registry.parse(sample)

    // ---- parsing ---------------------------------------------------------

    @Test
    fun `parses the document and keeps retired entries`() {
        assertEquals(1, registry.version)
        assertEquals(16, registry.nextNumber)
        assertEquals(7, registry.projects.size)
        assertEquals("retired entries are kept, not dropped", 6, registry.active.size)
        assertEquals(listOf(3, 7, 8, 9, 10, 11, 14), registry.projects.map { it.number })
    }

    @Test
    fun `a wrong schema or version is refused rather than half read`() {
        for (bad in listOf(
            """{"schema":"something-else","version":1,"projects":[]}""",
            """{"schema":"maia/projects","version":99,"projects":[]}""",
            """{"schema":"maia/projects","version":1}""",
            "not json at all",
        )) {
            runCatching { Registry.parse(bad) }
                .onSuccess { org.junit.Assert.fail("accepted: $bad") }
                .onFailure { assertTrue(it is RegistryException) }
        }
    }

    @Test
    fun `a reused number is refused`() {
        // The one corruption that must never pass quietly: it is exactly the
        // failure principle B exists to prevent.
        val doubled = """
            {"schema":"maia/projects","version":1,"projects":[
              {"number":7,"name":"maia","path":"/r/maia","state":"active"},
              {"number":7,"name":"other","path":"/r/other","state":"active"}]}
        """.trimIndent()
        val failure = runCatching { Registry.parse(doubled) }.exceptionOrNull()
        assertTrue("expected a refusal, got $failure", failure is RegistryException)
        assertTrue(failure!!.message!!.contains("used twice"))
    }

    // ---- by number -------------------------------------------------------

    @Test
    fun `a number is exact, and a retired number says so`() {
        assertEquals(Lookup.Hit(registry.projects.single { it.number == 7 }), registry.byNumber(7))
        val retired = registry.byNumber(9)
        assertTrue("nine is retired, not missing", retired is Lookup.Retired)
        assertEquals("nomad-workspace", (retired as Lookup.Retired).project.name)
        // Never allocated, so it is a miss rather than anything cleverer.
        assertTrue(registry.byNumber(12) is Lookup.Miss)
        assertTrue(registry.byNumber(0) is Lookup.Miss)
    }

    // ---- by name ---------------------------------------------------------

    @Test
    fun `name matching folds case and ignores separators`() {
        for (spoken in listOf("maia", "Maia", "MAIA", " maia ")) {
            assertEquals(spoken, 7, hit(registry.byName(spoken)).number)
        }
        for (spoken in listOf("escha-amd-port", "escha amd port", "EschaAmdPort", "escha_amd_port")) {
            assertEquals(spoken, 3, hit(registry.byName(spoken)).number)
        }
        // The two the recogniser is known to mangle, spelled the way dictation
        // hands them over.
        assertEquals(14, hit(registry.byName("streamz final")).number)
        assertEquals(8, hit(registry.byName("memory clip")).number)
    }

    @Test
    fun `a whole name that is also the start of another name asks rather than picks`() {
        // "openbrowser" is project 10's entire name, so resolving it to 10 is
        // tempting and this module used to do it. It is still the wrong call:
        // the user who says it may have meant openbrowser-ai, and being wrong
        // means an agent with pre-approved tool permissions runs in the wrong
        // repository. Asking costs one utterance, and project 10 is always
        // reachable as "ten". :core-nlu resolves spoken names by this same
        // rule, and this table is the contract between the two.
        val short = registry.byName("open browser")
        assertTrue("expected the list, got $short", short is Lookup.Ambiguous)
        assertEquals(listOf(10, 11), (short as Lookup.Ambiguous).candidates.map { it.number })

        // A name nothing else shares still resolves outright.
        assertEquals(11, hit(registry.byName("open browser AI")).number)
        assertEquals(7, hit(registry.byName("maia")).number)
    }

    @Test
    fun `a near miss is never resolved by guessing`() {
        // Section 8: the phone never picks between openbrowser and
        // openbrowser-ai on its own.
        val both = registry.byName("openbrows")
        assertTrue("expected the list, got $both", both is Lookup.Ambiguous)
        assertEquals(listOf(10, 11), (both as Lookup.Ambiguous).candidates.map { it.number })

        // And a unique near miss is still ambiguous, deliberately: one
        // candidate shortens the list, it does not skip the asking.
        val one = registry.byName("streamz")
        assertTrue("expected the list, got $one", one is Lookup.Ambiguous)
        assertEquals(listOf(14), (one as Lookup.Ambiguous).candidates.map { it.number })
    }

    @Test
    fun `a name that matches nothing is a miss, and a retired name is not a candidate`() {
        assertTrue(registry.byName("quantum cheese") is Lookup.Miss)
        assertTrue(registry.byName("") is Lookup.Miss)
        assertTrue(registry.byName("   ") is Lookup.Miss)
        // nomad-workspace is retired: number 9 still answers, the name does not.
        assertTrue("a retired name must not run anything", registry.byName("nomad workspace") is Lookup.Miss)
    }

    // ---- sources ---------------------------------------------------------

    @Test
    fun `a text source loads, and bad text is a value rather than a throw`() {
        val loaded = TextRegistrySource(sample).load()
        assertTrue(loaded is RegistryFetch.Loaded)
        assertEquals(7, (loaded as RegistryFetch.Loaded).registry.byNumber(7).let { hit(it).number })

        val bad = TextRegistrySource("{}").load()
        assertTrue("a source never throws", bad is RegistryFetch.Unavailable)
        assertEquals(0, (bad as RegistryFetch.Unavailable).status)
    }

    @Test
    fun `an http source maps every failure to one line, and never to an empty registry`() {
        assertEquals(
            "an empty body is not an empty project list",
            RegistryFetch.Unavailable(200, "the registry endpoint returned nothing"),
            HttpRegistrySource(StubChannel(Reply(200, "   "))).load(),
        )
        val unauthorised = HttpRegistrySource(StubChannel(Reply(401, "nope"))).load()
        assertTrue(unauthorised is RegistryFetch.Unavailable)
        assertEquals(401, (unauthorised as RegistryFetch.Unavailable).status)
        assertTrue(unauthorised.detail.contains("passphrase"))

        val down = HttpRegistrySource(StubChannel(Reply(0, ""), fail = "connection refused")).load()
        assertTrue(down is RegistryFetch.Unavailable)
        assertEquals("a dead tunnel is a value, not an exception", 0, (down as RegistryFetch.Unavailable).status)

        val good = HttpRegistrySource(StubChannel(Reply(200, sample))).load()
        assertTrue(good is RegistryFetch.Loaded)
        assertEquals(6, (good as RegistryFetch.Loaded).registry.active.size)
    }

    /**
     * The generated file on this box, against this parser.
     *
     * The script and the phone are two halves of one schema and nothing else
     * checks that they agree. Skipped where the registry has not been
     * generated, which is every machine that is not the devbox.
     */
    @Test
    fun `the registry this box generated parses, and carries section 8's numbering`() {
        val file = File(Registry.DEFAULT_PATH)
        assumeTrue("no ${Registry.DEFAULT_PATH}; run tunnel/scripts/maia-projects.sh", file.isFile)

        val registry = Registry.parse(file.readText())
        assertEquals(Registry.SCHEMA_VERSION, registry.version)
        assertTrue("numbers start at 1", registry.projects.all { it.number >= 1 })
        assertTrue("every path is absolute", registry.projects.all { it.path.startsWith("/") })
        assertEquals("maia is 7 in section 8", 7, hit(registry.byName("maia")).number)
        assertEquals(
            "nextNumber must be past every allocation",
            true,
            registry.nextNumber > registry.projects.maxOf { it.number },
        )
    }

    private fun hit(lookup: Lookup): ProjectEntry {
        assertTrue("expected a hit, got $lookup", lookup is Lookup.Hit)
        return (lookup as Lookup.Hit).project
    }

    /** One canned reply, or one failure to reach anything at all. */
    private class StubChannel(
        private val reply: Reply,
        private val fail: String? = null,
    ) : AgentChannel {
        override fun request(method: String, path: String, body: String?): Reply {
            if (fail != null) throw java.io.IOException(fail)
            return reply
        }

        override fun stream(path: String, sink: LineSink) =
            throw UnsupportedOperationException("the registry is one GET, not a stream")

        override fun close() = Unit
    }
}
