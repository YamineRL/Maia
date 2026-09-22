package dev.maia.nlu.text

/**
 * One token after normalisation, and where it came from.
 *
 * `span` is the range of positions in the original word sequence that this
 * token was built from, which is a range rather than the single index the M1
 * brief specified for one reason: normalisation merges. `p m` is two spoken
 * words and one token, `twenty five` is two spoken words and one number. The
 * preview card has to underline the words it heard a field from, and it can
 * only do that if a merged token still remembers both halves.
 */
data class Token(
    val text: String,
    val span: IntRange,
    val kind: Kind = Kind.Word,
) {
    enum class Kind {
        /** Anything with no numeric or temporal meaning of its own. */
        Word,

        /** A number. [text] holds the digits, so `twenty five` is `25`. */
        Cardinal,

        /** An ordinal. [text] holds the digits, so `twenty first` is `21`. */
        Ordinal,

        /** `am` or `pm`, already collapsed from the spoken `a m` and `p m`. */
        Meridiem,

        /** `oclock`, collapsed from `o clock` or `oh clock`. */
        OClock,
    }

    /** The numeric value, for the two kinds that carry one. */
    val value: Int?
        get() = when (kind) {
            Kind.Cardinal, Kind.Ordinal -> text.toIntOrNull()
            else -> null
        }
}

/**
 * A normalised sentence. Thin on purpose: it exists so the grammar has one
 * type to match against and so the span arithmetic lives in one place.
 */
data class Tokens(val list: List<Token>) {
    val size: Int get() = list.size
    operator fun get(i: Int): Token = list[i]
    fun isEmpty(): Boolean = list.isEmpty()

    /** The original word positions covered by tokens [from] until [until]. */
    /**
     * The tokens at indices not in [consumed], in order.
     *
     * The spans on the survivors are not renumbered, because they point at the
     * original words and that is the only reason they exist. What this breaks
     * is indexing: a capture over the result is a range in the residual list,
     * not in the sentence, so anything that needs the words back has to go
     * through [Token.span] rather than through the index.
     */
    fun without(consumed: Set<Int>): Tokens =
        Tokens(list.filterIndexed { i, _ -> i !in consumed })

    fun span(from: Int, until: Int): IntRange? {
        if (from >= until || list.isEmpty()) return null
        return list[from].span.first..list[until - 1].span.last
    }

    override fun toString(): String = list.joinToString(" ") { it.text }
}
