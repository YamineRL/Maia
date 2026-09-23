package dev.maia.audio.speech

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * sherpa-onnx's offline TTS with a Piper voice, played as it is generated.
 *
 * Nothing new enters the build for this: the AAR that carries the recogniser
 * already carries `OfflineTts` (brief section 5). None of this class runs on
 * the build machine, because the AAR is arm64-v8a and the box is x86_64. What
 * the JVM proves is [SpeechEnvelope], [SpeakingPolicy] and the fakes; playback,
 * latency and the envelope's calibration wait for the Pixel.
 *
 * Construction loads the voice and takes a noticeable moment, so there should
 * be one of these for the life of the process, like the recogniser.
 *
 * **Streaming.** `generateWithCallback` calls back on the generating thread
 * with each chunk of samples. Chunks cross a small channel to the collector,
 * which writes them to the track in [SpeechEnvelope.WINDOW_MS] windows with
 * blocking writes, so the envelope is emitted at the pace audio is accepted
 * rather than at the pace the model produces it. A blocking write returns when
 * the track has buffered the window, so the envelope leads the loudspeaker by
 * the track's output latency; whether that is visible is a Pixel question.
 *
 * **Cancellation.** Cancelling the collection closes the channel, so the next
 * callback returns [STOP] and sherpa stops generating; the track is paused,
 * flushed and released in `finally`, whichever way the flow ends.
 */
class SherpaSpeaker(
    paths: VoicePaths,
    private val speakerId: Int = 0,
    private val speed: Float = 1.0f,
    threads: Int = 2,
) : Speaker, Closeable {

    private val tts = OfflineTts(
        config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = paths.model,
                    tokens = paths.tokens,
                    dataDir = paths.dataDir,
                ),
                numThreads = threads,
            ),
        ),
    )

    private val sampleRate = tts.sampleRate()

    /**
     * One sentence at a time. A native generator shared by two collectors is
     * not something to find out about on a user's phone, and two voices over
     * each other is wrong whatever the engine would tolerate.
     */
    private val oneAtATime = Mutex()

    override fun speak(text: String): Flow<Float> = flow {
        if (text.isBlank()) return@flow
        oneAtATime.withLock { play(text) }
    }.flowOn(Dispatchers.IO)

    private suspend fun FlowCollector<Float>.play(text: String) {
        // Opened before generation starts, so a device that refuses the format
        // fails here with nothing native left running.
        val track = openTrack()
        try {
            coroutineScope {
                val chunks = Channel<FloatArray>(CHUNK_BACKLOG)
                val producer = launch(Dispatchers.Default) {
                    try {
                        // An object expression, not a lambda: sherpa's JNI
                        // looks up the specialised invoke(float[]):Integer
                        // that only a real class declares. Kotlin 2 compiles
                        // lambdas through invokedynamic, and the synthesised
                        // class carries only the erased invoke(Object), so a
                        // lambda here aborts the process on the first call.
                        tts.generateWithCallback(
                            text,
                            speakerId,
                            speed,
                            object : Function1<FloatArray, Int> {
                                override fun invoke(samples: FloatArray): Int {
                                    // Copied because the array's lifetime
                                    // after the callback returns belongs to
                                    // the native side.
                                    return if (chunks.trySendBlocking(samples.copyOf()).isSuccess) CONTINUE else STOP
                                }
                            },
                        )
                    } finally {
                        chunks.close()
                    }
                }
                try {
                    track.play()
                    val size = SpeechEnvelope.windowSize(sampleRate)
                    var written = 0L
                    for (chunk in chunks) {
                        for (window in SpeechEnvelope.windows(chunk.size, size)) {
                            ensureActive()
                            val n = track.write(chunk, window.offset, window.length, AudioTrack.WRITE_BLOCKING)
                            if (n < 0) throw IOException("AudioTrack.write returned $n")
                            written += n
                            emit(SpeechEnvelope.level(SpeechEnvelope.rms(chunk, window.offset, window.length)))
                        }
                    }
                    awaitDrain(track, written)
                    emit(0f)
                } finally {
                    // Cancel first: a callback blocked on a full channel is
                    // released by this, returns STOP, and the producer can end.
                    chunks.cancel()
                    producer.cancel()
                }
            }
        } finally {
            runCatching {
                track.pause()
                track.flush()
            }
            track.release()
        }
    }

    /**
     * Waits for the last buffered audio to leave the speaker, bounded.
     *
     * Releasing the track as soon as the final write returns would clip the
     * end of the sentence, which is where the time is. The bound is the
     * sentence's own length plus slack, so a head position that never moves
     * cannot hold the flow open.
     */
    private suspend fun awaitDrain(track: AudioTrack, frames: Long) {
        val limit = frames * 1000 / sampleRate + DRAIN_SLACK_MS
        var waited = 0L
        while ((track.playbackHeadPosition.toLong() and 0xFFFFFFFFL) < frames && waited < limit) {
            delay(DRAIN_POLL_MS)
            waited += DRAIN_POLL_MS
        }
    }

    private fun openTrack(): AudioTrack {
        val minimum = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            // A tenth of a second, or the platform minimum if that is larger.
            // getMinBufferSize returns a negative error code on failure.
            .setBufferSizeInBytes(maxOf(minimum, sampleRate / 10 * Float.SIZE_BYTES))
            .build()
    }

    override fun close() = tts.release()

    companion object {
        /** sherpa's callback contract: non-zero keeps generating, zero stops. */
        const val CONTINUE = 1
        const val STOP = 0

        /**
         * A speaker if the voice is on disk and the engine will open, else
         * null.
         *
         * Null rather than a throw is the contract: the voice is an optional
         * download, so "not there" and "would not load" are the same outcome
         * for the caller, which keeps [SilentSpeaker] or simply stays quiet
         * either way (M9 PRD section 11, "Voice unavailable: show the answer
         * and remain silent"). The completeness check runs before any native
         * call, so this costs one stat per file on a fresh install and never
         * touches sherpa on a phone that refused the download. Failures the
         * native side does report, including a JNI link failure, land in the
         * [runCatching] and come back as null rather than as a crash at
         * wiring time. `root` is the voice directory, the same one handed to
         * [VoiceStore]: `File(filesDir, "voice")`.
         */
        fun createIfReady(root: File, threads: Int = 2): SherpaSpeaker? {
            val store = VoiceStore(root)
            if (!store.isComplete) return null
            return runCatching { SherpaSpeaker(store.paths(), threads = threads) }.getOrNull()
        }

        /** Chunks waiting for the track. A sentence is a few chunks at most. */
        const val CHUNK_BACKLOG = 4

        const val DRAIN_POLL_MS = 20L
        const val DRAIN_SLACK_MS = 500L
    }
}
