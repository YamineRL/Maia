package dev.maia.nlu.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NormaliserTest {

    private fun texts(raw: String) = Normaliser.normalise(raw).list.map { it.text }
    private fun kinds(raw: String) = Normaliser.normalise(raw).list.map { it.kind }

    @Test
    fun `empty input gives no tokens`() {
        assertTrue(Normaliser.normalise("").isEmpty())
        assertTrue(Normaliser.normalise("   ").isEmpty())
    }

    /** The exact shape M0 hands over, from docs/M0-brief.md section 1.4. */
    @Test
    fun `collapses the spelled out meridiem`() {
        assertEquals(listOf("3", "pm", "on", "thursday"), texts("three p m on thursday"))
        assertEquals(
            listOf(Token.Kind.Cardinal, Token.Kind.Meridiem, Token.Kind.Word, Token.Kind.Word),
            kinds("three p m on thursday"),
        )
        assertEquals(listOf("9", "am"), texts("nine a m"))
    }

    @Test
    fun `collapses both spellings of o clock`() {
        assertEquals(listOf("8", "oclock"), texts("eight o clock"))
        assertEquals(listOf("8", "oclock"), texts("eight oh clock"))
        assertEquals(Token.Kind.OClock, Normaliser.normalise("eight o clock")[1].kind)
    }

    @Test
    fun `joins tens to units`() {
        assertEquals(listOf("25"), texts("twenty five"))
        assertEquals(listOf("45", "minutes"), texts("forty five minutes"))
        // A tens word with nothing joinable after it stands on its own.
        assertEquals(listOf("20", "minutes"), texts("twenty minutes"))
    }

    @Test
    fun `joins ordinals including the compound ones`() {
        assertEquals(listOf("the", "3"), texts("the third"))
        assertEquals(listOf("the", "21"), texts("the twenty first"))
        assertEquals(listOf("the", "30"), texts("the thirtieth"))
        assertEquals(Token.Kind.Ordinal, Normaliser.normalise("the twenty first")[1].kind)
    }

    @Test
    fun `reads a bare oh as zero, but only after o clock is gone`() {
        assertEquals(listOf("8", "0", "5"), texts("eight oh five"))
        assertEquals(listOf("8", "oclock"), texts("eight oh clock"))
    }

    @Test
    fun `joins the military hundred`() {
        assertEquals(listOf("1400"), texts("fourteen hundred"))
    }

    /**
     * The year is deliberately left to the grammar. See the class KDoc: this
     * pass does not know whether it is standing in a date slot, and the fix
     * for that is not to teach it.
     */
    @Test
    fun `leaves the year form as two cardinals`() {
        assertEquals(listOf("the", "3", "of", "march", "20", "26"),
            texts("the third of march twenty twenty six"))
    }

    /** The card underlines what it heard, so a merge has to remember both words. */
    @Test
    fun `a merged token spans every word it was built from`() {
        val t = Normaliser.normalise("dinner at twenty five past three p m")
        assertEquals(2..3, t.list.first { it.text == "25" }.span)
        assertEquals(6..7, t.list.first { it.text == "pm" }.span)
        assertEquals(0..0, t[0].span)
    }

    @Test
    fun `spans stay contiguous across the whole sentence`() {
        val t = Normaliser.normalise("three p m on thursday")
        assertEquals(0..4, t.span(0, t.size))
        assertNull(t.span(0, 0))
    }

    @Test
    fun `only numeric kinds carry a value`() {
        val t = Normaliser.normalise("twenty five p m")
        assertEquals(25, t[0].value)
        assertNull(t[1].value)
    }

    @Test
    fun `case and extra whitespace are not the caller's problem`() {
        assertEquals(listOf("3", "pm"), texts("  Three   P   M  "))
    }

    /**
     * The parakeet rescorer rewrites finals as written English: capitals and
     * punctuation. A trailing full stop on a number was the first M8-on-device
     * failure: `seven.` was no cardinal, so `project seven.` could not parse.
     */
    @Test
    fun `edge punctuation is stripped from the word it hangs off`() {
        assertEquals(listOf("project", "7"), texts("Project seven."))
        assertEquals(
            listOf(Token.Kind.Word, Token.Kind.Cardinal),
            kinds("project seven."),
        )
        assertEquals(listOf("7", "run", "the", "tests"), texts("seven, run the tests."))
        assertEquals(listOf("don't"), texts("don't"))
    }

    /** A word that is only punctuation keeps no token slot at all. */
    @Test
    fun `a word that is only punctuation is dropped`() {
        assertEquals(listOf("7"), texts("seven ."))
        assertTrue(Normaliser.normalise("...").isEmpty())
    }

    /**
     * Dropped words leave a gap in the spans on purpose: spans index the raw
     * transcript, so the instruction read-back still lands on the real words.
     */
    @Test
    fun `a dropped word leaves its span behind`() {
        val t = Normaliser.normalise("seven . run")
        assertEquals(0..0, t[0].span)
        assertEquals(2..2, t[1].span)
    }

    /**
     * The rescorer writes digits as often as words: `Project 7. Reply` was
     * the second on-device transcript and it fell to the calendar because no
     * cardinal was made of `7`.
     */
    @Test
    fun `a digit word is already a cardinal`() {
        assertEquals(
            listOf(Token.Kind.Word, Token.Kind.Cardinal, Token.Kind.Word),
            kinds("project 7. reply"),
        )
        assertEquals(7, Normaliser.normalise("project 7")[1].value)
        assertEquals(listOf("in", "2026"), texts("in 2026"))
    }

    @Test
    fun `a digit ordinal is an ordinal`() {
        assertEquals(listOf(Token.Kind.Ordinal), kinds("7th"))
        assertEquals(21, Normaliser.normalise("21st")[0].value)
    }
}
