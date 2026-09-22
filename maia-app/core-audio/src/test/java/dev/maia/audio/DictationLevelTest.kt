package dev.maia.audio

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationLevelTest {
    @Test
    fun `loudness maps decibels onto 0 to 1`() {
        assertEquals(0f, Dictation.loudness(0f), 0f)
        assertEquals(0f, Dictation.loudness(0.0001f), 0f)
        // The onset gate, -40 dBFS, sits a little under halfway.
        assertEquals(20f / 48f, Dictation.loudness(Dictation.ONSET_RMS), 1e-4f)
        assertEquals(1f, Dictation.loudness(1f), 0f)
        val steps = listOf(0.002f, 0.01f, 0.05f, 0.2f).map(Dictation::loudness)
        assertEquals(steps.sorted(), steps)
    }

    @Test
    fun `level follows the frames while running and returns to zero after`() = runTest {
        val loud = FloatArray(160) { if (it % 2 == 0) 0.1f else -0.1f }
        val seen = mutableListOf<Float>()
        lateinit var dictation: Dictation
        val utterance = object : Utterance {
            override fun accept(frame: FloatArray): Transcript? {
                seen += dictation.level.value
                return Transcript.Partial("HELLO")
            }
            override fun close() = Unit
        }
        dictation = Dictation(
            frames = listOf(FloatArray(160), loud).asFlow(),
            open = { utterance },
            now = { 0L },
        )
        dictation.run().toList()
        assertEquals(0f, seen[0], 0f)
        assertEquals(Dictation.loudness(0.1f), seen[1], 1e-4f)
        assertTrue(seen[1] > 0.5f)
        assertEquals(0f, dictation.level.value, 0f)
    }
}
