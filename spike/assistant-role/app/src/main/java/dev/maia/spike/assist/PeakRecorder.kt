package dev.maia.spike.assist

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.abs

/**
 * G5 and G8. Opens an `AudioRecord` on `VOICE_RECOGNITION` for two seconds and
 * reports the peak absolute sample.
 *
 * Two seconds is chosen so the system microphone indicator has time to appear
 * and then visibly go out, which is half of what G5 reads. There is no
 * foreground service on purpose: whether a shown session is enough for
 * while-in-use microphone access is exactly R2.
 *
 * Nothing is written anywhere. The samples are read into one short array,
 * reduced to a single number, and dropped.
 */
object PeakRecorder {

    private const val SAMPLE_RATE = 16_000
    private const val DURATION_MS = 2_000L

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var busy = false

    fun record(context: Context, onProgress: (String) -> Unit) {
        if (busy) {
            onProgress("already recording")
            return
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // A runtime prompt cannot be answered from a lock screen, so this
            // is the state the checklist tells the operator to clear first.
            report(onProgress, "RECORD_AUDIO not granted: open the spike from the launcher and allow it")
            return
        }
        busy = true
        report(onProgress, "recording 2 s on VOICE_RECOGNITION ...")

        Thread {
            var record: AudioRecord? = null
            try {
                val minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                if (minBuffer <= 0) {
                    report(onProgress, "getMinBufferSize returned $minBuffer")
                    return@Thread
                }
                record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuffer * 2,
                )
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    report(onProgress, "AudioRecord state=${record.state}, not initialised")
                    return@Thread
                }
                record.startRecording()
                report(onProgress, "startRecording, recordingState=${record.recordingState}")

                val buffer = ShortArray(minBuffer)
                var peak = 0
                var frames = 0L
                var reads = 0
                var lastError = 0
                val deadline = SystemClock.elapsedRealtime() + DURATION_MS
                while (SystemClock.elapsedRealtime() < deadline) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n < 0) {
                        lastError = n
                        break
                    }
                    reads++
                    frames += n
                    for (i in 0 until n) {
                        val v = abs(buffer[i].toInt())
                        if (v > peak) peak = v
                    }
                }
                val verdict = when {
                    lastError < 0 -> "read error $lastError"
                    frames == 0L -> "no frames: silent or denied"
                    peak == 0 -> "frames but peak 0: digital silence, the classic locked-capture refusal"
                    else -> "peak ok"
                }
                report(
                    onProgress,
                    "peak=$peak frames=$frames reads=$reads -> $verdict",
                )
            } catch (t: Throwable) {
                report(onProgress, "threw ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                try {
                    if (record?.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
                } catch (_: Throwable) {
                }
                record?.release()
                busy = false
                report(onProgress, "recorder released, microphone indicator should now go out")
            }
        }.start()
    }

    private fun report(onProgress: (String) -> Unit, message: String) {
        log("PeakRecorder: $message")
        main.post { onProgress(message) }
    }
}
