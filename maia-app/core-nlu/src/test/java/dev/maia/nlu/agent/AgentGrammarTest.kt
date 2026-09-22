package dev.maia.nlu.agent

import dev.maia.nlu.Intent
import dev.maia.nlu.Parser
import dev.maia.nlu.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * Step 3 of M8 PRD section 14, through the real [Parser].
 *
 * Every case below runs the whole pipe, normaliser included, because a grammar
 * that only works on hand-built tokens has proved nothing about a sentence
 * anyone will say. The clock is the same Saturday the rest of the module uses,
 * so a sentence that mentions a time can be checked against the calendar tests.
 *
 * The file is in three parts and the last one is the important one: what the
 * grammar refuses. An address that fires when it should not sends four minutes
 * of work to the wrong repository, and a control word heard inside an
 * instruction throws four minutes of work away.
 */
class AgentGrammarTest {

    private val zone = ZoneId.of("Europe/Zurich")
    private val clock = Clock.fixed(Instant.parse("2026-09-12T09:30:00Z"), zone)

    /** The phone after it has synced the registry. */
    private val parser = Parser(clock, Projects.asOf20260917)

    /** The phone before it has, which is every caller that predates M8. */
    private val unsynced = Parser(clock)

    private fun instruction(s: String): Intent.AgentInstruction =
        parser.parse(s) as Intent.AgentInstruction

    // ------------------------------------------------------- the address

    @Test
    fun `a marked number takes the rest of the sentence as the instruction`() {
        val heard = instruction("project seven run the tests and tell me what fails")
        assertEquals(ProjectRef.Numbered(7), heard.project)
        assertEquals("run the tests and tell me what fails", heard.instruction.value)
        assertEquals(Provenance.Heard, heard.instruction.provenance)
        assertEquals(2..9, heard.instruction.span)
    }

    @Test
    fun `the word number is allowed between the marker and the number`() {
        assertEquals(
            ProjectRef.Numbered(3),
            instruction("project number three build the port").project,
        )
    }

    @Test
    fun `a bare number is an address when the registry has that project`() {
        // Scenario 1 of section 11, said the way the PRD writes it.
        val heard = instruction("seven run the tests")
        assertEquals(ProjectRef.Numbered(7), heard.project)
        assertEquals("run the tests", heard.instruction.value)
    }

    @Test
    fun `tell and ask address a project by number`() {
        assertEquals(ProjectRef.Numbered(14), instruction("tell fourteen to deploy").project)
        assertEquals("deploy", instruction("tell fourteen to deploy").instruction.value)
        assertEquals(ProjectRef.Numbered(5), instruction("ask five what changed today").project)
    }

    @Test
    fun `a spoken name the registry knows resolves to its number`() {
        // Scenario 2 of section 11, in the case where the recogniser got it
        // right. The case where it does not is two tests below.
        val heard = instruction("streamz final what changed today")
        assertEquals(ProjectRef.Named("streamz final", 14), heard.project)
        assertEquals("what changed today", heard.instruction.value)
    }

    @Test
    fun `the instruction keeps the words the calendar grammar would have eaten`() {
        // The temporal extractor runs after this, and would have taken four of
        // these words away and handed the agent a sentence nobody said.
        val heard = instruction("project seven deploy tonight at three p m")
        assertEquals("deploy tonight at three p m", heard.instruction.value)
    }

    @Test
    fun `a name that fits two projects is reported as both`() {
        val heard = instruction("openbrowser what changed today")
        assertEquals(ProjectRef.Ambiguous("openbrowser", listOf(10, 11)), heard.project)
        assertEquals("what changed today", heard.instruction.value)
    }

    @Test
    fun `a marked name nobody knows is unknown rather than a guess`() {
        val heard = instruction("project quicksilver run the tests")
        assertEquals(ProjectRef.Unknown("quicksilver"), heard.project)
        assertEquals("run the tests", heard.instruction.value)
    }

    @Test
    fun `an address with no instruction is a focus and never an empty prompt`() {
        assertEquals(Intent.AgentFocus(ProjectRef.Numbered(7), "project seven"), parser.parse("project seven"))
        // A bare number on its own is how the numbered list gets answered out
        // loud, which is the other half of scenario 2.
        assertEquals(ProjectRef.Numbered(14), (parser.parse("fourteen") as Intent.AgentFocus).project)
        assertEquals(
            ProjectRef.Named("sonora", 13),
            (parser.parse("sonora") as Intent.AgentFocus).project,
        )
    }

    @Test
    fun `the agent can be addressed with no project at all`() {
        val heard = instruction("tell the agent to run the tests")
        assertEquals(ProjectRef.Current, heard.project)
        assertEquals("run the tests", heard.instruction.value)
        assertEquals(ProjectRef.Current, instruction("agent what changed today").project)
    }

    @Test
    fun `a number works before the phone has ever synced a registry`() {
        // Principle B, at its most literal: the marked number is the address
        // that needs nothing to have loaded first.
        val heard = unsynced.parse("project seven run the tests") as Intent.AgentInstruction
        assertEquals(ProjectRef.Numbered(7), heard.project)
    }

    // ------------------------------------------------------- the controls

    @Test
    fun `stopping is its own intent and has several spoken shapes`() {
        for (said in listOf("stop", "stop it", "stop that", "stop the agent", "interrupt", "interrupt it")) {
            assertTrue("[$said] should stop the agent", parser.parse(said) is Intent.AgentStop)
        }
    }

    @Test
    fun `asking what is running is its own intent`() {
        for (
        said in listOf(
            "what is running",
            "whats running",
            "whats it doing",
            "what is the agent doing",
            "is anything running",
            "are any agents running",
        )
        ) {
            assertTrue("[$said] should ask for status", parser.parse(said) is Intent.AgentStatus)
        }
    }

    // ------------------------------------------------------- the refusals

    @Test
    fun `a control word inside an instruction stays an instruction`() {
        // The expensive mistake in this file. On the agent surface this is an
        // instruction to the current session; off it, it is not agent control
        // at all. Neither reading calls interrupt.
        val said = "stop the flaky test from retrying"
        val heard = parser.parseAgentTurn(said) as Intent.AgentInstruction
        assertEquals(ProjectRef.Current, heard.project)
        assertEquals(said, heard.instruction.value)
        assertTrue(parser.parse(said) !is Intent.AgentStop)
    }

    @Test
    fun `an unaddressed instruction is only an instruction on the agent surface`() {
        val said = "run the tests and tell me what fails"
        val heard = parser.parseAgentTurn(said) as Intent.AgentInstruction
        assertEquals(ProjectRef.Current, heard.project)
        assertEquals(said, heard.instruction.value)
        // Off that surface the same sentence has no address, and a parser that
        // invented one would send the calendar's sentences to a shell.
        assertTrue(parser.parse(said) !is Intent.AgentInstruction)
    }

    @Test
    fun `a number a temporal phrase already claimed is a time`() {
        assertTrue(parser.parse("seven thirty dentist") !is Intent.AgentInstruction)
        assertTrue(parser.parse("book a haircut thursday at three p m") is Intent.CreateEvent)
    }

    @Test
    fun `a number the registry never allocated is not an address`() {
        // "twenty minutes of yoga" is the sentence this guard exists for.
        assertTrue(parser.parse("twenty minutes of yoga tomorrow") !is Intent.AgentInstruction)
        assertTrue(parser.parse("twenty") !is Intent.AgentFocus)
    }

    @Test
    fun `tell me my calendar is still an agenda question`() {
        // The "tell" pattern opens exactly like this line of the seed corpus,
        // and "me" is what stops it: a word that is not a number and not a
        // project name hands the sentence straight back.
        assertTrue(parser.parse("tell me my calendar for monday") is Intent.Agenda)
        assertTrue(parser.parse("what do i have tomorrow") is Intent.Agenda)
        assertTrue(parser.parse("am i free thursday afternoon") is Intent.Availability)
    }

    @Test
    fun `an unknown name is refused outright before the phone has a list to show`() {
        // With no registry there is no numbered list to fall back to, so the
        // honest answer is the one the app already had: a card with the
        // sentence on it.
        assertTrue(unsynced.parse("project quicksilver run the tests") !is Intent.AgentInstruction)
        assertTrue(unsynced.parse("streamz final what changed today") !is Intent.AgentInstruction)
        assertTrue(unsynced.parse("seven run the tests") !is Intent.AgentInstruction)
    }

    @Test
    fun `the seed corpus is untouched by a loaded registry`() {
        // The regression test for the whole feature. Every calendar sentence in
        // the corpus is parsed by a phone that has synced all fifteen projects,
        // and not one of them may become an agent utterance.
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream("corpus/seed.tsv"))
        val utterances = stream.bufferedReader().readLines()
            .filterNot { it.startsWith("#") || it.isBlank() }
            .drop(1)
            .map { it.substringBefore('\t') }
        assertTrue("corpus is suspiciously small: ${utterances.size}", utterances.size >= 60)
        for (said in utterances) {
            val got = parser.parse(said)
            val agent = got is Intent.AgentInstruction || got is Intent.AgentFocus ||
                got is Intent.AgentStop || got is Intent.AgentStatus
            assertTrue("[$said] became $got", !agent)
        }
    }
}
