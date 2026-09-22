package dev.maia.app.screens

import dev.maia.app.R
import dev.maia.app.agent.ListReach

/**
 * The project list when there is no project list, copy section 5.16.
 *
 * Three situations render as "nothing to show" and they are three different
 * facts, and a fourth changes the note above a list that is still drawn. `registry-serve.py` goes out of its way to answer **503 naming the
 * cause** rather than serving an empty list, because an empty project list on
 * a phone is indistinguishable from having lost every project. That
 * distinction was being made on the devbox and thrown away at the last step
 * here, so in the one case where Maia genuinely knows more than "something
 * went wrong" the user got the vaguer screen. This is where it gets it back.
 *
 * What the three are, and why each says what it says:
 *
 * - **Never had a list, and a fetch is running.** A wait, and the only place
 *   the product ever tells the user the list survives the tunnel going down.
 * - **Never had a list, and the fetch failed.** The body says "and nothing
 *   older to fall back on", which is the single sentence separating the first
 *   failure from every later one. The title says what is true on this phone
 *   and does not name a cause nobody observed.
 * - **The machine answered and has none.** A 503 from 4097 means the tunnel is
 *   up, the unit is running and the far end said in as many words that it has
 *   no registry. That is a fact about the devbox, so the phone is specific.
 *
 * The fourth is the same 503 arriving while a list is held, and it is handled
 * by [staleNote] rather than here, because there is a list and it is drawn.
 */
object ListCopy {

    /**
     * A title, a body, and optionally a path under the body.
     *
     * [path] is a file on the user's own computer, which section 5.8 and 5.15
     * allow naming: there is no way to find it except by its name. It stops at
     * the name and never prints a command line.
     */
    data class Empty(val title: Int, val body: Int, val path: Int? = null)

    /** Null when there is a list to draw, so the caller has one thing to check. */
    fun empty(reach: ListReach, held: Boolean): Empty? = when {
        held -> null
        reach == ListReach.Fetching -> Empty(
            R.string.m8_list_first_sync_title,
            R.string.m8_list_first_sync_body,
        )
        reach == ListReach.NoRegistry -> Empty(
            R.string.m8_list_no_registry_title,
            R.string.m8_list_no_registry_body,
            R.string.m8_list_registry_path,
        )
        else -> Empty(R.string.m8_list_none_yet_title, R.string.m8_list_none_yet_body)
    }

    /**
     * The note above a list that could not be refreshed, or null.
     *
     * **It does not say the numbering may be stale, and that is deliberate.**
     * `tunnel/scripts/maia-projects.sh` keeps every allocated number, so
     * numbers are never reused and a project's number never moves. What a
     * stale list actually loses is projects added since it was fetched, which
     * is both smaller and true. The looser sentence would make a user hesitate
     * to say "seven" when seven is still exactly what it was, on the one
     * screen whose entire premise is that the number is the primary key.
     *
     * Two notes and not one. `m8_list_stale_note` promises implicitly that a
     * refresh will fix this, and after a 503 that promise is false: the far
     * end answered, and it will keep answering 503 until somebody runs the
     * script on the devbox. `m8_list_stale_no_registry_note` says both halves,
     * and section 5.16 reuses `m8_list_registry_path` beneath it and leaves
     * `m8_list_sync_action` where it is, because a sync is still the thing to
     * press once that script has been run.
     */
    fun staleNote(reach: ListReach, held: Boolean): Int? = when {
        !held -> null
        reach == ListReach.Stale -> R.string.m8_list_stale_note
        reach == ListReach.StaleNoRegistry -> R.string.m8_list_stale_no_registry_note
        else -> null
    }

    /**
     * Whether `m8_list_registry_path` is drawn under the stale note.
     *
     * Only under `m8_list_stale_no_registry_note`, whose last words are "built
     * at that end by:" and which is the only note that ends in a colon. Under
     * the ordinary stale note the path answers a question nobody asked.
     */
    fun stalePath(reach: ListReach, held: Boolean): Int? =
        if (held && reach == ListReach.StaleNoRegistry) R.string.m8_list_registry_path else null

    /**
     * The orb while the list is on screen, section 5.16's last note.
     *
     * `Listening` on all of them, because the microphone stays open and the
     * user can answer by voice, except during the first fetch, where there is
     * nothing they could usefully say yet. Returned as a boolean rather than
     * an `ApertureState` so `:app` does not have to reach into `:ui-orb` for
     * a copy decision.
     */
    fun thinking(reach: ListReach, held: Boolean): Boolean =
        !held && reach == ListReach.Fetching
}
