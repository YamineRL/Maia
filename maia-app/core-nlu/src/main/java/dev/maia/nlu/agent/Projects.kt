package dev.maia.nlu.agent

/**
 * One project, as `~/.config/maia/projects.json` knows it.
 *
 * The number is the identity and the name is the accelerator, which is the
 * whole of M8 PRD principle B. Numbers are allocated once and never reused, so
 * a number that this registry does not know is not an address that has gone
 * stale: it is a number that was never a project, and the grammar treats it as
 * an ordinary spoken number rather than as a project nobody can name.
 */
data class Project(val number: Int, val name: String)

/**
 * The projects the phone has synced, and the only place a spoken name is
 * turned into one of them.
 *
 * Empty until the phone has synced (step 2 of M8 section 14), and empty is the
 * state every existing caller is in: a [ProjectRegistry] with nothing in it can
 * never claim a sentence by name or by bare number, which is what keeps the
 * calendar grammar behaving exactly as it did before agents existed.
 *
 * **Matching is lenient in one direction only.** The spoken words are case
 * folded and stripped of everything that is not a letter or a digit, then
 * matched as a prefix of a name treated the same way, so `streamz final` finds
 * `streamzFinal` and `co lab` finds `co-lab`. What it will not do is pick. M8
 * PRD section 8 is explicit that the phone never chooses between `openbrowser`
 * and `openbrowser-ai`, and that holds even when the user said `openbrowser`
 * exactly: an exact name that is also the start of another name comes back as
 * [Resolution.Several] and the numbered list is shown. The number always works,
 * which is why the name is allowed to refuse.
 */
class ProjectRegistry(projects: List<Project>) {

    val projects: List<Project> = projects.sortedBy { it.number }

    private val keyed: List<Pair<String, Project>> = this.projects.map { key(it.name) to it }

    val isEmpty: Boolean get() = projects.isEmpty()

    fun byNumber(number: Int): Project? = projects.firstOrNull { it.number == number }

    /** What a run of spoken words resolves to. */
    fun match(words: List<String>): Resolution {
        val spoken = key(words.joinToString(""))
        if (spoken.isEmpty()) return Resolution.None
        // Said in full, and nothing else extends it: that is the project.
        // Said in full but another name continues it, or said only in part:
        // the list, even when one thing is on it. :transport's Registry.byName
        // resolves by this same rule and the two must not drift.
        val exact = keyed.filter { it.first == spoken }.map { it.second }
        val extenders = keyed.filter { it.first.startsWith(spoken) && it.first != spoken }
            .map { it.second }
        if (exact.size == 1 && extenders.isEmpty()) return Resolution.One(exact.single())
        if (exact.size == 1) return Resolution.Several(exact + extenders)
        return if (extenders.isEmpty()) Resolution.None else Resolution.Several(extenders)
    }

    /** The answer to a name, and there are three of them because guessing is not one. */
    sealed interface Resolution {

        /** Exactly one project starts with what was said. */
        data class One(val project: Project) : Resolution

        /** More than one does. The phone shows these and asks, and never picks. */
        data class Several(val projects: List<Project>) : Resolution

        /** None does. The phone shows the whole list. */
        data object None : Resolution
    }

    companion object {

        val EMPTY = ProjectRegistry(emptyList())

        /** Case folded, separators dropped: `streamzFinal` and `streamz final` agree. */
        fun key(raw: String): String =
            raw.lowercase().filter { it.isLetterOrDigit() }
    }
}

/**
 * Which project an utterance was aimed at, including the two honest ways of
 * failing to say.
 *
 * The flow machine switches on this and each case has its own screen: three of
 * them send, one shows a shortlist, one shows the whole list. Nothing here ever
 * means "probably project six".
 */
sealed interface ProjectRef {

    /** "project seven", or a bare "seven" the registry recognises. */
    data class Numbered(val number: Int) : ProjectRef

    /** Words that matched exactly one project. [spoken] is what was said, for the screen. */
    data class Named(val spoken: String, val number: Int) : ProjectRef

    /**
     * Words that matched more than one. [candidates] are their numbers, in
     * order, so the phone can show a shortlist rather than all fifteen.
     */
    data class Ambiguous(val spoken: String, val candidates: List<Int>) : ProjectRef

    /** Words that matched none. The phone shows the numbered list. */
    data class Unknown(val spoken: String) : ProjectRef

    /**
     * No address was said at all.
     *
     * Section 13 answer 1: one project streams at a time, so there is always a
     * current session while the agent surface is on screen, and an instruction
     * with no address belongs to it.
     */
    data object Current : ProjectRef
}
