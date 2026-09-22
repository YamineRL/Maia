package dev.maia.audio.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechEnvelopeTest {

    @Test
    fun `a window is fifty milliseconds at the voice's own rate`() {
        assertEquals(1102, SpeechEnvelope.windowSize(22050))
        assertEquals(800, SpeechEnvelope.windowSize(16000))
        assertEquals(1, SpeechEnvelope.windowSize(10))
    }

    @Test
    fun `a chunk is cut into windows and the short tail is kept`() {
        assertEquals(
            listOf(
                SpeechEnvelope.Window(0, 1100),
                SpeechEnvelope.Window(1100, 1100),
                SpeechEnvelope.Window(2200, 300),
            ),
            SpeechEnvelope.windows(2500, 1100),
        )
        assertEquals(listOf(SpeechEnvelope.Window(0, 500)), SpeechEnvelope.windows(500, 1100))
        assertEquals(emptyList<SpeechEnvelope.Window>(), SpeechEnvelope.windows(0, 1100))
    }

    @Test
    fun `the windows cover every sample exactly once`() {
        val windows = SpeechEnvelope.windows(12345, 1102)
        assertEquals(12345, windows.sumOf { it.length })
        windows.zipWithNext().forEach { (a, b) -> assertEquals(a.offset + a.length, b.offset) }
    }

    @Test
    fun `rms measures only the window it is given`() {
        val chunk = FloatArray(200) { if (it < 100) 0f else if (it % 2 == 0) 0.5f else -0.5f }
        assertEquals(0f, SpeechEnvelope.rms(chunk, 0, 100), 0f)
        assertEquals(0.5f, SpeechEnvelope.rms(chunk, 100, 100), 1e-6f)
        assertEquals(0f, SpeechEnvelope.rms(chunk, 0, 0), 0f)
    }

    @Test
    fun `level maps decibels onto 0 to 1 and is monotonic`() {
        assertEquals(0f, SpeechEnvelope.level(0f), 0f)
        // -50 dBFS is the floor, -10 the ceiling, -30 half way.
        assertEquals(0f, SpeechEnvelope.level(0.00316f), 1e-3f)
        assertEquals(0.5f, SpeechEnvelope.level(0.0316f), 1e-3f)
        assertEquals(1f, SpeechEnvelope.level(0.316f), 1e-3f)
        assertEquals(1f, SpeechEnvelope.level(1f), 0f)
        val steps = listOf(0.001f, 0.005f, 0.02f, 0.1f, 0.3f).map(SpeechEnvelope::level)
        assertEquals(steps.sorted(), steps)
    }

    @Test
    fun `a NaN from the model draws as silence`() {
        assertEquals(0f, SpeechEnvelope.level(Float.NaN), 0f)
        assertEquals(0f, SpeechEnvelope.level(-1f), 0f)
    }

    @Test
    fun `a chunk with a word and a gap rises and falls`() {
        val size = SpeechEnvelope.windowSize(22050)
        val loud = FloatArray(size * 2) { if (it % 2 == 0) 0.2f else -0.2f }
        val quiet = FloatArray(size)
        val envelope = SpeechEnvelope.envelope(quiet + loud + quiet, size)
        assertEquals(4, envelope.size)
        assertEquals(0f, envelope.first(), 0f)
        assertTrue(envelope[1] > 0.8f && envelope[2] > 0.8f)
        assertEquals(0f, envelope.last(), 0f)
        assertTrue(envelope.all { it in 0f..1f })
    }
}
