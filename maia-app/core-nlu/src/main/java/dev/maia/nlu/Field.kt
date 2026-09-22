package dev.maia.nlu

/**
 * A value, where it came from, and which spoken words it came from.
 *
 * PRD section 5 and section 7 both require anything the parser guessed to be
 * visibly marked on the preview card, and a boolean cannot express this
 * because by the time the card is on screen there is a third state.
 */
data class Field<T>(
    val value: T,
    val provenance: Provenance,
    /** Positions in the original word sequence, for underlining what was heard. */
    val span: IntRange? = null,
)

enum class Provenance {
    /** Came from words the user actually said. [Field.span] says which ones. */
    Heard,

    /** The parser supplied it: the one hour duration, a guessed meridiem. */
    Inferred,

    /**
     * The user changed it on the card.
     *
     * This is the state a boolean cannot hold, and it is load bearing. A
     * corrected field must never be re-inferred when a neighbouring field
     * changes: set the date after correcting the time, and a recompute that
     * does not check provenance quietly overwrites the correction. That is
     * precisely the class of bug that costs trust.
     */
    Corrected,
}
