package dev.maia.audio

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The timing arithmetic, which is the part of the pipeline that can be wrong
 * without anything looking wrong.
 *
 * Nothing here loads sherpa. [Dictation] takes an [Utterance] factory and a
 * clock, so a test supplies a scripted recogniser and a clock that advances by
 * exactly one frame per frame. Every number below is therefore exact rather
 * than approximate, and these tests do not get flaky on a loaded machine.
 */
class DictationTest {

    /**
     * A clock that ticks [AudioCapture.FRAME_MS] per call pair. [Dictation]
     * reads it twice per frame, once before the decode and once after, so a
     * test that wants "one frame later" advances on the first read only.
     */
    private class FakeClock(var millis: Long = 1_000L) {
        private var reads = 0

        fun now(): Long {
            // Even reads are the frame timestamp, odd reads are the post-decode
            // timestamp. Keeping them equal makes the arithmetic readable: a
            // partial seen on frame N is timestamped at frame N's time.
            val value = millis
            if (reads % 2 == 1) millis += AudioCapture.FRAME_MS
            reads++
            return value
        }
    }

    /** Plays back a fixed script, one entry per frame. */
    private class ScriptedUtterance(private val script: List<Transcript?>) : Utterance {
        var closes = 0
            private set

        private var index = 0

        override fun accept(frame: FloatArray): Transcript? =
            script.getOrNull(index++)

        override fun close() {
            closes++
        }
    }

    private fun silence(): FloatArray = FloatArray(AudioCapture.FRAME_SAMPLES)

    private fun speech(level: Float = 0.05f): FloatArray =
        FloatArray(AudioCapture.FRAME_SAMPLES) { if (it % 2 == 0) level else -level }

    private fun partial(text: String) = Transcript.Partial(text)

    private fun final(text: String) =
        Transcript.Final(text = text, utteranceMs = 0, silenceToFinalMs = 0)

    @Test
    fun `first partial is measured from speech onset, not from the stream opening`() = runTest {
        // Three frames of silence before anyone says anything. Timing from the
        // first frame would report 400 ms; the criterion asks for 100.
        val frames = listOf(silence(), silence(), silence(), speech(), speech())
        val script = listOf<Transcript?>(null, null, null, null, partial("hello"))
        val clock = FakeClock()

        val events = Dictation(frames.asFlow(), { ScriptedUtterance(script) }, clock::now)
            .run()
            .toList()

        assertEquals(1, events.size)
        assertEquals(AudioCapture.FRAME_MS.toLong(), events[0].firstPartialMs)
    }

    @Test
    fun `a speaker too quiet for the RMS gate still gets timed from the first text`() = runTest {
        // Every frame is below ONSET_RMS, so text is the only evidence of
        // speech there is. The fallback must not leave the measurement unset.
        val whisperLevel = Dictation.ONSET_RMS / 10f
        val frames = listOf(speech(whisperLevel), speech(whisperLevel))
        val script = listOf<Transcript?>(null, partial("barely"))
        val clock = FakeClock()

        val events = Dictation(frames.asFlow(), { ScriptedUtterance(script) }, clock::now)
            .run()
            .toList()

        assertEquals(1, events.size)
        assertEquals(0L, events[0].firstPartialMs)
    }

    @Test
    fun `first partial is fixed at the first one and not rewritten by later partials`() = runTest {
        val frames = List(4) { speech() }
        val script = listOf<Transcript?>(
            partial("one"),
            partial("one two"),
            partial("one two three"),
            partial("one two three four"),
        )
        val clock = FakeClock()

        val events = Dictation(frames.asFlow(), { ScriptedUtterance(script) }, clock::now)
            .run()
            .toList()

        assertEquals(4, events.size)
        assertTrue(events.all { it.firstPartialMs == 0L })
    }

    @Test
    fun `a final with no partial before it reports no first partial`() = runTest {
        val frames = listOf(speech(), speech())
        val script = listOf<Transcript?>(null, final("straight to the end"))
        val clock = FakeClock()

        val events = Dictation(frames.asFlow(), { ScriptedUtterance(script) }, clock::now)
            .run()
            .toList()

        assertEquals(1, events.size)
        assertNull(events[0].firstPartialMs)
    }

    @Test
    fun `silence to final runs from the last growth in the text`() = runTest {
        // Text stops growing on frame 2, the endpointer fires on frame 5.
        val frames = List(6) { speech() }
        val script = listOf<Transcript?>(
            partial("dentist"),
            partial("dentist tuesday"),
            partial("dentist tuesday"),
            partial("dentist tuesday"),
            null,
            final("dentist tuesday"),
        )
        val clock = FakeClock()

        val events = Dictation(frames.asFlow(), { ScriptedUtterance(script) }, clock::now)
            .run()
            .toList()

        val last = events.last().transcript as Transcript.Final
        // Growth stopped on frame 1. Frames 2, 3, 4 and 5 elapsed after that.
        assertEquals(4L * AudioCapture.FRAME_MS, last.silenceToFinalMs)
    }

    @Test
    fun `silence to final is zero when the text never grew`() = runTest {
        val frames = listOf(speech(), speech())
        val script = listOf<Transcript?>(null, final("appeared at once"))
        val clock = FakeClock()

        val events = Dictation(frames.asFlow(), { ScriptedUtterance(script) }, clock::now)
            .run()
            .toList()

        assertEquals(0L, (events.last().transcript as Transcript.Final).silenceToFinalMs)
    }

    @Test
    fun `the second utterance is measured from its own onset, not the session`() = runTest {
        // The reset after a final is the thing most likely to be quietly wrong:
        // if any utterance-scoped counter survives, the second utterance
        // inherits the first one's clock and every number after it is nonsense.
        val frames = listOf(
            speech(), speech(),          // utterance one, partial then final
            silence(), silence(),        // a gap with nobody talking
            speech(), speech(),          // utterance two
        )
        val script = listOf<Transcript?>(
            partial("first"),
            final("first"),
            null,
            null,
            null,
            partial("second"),
        )
        val clock = FakeClock()

        val events = Dictation(frames.asFlow(), { ScriptedUtterance(script) }, clock::now)
            .run()
            .toList()

        assertEquals(3, events.size)
        val secondUtterance = events.last()
        assertEquals("second", secondUtterance.transcript.text)
        // Onset is frame 4 and the partial lands on frame 5, so one frame. If
        // speechStart had survived the final this would read 500 ms instead.
        assertEquals(AudioCapture.FRAME_MS.toLong(), secondUtterance.firstPartialMs)
    }

    @Test
    fun `utterance duration runs from the first frame, including the lead-in silence`() = runTest {
        // Deliberately a different clock from firstPartialMs. This one answers
        // "how long was the stream open for this sentence", which is what makes
        // a lead-in visible rather than hiding it.
        val frames = listOf(silence(), silence(), speech(), speech())
        val script = listOf<Transcript?>(null, null, partial("hi"), final("hi"))
        val clock = FakeClock()

        val events = Dictation(frames.asFlow(), { ScriptedUtterance(script) }, clock::now)
            .run()
            .toList()

        val last = events.last().transcript as Transcript.Final
        assertEquals(3L * AudioCapture.FRAME_MS, last.utteranceMs)
    }

    @Test
    fun `the real time factor is session scoped and survives a final`() = runTest {
        val frames = List(4) { speech() }
        val script = listOf<Transcript?>(partial("a"), final("a"), partial("b"), final("b"))
        val clock = FakeClock()

        val events = Dictation(frames.asFlow(), { ScriptedUtterance(script) }, clock::now)
            .run()
            .toList()

        assertEquals(4, events.size)
        // Real decode time is nanoseconds of doing nothing, so the value is
        // tiny, but it must be a real number and must never reset to 0.
        assertTrue(events.all { it.realTimeFactor >= 0f })
        assertTrue(events.all { it.realTimeFactor.isFinite() })
    }

    @Test
    fun `the session is closed when the flow completes`() = runTest {
        val session = ScriptedUtterance(listOf(partial("x")))
        Dictation(listOf(speech()).asFlow(), { session }, FakeClock()::now).run().toList()
        assertEquals(1, session.closes)
    }

    @Test
    fun `the session is closed when the frame source fails mid utterance`() = runTest {
        // Cancellation and failure both unwind through the same finally. If
        // this leaks, the microphone stays open and the next press finds it
        // held, which on a phone looks like the app being broken.
        val session = ScriptedUtterance(listOf(partial("x")))
        val exploding = flow<FloatArray> {
            emit(speech())
            throw IllegalStateException("microphone died")
        }

        val thrown = runCatching {
            Dictation(exploding, { session }, FakeClock()::now).run().toList()
        }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertEquals(1, session.closes)
    }
}
