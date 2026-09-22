package dev.maia.audio.speech

import android.media.AudioManager
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Criterion 10.2.20 on the JVM side: silent and vibrate produce no speech. */
class SpeakingPolicyTest {

    @Test
    fun `only a normal ringer speaks`() {
        assertTrue(SpeakingPolicy.shouldSpeak(AudioManager.RINGER_MODE_NORMAL))
        assertFalse(SpeakingPolicy.shouldSpeak(AudioManager.RINGER_MODE_VIBRATE))
        assertFalse(SpeakingPolicy.shouldSpeak(AudioManager.RINGER_MODE_SILENT))
    }

    @Test
    fun `a mode nobody has heard of is silence`() {
        assertFalse(SpeakingPolicy.shouldSpeak(99))
        assertFalse(SpeakingPolicy.shouldSpeak(-1))
    }

    @Test
    fun `speech turned off wins over a normal ringer`() {
        assertFalse(SpeakingPolicy.shouldSpeak(AudioManager.RINGER_MODE_NORMAL, speechEnabled = false))
    }

    @Test
    fun `on a silent ringer the voice is never even asked`() = runTest {
        var asked = 0
        val voice = Speaker { asked++; emptyFlow() }
        val speaker = RingerAwareSpeaker(voice, ringerMode = { AudioManager.RINGER_MODE_SILENT })
        assertEquals(emptyList<Float>(), speaker.speak("Saved, lunch, today at one.").toList())
        assertEquals(0, asked)
    }

    @Test
    fun `on a normal ringer the envelope passes through untouched`() = runTest {
        val voice = ScriptedSpeaker(envelope = listOf(0.2f, 0.9f, 0f))
        val speaker = RingerAwareSpeaker(voice, ringerMode = { AudioManager.RINGER_MODE_NORMAL })
        assertEquals(listOf(0.2f, 0.9f, 0f), speaker.speak("Saved.").toList())
        assertEquals(listOf("Saved."), voice.spoken)
    }

    @Test
    fun `the ringer is read when speaking starts, not when the flow is built`() = runTest {
        var mode = AudioManager.RINGER_MODE_NORMAL
        val voice = ScriptedSpeaker()
        val flow = RingerAwareSpeaker(voice, ringerMode = { mode }).speak("Saved.")
        // The switch flipped between the hold and the confirmation.
        mode = AudioManager.RINGER_MODE_VIBRATE
        assertEquals(emptyList<Float>(), flow.toList())
        assertEquals(emptyList<String>(), voice.spoken)
    }

    @Test
    fun `the silent speaker completes at once with nothing`() = runTest {
        assertEquals(emptyList<Float>(), SilentSpeaker.speak("Saved.").toList())
    }

    @Test
    fun `the scripted speaker records only what was collected`() = runTest {
        val voice = ScriptedSpeaker(envelope = listOf(0.5f))
        val never = voice.speak("never collected")
        assertEquals(emptyList<String>(), voice.spoken)
        assertEquals(listOf(0.5f), voice.speak("Saved.").toList())
        assertEquals(listOf("Saved."), voice.spoken)
        check(never !== voice.speak("x"))
    }
}
