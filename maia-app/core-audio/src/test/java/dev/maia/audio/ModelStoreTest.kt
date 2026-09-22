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
 * The download contract, and in particular that a truncated model never gets
 * its real name.
 *
 * That is the one failure here worth proving. A half written encoder that
 * looks complete is rejected deep inside ONNX Runtime with a message that says
 * nothing about truncation, so the app reads as broken rather than as
 * incomplete, and the fix is to delete a file nobody can see.
 *
 * The origin is [FakeOrigin], a loopback socket. No test dependency is added
 * and nothing here reaches the network.
 */
class ModelStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var origin: FakeOrigin

    @Before
    fun startOrigin() {
        origin = FakeOrigin()
        ModelStore.FILES.forEachIndexed { index, name ->
            // Distinct, non-trivial contents so a mixed-up file is visible.
            origin.bodies[name] = ByteArray(512 + index) { (index * 31 + it).toByte() }
        }
    }

    @After
    fun stopOrigin() {
        origin.close()
    }

    private fun store() = ModelStore(temp.root, origin.url)

    private fun file(name: String) = File(temp.root, name)

    private fun part(name: String) = File(temp.root, "$name.part")

    @Test
    fun `is incomplete until every file is present`() {
        val store = store()
        assertFalse(store.isComplete)

        val all = ModelStore.FILES
        all.dropLast(1).forEach { file(it).writeBytes(byteArrayOf(1)) }
        assertFalse("four of five files is not complete", store.isComplete)

        file(all.last()).writeBytes(byteArrayOf(1))
        assertTrue(store.isComplete)
    }

    @Test
    fun `paths point at the five files under the root`() {
        val paths = store().paths()
        listOf(paths.encoder, paths.decoder, paths.joiner, paths.tokens, paths.bpeVocab)
            .forEach { path ->
                assertEquals(temp.root, File(path).parentFile)
                assertTrue("$path is not one of the model files", File(path).name in ModelStore.FILES)
            }
    }

    @Test
    fun `a complete download lands with the exact bytes and leaves no part file`() = runTest {
        val progress = store().ensure().toList()

        ModelStore.FILES.forEach { name ->
            assertArrayEqualsNamed(name, origin.bodies.getValue(name), file(name).readBytes())
            assertFalse("$name.part survived", part(name).exists())
        }
        assertEquals(ModelStore.Progress.Ready, progress.last())
        assertTrue(store().isComplete)
    }

    @Test
    fun `a truncated response throws and never gives the file its real name`() = runTest {
        val victim = ModelStore.FILES.first()
        origin.truncate += victim

        val thrown = runCatching { store().ensure().toList() }.exceptionOrNull()

        assertTrue("expected an IOException, got $thrown", thrown is IOException)
        assertFalse("a truncated file kept its real name", file(victim).exists())
        // M2 brief section 7: the part is kept for resume, and it must hold
        // only bytes that are really the head of the file.
        val body = origin.bodies.getValue(victim)
        val kept = part(victim).readBytes()
        assertEquals("the part is not the delivered half", body.size / 2, kept.size)
        assertTrue("the part holds bytes that are not the file's head", body.copyOf(kept.size).contentEquals(kept))
    }

    @Test
    fun `a refused file throws and leaves nothing behind`() = runTest {
        val victim = ModelStore.FILES.first()
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
        assertEquals(ModelStore.FILES.size, afterFirst)

        // Second launch. Every file is present, so nothing should be fetched.
        store().ensure().toList()
        assertEquals("a second launch re-downloaded something", afterFirst, origin.requests)
    }

    @Test
    fun `only the missing file is fetched`() = runTest {
        store().ensure().toList()
        origin.resetRequests()

        val victim = ModelStore.FILES[2]
        assertTrue(file(victim).delete())
        val progress = store().ensure().toList()

        assertEquals(1, origin.requests)
        val downloading = progress.filterIsInstance<ModelStore.Progress.Downloading>()
        assertTrue(downloading.isNotEmpty())
        assertTrue("progress named the wrong file", downloading.all { it.file == victim })
        assertTrue("count should be the missing set", downloading.all { it.count == 1 })
    }

    @Test
    fun `progress fractions stay in range and Ready is last`() = runTest {
        val progress = store().ensure().toList()

        val downloading = progress.filterIsInstance<ModelStore.Progress.Downloading>()
        assertTrue(downloading.isNotEmpty())
        assertTrue(
            "a fraction escaped 0 to 1",
            downloading.all { it.fraction >= 0f && it.fraction <= 1f },
        )
        assertTrue(
            "index outside 1..count",
            downloading.all { it.index in 1..it.count },
        )
        assertEquals(ModelStore.Progress.Ready, progress.last())
        assertEquals(
            "Ready was emitted more than once",
            1,
            progress.count { it is ModelStore.Progress.Ready },
        )
    }

    @Test
    fun `abandoning the download leaves no half written model`() = runTest {
        // take(1) cancels the flow at the first emission, which is the moment
        // before the first file starts arriving.
        store().ensure().take(1).toList()

        ModelStore.FILES.forEach { name ->
            assertFalse("$name was left behind", file(name).exists())
            assertFalse("$name.part was left behind", part(name).exists())
        }
    }

    @Test
    fun `a dropped connection resumes with a Range header and lands the exact bytes`() = runTest {
        val victim = ModelStore.FILES.first()
        origin.cutOnce[victim] = 200

        assertTrue(runCatching { store().ensure().toList() }.exceptionOrNull() is IOException)
        assertEquals(200L, part(victim).length())

        val progress = store().ensure().toList()
        assertEquals(listOf("bytes=200-"), origin.ranges.toList())
        assertArrayEqualsNamed(victim, origin.bodies.getValue(victim), file(victim).readBytes())
        assertFalse(part(victim).exists())
        val first = progress.filterIsInstance<ModelStore.Progress.Downloading>()
            .first { it.file == victim && it.totalBytes > 0 }
        assertEquals("resumed progress must start from the kept bytes", 200L, first.bytes)
    }

    @Test
    fun `a cut at any byte still ends with the exact file`() = runTest {
        val victim = ModelStore.FILES.first()
        val size = origin.bodies.getValue(victim).size
        for (cut in listOf(1, 7, 64, size / 2, size - 1)) {
            file(victim).delete()
            part(victim).delete()
            origin.ranges.clear()
            origin.cutOnce[victim] = cut
            runCatching { store().ensure().toList() }
            store().ensure().toList()
            assertArrayEqualsNamed("$victim cut at $cut", origin.bodies.getValue(victim), file(victim).readBytes())
            assertEquals("cut at $cut", listOf("bytes=$cut-"), origin.ranges.toList())
        }
    }

    @Test
    fun `a server that ignores the range restarts the file instead of appending a second head`() = runTest {
        val victim = ModelStore.FILES.first()
        origin.cutOnce[victim] = 100
        runCatching { store().ensure().toList() }
        origin.ignoreRange += victim

        store().ensure().toList()

        assertArrayEqualsNamed(victim, origin.bodies.getValue(victim), file(victim).readBytes())
    }

    @Test
    fun `a part longer than the file is thrown away and the file fetched whole`() = runTest {
        val victim = ModelStore.FILES.first()
        val size = origin.bodies.getValue(victim).size
        part(victim).writeBytes(ByteArray(size + 10) { 9 })

        store().ensure().toList()

        assertArrayEqualsNamed(victim, origin.bodies.getValue(victim), file(victim).readBytes())
        assertEquals(listOf("bytes=${size + 10}-"), origin.ranges.toList())
    }

    @Test
    fun `a refusal on resume deletes the part rather than keeping it`() = runTest {
        val victim = ModelStore.FILES.first()
        origin.cutOnce[victim] = 50
        runCatching { store().ensure().toList() }
        assertTrue(part(victim).exists())
        origin.refuse += victim

        assertTrue(runCatching { store().ensure().toList() }.exceptionOrNull() is IOException)
        assertFalse(part(victim).exists())
        assertFalse(file(victim).exists())
    }

    @Test
    fun `progress bytes never go backwards within a file and end at its length`() = runTest {
        val progress = store().ensure().toList().filterIsInstance<ModelStore.Progress.Downloading>()
        ModelStore.FILES.forEach { name ->
            val bytes = progress.filter { it.file == name }.map { it.bytes }
            assertEquals(bytes.sorted(), bytes)
            assertEquals(origin.bodies.getValue(name).size.toLong(), bytes.last())
            assertEquals(origin.bodies.getValue(name).size.toLong(), progress.last { it.file == name }.totalBytes)
        }
    }

    private fun assertArrayEqualsNamed(name: String, expected: ByteArray, actual: ByteArray) {
        assertEquals("$name has the wrong length", expected.size, actual.size)
        assertTrue("$name has the wrong contents", expected.contentEquals(actual))
    }
}
