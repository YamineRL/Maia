package dev.maia.audio.speech

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The mid-process activation the class exists for: a voice downloaded while
 * the wiring is already live speaks on the next sentence, a phone without
 * the voice stays silent, and a build is attempted at most once.
 */
class DeferredSpeakerTest {

    @Test
    fun `a phone without the voice is silent and never builds`() = runTest {
        var builds = 0
        val speaker = DeferredSpeaker(
            ready = { false },
            build = { builds++; ScriptedSpeaker() },
        )

        assertEquals(emptyList<Float>(), speaker.speak("hello").toList())
        assertEquals(0, builds)
    }

    @Test
    fun `a voice that lands mid-process speaks on the next sentence`() = runTest {
        var ready = false
        val voice = ScriptedSpeaker(envelope = listOf(0.5f), frameMillis = 0)
        val speaker = DeferredSpeaker(ready = { ready }, build = { voice })

        speaker.speak("first").toList()
        assertEquals("the fallback said nothing", emptyList<String>(), voice.spoken)

        ready = true
        speaker.speak("second").toList()
        assertEquals(listOf("second"), voice.spoken)
    }

    @Test
    fun `the voice is built once and kept`() = runTest {
        var builds = 0
        val voice = ScriptedSpeaker(envelope = listOf(0.5f), frameMillis = 0)
        val speaker = DeferredSpeaker(ready = { true }, build = { builds++; voice })

        speaker.speak("one").toList()
        speaker.speak("two").toList()

        assertEquals(1, builds)
        assertEquals(listOf("one", "two"), voice.spoken)
    }

    @Test
    fun `a voice that will not open falls back and is not retried`() = runTest {
        var builds = 0
        val speaker = DeferredSpeaker(
            ready = { true },
            build = { builds++; null },
            fallback = SilentSpeaker,
        )

        speaker.speak("one").toList()
        speaker.speak("two").toList()

        assertEquals("a failed build latches to the fallback", 1, builds)
    }

    @Test
    fun `an incomplete voice does not latch the fallback`() = runTest {
        var ready = false
        val voice = ScriptedSpeaker(envelope = listOf(0.5f), frameMillis = 0)
        val speaker = DeferredSpeaker(ready = { ready }, build = { voice })

        // Two silent sentences before the download finishes: if the first
        // had latched the fallback, the second readiness would never be seen.
        speaker.speak("a").toList()
        speaker.speak("b").toList()
        ready = true
        speaker.speak("c").toList()

        assertEquals(listOf("c"), voice.spoken)
    }
}
