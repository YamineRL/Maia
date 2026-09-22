package dev.maia.nlu.calc

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/**
 * A safe arithmetic evaluator for [dev.maia.nlu.Intent.Calculate].
 *
 * The expression language is infix arithmetic over doubles: `+ - * / % ^`,
 * parentheses, unary minus, integers and decimals. `%` is remainder, not
 * percent; the grammar writes "15 percent of 200" as `15/100*200`, which is
 * the same thing said in a way this grammar cannot get wrong.
 *
 * Safe means bounded rather than clever. There is no `eval`, no reflection,
 * and nothing to call but the arithmetic itself. The hard limits exist
 * because the input may come from a remote model that was asked for an
 * expression and is not trusted to have produced a small one:
 *
 *  - the source is at most [MAX_LENGTH] characters;
 *  - nesting depth is at most [MAX_DEPTH];
 *  - every intermediate result must stay finite and inside [LIMIT].
 *
 * Division or remainder by zero is an error, not an infinity.
 */
object Calc {

    sealed interface Result
    data class Ok(val value: Double) : Result
    data object Err : Result

    private const val MAX_LENGTH = 64
    private const val MAX_DEPTH = 16
    private const val LIMIT = 1e15

    fun eval(source: String): Result {
        if (source.length > MAX_LENGTH) return Err
        val tokens = tokenize(source) ?: return Err
        val value = Eval(tokens).parse() ?: return Err
        return Ok(value)
    }

    /**
     * The spoken form of a result: whole numbers with no decimal point,
     * fractions capped at six places with the trailing zeros stripped.
     */
    fun format(value: Double): String {
        if (!value.isFinite()) return value.toString()
        if (value == Math.floor(value) && abs(value) < LIMIT) {
            return value.toLong().toString()
        }
        return BigDecimal(value)
            .setScale(6, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
    }

    private sealed interface Tok {
        data class Num(val value: Double) : Tok
        data class Op(val c: Char) : Tok
    }

    private fun tokenize(s: String): List<Tok>? {
        val out = ArrayList<Tok>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || c == '.' -> {
                    var j = i
                    while (j < s.length && (s[j].isDigit() || s[j] == '.')) j++
                    val v = s.substring(i, j).toDoubleOrNull() ?: return null
                    out += Tok.Num(v)
                    i = j
                }
                c in "+-*/%^()" -> {
                    out += Tok.Op(c)
                    i++
                }
                else -> return null
            }
        }
        return out
    }

    /**
     * Recursive descent, four levels:
     *
     * ```
     * expr    := term (('+' | '-') term)*
     * term    := unary (('*' | '/' | '%') unary)*
     * unary   := ('-' | '+') unary | power
     * power   := primary ('^' unary)?
     * primary := number | '(' expr ')'
     * ```
     *
     * `^` sits above unary minus on the right and below it on the left, which
     * is the reading a maths book gives `-2^2` (-4) and `2^-3` (0.125).
     */
    private class Eval(private val t: List<Tok>) {

        private var i = 0

        fun parse(): Double? = expr(0)?.takeIf { i == t.size }

        private fun expr(depth: Int): Double? {
            var v = term(depth) ?: return null
            while (true) {
                val c = op() ?: break
                if (c != '+' && c != '-') break
                i++
                val r = term(depth) ?: return null
                v = bounded(if (c == '+') v + r else v - r) ?: return null
            }
            return v
        }

        private fun term(depth: Int): Double? {
            var v = unary(depth) ?: return null
            while (true) {
                val c = op() ?: break
                if (c != '*' && c != '/' && c != '%') break
                i++
                val r = unary(depth) ?: return null
                v = when (c) {
                    '*' -> bounded(v * r)
                    '/' -> if (r == 0.0) return null else bounded(v / r)
                    else -> if (r == 0.0) return null else bounded(v % r)
                } ?: return null
            }
            return v
        }

        private fun unary(depth: Int): Double? {
            if (depth > MAX_DEPTH) return null
            return when (op()) {
                '-' -> {
                    i++
                    unary(depth + 1)?.unaryMinus()
                }
                '+' -> {
                    i++
                    unary(depth + 1)
                }
                else -> power(depth)
            }
        }

        private fun power(depth: Int): Double? {
            val base = primary(depth) ?: return null
            if (op() == '^') {
                i++
                val e = unary(depth + 1) ?: return null
                return bounded(Math.pow(base, e))
            }
            return base
        }

        private fun primary(depth: Int): Double? {
            if (depth > MAX_DEPTH) return null
            return when (val t = t.getOrNull(i)) {
                is Tok.Num -> {
                    i++
                    bounded(t.value)
                }
                is Tok.Op -> {
                    if (t.c != '(') return null
                    i++
                    val v = expr(depth + 1) ?: return null
                    if (op() != ')') return null
                    i++
                    v
                }
                null -> null
            }
        }

        private fun op(): Char? = (t.getOrNull(i) as? Tok.Op)?.c

        private fun bounded(v: Double): Double? =
            v.takeIf { it.isFinite() && abs(it) <= LIMIT }
    }
}
