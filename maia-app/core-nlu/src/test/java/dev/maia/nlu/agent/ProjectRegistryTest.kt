package dev.maia.nlu.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The name half of M8 PRD section 8, on its own.
 *
 * The number half needs no tests here because it is an integer lookup. What
 * needs them is the rule that the registry is allowed to refuse: a name that
 * fits two projects comes back as two, not as the first one, and that is the
 * behaviour a future "helpful" edit would quietly remove.
 */
class ProjectRegistryTest {

    private val registry = Projects.asOf20260917

    @Test
    fun `a spoken name finds the project whose punctuation it lost`() {
        val one = registry.match(listOf("streamz", "final")) as ProjectRegistry.Resolution.One
        assertEquals(14, one.project.number)

        val colab = registry.match(listOf("co", "lab")) as ProjectRegistry.Resolution.One
        assertEquals(2, colab.project.number)
    }

    @Test
    fun `a partial name shows a list of one rather than resolving it`() {
        // "streamz" is not the whole of any project's name, so it is a near
        // miss, and section 8 says a near miss shows the numbered list too.
        // A list of one is a short list, not a resolution: the candidate
        // shortens what the user is shown, it does not skip the asking.
        // Saying "streamz final" in full resolves outright, as does any name
        // nothing else extends.
        val several = registry.match(listOf("streamz")) as ProjectRegistry.Resolution.Several
        assertEquals(listOf(14), several.projects.map { it.number })

        val one = registry.match(listOf("streamz", "final")) as ProjectRegistry.Resolution.One
        assertEquals(14, one.project.number)
    }

    @Test
    fun `a name that fits two projects resolves to both and never to one`() {
        val several = registry.match(listOf("openbrowser")) as ProjectRegistry.Resolution.Several
        assertEquals(listOf(10, 11), several.projects.map { it.number })
    }

    @Test
    fun `an exact name that another name extends is still ambiguous`() {
        // "openbrowser" is the whole of project 10's name and the start of
        // project 11's. Section 8 says the phone never picks between them, and
        // being exactly right about one of them does not change that. The
        // number always works, which is what makes the refusal affordable.
        assertTrue(
            registry.match(listOf("open", "browser")) is ProjectRegistry.Resolution.Several,
        )
    }

    @Test
    fun `a name nobody has resolves to none`() {
        assertEquals(ProjectRegistry.Resolution.None, registry.match(listOf("quicksilver")))
        assertEquals(ProjectRegistry.Resolution.None, registry.match(listOf("streams", "final")))
    }

    @Test
    fun `an empty registry resolves nothing at all`() {
        assertTrue(ProjectRegistry.EMPTY.isEmpty)
        assertEquals(ProjectRegistry.Resolution.None, ProjectRegistry.EMPTY.match(listOf("maia")))
        assertNull(ProjectRegistry.EMPTY.byNumber(7))
    }

    @Test
    fun `a number that was never allocated is not a project`() {
        assertEquals(7, registry.byNumber(7)?.number)
        assertNull("numbers are allocated once, and twenty never was", registry.byNumber(20))
    }
}

/** The fifteen of M8 PRD section 8, as one fixture both agent test files use. */
internal object Projects {

    val asOf20260917 = ProjectRegistry(
        listOf(
            Project(1, "ai-data-extraction"),
            Project(2, "co-lab"),
            Project(3, "escha-amd-port"),
            Project(4, "frenverse"),
            Project(5, "insitu-ai"),
            Project(6, "agentharnessfork"),
            Project(7, "maia"),
            Project(8, "MemoryClip"),
            Project(9, "nomad-workspace"),
            Project(10, "openbrowser"),
            Project(11, "openbrowser-ai"),
            Project(12, "pressenter"),
            Project(13, "sonora"),
            Project(14, "streamzFinal"),
            Project(15, "xfollowers-oss"),
        ),
    )
}
