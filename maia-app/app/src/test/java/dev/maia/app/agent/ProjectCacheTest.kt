package dev.maia.app.agent

import dev.maia.transport.Registry
import dev.maia.transport.RegistryFetch
import dev.maia.transport.RegistrySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry over a tunnel that is sometimes not there.
 *
 * The case worth naming is the last one. `RegistryFetch` separates "the
 * registry says there are no projects" from "the registry could not be
 * reached", and collapsing them would put a user in front of an empty list
 * screen every time their tunnel blinked, which reads as their projects
 * having been deleted.
 */
class ProjectCacheTest {

    private fun json(vararg numbers: Int): String {
        val projects = numbers.joinToString(",") {
            """{"number":$it,"name":"p$it","path":"/home/user/p$it","state":"active"}"""
        }
        return """{"schema":"maia/projects","version":1,"nextNumber":99,"projects":[$projects]}"""
    }

    private class Source(var text: String?) : RegistrySource {
        var loads = 0
        override fun load(): RegistryFetch {
            loads++
            val body = text ?: return RegistryFetch.Unavailable(0, "no route to host")
            return RegistryFetch.Loaded(Registry.parse(body))
        }
    }

    @Test
    fun `the first read fetches and later reads inside the window do not`() {
        val source = Source(json(3, 7))
        var now = 0L
        val cache = ProjectCache(source, { now }, ttlMs = 1_000)

        assertEquals(listOf(3, 7), cache.list().map { it.number })
        now = 999
        assertEquals(listOf(3, 7), cache.list().map { it.number })
        assertEquals(1, source.loads)
    }

    @Test
    fun `a project added on the devbox arrives on the next window`() {
        val source = Source(json(3))
        var now = 0L
        val cache = ProjectCache(source, { now }, ttlMs = 1_000)
        assertEquals(listOf(3), cache.list().map { it.number })

        source.text = json(3, 9)
        now = 1_000
        assertEquals(listOf(3, 9), cache.list().map { it.number })
    }

    @Test
    fun `a registry that cannot be reached is not an empty registry`() {
        val source = Source(json(3, 7))
        var now = 0L
        val cache = ProjectCache(source, { now }, ttlMs = 1_000)
        cache.list()

        source.text = null
        now = 5_000
        assertEquals(
            "a dropped tunnel emptied the registry",
            listOf(3, 7),
            cache.list().map { it.number },
        )
        assertTrue(cache.known)
    }

    @Test
    fun `nothing good has ever arrived, and it is retried rather than hammered`() {
        val source = Source(null)
        var now = 0L
        val cache = ProjectCache(source, { now }, ttlMs = 1_000)

        assertEquals(emptyList<Int>(), cache.list().map { it.number })
        assertFalse(cache.known)
        // Inside the window, no second attempt: a resolve happens on the work
        // thread between the user finishing a sentence and the send.
        assertEquals(1, source.loads)

        now = 1_000
        source.text = json(7)
        assertEquals(listOf(7), cache.list().map { it.number })
    }

    @Test
    fun `a source that throws is a source that failed`() {
        val throwing = object : RegistrySource {
            override fun load(): RegistryFetch = throw IllegalStateException("boom")
        }
        val cache = ProjectCache(throwing, { 0L })
        assertEquals(emptyList<Int>(), cache.list().map { it.number })
    }

    @Test
    fun `retired projects are kept, because a retired number is an answer`() {
        val source = Source(
            """{"schema":"maia/projects","version":1,"nextNumber":9,"projects":[
               {"number":4,"name":"oldthing","path":"/home/user/oldthing","state":"retired"}]}""",
        )
        val cache = ProjectCache(source, { 0L })
        assertEquals(listOf(4), cache.list().map { it.number })
    }
}
