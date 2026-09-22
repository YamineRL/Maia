package dev.maia.nlu.grammar

import dev.maia.nlu.Specificity

/**
 * A sequence of [Term]s that a sentence either fits or does not.
 *
 * Anchored at the first token and required to consume every token, which is a
 * deliberate restriction. An unanchored matcher finds `note` in the middle of
 * `dinner with the notary` and is then asked to explain itself; an anchored one
 * that must account for the whole sentence cannot. Patterns that legitimately
 * trail off end in `Capture(min = 0)`.
 */
data class Pattern(
    val name: String,
    val terms: List<Term>,
    val specificity: Specificity,
) {
    constructor(name: String, specificity: Specificity, vararg terms: Term) :
        this(name, terms.toList(), specificity)
}

/** Where each named capture landed, as token indices. */
data class Match(val pattern: Pattern, val captures: Map<String, IntRange>) {
    fun capture(name: String): IntRange? = captures[name]?.takeIf { !it.isEmpty() }
}
