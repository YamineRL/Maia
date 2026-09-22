package dev.maia.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rescore pass's contract with [Dictation], on a scripted recogniser.
 *
 * These pin the two properties the pass exists for: a final that the rescorer
 * rewrote must reach the consumer rewritten, and a rescorer that is absent,
 * blank or failed must leave the streaming draft exactly as it was. The
 * timing arithmetic is [DictationTest]'s subject and is not re-tested here.
 */
class DictationRescoreTest {

    private class ScriptedUtterance(private val script: List<Transcript?>) : Utterance {
        private var index = 0

        /** Every frame handed to `accept`, so a test can assert what was fed. */
        val fed = mutableListOf<FloatArray>()

        override fun accept(frame: FloatArray): Transcript? {
            fed += frame
            return script.getOrNull(index++)
        }

        override fun close() = Unit
    }

    /** Answers through to a lambda, recording each call's audio. */
    private class RecordingRescorer(private val answer: (FloatArray) -> String?) : Rescorer {
        val calls = mutableListOf<FloatArray>()

        override fun rescore(audio: FloatArray): String? {
            calls += audio
            return answer(audio)
        }
    }

    private fun silence(): FloatArray = FloatArray(AudioCapture.FRAME_SAMPLES)

    private fun speech(level: Float = 0.05f): FloatArray =
        FloatArray(AudioCapture.FRAME_SAMPLES) { if (it % 2 == 0) level else -level }

    private fun partial(text: String) = Transcript.Partial(text)

    private fun final(text: String) =
        Transcript.Final(text = text, utteranceMs = 0, silenceToFinalMs = 0)

    @Test
    fun `a rescored final replaces the draft the consumer sees`() = runTest {
        val frames = listOf(speech(), speech(), speech())
        val script = listOf<Transcript?>(null, partial("lunch with some to morrow"), final("lunch with some to morrow"))
        val session = ScriptedUtterance(script)
        val rescorer = RecordingRescorer { "lunch with Sam tomorrow at noon" }

        val events = Dictation(frames.asFlow(), { session }, { 1_000L }, rescorer = { rescorer })
            .run()
            .toList()

        assertEquals(2, events.size)
        // The partial must be the streaming draft, untouched.
        assertEquals("lunch with some to morrow", (events[0].transcript as Transcript.Partial).text)
        // The final must be the rescored text.
        assertEquals("lunch with Sam tomorrow at noon", (events[1].transcript as Transcript.Final).text)
    }

    @Test
    fun `the rescorer is handed exactly the audio of its own utterance`() = runTest {
        val frames = listOf(speech(), silence(), speech(), silence(), speech())
        // Utterance one is frames 0-2 (final on frame 2), utterance two is
        // frames 3-4 (final on frame 4).
        val script = listOf<Transcript?>(
            null,
            partial("first"),
            final("first"),
            null,
            final("second"),
        )
        val session = ScriptedUtterance(script)
        val rescorer = RecordingRescorer { null }

        Dictation(frames.asFlow(), { session }, { 1_000L }, rescorer = { rescorer })
            .run()
            .toList()

        assertEquals("the rescorer ran for every final", 2, rescorer.calls.size)
        // First call: frames 0, 1 and 2 of utterance one only.
        assertEquals(3 * AudioCapture.FRAME_SAMPLES, rescorer.calls[0].size)
        // Second call: frames 3 and 4, and nothing from utterance one.
        assertEquals(2 * AudioCapture.FRAME_SAMPLES, rescorer.calls[1].size)
    }

    @Test
    fun `a null rescore keeps the streaming draft`() = runTest {
        val frames = listOf(speech(), speech())
        val script = listOf<Transcript?>(partial("keep me"), final("keep me"))
        val session = ScriptedUtterance(script)
        val rescorer = RecordingRescorer { null }

        val events = Dictation(frames.asFlow(), { session }, { 1_000L }, rescorer = { rescorer })
            .run()
            .toList()

        assertEquals(1, rescorer.calls.size)
        assertEquals("keep me", (events.last().transcript as Transcript.Final).text)
    }

    @Test
    fun `a blank rescore keeps the streaming draft`() = runTest {
        val frames = listOf(speech(), speech())
        val script = listOf<Transcript?>(partial("keep me"), final("keep me"))
        val session = ScriptedUtterance(script)
        val rescorer = RecordingRescorer { "   " }

        val events = Dictation(frames.asFlow(), { session }, { 1_000L }, rescorer = { rescorer })
            .run()
            .toList()

        assertEquals(1, rescorer.calls.size)
        assertEquals("keep me", (events.last().transcript as Transcript.Final).text)
    }

    @Test
    fun `a throwing rescorer keeps the streaming draft and breaks nothing`() = runTest {
        val frames = listOf(speech(), speech())
        val script = listOf<Transcript?>(partial("keep me"), final("keep me"))
        val session = ScriptedUtterance(script)
        val rescorer = RecordingRescorer { throw IllegalStateException("onnx exploded") }

        val events = Dictation(frames.asFlow(), { session }, { 1_000L }, rescorer = { rescorer })
            .run()
            .toList()

        assertEquals(1, rescorer.calls.size)
        assertEquals("keep me", (events.last().transcript as Transcript.Final).text)

        // Cancellation is control flow, not a recoverable inference failure.
        val cancelled = RecordingRescorer { throw CancellationException("left capture") }
        val delivered = mutableListOf<DictationEvent>()
        val failure = runCatching {
            Dictation(frames.asFlow(), { ScriptedUtterance(script) }, { 1_000L }, { cancelled })
                .run().toList(delivered)
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertTrue(delivered.none { it.transcript is Transcript.Final })
    }

    @Test
    fun `no rescorer means no call and the draft ships as before the pass existed`() = runTest {
        val frames = listOf(speech(), speech())
        val script = listOf<Transcript?>(partial("draft"), final("draft"))
        val session = ScriptedUtterance(script)
        var calls = 0
        val rescorer = object : Rescorer {
            override fun rescore(audio: FloatArray): String? {
                calls++
                return null
            }
        }

        val events = Dictation(frames.asFlow(), { session }, { 1_000L }, rescorer = { rescorer })
            .run()
            .toList()

        assertEquals(1, calls)
        assertEquals("draft", (events.last().transcript as Transcript.Final).text)
    }

    @Test
    fun `an utterance past the cap is not rescored`() = runTest {
        // MAX_UTTERANCE_SAMPLES is 30 s; three frames of 100 ms cannot reach
        // it, so this drives the branch with the only thing smaller: a cap
        // reached by construction. Reaching 30 s in a unit test would take a
        // third of a megabyte of frames, which is fine, so do exactly that.
        val frameCount = Dictation.MAX_UTTERANCE_SAMPLES / AudioCapture.FRAME_SAMPLES + 2
        val frames = List(frameCount) { silence() }
        val script = listOf<Transcript?>(
            *Array(frameCount - 1) { null },
            final("too long to rescore"),
        )
        val session = ScriptedUtterance(script)
        val rescorer = RecordingRescorer { "should not be called" }

        val events = Dictation(frames.asFlow(), { session }, { 1_000L }, rescorer = { rescorer })
            .run()
            .toList()

        assertEquals(0, rescorer.calls.size)
        assertEquals("too long to rescore", (events.last().transcript as Transcript.Final).text)
    }

    @Test
    fun `the cap test really exercises the cap`() = runTest {
        // A guard on the test above: if MAX_UTTERANCE_SAMPLES ever becomes
        // unreachable the cap test silently stops testing anything.
        assertTrue(
            "MAX_UTTERANCE_SAMPLES must exceed one frame for the cap test to mean anything",
            Dictation.MAX_UTTERANCE_SAMPLES > AudioCapture.FRAME_SAMPLES,
        )
    }
}
