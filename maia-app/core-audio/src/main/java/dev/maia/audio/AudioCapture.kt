package dev.maia.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * The microphone, as 100 ms slices of 16 kHz mono float audio.
 *
 * 16000 Hz is not a preference. The zipformer's feature extractor is built for
 * it and sherpa-onnx does not resample on our behalf, so another rate decodes
 * as confident nonsense rather than failing in any visible way.
 *
 * VOICE_RECOGNITION is chosen over MIC deliberately: it is the one source that
 * does not apply the aggressive AGC and noise suppression tuned for telephony,
 * which help a human listener and hurt an acoustic model.
 */
class AudioCapture(private val sampleRate: Int = SAMPLE_RATE) {

    /**
     * A recorder built ahead of time and not yet started.
     *
     * Written for the Quick Settings tile. `onStartListening` fires when the
     * shade opens, a second or two before the tap, and that is the only window
     * in which the cost of getting ready can be paid without the user watching
     * it. Constructing the recorder reserves the buffer; it does not light the
     * system microphone indicator, because that happens on `startRecording`
     * and only on the tap. Lighting it because somebody opened the shade would
     * be the interface telling a lie about what the app is doing.
     *
     * Held until the next [frames] collection takes it, or until [unreserve]
     * drops it. If neither happens it stays reserved until the process dies,
     * which is a buffer and not a microphone.
     */
    @Volatile
    private var reserved: AudioRecord? = null

    /** Idempotent. Safe to call on every shade open. */
    @Synchronized
    fun reserve() {
        if (reserved != null) return
        reserved = runCatching { openRecord() }.getOrNull()
    }

    /**
     * Drop an unstarted reservation, if any. Idempotent. Never touches a
     * recorder that is recording.
     *
     * At M1 a reservation was held from a shade open until the next capture or
     * until the process died, and that was harmless because the process was
     * short lived: nothing kept Maia resident once the shade closed. The
     * assistant role of M3 changes the arithmetic. The system keeps the active
     * assistant's service bound for as long as the role is held, so a session
     * that is shown and then dismissed without anyone speaking would leave a
     * constructed [AudioRecord], and the buffer behind it, held more or less
     * permanently. This is the door out of that: the dismissal path drops what
     * the prepare path reserved.
     *
     * The second sentence of the summary is the load-bearing one, and it is
     * structural rather than a promise. [frames] takes the reservation under
     * this same lock and clears the field before it calls `startRecording`, so
     * a recorder that is actually recording is no longer reachable from
     * [reserved] and cannot be released from here while a collector is reading
     * it. That also makes the idempotence real rather than defensive: the
     * field is cleared before the release, so a second call, or a call on a
     * capture that never reserved, finds nothing and returns instead of
     * releasing the same recorder twice.
     */
    @Synchronized
    fun unreserve() {
        val record = reserved ?: return
        reserved = null
        // Bare, as in the finally of frames, and for the same reason: release
        // is documented not to throw, unlike stop, which objects to a recorder
        // that was never started. A reservation is by definition unstarted.
        record.release()
    }

    @Synchronized
    private fun takeReserved(): AudioRecord? = reserved?.also { reserved = null }

    @SuppressLint("MissingPermission")
    private fun openRecord(): AudioRecord {
        val minimum = AudioRecord.getMinBufferSize(sampleRate, CHANNEL, ENCODING)
        if (minimum <= 0) throw IOException("AudioRecord rejects ${sampleRate}Hz on this device")

        // Twice the minimum. The minimum is the point at which the recorder
        // starts dropping audio if a read is late, and we would rather spend
        // a few kilobytes than lose the front of a word to a scheduling hiccup.
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            CHANNEL,
            ENCODING,
            minimum * 2,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IOException("microphone unavailable, another app may hold it")
        }
        return record
    }

    /**
     * Cold flow. The recorder is opened on collection and released when
     * collection ends, including on cancellation, so there is exactly one
     * owner of the microphone and no way to leak it by forgetting to stop.
     */
    fun frames(): Flow<FloatArray> = flow {
        // A reservation is taken, not shared: whoever collects owns the
        // recorder and releases it in the finally below, so there is still
        // exactly one owner of the microphone.
        val record = takeReserved() ?: openRecord()

        try {
            record.startRecording()
            val pcm = ShortArray(FRAME_SAMPLES)
            while (true) {
                coroutineContext.ensureActive()
                val read = record.read(pcm, 0, pcm.size)
                if (read < 0) throw IOException("AudioRecord.read failed with $read")
                if (read == 0) continue
                val frame = FloatArray(read)
                for (i in 0 until read) frame[i] = pcm[i] / 32768f
                emit(frame)
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 100
        const val FRAME_SAMPLES = SAMPLE_RATE / 1000 * FRAME_MS

        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
