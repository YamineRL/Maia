package dev.maia.app.screens

import dev.maia.app.R
import dev.maia.app.agent.ListReach
import dev.maia.app.agent.ProjectCache
import dev.maia.transport.Registry
import dev.maia.transport.RegistryFetch
import dev.maia.transport.RegistrySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Section 5.16, both halves: the cache keeping the situations apart and the
 * copy saying the right sentence about each.
 *
 * The thing under test is a distinction that was being made carefully on the
 * devbox and discarded on the phone. `registry-serve.py` answers 503 naming
 * the cause instead of serving an empty list, and until now nothing here read
 * the status, so the one case where Maia knew more than "something went wrong"
 * produced the vaguest screen in the product.
 */
class ListReachTest {

    private val json =
        """{"schema":"maia/projects","version":1,"nextNumber":9,"projects":[""" +
            """{"number":7,"name":"maia","path":"/home/user/maia","state":"active"}]}"""

    private class Source(var answer: RegistryFetch) : RegistrySource {
        override fun load(): RegistryFetch = answer
    }

    private fun cache(answer: RegistryFetch): ProjectCache =
        ProjectCache(Source(answer), clock = { 0L })

    @Test
    fun `nothing asked for yet is the first sync, not a failure`() {
        val cache = cache(RegistryFetch.Loaded(Registry.parse(json)))
        assertEquals(ListReach.Fetching, cache.reach)
        val empty = ListCopy.empty(cache.reach, held = false)
        assertEquals(R.string.m8_list_first_sync_title, empty?.title)
        assertNull("a wait names no file", empty?.path)
    }

    @Test
    fun `a list that arrived is fresh and has no empty screen and no note`() {
        val cache = cache(RegistryFetch.Loaded(Registry.parse(json)))
        cache.refresh()
        assertEquals(ListReach.Fresh, cache.reach)
        assertNull(ListCopy.empty(cache.reach, held = true))
        assertNull(ListCopy.staleNote(cache.reach, held = true))
    }

    /**
     * Never had one and could not reach the machine. The body's "nothing older
     * to fall back on" is the one sentence separating this from every later
     * failure, and the title names what this phone observed rather than a
     * cause it inferred.
     */
    @Test
    fun `a first fetch that did not reach the machine is none yet`() {
        val cache = cache(RegistryFetch.Unavailable(0, "no route to host"))
        cache.refresh()
        assertEquals(ListReach.Unreachable, cache.reach)
        val empty = ListCopy.empty(cache.reach, held = false)
        assertEquals(R.string.m8_list_none_yet_title, empty?.title)
        assertEquals(R.string.m8_list_none_yet_body, empty?.body)
        assertNull("this phone cannot name a file for a failure it did not reach", empty?.path)
    }

    /**
     * The 503, which is the whole reason this file exists. It is a fact about
     * the devbox and not about the network, so the phone is specific and names
     * the script that builds the list.
     */
    @Test
    fun `a 503 is the machine answering that it has no list`() {
        val cache = cache(RegistryFetch.Unavailable(503, "no registry file"))
        cache.refresh()
        assertEquals(ListReach.NoRegistry, cache.reach)
        val empty = ListCopy.empty(cache.reach, held = false)
        assertEquals(R.string.m8_list_no_registry_title, empty?.title)
        assertEquals(R.string.m8_list_registry_path, empty?.path)
    }

    /** Every other status is a failure to reach, and says the smaller thing. */
    @Test
    fun `other statuses are not the 503 screen`() {
        listOf(0, 401, 404, 500, 502, 504).forEach { status ->
            val cache = cache(RegistryFetch.Unavailable(status, "whatever the far end said"))
            cache.refresh()
            assertEquals("status $status", ListReach.Unreachable, cache.reach)
        }
    }

    @Test
    fun `a refresh that fails over a held list is stale and keeps the list`() {
        val source = Source(RegistryFetch.Loaded(Registry.parse(json)))
        val cache = ProjectCache(source, clock = { 0L })
        cache.refresh()
        source.answer = RegistryFetch.Unavailable(0, "no route to host")
        cache.refresh()
        assertEquals(ListReach.Stale, cache.reach)
        assertEquals(listOf(7), cache.list().map { it.number })
        assertNull("there is a list, so there is no empty screen", ListCopy.empty(cache.reach, true))
        assertEquals(R.string.m8_list_stale_note, ListCopy.staleNote(cache.reach, held = true))
    }

    /**
     * A 503 arriving over a list Maia already holds is its own state.
     *
     * The list is still drawn, so this is not the no-registry screen. What
     * changes is the note: the ordinary stale note implies a refresh will fix
     * it, and here it will not, because the far end answered and will keep
     * answering 503 until the script is run on the devbox. The path is drawn
     * under this note and under no other.
     */
    @Test
    fun `a 503 over a held list keeps the list and says a refresh will not help`() {
        val source = Source(RegistryFetch.Loaded(Registry.parse(json)))
        val cache = ProjectCache(source, clock = { 0L })
        cache.refresh()
        source.answer = RegistryFetch.Unavailable(503, "no registry file")
        cache.refresh()
        assertEquals(ListReach.StaleNoRegistry, cache.reach)
        assertEquals(listOf(7), cache.list().map { it.number })
        assertNull("there is a list, so there is no empty screen", ListCopy.empty(cache.reach, true))
        assertEquals(
            R.string.m8_list_stale_no_registry_note,
            ListCopy.staleNote(cache.reach, held = true),
        )
        assertEquals(
            R.string.m8_list_registry_path,
            ListCopy.stalePath(cache.reach, held = true),
        )
    }

    /** The path belongs to the 503 note alone. An ordinary stale list has no path. */
    @Test
    fun `the registry path is drawn under the 503 note and no other`() {
        assertNull(ListCopy.stalePath(ListReach.Stale, held = true))
        assertNull(ListCopy.stalePath(ListReach.Fresh, held = true))
        assertNull(ListCopy.stalePath(ListReach.StaleNoRegistry, held = false))
    }

    /**
     * Section 5.16's last note. The microphone stays open on all of these and
     * the orb is `Listening`, except during the first fetch: Maia is fetching
     * and there is nothing the user could usefully say yet.
     */
    @Test
    fun `the orb thinks only during the first fetch`() {
        assertTrue(ListCopy.thinking(ListReach.Fetching, held = false))
        listOf(
            ListReach.Unreachable,
            ListReach.NoRegistry,
            ListReach.Stale,
            ListReach.StaleNoRegistry,
            ListReach.Fresh,
        )
            .forEach { assertFalse("$it", ListCopy.thinking(it, held = false)) }
        assertFalse("a held list is drawn, not waited for", ListCopy.thinking(ListReach.Fetching, true))
    }

    /**
     * Nothing the devbox said can reach a screen. `RegistryFetch.Unavailable`
     * carries a `detail` string written by the far end, and the road from it
     * to the user goes through [ListReach], which is an enum, and [ListCopy],
     * which returns resource ids. Pinned so that widening either to carry text
     * fails here.
     */
    @Test
    fun `no devbox text can reach the list screen`() {
        val offenders = ListCopy.Empty::class.java.declaredFields
            .filterNot { it.isSynthetic || it.name == "\$stable" }
            .filter { it.type != Int::class.java && it.type != Integer::class.java }
            .map { "${it.name}: ${it.type}" }
        assertEquals(emptyList<String>(), offenders)
    }
}
