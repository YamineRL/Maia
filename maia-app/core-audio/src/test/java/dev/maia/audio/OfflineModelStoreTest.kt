package dev.maia.audio

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * The download contract for the second-pass model, mirroring [ModelStoreTest]
 * on the same [FakeOrigin] loopback origin.
 *
 * The same proofs, because it is the same engine: a truncated model never
 * gets its real name, a refusal leaves nothing behind, and a dropped
 * connection resumes with a `Range` header. The engine was copied rather
 * than shared so that a regression here cannot take the shipping zipformer
 * path with it; see `OfflineModelStore` for why that trade was taken.
 */
class OfflineModelStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var origin: FakeOrigin

    @Before
    fun startOrigin() {
        origin = FakeOrigin()
        OfflineModelStore.FILES.forEachIndexed { index, name ->
            origin.bodies[name] = ByteArray(512 + index) { (index * 31 + it).toByte() }
        }
    }

    @After
    fun stopOrigin() {
        origin.close()
    }

    private fun store() = OfflineModelStore(temp.root, origin.url)

    private fun file(name: String) = File(temp.root, name)

    private fun part(name: String) = File(temp.root, "$name.part")

    @Test
    fun `is incomplete until every file is present`() {
        val store = store()
        assertFalse(store.isComplete)

        val all = OfflineModelStore.FILES
        all.dropLast(1).forEach { file(it).writeBytes(byteArrayOf(1)) }
        assertFalse("three of four files is not complete", store.isComplete)

        file(all.last()).writeBytes(byteArrayOf(1))
        assertTrue(store.isComplete)
    }

    @Test
    fun `paths point at the four files under the root`() {
        val paths = store().paths()
        listOf(paths.encoder, paths.decoder, paths.joiner, paths.tokens)
            .forEach { path ->
                assertEquals(temp.root, File(path).parentFile)
                assertTrue("$path is not one of the model files", File(path).name in OfflineModelStore.FILES)
            }
    }

    @Test
    fun `a custom file list fetches only those files`() = runTest {
        val only = "single.bin"
        origin.bodies[only] = ByteArray(700) { it.toByte() }
        val store = OfflineModelStore(temp.root, origin.url, files = listOf(only))
        assertFalse(store.isComplete)

        store.ensure().toList()

        assertTrue(store.isComplete)
        assertTrue(file(only).readBytes().contentEquals(origin.bodies.getValue(only)))
        OfflineModelStore.FILES.forEach { assertFalse("$it was fetched", file(it).exists()) }
    }

    @Test
    fun `a complete download lands with the exact bytes and leaves no part file`() = runTest {
        val progress = store().ensure().toList()

        OfflineModelStore.FILES.forEach { name ->
            assertArrayEqualsNamed(name, origin.bodies.getValue(name), file(name).readBytes())
            assertFalse("$name.part survived", part(name).exists())
        }
        assertEquals(OfflineModelStore.Progress.Ready, progress.last())
        assertTrue(store().isComplete)
    }

    @Test
    fun `a truncated response throws and never gives the file its real name`() = runTest {
        val victim = OfflineModelStore.FILES.first()
        origin.truncate += victim

        val thrown = runCatching { store().ensure().toList() }.exceptionOrNull()

        assertTrue("expected an IOException, got $thrown", thrown is IOException)
        assertFalse("a truncated file kept its real name", file(victim).exists())
        val body = origin.bodies.getValue(victim)
        val kept = part(victim).readBytes()
        assertEquals("the part is not the delivered half", body.size / 2, kept.size)
        assertTrue("the part holds bytes that are not the file's head", body.copyOf(kept.size).contentEquals(kept))
    }

    @Test
    fun `a refused file throws and leaves nothing behind`() = runTest {
        val victim = OfflineModelStore.FILES.first()
        origin.refuse += victim

        val thrown = runCatching { store().ensure().toList() }.exceptionOrNull()

        assertTrue("expected an IOException, got $thrown", thrown is IOException)
        assertFalse(file(victim).exists())
        assertFalse(part(victim).exists())
    }

    @Test
    fun `files already on disk cost no request`() = runTest {
        store().ensure().toList()
        val afterFirst = origin.requests
        assertEquals(OfflineModelStore.FILES.size, afterFirst)

        // Second launch. Every file is present, so nothing should be fetched.
        store().ensure().toList()
        assertEquals("a second launch re-downloaded something", afterFirst, origin.requests)
    }

    @Test
    fun `only the missing file is fetched`() = runTest {
        store().ensure().toList()
        origin.resetRequests()

        val victim = OfflineModelStore.FILES[2]
        assertTrue(file(victim).delete())
        val progress = store().ensure().toList()

        assertEquals(1, origin.requests)
        val downloading = progress.filterIsInstance<OfflineModelStore.Progress.Downloading>()
        assertTrue(downloading.isNotEmpty())
        assertTrue("progress named the wrong file", downloading.all { it.file == victim })
        assertTrue("count should be the missing set", downloading.all { it.count == 1 })
    }

    @Test
    fun `a dropped connection resumes with a Range header and lands the exact bytes`() = runTest {
        val victim = OfflineModelStore.FILES.first()
        origin.cutOnce[victim] = 200

        assertTrue(runCatching { store().ensure().toList() }.exceptionOrNull() is IOException)
        assertEquals(200L, part(victim).length())

        val progress = store().ensure().toList()
        assertEquals(listOf("bytes=200-"), origin.ranges.toList())
        assertArrayEqualsNamed(victim, origin.bodies.getValue(victim), file(victim).readBytes())
        assertFalse(part(victim).exists())
    }

    @Test
    fun `abandoning the download leaves no half written model`() = runTest {
        store().ensure().take(1).toList()

        OfflineModelStore.FILES.forEach { name ->
            assertFalse("$name was left behind", file(name).exists())
            assertFalse("$name.part was left behind", part(name).exists())
        }
    }

    @Test
    fun `a server that ignores the range restarts the file instead of appending a second head`() = runTest {
        val victim = OfflineModelStore.FILES.first()
        origin.cutOnce[victim] = 100
        runCatching { store().ensure().toList() }
        origin.ignoreRange += victim

        store().ensure().toList()

        assertArrayEqualsNamed(victim, origin.bodies.getValue(victim), file(victim).readBytes())
    }

    @Test
    fun `progress fractions stay in range and Ready is last`() = runTest {
        val progress = store().ensure().toList()

        val downloading = progress.filterIsInstance<OfflineModelStore.Progress.Downloading>()
        assertTrue(downloading.isNotEmpty())
        assertTrue(
            "a fraction escaped 0 to 1",
            downloading.all { it.fraction >= 0f && it.fraction <= 1f },
        )
        assertEquals(OfflineModelStore.Progress.Ready, progress.last())
        assertEquals(
            "Ready was emitted more than once",
            1,
            progress.count { it is OfflineModelStore.Progress.Ready },
        )
    }

    private fun assertArrayEqualsNamed(name: String, expected: ByteArray, actual: ByteArray) {
        assertEquals("$name has the wrong length", expected.size, actual.size)
        assertTrue("$name has the wrong contents", expected.contentEquals(actual))
    }
}
