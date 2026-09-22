package dev.maia.nlu.grammar

import dev.maia.nlu.Specificity
import dev.maia.nlu.text.Normaliser
import dev.maia.nlu.text.Token
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatcherTest {

    private fun tokens(s: String) = Normaliser.normalise(s)

    private fun pattern(vararg terms: Term) =
        Pattern("test", Specificity.Medium, *terms)

    @Test
    fun `literals match in order`() {
        val m = Matcher.match(
            pattern(Term.Literal("new"), Term.Literal("event")),
            tokens("new event"),
        )
        assertEquals("test", m?.pattern?.name)
    }

    @Test
    fun `a pattern that does not consume every token fails`() {
        assertNull(
            Matcher.match(pattern(Term.Literal("new")), tokens("new event")),
        )
    }

    @Test
    fun `a pattern is anchored at the first token`() {
        assertNull(
            Matcher.match(pattern(Term.Literal("event")), tokens("new event")),
        )
    }

    @Test
    fun `AnyOf accepts any of its words and nothing else`() {
        val p = pattern(Term.AnyOf("schedule", "book", "add"))
        assertTrue(Matcher.match(p, tokens("book")) != null)
        assertTrue(Matcher.match(p, tokens("add")) != null)
        assertNull(Matcher.match(p, tokens("cancel")))
    }

    @Test
    fun `OfKind matches on the token kind rather than the word`() {
        val p = pattern(Term.Literal("at"), Term.OfKind(Token.Kind.Cardinal))
        assertTrue(Matcher.match(p, tokens("at three")) != null)
        assertNull(Matcher.match(p, tokens("at noon")))
    }

    @Test
    fun `an optional word matches whether or not it is there`() {
        val p = pattern(
            Term.Literal("remind"),
            Term.Optional(Term.Literal("me")),
            Term.Literal("later"),
        )
        assertTrue(Matcher.match(p, tokens("remind me later")) != null)
        assertTrue(Matcher.match(p, tokens("remind later")) != null)
    }

    @Test
    fun `an optional word backtracks when taking it would break the rest`() {
        // "me" can satisfy either the optional or the capture, and the optional
        // is tried first. Taking it there leaves the capture with nothing and
        // its min of one unmet, so the only way this matches at all is if the
        // matcher gives the word back and lets the capture have it.
        val p = pattern(
            Term.Literal("remind"),
            Term.Optional(Term.Literal("me")),
            Term.Capture("what"),
        )
        val m = Matcher.match(p, tokens("remind me"))
        assertEquals(1..1, m?.capture("what"))
    }

    @Test
    fun `a capture records the tokens it took`() {
        val p = pattern(
            Term.Literal("note"),
            Term.Capture("body"),
        )
        val m = Matcher.match(p, tokens("note call the dentist"))
        assertEquals(1..3, m?.capture("body"))
    }

    @Test
    fun `a capture gives up the shortest run that lets the rest match`() {
        val p = pattern(
            Term.Literal("schedule"),
            Term.Capture("title"),
            Term.Literal("with"),
            Term.Literal("sam"),
        )
        val m = Matcher.match(p, tokens("schedule a long lunch with sam"))
        assertEquals(1..3, m?.capture("title"))
    }

    @Test
    fun `a capture with min zero may take nothing`() {
        val p = pattern(Term.Literal("agenda"), Term.Capture("rest", min = 0))
        val m = Matcher.match(p, tokens("agenda"))
        assertTrue(m != null)
        assertNull(m?.capture("rest"))
    }

    @Test
    fun `an abandoned branch leaves no capture behind`() {
        // The first capture attempt takes "with", fails at the literal, and is
        // retried longer. If captures accumulated, "title" would end up holding
        // the shorter run from the dead branch.
        val p = pattern(
            Term.Literal("schedule"),
            Term.Capture("title"),
            Term.Literal("with"),
            Term.Capture("who"),
        )
        val m = Matcher.match(p, tokens("schedule coffee with with sam"))
        assertEquals(1..1, m?.capture("title"))
        assertEquals(3..4, m?.capture("who"))
    }

    @Test
    fun `best prefers the more specific pattern`() {
        val loose = Pattern("loose", Specificity.Low, Term.Capture("all"))
        val tight = Pattern(
            "tight",
            Specificity.High,
            Term.Literal("note"),
            Term.Capture("body"),
        )
        assertEquals(
            "tight",
            Matcher.best(listOf(loose, tight), tokens("note buy milk"))?.pattern?.name,
        )
        assertEquals(
            "tight",
            Matcher.best(listOf(tight, loose), tokens("note buy milk"))?.pattern?.name,
        )
    }

    @Test
    fun `best breaks a specificity tie on declaration order`() {
        val first = Pattern("first", Specificity.Medium, Term.Capture("a"))
        val second = Pattern("second", Specificity.Medium, Term.Capture("b"))
        assertEquals(
            "first",
            Matcher.best(listOf(first, second), tokens("anything at all"))?.pattern?.name,
        )
    }

    @Test
    fun `best returns null when nothing matches`() {
        val p = Pattern("p", Specificity.High, Term.Literal("agenda"))
        assertNull(Matcher.best(listOf(p), tokens("note buy milk")))
    }
}
