package dev.maia.app.notes

import dev.maia.actions.notes.NotesFolder
import dev.maia.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M4 row 7: the settings row's four states (copy section 5, D5) and the
 * no-folder screen's two (copy section 2, D2), as the pure functions that pick
 * them. The Compose drawing is checked by eye in the previews and on the phone
 * (P6); what is drawn, and which words, is checked here.
 */
class NotesFolderRowTest {

    private val notes = NotesFolder("content://com.android.externalstorage.documents/tree/primary%3ANotes", "Notes")
    private val root = NotesFolder("content://com.android.externalstorage.documents/tree/primary%3A", "primary")

    @Test
    fun `no grant is none, and no grant after a release on this screen is released`() {
        assertEquals(FolderRow.None, folderRow(null, null, present = false, released = false))
        assertEquals(FolderRow.Released, folderRow(null, null, present = false, released = true))
    }

    @Test
    fun `a held grant is held only when the tree document answered`() {
        assertEquals(FolderRow.Held("Notes", atRoot = false), folderRow(notes, "primary:Notes", present = true, released = false))
        // G9(b): the grant outlives the folder, so a listed grant is not enough.
        assertEquals(FolderRow.Gone("Notes", atRoot = false), folderRow(notes, "primary:Notes", present = false, released = false))
    }

    @Test
    fun `a folder picked again after a release is held, not released`() {
        assertEquals(FolderRow.Held("Notes", atRoot = false), folderRow(notes, "primary:Notes", present = true, released = true))
    }

    @Test
    fun `the top of a volume is named as the top of your storage, not primary`() {
        assertEquals(FolderRow.Held("primary", atRoot = true), folderRow(root, "primary:", present = true, released = false))
        assertEquals(FolderRow.Gone("primary", atRoot = true), folderRow(root, "primary:", present = false, released = false))
        assertEquals(FolderRow.Held("primary", atRoot = false), folderRow(root, null, present = true, released = false))

        assertTrue(isStorageRoot("primary:"))
        assertTrue(isStorageRoot("1A2B-3C4D:"))
        assertFalse(isStorageRoot("primary:Notes"))
        // A folder whose own name ends in a colon is not the volume root.
        assertFalse(isStorageRoot("primary:Notes:"))
        assertFalse(isStorageRoot("primary:Documents/Notes"))
        assertFalse(isStorageRoot("primary"))
        assertFalse(isStorageRoot(""))

        assertEquals(R.string.m4_settings_notes_value_root, folderRowCopy(FolderRow.Held("primary", atRoot = true)).value)
        assertFalse(folderRowCopy(FolderRow.Held("primary", atRoot = true)).valueIsName)
    }

    @Test
    fun `held names the folder, says what access means, and offers a change and a release`() {
        val copy = folderRowCopy(FolderRow.Held("Notes", atRoot = false))
        assertEquals(R.string.m4_settings_notes_value, copy.value)
        assertTrue(copy.valueIsName)
        assertEquals(ValueInk.High, copy.valueInk)
        assertEquals(R.string.m4_settings_notes_access, copy.caption)
        assertEquals(R.string.m4_settings_notes_change_action, copy.choose)
        assertEquals(ChooseWeight.Mid, copy.chooseWeight)
        assertTrue(copy.release)
    }

    @Test
    fun `gone keeps the name, says it is not there, and offers choose again and a release`() {
        val copy = folderRowCopy(FolderRow.Gone("Notes", atRoot = false))
        assertEquals(R.string.m4_settings_notes_value, copy.value)
        assertEquals(R.string.m4_settings_notes_gone_caption, copy.caption)
        assertEquals(R.string.m4_folder_gone_choose_action, copy.choose)
        assertEquals(ChooseWeight.Loud, copy.chooseWeight)
        assertTrue(copy.release)
    }

    @Test
    fun `none and released offer a choose and nothing to release`() {
        val none = folderRowCopy(FolderRow.None)
        assertEquals(R.string.m4_settings_notes_none, none.value)
        assertEquals(ValueInk.Low, none.valueInk)
        assertEquals(R.string.m4_settings_notes_none_caption, none.caption)
        assertEquals(R.string.m4_settings_notes_choose_action, none.choose)
        assertFalse(none.release)

        val released = folderRowCopy(FolderRow.Released)
        assertEquals(R.string.m4_settings_notes_released, released.caption)
        assertEquals(R.string.m4_settings_notes_choose_action, released.choose)
        assertFalse(released.release)
    }

    @Test
    fun `both no-folder screens carry the V1 sentence, since both launch the picker`() {
        val none = noFolderCopy(gone = false)
        assertEquals(R.string.m4_no_folder_title, none.title)
        assertEquals(listOf(R.string.m4_no_folder_body), none.bodies)
        assertEquals(R.string.m4_no_folder_choose_action, none.choose)

        val gone = noFolderCopy(gone = true)
        assertEquals(R.string.m4_folder_gone_title, gone.title)
        assertEquals(listOf(R.string.m4_folder_gone_body, R.string.m4_no_folder_body), gone.bodies)
        assertEquals(R.string.m4_folder_gone_choose_action, gone.choose)
    }

    @Test
    fun `a stored tree is what makes the no-folder screen the gone one`() {
        assertFalse(folderGone(null))
        assertFalse(folderGone(""))
        assertTrue(folderGone(notes.uri))
    }
}
