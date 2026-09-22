package dev.maia.nlu.grammar

import dev.maia.nlu.text.Normaliser
import dev.maia.nlu.text.Tokens
import dev.maia.nlu.time.TemporalExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The end of the pipe, as far as M1 step 4 goes: a spoken sentence in, the
 * name of the pattern that claimed it out.
 *
 * Each case runs the real normaliser and the real extractor first, because a
 * pattern that only matches hand-built tokens has proved nothing about the
 * sentence anyone will actually say.
 */
class IntentPatternsTest {

    private fun residual(s: String): Tokens {
        val tokens = Normaliser.normalise(s)
        return tokens.without(TemporalExtractor.extract(tokens).consumed)
    }

    private fun claim(s: String): String? =
        Matcher.best(IntentPatterns.all, residual(s))?.pattern?.name

    private fun title(s: String): String? {
        val r = residual(s)
        val m = Matcher.best(IntentPatterns.all, r) ?: return null
        val range = m.capture("title") ?: m.capture("body") ?: return null
        return r.list.slice(range).joinToString(" ") { it.text }
    }

    // ------------------------------------------------------------ the five

    @Test
    fun `a spoken event with a verb is an event`() {
        assertEquals("create.verb", claim("schedule lunch with sam tomorrow at noon"))
        assertEquals("lunch with sam", title("schedule lunch with sam tomorrow at noon"))
    }

    @Test
    fun `a spoken event with no verb is still an event`() {
        assertEquals("create.bare", claim("dentist thursday at three p m"))
        assertEquals("dentist", title("dentist thursday at three p m"))
    }

    @Test
    fun `a note is a note`() {
        assertEquals("note.remind_me_to", claim("remind me to call the plumber"))
        assertEquals("call the plumber", title("remind me to call the plumber"))
    }

    @Test
    fun `an agenda question is an agenda question`() {
        assertTrue(claim("what do i have tomorrow")!!.startsWith("agenda."))
        assertTrue(claim("show me my agenda")!!.startsWith("agenda."))
    }

    @Test
    fun `an availability question is an availability question`() {
        assertTrue(claim("am i free thursday afternoon")!!.startsWith("availability."))
        assertTrue(claim("do i have anything friday")!!.startsWith("availability."))
    }

    @Test
    fun `a sentence that is nothing but a time claims nothing`() {
        // Everything was temporal, so there is no residue to match and no
        // intent to guess. This is the Unparsed case, and the card it leads to
        // is the one that asks rather than the one that invents.
        assertNull(claim("tomorrow at three p m"))
    }

    // ------------------------------------------------------- the tie breaks

    @Test
    fun `a note beats the event that its body would make`() {
        // "call the plumber" is a fine event title, and create.bare matches it.
        // Declaration order is what stops it winning.
        assertEquals("note.remind_me_to", claim("remind me to call the plumber"))
        assertEquals("note.bare", claim("note buy milk"))
    }

    @Test
    fun `a bare event never beats a verb`() {
        assertEquals("create.verb", claim("block out some time for deep work"))
    }

    @Test
    fun `an agenda word inside a title does not make it an agenda`() {
        assertEquals("create.verb", claim("book a calendar review"))
    }

    // ------------------------------------------------------------ the words

    @Test
    fun `an opener is dropped rather than becoming the title`() {
        assertEquals("coffee with mo", title("please schedule coffee with mo tomorrow"))
    }

    @Test
    fun `the temporal words never reach the title`() {
        assertEquals("standup", title("schedule standup tomorrow at nine for thirty minutes"))
    }

    @Test
    fun `a residual token still points at the word it came from`() {
        // Indices are into the residue, spans are into the sentence, and it is
        // the spans the card underlines.
        val r = residual("schedule dentist thursday")
        assertEquals(listOf("schedule", "dentist"), r.list.map { it.text })
        assertEquals(1, r.list[1].span.first)
    }
}
