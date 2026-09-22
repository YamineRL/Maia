package dev.maia.nlu.agent

import dev.maia.nlu.Field
import dev.maia.nlu.Intent
import dev.maia.nlu.Provenance
import dev.maia.nlu.grammar.AgentPatterns
import dev.maia.nlu.grammar.Matcher
import dev.maia.nlu.text.Token
import dev.maia.nlu.text.Tokens
import dev.maia.nlu.time.TemporalExtractor

/**
 * The seam between the address and the payload, and nothing else.
 *
 * A spoken instruction to an agent is two things stuck together: which project,
 * and what to do. The first is a grammar problem and is solved here. The second
 * is not a grammar problem at all, it is a string that belongs to the agent, so
 * this file finds where it starts and takes the rest of the sentence verbatim.
 * Nothing below inspects an instruction, and nothing below should ever start.
 *
 * Three rules govern the whole file.
 *
 * **The number is the name** (M8 PRD principle B). A number is the address that
 * always works, because the recogniser cannot mangle `seven` the way it mangles
 * `streamzFinal`. A name is an accelerator that is allowed to fail, and failing
 * means [ProjectRef.Unknown] or [ProjectRef.Ambiguous], never a guess.
 *
 * **A bare number has to earn it.** "seven, run the tests" is an address only
 * when the registry has a project seven and no temporal phrase already claimed
 * the word. Without both guards "twenty minutes of yoga tomorrow" is an
 * instruction to project twenty, which is the exact class of regression the
 * calendar grammar cannot afford. With an empty registry, which is every
 * caller that has not synced, no bare form can fire at all.
 *
 * **When in doubt it is an instruction, not a control word.** A control word
 * heard by mistake calls `interrupt` and throws away work the user cannot see.
 * The control patterns are anchored and must account for every token, so
 * "stop the flaky test from retrying" stays an instruction with the word stop
 * in it, which is what the user meant.
 */
object AgentGrammar {

    /** The longest run of words tried as a project name. Three word names exist, four do not. */
    private const val LONGEST_NAME = 4

    /** Words that never begin a project name, so they never begin an address. */
    private val stopwords = setOf(
        "the", "a", "an", "my", "me", "it", "this", "that", "them", "us", "you", "i",
    )

    /** Filler between the address and the payload. Dropped, and only here. */
    private val glue = setOf("to", "please")

    /**
     * The sentence read as agent control, or null when it is not one.
     *
     * Null is the common answer and is what hands the sentence back to the
     * calendar grammar unchanged.
     */
    fun read(
        tokens: Tokens,
        words: List<String>,
        transcript: String,
        registry: ProjectRegistry,
    ): Intent? {
        if (tokens.isEmpty()) return null
        control(tokens, transcript)?.let { return it }
        return addressed(tokens, words, transcript, registry)
    }

    /**
     * The same read, for a sentence said while an agent session is on screen.
     *
     * The difference is the fallback. On the agent surface there is a session
     * streaming (section 13 answer 1: exactly one), so a sentence with no
     * address is not a mystery, it is the next thing to say to that session and
     * it is forwarded word for word. The cost is real and worth naming: while
     * this surface is open, "what do i have tomorrow" is sent to an agent
     * rather than read off the calendar. That is the price of a modal surface
     * and the user can leave it.
     */
    fun turn(
        tokens: Tokens,
        words: List<String>,
        transcript: String,
        registry: ProjectRegistry,
    ): Intent? {
        if (tokens.isEmpty()) return null
        read(tokens, words, transcript, registry)?.let { return it }
        return instruction(ProjectRef.Current, tokens, 0, words, transcript)
    }

    private fun control(tokens: Tokens, transcript: String): Intent? {
        val match = Matcher.best(AgentPatterns.control, tokens) ?: return null
        return if (match.pattern.name.startsWith("agent.status")) {
            Intent.AgentStatus(transcript)
        } else {
            Intent.AgentStop(transcript)
        }
    }

    /**
     * The first address pattern that matches and whose guard also accepts.
     *
     * Declaration order, not [Matcher.best]. A guard can reject a pattern that
     * matched, and the sentence then has to be offered to the next pattern
     * rather than to nothing, which is what picking a single winner first would
     * leave.
     */
    private fun addressed(
        tokens: Tokens,
        words: List<String>,
        transcript: String,
        registry: ProjectRegistry,
    ): Intent? {
        for (pattern in AgentPatterns.address) {
            val match = Matcher.match(pattern, tokens) ?: continue
            if (pattern.name == "agent.current") {
                val at = match.capture("instruction")?.first ?: continue
                return instruction(ProjectRef.Current, tokens, at, words, transcript)
            }
            val at = match.capture("addr")?.first ?: continue
            val marker = tokens.list.getOrNull(at - 1)?.text
            val (ref, next) = reference(
                tokens = tokens,
                at = at,
                registry = registry,
                // "project" said out loud is the user naming the thing they are
                // addressing, so an unrecognised word after it is a name Maia
                // does not know rather than a sentence about something else.
                // After "tell" it is not, because "tell me my calendar" exists.
                marked = marker in AgentPatterns.markers,
                bare = pattern.name == "agent.bare",
            ) ?: continue
            return instruction(ref, tokens, next, words, transcript)
        }
        return null
    }

    /** The project that was addressed, and the index the payload starts at. */
    private fun reference(
        tokens: Tokens,
        at: Int,
        registry: ProjectRegistry,
        marked: Boolean,
        bare: Boolean,
    ): Pair<ProjectRef, Int>? {
        val list = tokens.list
        val token = list[at]

        if (token.kind == Token.Kind.Cardinal) {
            val number = token.value ?: return null
            // A marked number is an address even when the registry has never
            // heard of it: the user said "project", and being told there is no
            // project twenty is a better answer than a calendar entry.
            if (bare && registry.byNumber(number) == null) return null
            if (bare && claimedByTime(tokens, at)) return null
            return ProjectRef.Numbered(number) to at + 1
        }

        if (token.text in stopwords) return null

        // Longest run first: "streamz final" beats "streamz", which would match
        // as a prefix and address the same project by luck rather than by rule.
        var take = minOf(LONGEST_NAME, list.size - at)
        while (take >= 1) {
            val run = list.subList(at, at + take)
            if (run.all { it.kind == Token.Kind.Word }) {
                val named: ProjectRef? = when (val found = registry.match(run.map { it.text })) {
                    is ProjectRegistry.Resolution.One ->
                        ProjectRef.Named(spoken(run), found.project.number)
                    is ProjectRegistry.Resolution.Several ->
                        ProjectRef.Ambiguous(spoken(run), found.projects.map { it.number })
                    ProjectRegistry.Resolution.None -> null
                }
                if (named != null) {
                    if (bare && claimedByTime(tokens, at)) return null
                    return named to at + take
                }
            }
            take -= 1
        }

        // A name nobody knows, and only when the user said "project" and the
        // phone has a list to show. Before the first sync there is no list, so
        // there is nothing honest to do with the sentence but give it back.
        if (!marked || registry.isEmpty) return null
        return ProjectRef.Unknown(token.text) to at + 1
    }

    private fun spoken(run: List<Token>): String = run.joinToString(" ") { it.text }

    /**
     * True when a temporal phrase already claimed the word at [at].
     *
     * Only the unmarked forms ask, and they ask last, because this runs the
     * temporal extractor a second time and there is no point paying for it on
     * a sentence the cheaper guards have already refused. "seven thirty
     * dentist" is a time and an event; "seven, run the tests" is neither.
     */
    private fun claimedByTime(tokens: Tokens, at: Int): Boolean =
        at in TemporalExtractor.extract(tokens).consumed

    /**
     * The payload, read out of the original words rather than the tokens.
     *
     * The normaliser turns "at three p m" into "3 pm", which is right for a
     * calendar and wrong for something an agent is going to read, so the text
     * comes back from [words] through the spans, exactly as the event title
     * does. An address with nothing after it is [Intent.AgentFocus]: the user
     * moved the stream (section 13 answer 1) or answered the numbered list, and
     * sending an agent an empty prompt would be worse than saying nothing.
     */
    private fun instruction(
        ref: ProjectRef,
        tokens: Tokens,
        from: Int,
        words: List<String>,
        transcript: String,
    ): Intent {
        var i = from
        while (i < tokens.size && tokens[i].text in glue) i += 1
        if (i >= tokens.size) return Intent.AgentFocus(ref, transcript)
        val span = tokens[i].span.first..tokens.list.last().span.last
        if (span.isEmpty() || span.last >= words.size) return Intent.AgentFocus(ref, transcript)
        return Intent.AgentInstruction(
            project = ref,
            instruction = Field(words.slice(span).joinToString(" "), Provenance.Heard, span),
            transcript = transcript,
        )
    }
}
