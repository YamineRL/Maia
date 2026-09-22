package dev.maia.audio.speech

import android.media.AudioManager
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The M9 boundary in miniature: whatever reaches the inner speaker through an
 * [AnswerSpeaker] is answer text, cut into speakable pieces. The ringer rule
 * composes around it unchanged.
 */
class AnswerSpeakerTest {

    @Test
    fun `a short answer reaches the voice as one piece, verbatim`() = runTest {
        val voice = ScriptedSpeaker(envelope = listOf(0.5f))
        val speaker = AnswerSpeaker(voice)

        assertEquals(listOf(0.5f), speaker.speak("Timer set for twelve minutes.").toList())
        assertEquals(listOf("Timer set for twelve minutes."), voice.spoken)
    }

    @Test
    fun `a long answer is spoken in pieces within the chunk limit`() = runTest {
        val voice = ScriptedSpeaker(envelope = listOf(0.5f), frameMillis = 0)
        val speaker = AnswerSpeaker(voice)
        val answer = "Leaves change colour as daylight shortens. " +
            "Chlorophyll, which makes them green, breaks down first in autumn. " +
            "The yellows and oranges that remain were in the leaf all along, " +
            "hidden behind the green until now."

        speaker.speak(answer).toList()

        assertTrue("the answer reached the voice as one piece", voice.spoken.size > 1)
        assertTrue(
            "a piece escaped the limit: ${voice.spoken}",
            voice.spoken.all { it.length <= SpeechChunks.MAX_CHARS },
        )
        assertEquals(
            answer.filterNot { it.isWhitespace() },
            voice.spoken.joinToString("").filterNot { it.isWhitespace() },
        )
    }

    @Test
    fun `pieces are spoken in order`() = runTest {
        val voice = ScriptedSpeaker(envelope = listOf(0.5f), frameMillis = 0)
        val speaker = AnswerSpeaker(voice, maxChunkChars = 30)
        speaker.speak("One two three four five six. Seven eight nine ten.").toList()
        assertEquals(
            listOf("One two three four five six.", "Seven eight nine ten."),
            voice.spoken,
        )
    }

    @Test
    fun `a blank answer is never asked of the voice`() = runTest {
        val voice = ScriptedSpeaker(envelope = listOf(0.5f))
        assertEquals(emptyList<Float>(), AnswerSpeaker(voice).speak("   ").toList())
        assertEquals(emptyList<String>(), voice.spoken)
    }

    @Test
    fun `the ringer rule still applies around it`() = runTest {
        val voice = ScriptedSpeaker(envelope = listOf(0.5f))
        val speaker = RingerAwareSpeaker(
            AnswerSpeaker(voice),
            ringerMode = { AudioManager.RINGER_MODE_VIBRATE },
        )
        assertEquals(emptyList<Float>(), speaker.speak("Leaves change colour in autumn.").toList())
        assertEquals(emptyList<String>(), voice.spoken)
    }
}
