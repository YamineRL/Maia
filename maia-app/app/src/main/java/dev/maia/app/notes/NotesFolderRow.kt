package dev.maia.app.notes

import androidx.annotation.StringRes
import dev.maia.actions.notes.NotesFolder
import dev.maia.app.R

/**
 * What the notes folder row in settings is showing. M4 copy section 5 (D5).
 *
 * Four states, and they are the copy's four tables: a folder held and there, no
 * folder, a folder held but gone, and the moment right after the user removed
 * Maia's access. [Released] only lives as long as the settings screen that
 * pressed the button: once that screen is gone, the next reading is [None],
 * which is what a released grant is.
 *
 * Kept as plain values with no Android in them so the decision can be tested
 * on this box. The Android half, reading the grant and querying the tree, is
 * `SettingsActivity`'s.
 */
sealed interface FolderRow {
    data object None : FolderRow

    /**
     * @param atRoot the tree is the top of a storage volume. Its name would
     *   read `primary`, so the row says what that means instead.
     */
    data class Held(val name: String, val atRoot: Boolean) : FolderRow

    /** Step 0 G9(b): the grant is still listed, the folder it names is not. */
    data class Gone(val name: String, val atRoot: Boolean) : FolderRow

    data object Released : FolderRow
}

/**
 * Pick the row from what the Android side read.
 *
 * @param folder `SafNotes.folder()`: non-null only while the grant is listed.
 * @param treeDocumentId the tree's document id, for [isStorageRoot]. Null when
 *   it could not be read, which names the folder rather than the storage.
 * @param present whether one query on the tree document itself found a row.
 *   Anything else (no cursor, an empty one, a throw) is false, because a
 *   provider answers a deleted document with a null cursor and not an error.
 * @param released the user pressed "Remove Maia's access" on this screen.
 */
fun folderRow(
    folder: NotesFolder?,
    treeDocumentId: String?,
    present: Boolean,
    released: Boolean,
): FolderRow {
    if (folder == null) return if (released) FolderRow.Released else FolderRow.None
    val atRoot = treeDocumentId != null && isStorageRoot(treeDocumentId)
    return if (present) FolderRow.Held(folder.name, atRoot) else FolderRow.Gone(folder.name, atRoot)
}

/**
 * A tree picked at the top of a volume has a document id that ends at the
 * colon (`primary:`). `nameFromDocumentId` names that `primary`, which is true
 * and means nothing to a person, so the row shows `m4_settings_notes_value_root`.
 */
fun isStorageRoot(treeDocumentId: String): Boolean =
    treeDocumentId.contains(':') && treeDocumentId.substringAfter(':').isEmpty()

/** How the value line is inked: a folder's name is high, "None chosen" is low. */
enum class ValueInk { High, Low }

/** What choosing looks like: the loud button, or the quiet `ink.mid` line. */
enum class ChooseWeight { Loud, Mid }

/**
 * The strings for one [FolderRow], as resource ids.
 *
 * @property value the value line. [valueIsName] means it is
 *   `m4_settings_notes_value` filled with the folder's name.
 * @property release whether "Remove Maia's access" is offered.
 */
data class FolderRowCopy(
    @StringRes val value: Int,
    val valueIsName: Boolean,
    val valueInk: ValueInk,
    @StringRes val caption: Int,
    @StringRes val choose: Int,
    val chooseWeight: ChooseWeight,
    val release: Boolean,
)

/**
 * Copy section 5, table by table.
 *
 * After a release the copy gives only the caption. The row still has to offer
 * a way back, so it shows "None chosen" and "Choose a folder" under it, the
 * same as a row that never had a folder.
 */
fun folderRowCopy(row: FolderRow): FolderRowCopy = when (row) {
    is FolderRow.Held -> FolderRowCopy(
        value = if (row.atRoot) R.string.m4_settings_notes_value_root else R.string.m4_settings_notes_value,
        valueIsName = !row.atRoot,
        valueInk = ValueInk.High,
        caption = R.string.m4_settings_notes_access,
        choose = R.string.m4_settings_notes_change_action,
        chooseWeight = ChooseWeight.Mid,
        release = true,
    )
    is FolderRow.Gone -> FolderRowCopy(
        value = if (row.atRoot) R.string.m4_settings_notes_value_root else R.string.m4_settings_notes_value,
        valueIsName = !row.atRoot,
        valueInk = ValueInk.High,
        caption = R.string.m4_settings_notes_gone_caption,
        choose = R.string.m4_folder_gone_choose_action,
        chooseWeight = ChooseWeight.Loud,
        release = true,
    )
    FolderRow.None -> FolderRowCopy(
        value = R.string.m4_settings_notes_none,
        valueIsName = false,
        valueInk = ValueInk.Low,
        caption = R.string.m4_settings_notes_none_caption,
        choose = R.string.m4_settings_notes_choose_action,
        chooseWeight = ChooseWeight.Loud,
        release = false,
    )
    FolderRow.Released -> FolderRowCopy(
        value = R.string.m4_settings_notes_none,
        valueIsName = false,
        valueInk = ValueInk.Low,
        caption = R.string.m4_settings_notes_released,
        choose = R.string.m4_settings_notes_choose_action,
        chooseWeight = ChooseWeight.Loud,
        release = false,
    )
}

/**
 * The strings for the D2 screen (copy section 2), as resource ids.
 *
 * @property bodies read in this order. The gone screen says what happened and
 *   then the V1 sentence, because privacy V1 puts the three facts on every
 *   screen that launches the picker, and "Choose a folder again" launches it.
 */
data class NoFolderCopy(
    @StringRes val title: Int,
    val bodies: List<Int>,
    @StringRes val choose: Int,
)

fun noFolderCopy(gone: Boolean): NoFolderCopy = if (gone) {
    NoFolderCopy(
        title = R.string.m4_folder_gone_title,
        bodies = listOf(R.string.m4_folder_gone_body, R.string.m4_no_folder_body),
        choose = R.string.m4_folder_gone_choose_action,
    )
} else {
    NoFolderCopy(
        title = R.string.m4_no_folder_title,
        bodies = listOf(R.string.m4_no_folder_body),
        choose = R.string.m4_no_folder_choose_action,
    )
}

/**
 * Whether the no-folder screen is the "gone" one, from what the store holds.
 *
 * `FlowState.NoFolder` carries no flag, and adding one would change a state
 * the reducer tests pin. The store tells the two apart well enough:
 * `SafNotes.append` leaves the tree stored when a write finds the folder gone,
 * so a note that failed on a deleted folder arrives here with a tree; a phone
 * that never picked one has none. The case it misses is a grant revoked
 * outside Maia: `SafNotes.folder()` clears the store when it sees that, so the
 * screen says "Choose a folder" where "not there any more" would be truer.
 */
fun folderGone(storedTree: String?): Boolean = !storedTree.isNullOrEmpty()
