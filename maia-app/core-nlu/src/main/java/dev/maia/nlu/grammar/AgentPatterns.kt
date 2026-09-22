package dev.maia.nlu.grammar

import dev.maia.nlu.Specificity

/**
 * The patterns for talking to an agent: the address at the front, and the two
 * control utterances that are not an instruction at all.
 *
 * These are matched against the normalised sentence with its temporal phrases
 * still in it, which is the opposite of [IntentPatterns] and is deliberate. An
 * instruction is forwarded to the agent word for word, so "run the tests and
 * tell me what failed at three p m" has to keep its last four words; letting
 * [dev.maia.nlu.time.TemporalExtractor] eat them first would send the agent a
 * sentence the user never said. This module reads the address and stops. It
 * never tries to understand the payload, and there is no pattern here that
 * looks inside one.
 *
 * Every address pattern captures `addr`, one token wide, and the code in
 * [dev.maia.nlu.agent.AgentGrammar] decides what that token is: a spoken
 * number, the first word of a project name, or neither, in which case the
 * sentence was not aimed at an agent and the calendar grammar gets it back
 * untouched. That guard is what keeps "tell me my calendar for monday" an
 * agenda question rather than an instruction to a project called "me".
 *
 * The instruction itself is not read out of the pattern's capture. A project
 * name can be two or three words long and a capture is one token wide, so the
 * payload is taken as everything after the address run, in
 * [dev.maia.nlu.agent.AgentGrammar]. The capture is present so that the
 * pattern can account for the whole sentence, which [Matcher] requires.
 */
object AgentPatterns {

    /**
     * Stopping, and asking what is running. Two things, on purpose.
     *
     * A control word that is heard by mistake calls `interrupt` and discards
     * four minutes of work the user cannot see, so the set is kept at the size
     * where every member is unmistakable. [Pattern] is anchored and has to
     * account for every token, which does most of the work here for free:
     * "stop" is a control utterance and "stop the flaky test from retrying" is
     * not, because the second one leaves five tokens no term can take.
     *
     * Deliberately refused, and each for a reason. "cancel that" is what a user
     * says to a preview card as often as to an agent, and the two live on
     * different screens. "never mind" is a dismissal of whatever is in front of
     * them. "abort" and a bare "status" are not phrases this user says. Any of
     * them can be added the day it is heard in real use, which is the only
     * evidence that should add one.
     */
    val control: List<Pattern> = listOf(
        Pattern(
            "agent.stop",
            Specificity.High,
            IntentPatterns.please,
            Term.AnyOf("stop", "interrupt"),
            Term.Optional(Term.AnyOf("it", "that", "there")),
            Term.Optional(Term.Literal("the")),
            Term.Optional(Term.AnyOf("agent", "run", "job", "task")),
            Term.Optional(Term.Literal("please")),
        ),
        Pattern(
            "agent.status.what",
            Specificity.High,
            IntentPatterns.please,
            Term.AnyOf("what", "whats"),
            Term.Optional(Term.AnyOf("is", "s")),
            Term.Optional(Term.AnyOf("it", "the")),
            Term.Optional(Term.AnyOf("agent", "it")),
            Term.AnyOf("running", "doing"),
        ),
        Pattern(
            "agent.status.anything",
            Specificity.High,
            IntentPatterns.please,
            Term.AnyOf("is", "are"),
            Term.Optional(Term.Literal("there")),
            Term.AnyOf("anything", "any", "something"),
            Term.Optional(Term.AnyOf("agents", "jobs", "projects")),
            Term.Literal("running"),
        ),
    )

    /** Words that introduce a project by name or by number, spoken out loud. */
    val markers: Set<String> = setOf("project", "repo", "repository")

    /**
     * Addressed forms, in the order the first match wins.
     *
     * Order rather than specificity, because the choice here is not "which
     * pattern explained more of the sentence" but "which address did the user
     * actually use", and the guards can reject a pattern that matched. A loop
     * that takes the first pattern whose guard also passes is the honest shape
     * for that; [Matcher.best] would pick a winner that a guard then throws
     * away, with nothing left to fall back to.
     */
    val address: List<Pattern> = listOf(
        // "tell seven to run the tests", "ask fourteen what changed today".
        // The weakest marker of the three, because "tell me my calendar" opens
        // the same way, so its guard never accepts anything but a number or a
        // name the registry already knows.
        Pattern(
            "agent.tell",
            Specificity.High,
            IntentPatterns.please,
            Term.AnyOf("tell", "ask"),
            Term.Optional(Term.AnyOf("project", "repo", "repository")),
            Term.Capture("addr"),
            Term.Optional(Term.AnyOf("to", "please")),
            Term.Capture("instruction", min = 0),
        ),
        // "project seven, run the tests", "repo streamz final what changed".
        Pattern(
            "agent.project",
            Specificity.High,
            IntentPatterns.please,
            Term.AnyOf("project", "repo", "repository"),
            Term.Optional(Term.Literal("number")),
            Term.Capture("addr"),
            Term.Optional(Term.AnyOf("to", "please")),
            Term.Capture("instruction", min = 0),
        ),
        // "agent, run the tests". No project named, so it goes to the session
        // that is already streaming. The instruction is required here: a bare
        // "agent" addresses nothing and asks for nothing.
        Pattern(
            "agent.current",
            Specificity.High,
            IntentPatterns.please,
            Term.Optional(Term.AnyOf("tell", "ask")),
            Term.Optional(Term.AnyOf("the", "this")),
            Term.AnyOf("agent", "fusion"),
            Term.Optional(Term.AnyOf("to", "please")),
            Term.Capture("instruction", min = 1),
        ),
        // "seven, run the tests", and a bare "fourteen" answering the numbered
        // list. No marker at all, so this is the pattern with the most to lose:
        // its guards are in [dev.maia.nlu.agent.AgentGrammar] and they are what
        // stop "twenty minutes of yoga tomorrow" from becoming project twenty.
        Pattern(
            "agent.bare",
            Specificity.Medium,
            IntentPatterns.please,
            Term.Capture("addr"),
            Term.Optional(Term.AnyOf("to", "please")),
            Term.Capture("instruction", min = 0),
        ),
    )
}
