package dev.maia.nlu.assistant

import dev.maia.nlu.Intent

/**
 * A weather question, recognised well enough to open a forecast when nothing
 * on the phone can answer it.
 *
 * Weather goes to the devbox first, which can look it up. When the devbox is
 * out of reach the phone's own model has no live data and can only say so,
 * which answers nothing. A forecast page for the place the user named is the
 * useful thing left, so the driver asks this before the model.
 *
 * [of] returns an [Intent.OpenWeb] search, "weather in Paris tomorrow", or
 * null when the sentence is not about the weather. With no place named the
 * search uses [home], the city set in Settings, and without that it is just
 * "weather" and the search engine picks the place.
 */
object WeatherAsk {

    private val topic = Regex("""\b(weather|forecast|temperature|raining|rain|snowing|snow|umbrella)\b""")

    // The place is what follows "in", "for" or "at", up to a time phrase or
    // the end. Letters, spaces, dots, apostrophes and hyphens only, so
    // "St. John's" and "Aix-en-Provence" survive and a number never does.
    private val place = Regex(
        """\b(?:in|for|at)\s+([a-z][a-z .'-]*?)(?=\s+(?:$DAYS|this|next|on|right|now|at the moment|like|please|then)\b|\s*[?.!,]|\s*$)""",
    )

    private val day = Regex("""\b($DAYS|this weekend|this week|next week)\b""")

    /** Words after "in" or "for" that are times, not places. */
    private val notPlaces = setOf(
        "the morning", "the afternoon", "the evening", "the night", "the weekend",
        "a bit", "a while", "an hour", "now", "later", "general",
    ) + DAY_WORDS

    fun of(text: String, home: String? = null): Intent.OpenWeb? {
        val said = text.lowercase().trim()
        val subject = topic.find(said) ?: return null
        // Only a place named after the weather word: "use the devbox in the
        // office to check the weather" must not look up "the office".
        val where = place.findAll(said, subject.range.first)
            .map { it.groupValues[1].trim().trimEnd('.', '\'', '-') }
            .firstOrNull { it.isNotEmpty() && it !in notPlaces && !it.startsWith("the ") }
            ?.let(::titled)
            ?: home?.trim()?.takeIf { it.isNotEmpty() }
        val `when` = day.find(said)?.groupValues?.get(1)
        val query = buildString {
            append("weather")
            if (where != null) append(" in ").append(where)
            if (`when` != null) append(' ').append(`when`)
        }
        return Intent.OpenWeb(query, text)
    }

    private fun titled(place: String): String =
        place.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
}

private val DAY_WORDS = listOf(
    "today", "tonight", "tomorrow",
    "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
)
private val DAYS = DAY_WORDS.joinToString("|")
