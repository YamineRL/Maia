package dev.maia.nlu

import dev.maia.nlu.agent.AgentGrammar
import dev.maia.nlu.agent.ProjectRegistry
import dev.maia.nlu.assistant.AssistantGrammar
import dev.maia.nlu.grammar.IntentPatterns
import dev.maia.nlu.grammar.Match
import dev.maia.nlu.grammar.Matcher
import dev.maia.nlu.text.Normaliser
import dev.maia.nlu.text.Tokens
import dev.maia.nlu.time.TemporalExtractor
import dev.maia.nlu.time.TemporalResolver
import java.time.Clock
import java.time.Duration

/**
 * Transcript in, [Intent] out. The only entry point the app needs.
 *
 * Four passes, in this order and for this reason: normalise so the words are
 * comparable, pull the temporal phrases out so the patterns never have to know
 * about Thursdays, match what is left to decide what was wanted, then resolve
 * the temporal phrases against the clock. Resolution is last because it is the
 * only pass that depends on what time it is, and keeping it there is what lets
 * the other three be tested without one.
 *
 * [clock] is injected for that reason and is not optional. A parser that reads
 * the system clock is a parser whose tests pass until Thursday.
 *
 * [projects] is the M8 agent registry and defaults to empty, which is what
 * every caller before the phone has synced one is. Empty is not a degraded
 * mode: it is the state in which no sentence can be claimed by a project name
 * or by a bare number, so the calendar grammar behaves exactly as it did.
 */
class Parser(clock: Clock, private val projects: ProjectRegistry = ProjectRegistry.EMPTY) {

    private companion object {
        /** Longer than any event name people say; "dinner with mum and dad at luigi's" is seven. */
        const val MAX_TITLE_WORDS = 8
    }

    private val resolver = TemporalResolver(clock)

    fun parse(transcript: String): Intent = read(transcript, followUp = false)

    private fun read(transcript: String, followUp: Boolean): Intent {
        val words = words(transcript)
        val tokens = Normaliser.normalise(transcript)
        if (tokens.isEmpty()) return unparsed(transcript, tokens)

        // Before the temporal pass, not after. An instruction is forwarded to
        // an agent word for word, and by the time the extractor has run,
        // "deploy tonight" has lost the word that made it urgent.
        AgentGrammar.read(tokens, words, transcript, projects)?.let { return it }

        // M9's deterministic assistant actions run here, on the full token
        // list for the same reason the agent grammar does: "set a timer for
        // five minutes" loses its duration to the temporal pass, and "call
        // mum tonight" needs the un-eaten words to know the call is a plan
        // rather than a dial. Whatever these patterns do not claim reaches
        // the calendar grammar exactly as it did before.
        AssistantGrammar.read(tokens, words, transcript, resolver)?.let { return it }

        val temporal = TemporalExtractor.extract(tokens)
        val residue = tokens.without(temporal.consumed)
        val match = Matcher.best(IntentPatterns.all, residue)

        // The catch-all event pattern and a miss are the two ways a sentence
        // arrives here unread, plus one more the agenda grammar produces:
        // agenda.what_do_i_have is a chain of optionals and a free capture,
        // so "what is the capital of france" fits it as well as "what do i
        // have" does. The difference is in what is left over: a real agenda
        // question still names the schedule somewhere in the residue. When
        // it does not, the claim is spurious and the sentence is unread.
        val unread = match == null ||
            match.pattern.name == "create.bare" ||
            (match.pattern.name == "agenda.what_do_i_have" &&
                residue.list.none { it.text in scheduleWords })
        // A sentence nothing claimed that still reads like a question or a
        // request for information is a conversation, not a calendar draft:
        // "why is the sky blue" must never be offered as an event (M9 PRD
        // section 1).
        if (unread && conversational(transcript, tokens, words)) {
            return Intent.Conversation(transcript.trim(), transcript)
        }
        // The verbless event has to earn its card. It used to be the default
        // for anything left over, which made "i want to know which games are
        // on today" an event called "i want to know which games are on".
        // The two mistakes are not the same size: a sentence wrongly sent to
        // the assistant costs one reply, one wrongly drafted costs a card, a
        // discard and often the no-calendar screen. So a bare title that
        // reads like a sentence rather than a name goes to the assistant.
        if (match?.pattern?.name == "create.bare" && !titleLike(residue) && talkable(tokens, words)) {
            return Intent.Conversation(transcript.trim(), transcript)
        }
        // Inside a live conversation a verbless title with no day and no
        // time is a follow-up, not a plan: "the five best museums" after a
        // question about a city asks for a list, and drafting it as an
        // event led straight to the no-day picker. "dentist friday" keeps
        // its card, because the day is what makes it a plan.
        if (followUp && match?.pattern?.name == "create.bare" &&
            temporal.isEmpty && talkable(tokens, words)
        ) {
            return Intent.Conversation(transcript.trim(), transcript)
        }
        if (match == null) return unparsed(transcript, tokens)

        val family = match.pattern.name.substringBefore('.')
        return when (family) {
            "agenda" -> Intent.Agenda(
                resolver.resolveRange(temporal.date, temporal.time),
                transcript,
            )
            "availability" -> Intent.Availability(
                resolver.resolveRange(temporal.date, temporal.time),
                transcript,
            )
            "note" -> note(match, residue, words, temporal, transcript)
            else -> event(match, residue, words, temporal, transcript)
        }
    }

    /**
     * The parse for a sentence said while a conversation is still live.
     *
     * Same grammar, one different fallback: with exchanges the user saw still
     * held (M9 PRD section 4, rule 3), a sentence nothing claimed is a
     * follow-up to that conversation rather than the start of a calendar
     * draft. The assistant is the honest fallback there because it still
     * holds the context "tomorrow" or "and the small one" is continuing;
     * the card is for a phone with nothing to follow up on. Deterministic
     * wins are untouched: a timer or a timed verbless title keeps its
     * outcome, because a follow-up that meant an action still has to show
     * the card. A verbless title with no day or time is the exception.
     */
    fun parseFollowUp(transcript: String): Intent =
        when (val intent = read(transcript, followUp = true)) {
            is Intent.Unparsed ->
                if (transcript.isBlank()) intent else Intent.Conversation(transcript.trim(), transcript)
            else -> intent
        }

    /**
     * The parse for a sentence said while an agent session is on screen.
     *
     * Same grammar, one different fallback: with a session already streaming
     * (M8 PRD section 13 answer 1), a sentence that names no project is the
     * next instruction for that session rather than a calendar sentence, and
     * it is forwarded verbatim. Calling this instead of [parse] is the app
     * saying which surface the user is looking at, which is a fact the grammar
     * cannot read out of the words.
     */
    fun parseAgentTurn(transcript: String): Intent {
        val words = words(transcript)
        val tokens = Normaliser.normalise(transcript)
        if (tokens.isEmpty()) return unparsed(transcript, tokens)
        return AgentGrammar.turn(tokens, words, transcript, projects)
            ?: unparsed(transcript, tokens)
    }

    private fun words(transcript: String): List<String> =
        transcript.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }

    /**
     * True when nothing else claimed the sentence and it reads like a
     * question or a request for information, which is the whole of
     * [Intent.Conversation]'s brief.
     *
     * Two guards keep the fallback honest. A single word is never a
     * conversation: "spotify" is a handoff that missed its verb, not a
     * question. And a sentence carrying agent vocabulary stays with whatever
     * the earlier passes made of it, because "what is project seven doing" is
     * M8's question to answer and a missed address is better as a draft than
     * as a prompt to the devbox.
     */
    private fun conversational(transcript: String, tokens: Tokens, words: List<String>): Boolean {
        if (!talkable(tokens, words)) return false
        val first = tokens[0].text
        return first in questionOpeners ||
            (first == "tell" && tokens.list.getOrNull(1)?.text == "me") ||
            transcript.trimEnd().endsWith("?")
    }

    /** The two guards above, shared with the verbless-event check. */
    private fun talkable(tokens: Tokens, words: List<String>): Boolean =
        words.size >= 2 && tokens.list.none { it.text in agentWords }

    /**
     * True when what is left once the times are gone could be the name of
     * an event: "lunch with sam", "dentist", "call with the bank". False
     * when it opens the way a sentence does ("i want", "use the", "check
     * the"), opens the way a follow-up does ("and of", "for the"), asks
     * about the world ("weather", "score"), runs too long to be a name
     * anyone reads back, or is only the particles a date stranded
     * ("for", "at the").
     */
    private fun titleLike(residue: Tokens): Boolean {
        val rest = residue.list.map { it.text }
        if (rest.isEmpty() || rest.size > MAX_TITLE_WORDS) return false
        if (rest.all { it in strandedWords }) return false
        if (rest.first() in sentenceOpeners) return false
        if (rest.first() in continuationWords) return false
        return rest.none { it in worldWords }
    }

    /**
     * Words an event name does not start with. Pronouns and the verbs of a
     * request: nobody's lunch is called "i want", and "use the devbox to
     * check the weather" is an instruction to someone.
     */
    private val sentenceOpeners = setOf(
        "i", "im", "i'm", "id", "i'd", "ive", "i've", "ill", "i'll", "we", "you", "it", "its", "it's",
        "they", "he", "she", "this", "that", "there", "use", "check", "find", "look", "search",
        "tell", "show", "give", "get", "let", "lets", "let's", "want", "need", "wonder",
        "wondering", "know", "ask", "google", "translate", "say", "read", "play", "open",
    )

    /** Topics that are questions about the world, never the name of a plan. */
    private val worldWords = setOf(
        "weather", "forecast", "temperature", "raining", "news", "headlines", "score", "scores",
        "result", "results", "price", "prices", "stock", "stocks", "meaning", "definition",
    )

    /** What a removed date leaves behind; a title made only of these is no title. */
    private val strandedWords = setOf(
        "for", "at", "on", "in", "the", "a", "an", "to", "by", "of", "and", "from", "until",
    )

    /**
     * Words a follow-up opens with and an event name never does: connectives
     * and the stranded-date particles minus the articles. "And of germany"
     * and "for the same one" continue a thought already in the air, while
     * "the who tickets" is a name that happens to start with "the".
     */
    private val continuationWords = setOf(
        "and", "or", "but", "so", "then", "also", "plus",
        "for", "to", "of", "from", "by", "until", "at", "on", "in",
        "with", "about",
    )

    private val questionOpeners = setOf(
        "what", "whats", "what's", "who", "whos", "who's", "whom", "whose",
        "when", "whens", "when's", "where", "wheres", "where's", "why", "whys",
        "why's", "how", "hows", "how's", "which", "is", "are", "was", "were",
        "do", "does", "did", "can", "could", "would", "should", "explain",
        "define", "describe",
    )

    private val agentWords = setOf(
        "project", "projects", "repo", "repository", "agent", "agents",
        "devin", "maia", "fusion",
    )

    /**
     * The vocabulary a real agenda question still has in the residue once
     * the temporal words are gone: "what do i have", "whats on", "whats my
     * day look like". Without one of these the `what` sentence was asking
     * about the world, not the calendar.
     */
    private val scheduleWords = setOf(
        "agenda", "schedule", "calendar", "day", "week", "weekend",
        "have", "got", "on", "up", "plan", "plans", "meeting", "meetings",
        "event", "events", "appointment", "appointments", "happening",
        "going", "doing", "free", "busy", "available", "booked", "around",
        "anything", "something", "look",
    )

    private fun note(
        match: Match,
        residue: Tokens,
        words: List<String>,
        temporal: dev.maia.nlu.time.Temporal,
        transcript: String,
    ): Intent {
        val body = text(match, residue, words, "body")
            ?: return unparsed(transcript, residue)
        val at = resolver.resolve(temporal.date, temporal.time, temporal.duration)
        return Intent.CaptureNote(
            body = Field(body.first, Provenance.Heard, body.second),
            remindAt = at?.start,
            transcript = transcript,
        )
    }

    private fun event(
        match: Match,
        residue: Tokens,
        words: List<String>,
        temporal: dev.maia.nlu.time.Temporal,
        transcript: String,
    ): Intent {
        val title = text(match, residue, words, "title")
        // A sentence with a verb and nothing after it is not an event yet.
        if (title == null) return unparsed(transcript, residue)
        val resolved = resolver.resolve(temporal.date, temporal.time, temporal.duration)
        val draft = EventDraft(
            title = Field(title.first, Provenance.Heard, title.second),
            // No time at all means now, marked inferred, so the card opens on
            // something the user can push rather than on an empty field.
            start = resolved?.start ?: Field(resolver.now(), Provenance.Inferred),
            duration = resolved?.duration ?: Field(Duration.ofHours(1), Provenance.Inferred),
            allDay = resolved?.allDay ?: false,
            transcript = transcript,
        )
        return Intent.CreateEvent(draft)
    }

    /**
     * The fallback, and it is a full draft rather than a failure.
     *
     * Whatever time was in the sentence is kept if it resolved, because half
     * an understanding is still worth putting on the card.
     */
    private fun unparsed(transcript: String, tokens: Tokens): Intent {
        val temporal = TemporalExtractor.extract(tokens)
        val resolved = resolver.resolve(temporal.date, temporal.time, temporal.duration)
        return Intent.Unparsed(
            EventDraft(
                title = Field(transcript.trim(), Provenance.Heard),
                start = resolved?.start ?: Field(resolver.now(), Provenance.Inferred),
                duration = resolved?.duration ?: Field(Duration.ofHours(1), Provenance.Inferred),
                allDay = resolved?.allDay ?: false,
                transcript = transcript,
            ),
        )
    }

    /**
     * The captured run as the user said it, plus the span behind it.
     *
     * Read back out of [words], the original sentence, rather than out of the
     * tokens. The normaliser turns "one to one with priya" into "1 to 1 with
     * priya", which is right for a time and wrong for a title, and a title is
     * the one field the user will read back word for word.
     */
    private fun text(
        match: Match,
        residue: Tokens,
        words: List<String>,
        name: String,
    ): Pair<String, IntRange>? {
        val range = match.capture(name) ?: return null
        val taken = residue.list.slice(range)
        if (taken.isEmpty()) return null
        val span = taken.first().span.first..taken.last().span.last
        if (span.last >= words.size) return null
        return words.slice(span).joinToString(" ") to span
    }
}
