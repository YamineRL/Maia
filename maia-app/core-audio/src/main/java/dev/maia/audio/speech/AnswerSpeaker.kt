package dev.maia.audio.speech

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * The [Speaker] conversational answers go through, and nothing else.
 *
 * M9's rule (PRD section 9, extending M8) is that an answer's spoken text may
 * be voiced while coding-agent output, run state, and system markers are
 * never spoken. The boundary is not enforceable inside a speaker: any
 * [Speaker] will read whatever string it is given. What a type can do is
 * make the boundary a name. App code speaks a `ConversationalAnswer`'s
 * spoken field through this wrapper and speaks nothing else through it, so
 * the call sites that may produce speech stay enumerable and the never-spoken
 * review has exactly one type to grep for.
 *
 * Behaviour on top of the name is only what answers need: [SpeechChunks]
 * splits the text into sentence-sized pieces and each is played in turn, so
 * a longer answer is several short generations rather than one long one.
 * Cancellation lands between pieces if it misses the piece in flight.
 *
 * The ringer and speech-enabled rules are not in here. They live in
 * [RingerAwareSpeaker], which wraps this (or wraps the same delegate around
 * this), because silent mode applies to answers exactly as it applies to
 * acknowledgements.
 */
class AnswerSpeaker(
    private val delegate: Speaker,
    private val maxChunkChars: Int = SpeechChunks.MAX_CHARS,
) : Speaker {

    override fun speak(text: String): Flow<Float> = flow {
        for (chunk in SpeechChunks.split(text, maxChunkChars)) {
            emitAll(delegate.speak(chunk))
        }
    }
}
