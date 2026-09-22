package dev.maia.nlu.grammar

import dev.maia.nlu.Specificity

/**
 * Every pattern Maia knows, in the order ties break.
 *
 * These match the sentence with its temporal words already removed, which is
 * why none of them mention days or hours. "lunch with sam tomorrow at noon"
 * arrives here as "lunch with sam", and the four words that made it a Tuesday
 * are the extractor's business. Keeping the two apart is what stops a new
 * phrasing of a date from having to be added to nine patterns.
 *
 * Specificity is about how much the pattern committed to, not how long it is.
 * A pattern that named a verb knows what the user wanted; one that matched
 * whatever was there is guessing, and has to lose every tie it enters.
 */
object IntentPatterns {

    /**
     * Openers people put in front of a sentence that carry no meaning.
     *
     * Internal rather than private because [AgentPatterns] opens its sentences
     * the same way, and two copies of this list is two places to forget one.
     */
    internal val please = Term.Optional(Term.AnyOf("please", "hey", "ok", "okay"))

    val agenda: List<Pattern> = listOf(
        Pattern(
            "agenda.what_do_i_have",
            Specificity.High,
            please,
            // The rescorer writes "what's" with the apostrophe, and the
            // normaliser keeps it as one word, so it needs naming here.
            Term.AnyOf("what", "whats", "what's"),
            Term.Optional(Term.AnyOf("s", "is", "do")),
            Term.Optional(Term.Literal("i")),
            Term.Optional(Term.AnyOf("have", "got")),
            Term.Optional(Term.AnyOf("on", "up")),
            Term.Capture("rest", min = 0),
        ),
        Pattern(
            "agenda.my_schedule",
            Specificity.High,
            please,
            Term.AnyOf("show", "read", "tell"),
            Term.Optional(Term.Literal("me")),
            Term.Optional(Term.AnyOf("my", "the")),
            Term.AnyOf("agenda", "schedule", "calendar", "day"),
            Term.Capture("rest", min = 0),
        ),
        Pattern(
            "agenda.bare",
            Specificity.Medium,
            please,
            Term.Optional(Term.AnyOf("my", "the")),
            Term.AnyOf("agenda", "schedule", "calendar"),
            Term.Capture("rest", min = 0),
        ),
    )

    val availability: List<Pattern> = listOf(
        Pattern(
            "availability.am_i_free",
            Specificity.High,
            please,
            Term.AnyOf("am", "will"),
            Term.Literal("i"),
            Term.Optional(Term.Literal("be")),
            Term.AnyOf("free", "busy", "available", "around"),
            Term.Capture("rest", min = 0),
        ),
        Pattern(
            "availability.anything_on",
            Specificity.High,
            please,
            Term.AnyOf("is", "do"),
            Term.Optional(Term.Literal("i")),
            Term.Optional(Term.AnyOf("have", "there")),
            Term.AnyOf("anything", "something", "much"),
            Term.Capture("rest", min = 0),
        ),
    )

    val note: List<Pattern> = listOf(
        Pattern(
            "note.remind_me_to",
            Specificity.High,
            please,
            Term.Literal("remind"),
            Term.Optional(Term.Literal("me")),
            Term.Optional(Term.Literal("to")),
            Term.Capture("body"),
        ),
        Pattern(
            "note.make_a_note",
            Specificity.High,
            please,
            Term.AnyOf("make", "take", "write", "leave"),
            Term.Optional(Term.AnyOf("a", "me")),
            Term.Optional(Term.Literal("a")),
            Term.Literal("note"),
            Term.Optional(Term.AnyOf("to", "that", "saying", "about")),
            Term.Capture("body"),
        ),
        Pattern(
            "note.bare",
            Specificity.Medium,
            please,
            Term.AnyOf("note", "remember", "jot"),
            // Two, because "jot down that" is three words of filler in a row
            // and one optional only eats the first of them.
            Term.Optional(Term.AnyOf("that", "down", "to")),
            Term.Optional(Term.AnyOf("that", "to", "about")),
            Term.Capture("body"),
        ),
    )

    val createEvent: List<Pattern> = listOf(
        Pattern(
            "create.verb",
            Specificity.High,
            please,
            Term.AnyOf("schedule", "book", "add", "create", "set", "put", "block"),
            Term.Optional(Term.AnyOf("up", "out", "aside")),
            Term.Optional(Term.AnyOf("a", "an", "the", "some")),
            Term.Optional(Term.Literal("new")),
            // "meeting" and "appointment" are deliberately not swallowed here.
            // They read as filler in "book a meeting with priya" and as half
            // the title in "book the meeting room", and since optionals are
            // tried present first, absorbing them gets the second one wrong
            // every time. Leaving them in the title gets the first one mildly
            // long and the second one right, which is the better trade: a
            // title with a word too many is legible, one with a word missing
            // is a different event.
            Term.Optional(Term.AnyOf("event", "entry")),
            Term.Optional(Term.AnyOf("for", "called", "titled")),
            Term.Capture("title"),
            // Particles stranded by the date that used to follow them: "put
            // gym in for tomorrow" loses its tail when the temporal words go,
            // and loses two words of it, not one.
            Term.Optional(Term.AnyOf("in", "on", "down", "for", "at")),
            Term.Optional(Term.AnyOf("in", "on", "for", "at")),
        ),
        // No verb at all. "lunch with sam" is how most events actually get
        // said, and after the temporal words are gone it is also what is left
        // of almost every other sentence, so it is the weakest thing here and
        // loses every tie by construction.
        Pattern(
            "create.bare",
            Specificity.Low,
            please,
            Term.Capture("title"),
        ),
    )

    /**
     * All of them, in the order a tie is broken: the first declared wins.
     *
     * Notes come before events because "remind me to call the plumber" is a
     * note whose body would otherwise make a perfectly good event title, and
     * the reverse mistake is the more annoying one to undo.
     */
    val all: List<Pattern> = agenda + availability + note + createEvent
}
