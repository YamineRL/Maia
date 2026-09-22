package dev.maia.spike.assist

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Section 1.2. A refusing stub.
 *
 * It exists because the role picker is believed to require a
 * `recognitionService` (R1), and because taking the role is believed to point
 * the system default recogniser at it (R3, read by G7). The moment the spike
 * holds the role, any app on the phone calling `SpeechRecognizer` lands here.
 *
 * So it answers every request with `ERROR_RECOGNIZER_BUSY` and never opens the
 * microphone. Each call is logged, because "which app just asked" is a finding
 * in its own right.
 */
class SpikeRecognitionService : RecognitionService() {

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        log("RecognitionService.onStartListening from ${listener?.callingUid}, refusing with ERROR_RECOGNIZER_BUSY")
        try {
            listener?.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
        } catch (t: Throwable) {
            log("RecognitionService: error() threw ${t.javaClass.simpleName}")
        }
    }

    override fun onStopListening(listener: Callback?) {
        log("RecognitionService.onStopListening")
    }

    override fun onCancel(listener: Callback?) {
        log("RecognitionService.onCancel")
    }
}
