package dev.maia.app.screens

import dev.maia.app.R
import dev.maia.app.agent.Chooser
import dev.maia.transport.ProjectEntry
import dev.maia.transport.ProjectState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three ways into one screen, sections 5.9, 5.10 and 5.11.
 *
 * The list under all three is identical and nothing on it ranks anything,
 * which is the property these tests exist to hold: the day a highlight or a
 * sort by recency is added, one of these fails.
 */
class ChooserCopyTest {

    private fun entry(number: Int, name: String) =
        ProjectEntry(number, name, "/home/u/$name", ProjectState.ACTIVE)

    private val all = listOf(entry(3, "openbrowser"), entry(7, "maia"), entry(11, "openbrowser-ai"))

    @Test
    fun `a name that matched nothing says what to do before what went wrong`() {
        val heading = ChooserCopy.heading(Chooser("open browser", emptyList(), all, "run the tests"))
        assertEquals(R.string.m8_list_eyebrow, heading.eyebrow)
        assertEquals(R.string.m8_list_miss_title, heading.title)
        assertEquals(R.string.m8_list_miss_body, heading.body)
        assertTrue("the user can see what the recogniser made of the name", heading.youSaid)
        assertNull(heading.quantity)
        assertNull(heading.titleNumber)
    }

    /**
     * Two matches is prose, three is a count. English in Android has only
     * `one` and `other`, so the `two` case cannot live inside the plurals
     * resource and is chosen here instead.
     */
    @Test
    fun `exactly two matches gets the written out title and no quantity`() {
        val heading = ChooserCopy.heading(
            Chooser("openbrowser", listOf(all[0], all[2]), all, "go"),
        )
        assertEquals(R.string.m8_ambiguous_title_two, heading.title)
        assertNull("the two case is a plain string, not a plurals lookup", heading.quantity)
        assertEquals(R.string.m8_ambiguous_body, heading.body)
    }

    @Test
    fun `three matches gets the plurals resource and the count to resolve it`() {
        val heading = ChooserCopy.heading(Chooser("open", all, all, "go"))
        assertEquals(R.plurals.m8_ambiguous_title, heading.title)
        assertEquals(3, heading.quantity)
    }

    /**
     * Section 5.9. The title names the number the user said and the body names
     * the highest one there is, which is the fastest way to tell "I misspoke"
     * from "the list is older than I thought".
     */
    @Test
    fun `a number that does not exist names it and names the top of the list`() {
        val heading = ChooserCopy.heading(Chooser("41", emptyList(), all, null, badNumber = 41))
        assertEquals(R.string.m8_no_project_title, heading.title)
        assertEquals(41, heading.titleNumber)
        assertEquals(R.string.m8_no_project_body, heading.body)
        assertEquals(11, heading.bodyNumber)
    }

    /**
     * `YOU SAID 41` over `There is no project 41` is the same fact twice. The
     * heard words are worth showing when a name failed and not when the
     * recogniser produced the number that is already in the title.
     */
    @Test
    fun `the heard words are not repeated over a number`() {
        assertFalse(
            ChooserCopy.heading(Chooser("41", emptyList(), all, null, badNumber = 41)).youSaid,
        )
    }

    /** One list, no labels, when there is nothing to shortlist. */
    @Test
    fun `a miss draws one unlabelled group holding every project`() {
        val groups = ChooserCopy.groups(Chooser("open browser", emptyList(), all, "go"))
        assertEquals(1, groups.size)
        assertNull(groups.single().label)
        assertEquals(all, groups.single().rows)
    }

    /**
     * The shortlist first so the decision is one glance, everything else below
     * so a user who was misheard entirely has the escape without going back a
     * screen. A project in the shortlist is not repeated underneath it: a row
     * in both groups would make the user check whether the two are the same
     * project.
     */
    @Test
    fun `an ambiguous name splits the list in two and repeats nothing`() {
        val groups = ChooserCopy.groups(Chooser("openbrowser", listOf(all[0], all[2]), all, "go"))
        assertEquals(2, groups.size)
        assertEquals(R.string.m8_ambiguous_matches_label, groups[0].label)
        assertEquals(listOf(3, 11), groups[0].rows.map { it.number })
        assertEquals(R.string.m8_ambiguous_all_label, groups[1].label)
        assertEquals(listOf(7), groups[1].rows.map { it.number })
    }

    /** Rule 11: the order is the registry's, which is by number, always. */
    @Test
    fun `the list is not reordered here`() {
        val shuffled = listOf(all[1], all[0], all[2])
        assertEquals(
            shuffled,
            ChooserCopy.groups(Chooser("x", emptyList(), shuffled, null)).single().rows,
        )
    }

    /** A blank hold is not a hold. An empty `Held:` line would be a lie with a colon on it. */
    @Test
    fun `only a real held instruction is shown`() {
        assertEquals("run the tests", ChooserCopy.held(Chooser("x", emptyList(), all, "run the tests")))
        assertNull(ChooserCopy.held(Chooser("x", emptyList(), all, "   ")))
        assertNull(ChooserCopy.held(Chooser("x", emptyList(), all, null)))
    }

    /**
     * A single match never reaches this screen: it is a project, and the
     * driver sends to it. If one ever did, it is a miss and not an ambiguity,
     * because there is nothing to choose between.
     */
    @Test
    fun `one match is not an ambiguity`() {
        val heading = ChooserCopy.heading(Chooser("maia", listOf(all[1]), all, "go"))
        assertEquals(R.string.m8_list_miss_title, heading.title)
        assertEquals(1, ChooserCopy.groups(Chooser("maia", listOf(all[1]), all, "go")).size)
    }
}
