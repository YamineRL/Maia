package dev.maia.audio.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VoiceModelTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `paths are the voice's files under the root`() {
        val root = folder.root
        val paths = VoiceModel.paths(root)
        assertEquals(File(root, "en_GB-alba-medium.onnx").absolutePath, paths.model)
        assertEquals(File(root, "tokens.txt").absolutePath, paths.tokens)
        assertEquals(File(root, "espeak-ng-data").absolutePath, paths.dataDir)
    }

    @Test
    fun `the voice is complete only with both files and the espeak tree`() {
        val root = folder.root
        assertFalse(VoiceModel.isComplete(root))
        VoiceModel.FILES.forEach { File(root, it).writeText("x") }
        assertFalse("the espeak-ng directory is missing", VoiceModel.isComplete(root))
        // A file where the directory should be is not a voice.
        File(root, VoiceModel.ESPEAK_DATA).writeText("x")
        assertFalse(VoiceModel.isComplete(root))
        File(root, VoiceModel.ESPEAK_DATA).delete()
        File(root, VoiceModel.ESPEAK_DATA).mkdir()
        assertTrue(VoiceModel.isComplete(root))
    }

    @Test
    fun `the proposal names an en_GB medium Piper voice`() {
        assertTrue(VoiceModel.VOICE.startsWith("en_GB-"))
        assertTrue(VoiceModel.VOICE.endsWith("-medium"))
        assertTrue(VoiceModel.SOURCE.endsWith(VoiceModel.ARCHIVE))
    }
}
