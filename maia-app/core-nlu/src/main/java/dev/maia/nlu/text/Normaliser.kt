package dev.maia.nlu.text

/**
 * Pass one of three: raw recogniser output to [Tokens].
 *
 * This exists because of what the recogniser emits. sherpa's streaming
 * zipformer produces lowercase, unpunctuated English with every number spelled
 * out, so the grammar never sees `3pm on Thursday`, it sees
 * `three p m on thursday`. There is no inverse text normalisation pass in the
 * pipeline and adding one is two more ONNX passes and a latency row the PRD
 * does not have, so the repair happens here, in arithmetic, for free.
 *
 * Except that is no longer the whole input. The parakeet rescorer rewrites the
 * final transcript as it would be written: `Project seven.` with the capital
 * and the full stop. The capital was always handled (`lowercase()` below); the
 * punctuation was not, and a `seven.` token is neither a cardinal nor a word
 * any pattern claims, so a punctuated sentence could not parse at all.
 *
 * Order matters and is not arbitrary:
 *
 *  1. Split, then strip edge punctuation off each word, dropping what is left
 *     empty. The spans below stay indices into the original split, including
 *     the positions of dropped words: a transcript is `seven .` as often as
 *     `seven.`, and a token that is only a full stop still breaks an anchored
 *     match if it keeps a slot. Keeping its index in the spans it skips over
 *     is what lets the instruction read-back land on the right words of the
 *     raw transcript.
 *  2. Collapse the spelled-out abbreviations: `p m`, `a m`, `o clock`. This
 *     runs first so that step 3 can safely read a bare `oh` as zero, which it
 *     could not do while `oh clock` was still two words.
 *  3. Join numbers: tens to units, ordinal tens to ordinal units, and the
 *     military `fourteen hundred`.
 *
 * **Deliberate deviation from the M1 brief.** The brief put the year form
 * `twenty twenty six` here, joined "only when a year is grammatical in that
 * position". Position grammaticality is precisely what this pass does not
 * know, and teaching it would mean putting month names and date shapes into
 * the normaliser, which couples the two passes the brief separated on purpose.
 * So `twenty twenty six` normalises to the two cardinals `20` and `26`, and
 * the year is recognised by the grammar, in the one place that knows it is
 * looking at a date slot.
 */
object Normaliser {

    fun normalise(raw: String): Tokens {
        val words = raw.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        // Pair each word with its index before stripping, so the tokens that
        // survive still name their positions in the raw transcript.
        val cleaned = words.mapIndexedNotNull { index, word ->
            word.trim { !it.isLetterOrDigit() }.takeIf { it.isNotEmpty() }?.let { it to index }
        }
        if (cleaned.isEmpty()) return Tokens(emptyList())
        return Tokens(joinNumbers(collapse(cleaned)))
    }

    private fun collapse(words: List<Pair<String, Int>>): List<Token> {
        val out = ArrayList<Token>(words.size)
        var i = 0
        while (i < words.size) {
            val (w, at) = words[i]
            val next = words.getOrNull(i + 1)?.first
            when {
                w == "p" && next == "m" -> {
                    out += Token("pm", at..words[i + 1].second, Token.Kind.Meridiem); i += 2
                }
                w == "a" && next == "m" -> {
                    out += Token("am", at..words[i + 1].second, Token.Kind.Meridiem); i += 2
                }
                (w == "o" || w == "oh") && next == "clock" -> {
                    out += Token("oclock", at..words[i + 1].second, Token.Kind.OClock); i += 2
                }
                w == "am" || w == "pm" -> {
                    out += Token(w, at..at, Token.Kind.Meridiem); i += 1
                }
                w == "oclock" -> {
                    out += Token(w, at..at, Token.Kind.OClock); i += 1
                }
                else -> {
                    out += Token(w, at..at); i += 1
                }
            }
        }
        return out
    }

    private fun joinNumbers(tokens: List<Token>): List<Token> {
        val out = ArrayList<Token>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.kind != Token.Kind.Word) { out += t; i += 1; continue }
            val next = tokens.getOrNull(i + 1)?.takeIf { it.kind == Token.Kind.Word }

            // The rescorer writes numbers as digits as often as words:
            // "Project 7." is as much its output as "Project seven." A run of
            // digits is a cardinal already; "7th" is an ordinal. Neither joins
            // with a neighbour the way spelled-out tens and units do.
            val digits = DIGITS.matchEntire(t.text)
            if (digits != null) {
                out += Token(t.text, t.span, Token.Kind.Cardinal); i += 1; continue
            }
            val digitOrdinal = DIGIT_ORDINAL.matchEntire(t.text)
            if (digitOrdinal != null) {
                out += Token(digitOrdinal.groupValues[1], t.span, Token.Kind.Ordinal); i += 1; continue
            }

            val tensValue = SpokenNumbers.tens(t.text)
            if (tensValue != null && next != null) {
                val unit = SpokenNumbers.closingUnit(next.text)
                if (unit != null) {
                    out += Token("${tensValue + unit}", t.span.first..next.span.last, Token.Kind.Cardinal)
                    i += 2; continue
                }
                val ordinalUnit = SpokenNumbers.closingOrdinalUnit(next.text)
                if (ordinalUnit != null) {
                    out += Token("${tensValue + ordinalUnit}", t.span.first..next.span.last, Token.Kind.Ordinal)
                    i += 2; continue
                }
            }

            val cardinal = SpokenNumbers.cardinal(t.text)
            // "fourteen hundred", which is how a 24 hour time gets said aloud.
            if (cardinal != null && next != null && next.text == "hundred") {
                out += Token("${cardinal * 100}", t.span.first..next.span.last, Token.Kind.Cardinal)
                i += 2; continue
            }
            if (cardinal != null) {
                out += Token("$cardinal", t.span, Token.Kind.Cardinal); i += 1; continue
            }
            val ordinal = SpokenNumbers.ordinal(t.text)
            if (ordinal != null) {
                out += Token("$ordinal", t.span, Token.Kind.Ordinal); i += 1; continue
            }
            out += t; i += 1
        }
        return out
    }

    private val DIGITS = Regex("\\d+")
    private val DIGIT_ORDINAL = Regex("(\\d+)(?:st|nd|rd|th)")
}
