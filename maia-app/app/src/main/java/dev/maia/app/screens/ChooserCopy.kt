package dev.maia.app.screens

import dev.maia.app.R
import dev.maia.app.agent.Chooser
import dev.maia.transport.ProjectEntry

/**
 * The project list and its three headings, sections 5.9, 5.10 and 5.11.
 *
 * One screen, three ways in, and the list underneath is identical in all
 * three. That is the design and not an economy: a name that matched nothing, a
 * name that matched several and a number that does not exist are three
 * different mistakes, so they get three different sentences, but the answer to
 * all three is the same numbered list and the microphone is open over it.
 *
 * **Nothing here ranks anything.** On the ambiguous screen the matches are
 * lifted into their own group above a divider, and that is the only difference
 * between them and every other row: same number size, same name style, no
 * highlight, no accent, no bold. A ranking is a guess with a disclaimer on it,
 * and PRD section 8 is that the phone does not guess between two projects on
 * the user's machine.
 *
 * **Ordered by number, always** (rule 11). Never by recency and never by last
 * used, because the number is the primary key and a key that moves is not one.
 */
object ChooserCopy {

    /**
     * What sits above the list.
     *
     * @param eyebrow `WHICH PROJECT?`, the same on all three
     * @param youSaid whether the heard words are shown under M3's `YOU SAID`
     * @param title one of four strings, see [heading]
     * @param quantity non-null when [title] is a plurals resource, and then it
     *   is the number to resolve it with
     * @param titleNumber non-null when [title] takes a number argument
     * @param body the sentence under the title
     * @param bodyNumber non-null when [body] takes a number argument
     */
    data class Heading(
        val eyebrow: Int,
        val youSaid: Boolean,
        val title: Int,
        val quantity: Int? = null,
        val titleNumber: Int? = null,
        val body: Int,
        val bodyNumber: Int? = null,
    )

    /**
     * The heading for a chooser.
     *
     * Three cases, in the order the document defines them:
     *
     * - **A number that does not exist** (5.9). The title names the number the
     *   user said and the body names the highest one there is, which is the
     *   fastest way to tell "I misspoke" from "the list is older than I
     *   thought". The heard words are not shown, because there were none worth
     *   showing: the recogniser produced a number, the number is already in
     *   the title, and `YOU SAID 41` above `There is no project 41` is the
     *   same fact twice.
     * - **A name that matched more than one** (5.11). Not "Did you mean?",
     *   which belongs to a system that is about to pick one if you say
     *   nothing. `m8_ambiguous_body` states the rule to the user's face,
     *   because a user who reads it once understands why they will see this
     *   screen again next week and that it is a decision rather than a
     *   weakness.
     * - **A name that matched nothing** (5.10). The instruction first, in
     *   three words, then what went wrong immediately after. The user is
     *   holding a phone and needs to know what to do.
     *
     * [two] rather than the plurals resource when exactly two match: English
     * in Android has only `one` and `other`, so a `two` case cannot live
     * inside a plurals resource and has to be chosen here. "Two projects
     * match" reads as prose at that length where `2 projects match` reads as a
     * count in a report.
     */
    fun heading(chooser: Chooser): Heading {
        val bad = chooser.badNumber
        return when {
            bad != null -> Heading(
                eyebrow = R.string.m8_list_eyebrow,
                youSaid = false,
                title = R.string.m8_no_project_title,
                titleNumber = bad,
                body = R.string.m8_no_project_body,
                bodyNumber = chooser.all.maxOfOrNull { it.number } ?: 0,
            )
            chooser.matches.size >= 2 -> Heading(
                eyebrow = R.string.m8_list_eyebrow,
                youSaid = true,
                title =
                    if (chooser.matches.size == 2) {
                        R.string.m8_ambiguous_title_two
                    } else {
                        // A plurals resource, which is why [Heading.quantity]
                        // is set beside it. It is the one id in this object
                        // that is not an `R.string`.
                        R.plurals.m8_ambiguous_title
                    },
                quantity = chooser.matches.size.takeIf { it != 2 },
                body = R.string.m8_ambiguous_body,
            )
            else -> Heading(
                eyebrow = R.string.m8_list_eyebrow,
                youSaid = true,
                title = R.string.m8_list_miss_title,
                body = R.string.m8_list_miss_body,
            )
        }
    }

    /** A group of rows, with a label above it or none. */
    data class Group(val label: Int?, val rows: List<ProjectEntry>)

    /**
     * The list, in one group or two.
     *
     * Two only when a shortlist exists, which is the ambiguous screen. The
     * matches go first so the decision is one glance, and the whole list stays
     * underneath so a user who was misheard entirely has the escape without
     * going back a screen. Everything else is one unlabelled group, because a
     * single list with a heading on it is a heading that answers nothing.
     *
     * A project in the shortlist is not repeated below it. The point of the
     * second group is what it is labelled, `EVERYTHING ELSE`, and a row in
     * both groups would make the user check whether the two are the same
     * project.
     */
    fun groups(chooser: Chooser): List<Group> {
        if (chooser.matches.size < 2) return listOf(Group(null, chooser.all))
        val shortlisted = chooser.matches.map { it.number }.toSet()
        return listOf(
            Group(R.string.m8_ambiguous_matches_label, chooser.matches),
            Group(R.string.m8_ambiguous_all_label, chooser.all.filterNot { it.number in shortlisted }),
        )
    }

    /**
     * Whether the held instruction is shown, section 5.10.
     *
     * M4's no-folder shape, because the situation is identical: the words
     * exist, nothing has been sent, and the user is being asked for one
     * missing piece. `m8_list_held_note` says "It goes as soon as you choose",
     * which is deliberately unlike M4's note path where a hold still needs a
     * confirming tap: nothing is being written on the phone, the user already
     * said the instruction out loud, and asking them to confirm twice for
     * something they can interrupt by voice is friction with no safety in it.
     */
    fun held(chooser: Chooser): String? = chooser.held?.takeIf { it.isNotBlank() }
}
