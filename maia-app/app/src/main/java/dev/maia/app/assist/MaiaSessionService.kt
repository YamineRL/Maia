package dev.maia.app.assist

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * The factory the system binds to build one session per invocation. Brief
 * section 1.1.
 *
 * There is nothing to decide here, and that is the point: every rule about
 * what an invocation does lives in the pure reducer, and everything about the
 * window lives in [MaiaSession]. This class is the platform's required middle
 * step and stays empty on purpose.
 *
 * It is exported with `BIND_VOICE_INTERACTION` as its guard, which is the only
 * reason a service with no intent filter can be reached at all: the system
 * server binds it by explicit component, and the signature-level permission is
 * what stops anybody else.
 */
class MaiaSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle?): VoiceInteractionSession = MaiaSession(this)
}
