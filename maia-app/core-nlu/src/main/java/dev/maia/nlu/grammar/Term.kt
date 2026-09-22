package dev.maia.nlu.grammar

import dev.maia.nlu.text.Token

/**
 * One element of a [Pattern].
 *
 * The shape here is borrowed from `dicio-sentences-compiler`: optional words,
 * alternatives, captures, and a specificity for tie breaks. What is not
 * borrowed is the compiler. That project reads a DSL from a file and generates
 * Java, which means either a JitPack coordinate (a repository that builds
 * artefacts on demand, which is the opposite of the reproducible builds PRD
 * section 12 asks for) or vendored source plus a JavaExec codegen step plus
 * generated Java in the one module whose whole job is to be boringly portable.
 * That is a lot of build machinery in service of pattern matching, which is
 * the easy third of the problem.
 *
 * So the patterns are data instead, declared in [IntentPatterns]. If the intent
 * count ever grows past a handful, adding a DSL is then a parser over that one
 * file rather than a rewrite of everything that reads it.
 */
sealed interface Term {

    /** Exactly this word. */
    data class Literal(val word: String) : Term

    /** Any one of these words. Cheaper to read than a chain of alternatives. */
    data class AnyOf(val words: Set<String>) : Term {
        constructor(vararg words: String) : this(words.toSet())
    }

    /** Any single token of this kind, so a number or a meridiem. */
    data class OfKind(val kind: Token.Kind) : Term

    /** Any single token at all. */
    data object Any : Term

    /** Zero or one of [term]. */
    data class Optional(val term: Term) : Term

    /**
     * A run of tokens, recorded under [name].
     *
     * Matched shortest first. `schedule <title> with sam` has to give the title
     * up as soon as a `with sam` can follow it, and a greedy capture would
     * swallow the whole sentence and then fail.
     */
    data class Capture(val name: String, val min: Int = 1) : Term
}
