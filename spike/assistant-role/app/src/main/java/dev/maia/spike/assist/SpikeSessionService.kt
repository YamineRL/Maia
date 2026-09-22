package dev.maia.spike.assist

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** Section 1.1: it exists to hand back a session, and does nothing else. */
class SpikeSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        log("SessionService.onNewSession args=${args?.keySet()}")
        return SpikeSession(this)
    }
}
