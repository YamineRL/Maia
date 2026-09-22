package dev.maia.nlu

/**
 * How much of the sentence a pattern actually accounted for, used to break ties
 * when more than one pattern matches.
 *
 * Ordinal order is the ranking, highest wins, so the declaration order here is
 * load bearing and not alphabetical.
 */
enum class Specificity {
    /** Matched on a keyword alone, with most of the sentence left over. */
    Low,

    /** Matched a recognisable shape, but with a field guessed rather than heard. */
    Medium,

    /** Matched the whole shape, every field from spoken tokens. */
    High,
}
