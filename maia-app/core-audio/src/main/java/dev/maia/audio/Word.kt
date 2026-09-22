package dev.maia.audio

import kotlin.math.exp

/** One recognised word and how sure the recogniser is of it, from 0 to 1. */
data class Word(val text: String, val confidence: Float) {
    /** Drawn in `ink.high` when true and `ink.low` when not. */
    val confirmed: Boolean get() = confidence >= CONFIRMED

    companion object {
        /**
         * A placeholder, and stated as one. It wants tuning against the
         * recorded corpus in PRD section 7, which does not exist yet.
         */
        const val CONFIRMED = 0.6f

        /** Sentencepiece's word boundary mark, U+2581. */
        private const val BOUNDARY = '▁'

        /**
         * Joins sentencepiece pieces into words. A piece that starts with the
         * boundary mark begins a word and any other piece continues the last
         * one. A word is only as sure as its least sure piece, because one
         * doubtful piece is enough to make the whole word wrong.
         *
         * [logProbs] are natural log probabilities, one per token, which is
         * what sherpa-onnx reports as `ysProbs`. When the two arrays differ in
         * length the scores cannot be trusted to line up with the pieces, so
         * nothing is returned rather than something misaligned.
         */
        fun fromTokens(tokens: Array<String>, logProbs: FloatArray): List<Word> {
            if (tokens.isEmpty() || tokens.size != logProbs.size) return emptyList()
            val words = mutableListOf<Word>()
            val text = StringBuilder()
            var least = Float.POSITIVE_INFINITY
            fun flush() {
                if (text.isNotEmpty()) {
                    words += Word(text.toString(), exp(least.toDouble()).toFloat().coerceIn(0f, 1f))
                }
                text.clear()
                least = Float.POSITIVE_INFINITY
            }
            tokens.forEachIndexed { i, token ->
                if (token.firstOrNull() == BOUNDARY) flush()
                text.append(token.trimStart(BOUNDARY))
                least = minOf(least, logProbs[i])
            }
            flush()
            return words
        }
    }
}
