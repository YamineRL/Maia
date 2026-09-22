package dev.maia.audio.speech

import dev.maia.audio.FakeOrigin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * The voice download contract, exercised against [FakeOrigin] on loopback.
 * The engine is [dev.maia.audio.ModelStore]'s, so what this adds on top of
 * that suite is the voice's own shape: files in subdirectories under
 * `espeak-ng-data`, and the completeness rule the fails-soft speaker factory
 * relies on.
 */
class VoiceStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var origin: FakeOrigin

    @Before
    fun startOrigin() {
        origin = FakeOrigin()
        VoiceStore.FILES.forEachIndexed { index, name ->
            origin.bodies[name] = ByteArray(64 + index) { (index * 17 + it).toByte() }
        }
    }

    @After
    fun stopOrigin() {
        origin.close()
    }

    private fun store() = VoiceStore(temp.root, origin.url)

    private fun file(name: String) = File(temp.root, name)

    @Test
    fun `is incomplete until every file including the espeak tree is present`() {
        val store = store()
        assertFalse(store.isComplete)

        val all = VoiceStore.FILES
        all.dropLast(1).forEach { name ->
            File(temp.root, name).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1)) }
        }
        assertFalse("all but one file is not complete", store.isComplete)

        file(all.last()).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1)) }
        assertTrue(store.isComplete)
    }

    @Test
    fun `paths point at the model files and the espeak directory under the root`() {
        val paths = store().paths()
        assertEquals(File(temp.root, "en_GB-alan-low.onnx").absolutePath, paths.model)
        assertEquals(File(temp.root, "tokens.txt").absolutePath, paths.tokens)
        assertEquals(File(temp.root, "espeak-ng-data").absolutePath, paths.dataDir)
    }

    @Test
    fun `a complete download lands every file including nested ones`() = runTest {
        val progress = store().ensure().toList()

        VoiceStore.FILES.forEach { name ->
            val body = origin.bodies.getValue(name)
            assertEquals("$name has the wrong bytes", body.toList(), file(name).readBytes().toList())
        }
        assertEquals(VoiceStore.Progress.Ready, progress.last())
        assertTrue(store().isComplete)
    }

    @Test
    fun `a refused file throws and leaves nothing behind`() = runTest {
        val victim = "espeak-ng-data/en_dict"
        origin.refuse += victim

        val thrown = runCatching { store().ensure().toList() }.exceptionOrNull()

        assertTrue("expected an IOException, got $thrown", thrown is IOException)
        assertFalse(file(victim).exists())
        assertFalse(file("$victim.part").exists())
    }

    @Test
    fun `the factory returns null while the voice is incomplete`() {
        assertNull(SherpaSpeaker.createIfReady(temp.root))

        // Half a voice is still no voice.
        VoiceStore.FILES.take(3).forEach { name ->
            File(temp.root, name).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1)) }
        }
        assertNull(SherpaSpeaker.createIfReady(temp.root))
    }

    @Test
    fun `the factory returns null rather than throwing when the engine cannot open the files`() {
        // The files pass isComplete but are not a real voice. On this JVM
        // there is no sherpa native library to load either way, so what this
        // pins is the contract: a failure inside construction is a null, not
        // an exception reaching the wiring code.
        VoiceStore.FILES.forEach { name ->
            File(temp.root, name).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1)) }
        }
        assertNull(SherpaSpeaker.createIfReady(temp.root))
    }
}
