package dev.maia.nlu.grammar

import dev.maia.nlu.text.Token
import dev.maia.nlu.text.Tokens

/**
 * Walks a [Pattern] against a token list, with backtracking.
 *
 * Recursive rather than iterative because [Term.Optional] and [Term.Capture]
 * both need to try an alternative and take it back, and the sentences are one
 * clause long. The captures map is passed by value rather than mutated, so an
 * abandoned branch leaves nothing behind to undo, which is the class of bug an
 * accumulating matcher has.
 */
object Matcher {

    fun match(pattern: Pattern, tokens: Tokens): Match? {
        val caps = walk(pattern.terms, 0, tokens.list, 0, emptyMap()) ?: return null
        return Match(pattern, caps)
    }

    /**
     * The best match across [patterns], or null.
     *
     * Ties break on specificity, then on declaration order, so the result is
     * deterministic and a pattern added to the end of the list can never
     * silently displace one already there.
     */
    fun best(patterns: List<Pattern>, tokens: Tokens): Match? =
        patterns.asSequence()
            .mapNotNull { match(it, tokens) }
            .maxWithOrNull(compareBy<Match> { it.pattern.specificity }
                .thenByDescending { patterns.indexOf(it.pattern) })

    private fun walk(
        terms: List<Term>,
        ti: Int,
        tokens: List<Token>,
        pi: Int,
        caps: Map<String, IntRange>,
    ): Map<String, IntRange>? {
        if (ti == terms.size) return if (pi == tokens.size) caps else null

        return when (val term = terms[ti]) {
            is Term.Literal ->
                if (pi < tokens.size && tokens[pi].text == term.word) {
                    walk(terms, ti + 1, tokens, pi + 1, caps)
                } else {
                    null
                }

            is Term.AnyOf ->
                if (pi < tokens.size && tokens[pi].text in term.words) {
                    walk(terms, ti + 1, tokens, pi + 1, caps)
                } else {
                    null
                }

            is Term.OfKind ->
                if (pi < tokens.size && tokens[pi].kind == term.kind) {
                    walk(terms, ti + 1, tokens, pi + 1, caps)
                } else {
                    null
                }

            is Term.Any ->
                if (pi < tokens.size) walk(terms, ti + 1, tokens, pi + 1, caps) else null

            is Term.Optional ->
                // Present first, absent second. The other order makes an
                // optional word never match when what follows it could also
                // match the word itself.
                walk(listOf(term.term) + terms.drop(ti + 1), 0, tokens, pi, caps)
                    ?: walk(terms, ti + 1, tokens, pi, caps)

            is Term.Capture -> {
                var take = term.min
                while (pi + take <= tokens.size) {
                    val range = pi until pi + take
                    val next = walk(terms, ti + 1, tokens, pi + take, caps + (term.name to range))
                    if (next != null) return next
                    take += 1
                }
                null
            }
        }
    }
}
