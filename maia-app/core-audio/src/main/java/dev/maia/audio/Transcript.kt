package dev.maia.audio

/**
 * What the recogniser has heard, and whether it is still revising it.
 *
 * The distinction is the whole point of choosing a streaming engine. A
 * [Partial] exists so the user can see the machine is listening; it is
 * rewritten on almost every frame and must never be parsed or acted on. A
 * [Final] is what the endpointer decided was a complete utterance, and is the
 * only thing the parser downstream should ever see.
 */
sealed interface Transcript {
    val text: String

    data class Partial(
        override val text: String,
        /**
         * The same text as words with a confidence each, for the listening
         * screen's confirmed and uncertain inks. Empty when the recogniser
         * gave no usable per-token scores, and then every word renders as
         * confirmed: the split is a nicety, not something to guess at.
         */
        val words: List<Word> = emptyList(),
    ) : Transcript

    data class Final(
        override val text: String,
        /** Wall clock from the first frame of this utterance to this result. */
        val utteranceMs: Long,
        /**
         * From the last frame the recogniser judged to contain speech, to this
         * result landing. This is the number PRD section 13 budgets at 700 ms
         * with a 1500 ms ceiling, and it is mostly endpointer patience rather
         * than compute.
         */
        val silenceToFinalMs: Long,
    ) : Transcript
}

/** Timing for one utterance, kept so section 7 can be answered with numbers. */
data class Timings(
    val firstPartialMs: Long? = null,
    val lastFinal: Transcript.Final? = null,
    /** Real time divided by audio time over the session. Under 1.0 keeps up. */
    val realTimeFactor: Float = 0f,
)
