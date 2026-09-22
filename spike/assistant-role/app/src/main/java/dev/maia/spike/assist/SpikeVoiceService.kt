package dev.maia.spike.assist

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession

/**
 * The role holder. It has no UI of its own and it is bound by the system for
 * as long as the spike holds the assistant role, which is the fact R1 is about
 * and which G1 makes visible.
 */
class SpikeVoiceService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        // Section 1.3: screen context off, explicitly, from the first moment
        // the role is held. The spike does it for the same reason :app will,
        // so that no step 0 log line ever contains an assist structure.
        setDisabledShowContext(
            VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT,
        )
        instance = this
        log("VoiceService.onReady, disabledShowContext=${describeShowFlags(disabledShowContext)}, ${probeLock(this)}")
    }

    override fun onShutdown() {
        log("VoiceService.onShutdown")
        if (instance === this) instance = null
        super.onShutdown()
    }

    /**
     * API 33 and up. The system calls this before it shows a session, so it is
     * the earliest log line a gesture produces and the one G10's latency is
     * measured from where it is available.
     */
    override fun onPrepareToShowSession(args: Bundle, showFlags: Int) {
        log("VoiceService.onPrepareToShowSession flags=${describeShowFlags(showFlags)} args=${args.keySet()}")
        super.onPrepareToShowSession(args, showFlags)
    }

    override fun onShowSessionFailed(args: Bundle) {
        log("VoiceService.onShowSessionFailed args=${args.keySet()}")
        super.onShowSessionFailed(args)
    }

    /**
     * The keyguard voice affordance, if this build has one. Logged rather than
     * acted on: voice_interaction.xml does not claim
     * supportsLaunchVoiceAssistFromKeyguard, so seeing this line at all would
     * itself be a finding.
     */
    override fun onLaunchVoiceAssistFromKeyguard() {
        log("VoiceService.onLaunchVoiceAssistFromKeyguard, ${probeLock(this)}")
        super.onLaunchVoiceAssistFromKeyguard()
    }

    companion object {
        /**
         * R7 (brief section 11). A TileService and the voice service share this
         * process, so a tile tap can reach the bound service directly and ask
         * it to show a session. Whether the system honours that from a locked
         * phone is the whole question; this reference is the few lines the
         * brief asked for.
         *
         * Null whenever the role is not held, which is itself the answer to
         * "does a tile work without the role".
         */
        @Volatile
        @JvmStatic
        var instance: SpikeVoiceService? = null
            private set

        /** Returns the failure reason, or null if the request was made. */
        fun showSessionFromTile(): String? {
            val service = instance ?: return "voice service not bound (role not held?)"
            return try {
                service.showSession(Bundle(), VoiceInteractionSession.SHOW_SOURCE_PUSH_TO_TALK)
                null
            } catch (t: Throwable) {
                "${t.javaClass.simpleName}: ${t.message}"
            }
        }
    }
}
