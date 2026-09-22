package dev.maia.app.notes

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import dev.maia.actions.notes.SafNotes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The system folder picker, and the one place a picked tree reaches [SafNotes].
 * M4 brief section 2.1, row 7.
 *
 * **Why an Activity, and which.** `ACTION_OPEN_DOCUMENT_TREE` returns its Uri
 * as an activity result, and the only thing that can receive one is an
 * Activity that registered for it before it was started. `MaiaSession` holds a
 * `Dialog`, not an Activity, so it cannot host this; it hands the no-folder
 * screen over to `MainActivity` (`needsActivityHost`), which registers one of
 * these, and `SettingsActivity` registers the other. Build it in a property
 * initializer, as `registerForActivityResult` requires.
 *
 * **No initial Uri, ever.** [launch] passes `null`, so no `EXTRA_INITIAL_URI`
 * is sent: Maia suggests no place, and the picker opens where the system
 * chooses (privacy V4).
 *
 * **Only the returned Uri.** The result goes to [SafNotes.useFolder] untouched,
 * which takes the persistable grant and releases the previous tree. Nothing
 * else in the app calls `useFolder`.
 *
 * **A cancel is nothing.** A null result sends nothing and calls nothing, so
 * the screen that opened the picker is exactly as it was, with no fault and no
 * haptic: backing out is not a failure (copy section 2).
 *
 * @param notes read lazily, because the Activity has no Context yet when this
 *   is constructed.
 * @param onPicked called on the main thread after `useFolder` has run.
 *   `taken` is false when taking the grant threw, in which case nothing was
 *   stored and the caller should re-read rather than assume.
 */
class FolderPicker(
    private val activity: ComponentActivity,
    private val notes: () -> SafNotes,
    private val onPicked: suspend (taken: Boolean) -> Unit,
) {
    private val launcher =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
            if (tree == null) return@registerForActivityResult
            activity.lifecycleScope.launch {
                val taken = withContext(Dispatchers.IO) { runCatching { notes().useFolder(tree) }.isSuccess }
                onPicked(taken)
            }
        }

    /**
     * Open the picker. False when no Activity handles the intent, which a
     * GrapheneOS build without DocumentsUI would be; the screen stays put.
     */
    fun launch(): Boolean = try {
        launcher.launch(null)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/**
 * Whether the tree a grant names is still there. Copy section 7: one query on
 * the tree document itself, never its children, so a large folder costs the
 * same as an empty one. Run off the main thread.
 *
 * Only a cursor with a first row counts as there. `DocumentsProvider.query`
 * catches the provider's `FileNotFoundException` and returns null, so a deleted
 * folder is a null cursor and not an exception; an empty cursor or any throw
 * reads the same way. Not yet run on the phone (P6's settings half).
 */
suspend fun treeIsThere(resolver: ContentResolver, tree: Uri): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        resolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
            ?.use { it.moveToFirst() }
            ?: false
    }.getOrDefault(false)
}
