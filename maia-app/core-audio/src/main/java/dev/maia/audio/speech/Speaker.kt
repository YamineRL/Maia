package dev.maia.audio.speech

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow

/**
 * Something that can say a sentence out loud.
 *
 * The flow is the playback envelope, 0 to 1, one value per short window of
 * audio as it goes to the speaker. It is the aperture's Speaking pose that
 * reads it (M2 brief section 5), which is why the return type is loudness
 * rather than Unit: the orb and the voice have to move together, and the only
 * thing that knows when audio is actually leaving is the thing playing it.
 *
 * Nothing is produced until the flow is collected, and cancelling the
 * collection stops the voice. A user who cancels has asked for silence, and
 * that decision belongs to the screen reducer, which simply never collects:
 * criterion 10.1.4, "cancel produces no speech". Implementations do not
 * second guess it.
 */
fun interface Speaker {
    fun speak(text: String): Flow<Float>
}

/**
 * Says nothing and completes at once.
 *
 * For previews, for a phone with no voice downloaded, and as the honest
 * default: the voice is an optional second model set (brief section 5), so
 * dictation must work with this in place of a real speaker.
 */
object SilentSpeaker : Speaker {
    override fun speak(text: String): Flow<Float> = emptyFlow()
}

/**
 * Plays a fixed envelope and remembers what it was asked to say.
 *
 * For tests and Compose previews, where the Speaking pose needs something that
 * moves and there is no native library to move it. Text is recorded when the
 * flow is collected, not when [speak] is called, because collection is the
 * point at which a real speaker makes a sound.
 */
class ScriptedSpeaker(
    private val envelope: List<Float> = listOf(0.3f, 0.8f, 0.5f, 0.2f, 0f),
    private val frameMillis: Long = SpeechEnvelope.WINDOW_MS.toLong(),
) : Speaker {

    private val lock = Any()
    private val said = mutableListOf<String>()

    /** Every sentence collected so far, oldest first. */
    val spoken: List<String>
        get() = synchronized(lock) { said.toList() }

    override fun speak(text: String): Flow<Float> = flow {
        synchronized(lock) { said += text }
        for (level in envelope) {
            delay(frameMillis)
            emit(level)
        }
    }
}
