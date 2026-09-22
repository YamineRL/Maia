package dev.maia.nlu.text

/**
 * The spoken number vocabulary, as a table rather than as a dependency.
 *
 * `dicio-numbers` is the obvious library and it is the wrong trade here: it is
 * en-us and it-it only, it implements formatting rather than the date and
 * duration extraction that is most of what Maia needs, and taking it means a
 * repository entry for a table that fits on one screen. M1 takes the table and
 * leaves the library.
 *
 * The vocabulary Maia needs is small and closed because of what feeds it. The
 * recogniser emits lowercase, unpunctuated English with numbers as words
 * (`docs/M0-brief.md` section 1.4), and the only numbers that appear in a
 * sentence about a calendar are clock times, days of the month, durations and
 * the occasional year. Nothing here needs to say "four hundred and twelve
 * thousand".
 */
internal object SpokenNumbers {

    private val units: Map<String, Int> = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
        "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
        "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19,
        // "eight oh five" is twenty past eight said the short way, and the
        // recogniser writes the zero as a word. Safe to map here only because
        // the o-clock merge has already run and consumed the other "oh".
        "oh" to 0,
    )

    private val tens: Map<String, Int> = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        // Sixty upward never appears in a time or a day of the month, but it
        // does appear in a duration: "ninety minutes".
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )

    private val ordinalUnits: Map<String, Int> = mapOf(
        "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5,
        "sixth" to 6, "seventh" to 7, "eighth" to 8, "ninth" to 9,
        "tenth" to 10, "eleventh" to 11, "twelfth" to 12, "thirteenth" to 13,
        "fourteenth" to 14, "fifteenth" to 15, "sixteenth" to 16,
        "seventeenth" to 17, "eighteenth" to 18, "nineteenth" to 19,
    )

    private val ordinalTens: Map<String, Int> = mapOf(
        "twentieth" to 20, "thirtieth" to 30,
    )

    /** The cardinal this word names on its own, or null if it names none. */
    fun cardinal(word: String): Int? = units[word] ?: tens[word]

    /** True when this word is a tens word that a unit can legally follow. */
    fun isTens(word: String): Boolean = word in tens

    /** The tens value, for joining `twenty` to a following `five`. */
    fun tens(word: String): Int? = tens[word]

    /** The ordinal this word names on its own, or null. */
    fun ordinal(word: String): Int? = ordinalUnits[word] ?: ordinalTens[word]

    /**
     * The unit that can close a compound, so `twenty` plus `five` is 25 and
     * `twenty` plus `first` is the ordinal 21. Only one through nine can
     * close one: `twenty ten` is not English.
     */
    fun closingUnit(word: String): Int? = units[word]?.takeIf { it in 1..9 }

    fun closingOrdinalUnit(word: String): Int? =
        ordinalUnits[word]?.takeIf { it in 1..9 }
}
