package dev.maia.app.assist

import android.content.Intent
import android.speech.RecognitionService

/**
 * The `RecognitionService` stub. Brief section 1.2, decision U3 in
 * `docs/M3-status.md`: a refusing stub at M3, not a real recogniser for other
 * apps.
 *
 * It exists because it has to. `docs/research/R1.md` (AOSP `android16-release`,
 * read 2026-09-13) shows the `voice_interaction` meta-data is rejected outright
 * without a `recognitionService` in two separate places:
 * `VoiceInteractionServiceInfo` sets `mParseError = "No recognitionService
 * specified"`, and `VoiceInteractionManagerService`'s role observer writes an
 * **empty string** into `Settings.Secure.ASSISTANT` and
 * `VOICE_INTERACTION_SERVICE` when it is missing, which holds the role and
 * silently never delivers a gesture. So the stub is load-bearing, not
 * defensive.
 *
 * What it must never become by accident is a microphone for other apps. Every
 * decision here comes from [RecognitionPolicy.decide], which is pure and
 * tested (criterion J12). This class opens no recorder, touches no engine,
 * shows nothing and logs nothing at all: not the request, not the intent's
 * extras and, per privacy item V1, not the calling package.
 *
 * The reach is wider than the brief assumed, though not as wide as
 * `docs/research/R3.md` predicted. The role does not point
 * `Settings.Secure.VOICE_RECOGNITION_SERVICE` here, which R3 got right. R3 then
 * expected installation alone to do it, on the grounds that the framework picks
 * the only available `RecognitionService`, and G7 on the Pixel says otherwise:
 * with the spike's stub the only one on the phone, the setting stayed null
 * through install, role grant, reboot and unlock. So what this stub is reachable
 * through is its own exported intent filter, by an app that names it: from first
 * install, independently of the assistant role, and with no picker involved. An
 * app that asks for the system recogniser instead reaches nothing, because on
 * this phone there is none. See [RecognitionPolicy] for the
 * `selectableAsDefault` lever, which is the user's call, is not taken here, and
 * on this evidence would change nothing anyway.
 */
class MaiaRecognitionService : RecognitionService() {

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        refuse(RecognitionPolicy.Request.StartListening, listener)
    }

    override fun onStopListening(listener: Callback) {
        refuse(RecognitionPolicy.Request.StopListening, listener)
    }

    override fun onCancel(listener: Callback) {
        refuse(RecognitionPolicy.Request.Cancel, listener)
    }

    /**
     * The whole of the service's behaviour, in one place.
     *
     * `runCatching` because `Callback.error` is a binder call to a caller that
     * may already be gone, and a dead client must not take Maia's process with
     * it. The failure is dropped rather than logged, for the same reason
     * nothing else here is logged.
     */
    private fun refuse(request: RecognitionPolicy.Request, listener: Callback) {
        val decision = RecognitionPolicy.decide(request)
        runCatching { listener.error(decision.errorCode) }
    }
}
