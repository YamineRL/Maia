package dev.maia.audio.speech

/**
 * Cuts a conversational answer into pieces short enough to say reliably.
 *
 * Acknowledgements are one short sentence and need none of this. M9 answers
 * are longer (M9 PRD section 3.5 bounds the spoken form at two sentences and
 * 400 characters, but the bound belongs to the response schema, and a speaker
 * that assumes it holds has nothing to stop a longer string being handed in).
 * One generation call per piece keeps each native call short, puts a real
 * pause between sentences, and gives cancellation a seam to land in between
 * pieces rather than mid-word.
 */
object SpeechChunks {

    /**
     * At 16 kHz a 200 character piece is roughly ten seconds of speech, short
     * enough that a failed generation loses little and a stop lands fast.
     */
    const val MAX_CHARS = 200

    /**
     * Splits [text] into pieces of at most [maxChars], preferring sentence
     * boundaries, then word boundaries, and falling back to a hard cut only
     * for a run with no spaces at all. Whitespace-only and empty input
     * produce no pieces.
     */
    fun split(text: String, maxChars: Int = MAX_CHARS): List<String> {
        require(maxChars > 0) { "maxChars must be positive, was $maxChars" }
        if (text.isBlank()) return emptyList()

        val pieces = mutableListOf<String>()
        var current = StringBuilder()
        for (sentence in sentences(text)) {
            if (sentence.length <= maxChars) {
                append(current, pieces, sentence, maxChars)
            } else {
                flush(current, pieces)
                pieces += splitLong(sentence, maxChars)
            }
        }
        flush(current, pieces)
        return pieces
    }

    /**
     * Sentence-sized slices of [text], each trimmed. A boundary is a
     * terminator (`., !, ?, ;, :` or a newline) followed by whitespace or the
     * end of the text, with closing quotes and brackets absorbed into the
     * sentence they close so a piece never opens on a bare `"`.
     */
    private fun sentences(text: String): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c in TERMINATORS) {
                var end = i + 1
                while (end < text.length && text[end] in TERMINATORS + CLOSERS) end++
                // A whitespace terminator is the boundary itself; a punctuation
                // one needs whitespace or the end of the text after it, or
                // "3.14" would split in half.
                if (c.isWhitespace() || end >= text.length || text[end].isWhitespace()) {
                    out += text.substring(start, end).trim()
                    start = end
                    i = end
                    continue
                }
            }
            i++
        }
        if (start < text.length) {
            out += text.substring(start).trim()
        }
        return out.filter { it.isNotEmpty() }
    }

    /** Adds [sentence] to the current piece, flushing it first when it no longer fits. */
    private fun append(current: StringBuilder, pieces: MutableList<String>, sentence: String, maxChars: Int) {
        if (current.isNotEmpty() && current.length + 1 + sentence.length > maxChars) {
            flush(current, pieces)
        }
        if (current.isNotEmpty()) current.append(' ')
        current.append(sentence)
    }

    private fun flush(current: StringBuilder, pieces: MutableList<String>) {
        if (current.isNotEmpty()) {
            pieces += current.toString()
            current.clear()
        }
    }

    /**
     * A single sentence longer than [maxChars], cut at the last space inside
     * the limit, or at the limit itself when no space exists in the window.
     * Cutting at the limit instead of refusing is what keeps a pathological
     * input a quiet truncation risk rather than an unbounded native call.
     */
    private fun splitLong(sentence: String, maxChars: Int): List<String> {
        val out = mutableListOf<String>()
        var rest = sentence
        while (rest.length > maxChars) {
            val window = rest.substring(0, maxChars)
            val cut = window.lastIndexOf(' ').takeIf { it > 0 } ?: maxChars
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }

    private const val TERMINATORS = ".!?;:\n"
    private const val CLOSERS = "\"')]}’”"
}
