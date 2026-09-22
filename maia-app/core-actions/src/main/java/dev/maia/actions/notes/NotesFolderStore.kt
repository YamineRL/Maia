package dev.maia.actions.notes

import android.content.Context
import androidx.core.content.edit
import java.time.LocalDate

/**
 * Where the picked folder, and the shortcut to today's file inside it, are
 * kept between invocations.
 *
 * An interface rather than a `SharedPreferences` field on the writer, for the
 * reason [dev.maia.actions.TargetStore] gives in the same words: the
 * alternative is a hidden global that no test can reach. [FakeNotes] keeps its
 * folder in a var; the real writer has to persist, because a tree grant taken
 * once is expected to last for the life of the install and the user must not
 * be asked to pick a folder twice.
 *
 * Item I7 fixes the file name as `maia-notes` and its contents as the tree
 * `Uri` string plus a cached child `Uri` per date. It is not backed up:
 * `allowBackup` is already false, and a tree `Uri` restored onto another
 * device would name a grant that device never took.
 *
 * Two halves, and they fail differently, which is why they are separate
 * methods rather than one blob:
 *
 * - The **tree** is user intent. Losing it means asking the user to pick a
 *   folder again, which is the thing gate G2 exists to make sure never
 *   happens on its own.
 * - The **child** is a cache and nothing more. Losing it costs one provider
 *   query. Trusting it too far costs correctness, which is gate G8: a sync
 *   client that deletes and recreates today's file underneath may leave this
 *   `Uri` pointing at a ghost. Every caller must be able to drop it and
 *   re-resolve, so [clearCachedChild] exists and is not optional.
 */
interface NotesFolderStore {

    /** The persisted tree `Uri` string, or null if no folder has been picked. */
    fun treeUri(): String?

    /** Remember the tree the picker returned. Called once per pick. */
    fun setTreeUri(uri: String)

    /**
     * Forget the tree, after the grant is released or found to be gone.
     *
     * Drops the cached child with it: a child `Uri` under a tree Maia no
     * longer holds is not a shortcut, it is a way to write into a folder the
     * user revoked.
     */
    fun clearTree()

    /**
     * The document `Uri` for [date]'s file, if one was cached, saving the
     * child query in brief section 4 step 2.
     *
     * Keyed by date because the cache holds exactly one day: see
     * [setCachedChild].
     */
    fun cachedChild(date: LocalDate): String?

    /**
     * Cache [uri] as [date]'s file, dropping any entry for another date.
     *
     * One entry, not a map, and brief section 4 step 5 says so: the only file
     * ever written is today's, yesterday's entry can never be used again, and
     * a growing map of stale document `Uri`s is a set of ghosts waiting for
     * G8.
     */
    fun setCachedChild(date: LocalDate, uri: String)

    /** Drop the cached child, after a write against it failed. */
    fun clearCachedChild()

    companion object {

        /** The obvious implementation, kept out of the writer itself. */
        fun sharedPreferences(context: Context): NotesFolderStore = object : NotesFolderStore {
            private val prefs = context.applicationContext
                .getSharedPreferences(FILE, Context.MODE_PRIVATE)

            override fun treeUri(): String? =
                prefs.getString(KEY_TREE, null)?.takeIf { it.isNotEmpty() }

            override fun setTreeUri(uri: String) = prefs.edit { putString(KEY_TREE, uri) }

            override fun clearTree() = prefs.edit {
                remove(KEY_TREE)
                remove(KEY_CHILD_DATE)
                remove(KEY_CHILD_URI)
            }

            override fun cachedChild(date: LocalDate): String? {
                if (prefs.getString(KEY_CHILD_DATE, null) != date.toString()) return null
                return prefs.getString(KEY_CHILD_URI, null)?.takeIf { it.isNotEmpty() }
            }

            override fun setCachedChild(date: LocalDate, uri: String) = prefs.edit {
                putString(KEY_CHILD_DATE, date.toString())
                putString(KEY_CHILD_URI, uri)
            }

            override fun clearCachedChild() = prefs.edit {
                remove(KEY_CHILD_DATE)
                remove(KEY_CHILD_URI)
            }
        }

        /** Item I7. Named here so nothing else has to spell it. */
        const val FILE = "maia-notes"

        private const val KEY_TREE = "tree_uri"
        private const val KEY_CHILD_DATE = "child_date"
        private const val KEY_CHILD_URI = "child_uri"
    }
}

/**
 * A [NotesFolderStore] with nothing behind it, for tests and previews.
 *
 * In the main source set beside [FakeNotes], for the reason
 * [dev.maia.actions.FakeCalendars] gives: a preview cannot see test source.
 */
class InMemoryNotesFolderStore(tree: String? = null) : NotesFolderStore {
    private var tree: String? = tree
    private var childDate: LocalDate? = null
    private var childUri: String? = null

    override fun treeUri(): String? = tree

    override fun setTreeUri(uri: String) {
        tree = uri
    }

    override fun clearTree() {
        tree = null
        clearCachedChild()
    }

    override fun cachedChild(date: LocalDate): String? =
        childUri.takeIf { childDate == date }

    override fun setCachedChild(date: LocalDate, uri: String) {
        childDate = date
        childUri = uri
    }

    override fun clearCachedChild() {
        childDate = null
        childUri = null
    }
}
