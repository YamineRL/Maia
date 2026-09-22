package dev.maia.audio.speech

import android.media.AudioManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * When Maia is allowed to make a sound.
 *
 * The design seat's rule is that silent mode honours the ringer automatically,
 * with no setting to remember. So anything other than
 * [AudioManager.RINGER_MODE_NORMAL] is silence: vibrate and silent both, and
 * any mode a future release adds, because a new mode the code has not heard of
 * is safer quiet. The screen still confirms visually and with haptics.
 *
 * [USAGE_ASSISTANT][android.media.AudioAttributes.USAGE_ASSISTANT] does not
 * mute itself on a silent ringer, which is exactly why this has to be said in
 * code rather than left to the platform.
 *
 * A cancel is not in here. The reducer owns it by never asking for speech
 * after the user cancelled, so there is nothing for a policy to veto.
 */
object SpeakingPolicy {
    fun shouldSpeak(ringerMode: Int, speechEnabled: Boolean = true): Boolean =
        speechEnabled && ringerMode == AudioManager.RINGER_MODE_NORMAL
}

/**
 * A [Speaker] that asks [SpeakingPolicy] first, every time.
 *
 * Both inputs are read when the flow is collected, not when it is built: the
 * user can flip the ringer switch between the hold and the confirmation, and
 * the state that counts is the one at the moment of speaking. In the app,
 * [ringerMode] is `{ audioManager.ringerMode }`; [speechEnabled] is the user's
 * setting once one exists (brief section 5).
 */
class RingerAwareSpeaker(
    private val delegate: Speaker,
    private val ringerMode: () -> Int,
    private val speechEnabled: () -> Boolean = { true },
) : Speaker {
    override fun speak(text: String): Flow<Float> = flow {
        if (SpeakingPolicy.shouldSpeak(ringerMode(), speechEnabled())) {
            emitAll(delegate.speak(text))
        }
    }
}
