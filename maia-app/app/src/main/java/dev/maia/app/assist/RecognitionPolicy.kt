package dev.maia.app.assist

/**
 * What Maia's `RecognitionService` does with a request from another app, as a
 * pure function of the request alone.
 *
 * **Why this is a separate file with no Android import.** Criterion J12 has to
 * be provable on this box, and the only part worth proving is the decision: a
 * stub that refuses today can be edited into a stub that records tomorrow
 * without anybody noticing, unless the refusal is a value a test can read.
 * [decide] therefore returns what would happen rather than doing it, and
 * [MaiaRecognitionService] is a thin translation of that answer into the
 * platform callback. There is no second code path in the service that this
 * function does not describe.
 *
 * **Who can reach it, and from when.** Not only the assistant. The brief's item
 * I2 says Maia becomes the system speech recogniser "while it holds the role",
 * and `docs/research/R3.md` (read 2026-09-13, AOSP `android16-release`,
 * `VoiceInteractionManagerService.findAvailRecognizer` and `initRecognizer`)
 * shows that is mis-attributed. The role grant writes only
 * `Settings.Secure.ASSISTANT` and `Settings.Secure.VOICE_INTERACTION_SERVICE`.
 * `VOICE_RECOGNITION_SERVICE` is set separately, to the framework resource
 * `config_systemSpeechRecognizer` if that names an available service and
 * otherwise to the **first available** `RecognitionService` on the device.
 *
 * R3 read that as meaning installation alone makes Maia the system recogniser on
 * a clean GrapheneOS phone. G7 in `spike/assistant-role/README.md` says it does
 * not: with the spike's stub the only `RecognitionService` installed,
 * `voice_recognition_service` was null before the role, after it, after a reboot
 * and after the unlock. So the honest statement is narrower and still not
 * comfortable. This stub is exported with an intent filter and no permission,
 * because R1 requires the declaration, so any app that names it or resolves the
 * action itself reaches it from first install, role or no role. An app that just
 * asks for the system recogniser reaches nothing. That is why this refuses
 * rather than merely being unwired.
 *
 * The lever that would opt out of ever being selected as the system recogniser
 * is `selectableAsDefault="false"` in the `android.speech` meta-data
 * (`res/xml/speech_recognition.xml`). It is deliberately **not** set here: it is
 * the user's call, it is recommended in the M3 report rather than taken, and on
 * the reading above it would change nothing on this build. It would not close
 * the route that is actually open, which is an explicit caller.
 *
 * Privacy item V1: no request is logged, and in particular the calling
 * package is never read, never recorded and never passed anywhere. The
 * decision does not take the caller as a parameter, so there is nothing to
 * leak by accident.
 */
object RecognitionPolicy {

    /**
     * Mirrors `android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY`.
     *
     * A literal rather than a reference, so this file keeps its promise of
     * having no Android import at all. `RecognitionPolicyTest` asserts the two
     * are equal, which is a real check: the test fixture compiles against the
     * platform constant, so a platform that renumbered it fails the test rather
     * than shipping a wrong code.
     */
    const val ERROR_RECOGNIZER_BUSY: Int = 8

    /** The four things another app can ask a `RecognitionService` to do. */
    enum class Request { StartListening, StopListening, Cancel, CheckSupport }

    /**
     * What the service will do, in full.
     *
     * Every field but [errorCode] exists to be asserted false. They are the
     * promises the brief makes in section 1.2, written as data so that a change
     * to the service that breaks one of them has to break this function first.
     */
    data class Decision(
        /** Handed to the caller's `Callback.error`. Always an error, never a result. */
        val errorCode: Int,
        val opensMicrophone: Boolean = false,
        val touchesEngine: Boolean = false,
        val showsUi: Boolean = false,
        val logsCallerPackage: Boolean = false,
    )

    /**
     * Refuse, immediately, whatever was asked.
     *
     * `ERROR_RECOGNIZER_BUSY` rather than `ERROR_CLIENT` or
     * `ERROR_INSUFFICIENT_PERMISSIONS`, because busy is the one error a caller
     * is built to survive: it means try again, not you are broken and not you
     * are forbidden. Maia is, in a sense, permanently busy being Maia.
     *
     * The answer is the same for every request, including stop and cancel,
     * which is criterion J12 read literally. A uniform answer is also the
     * private one: a caller cannot use the timing or the shape of the refusal
     * to learn anything about what Maia is doing, because there is nothing to
     * vary.
     */
    @Suppress("UNUSED_PARAMETER")
    fun decide(request: Request): Decision = Decision(errorCode = ERROR_RECOGNIZER_BUSY)
}
