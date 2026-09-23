package dev.maia.app.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import dev.maia.actions.notes.SafNotes
import dev.maia.app.EngineHolder
import dev.maia.app.notes.FolderPicker
import dev.maia.app.notes.FolderRow
import dev.maia.app.notes.folderRow
import dev.maia.app.notes.treeIsThere
import dev.maia.app.ui.LocalMaiaColours
import dev.maia.app.ui.MaiaOrbHost
import dev.maia.app.ui.MaiaTheme
import dev.maia.app.ui.maiaColours
import dev.maia.orb.ApertureState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Maia's settings: the notes folder (M4 copy section 5 (D5), brief section
 * 2.1, privacy V4, row 7) and where events go ([EventsTo]).
 *
 * **Why a second Activity** and not a state in the flow: settings is not part
 * of an invocation, the reducer has no business knowing a settings screen is
 * open, and the folder picker needs an Activity to return to. It is not
 * exported; `MainActivity` opens it from the idle screen.
 *
 * **What it reads, every time it comes to the front.** `SafNotes.folder()`,
 * which is the persisted grant list and not the stored string, and then, only
 * if a grant is held, one query on the tree document ([treeIsThere]), because
 * step 0 G9(b) measured that a deleted folder keeps its grant listed. Both off
 * the main thread. The decision is [folderRow], tested on the box.
 *
 * **What it writes.** `useFolder` through [FolderPicker] with the picker's Uri
 * and nothing else, and `forget()` on "Remove Maia's access". Nothing in the
 * folder is touched by either.
 */
class SettingsActivity : ComponentActivity() {

    private val notes by lazy { SafNotes(this) }

    private var row by mutableStateOf<FolderRow?>(null)

    private var sheetOpen by mutableStateOf(false)

    private val prefs by lazy { MaiaPrefs(this) }

    private var eventsTo by mutableStateOf(EventsTo.Phone)

    private var homeCity by mutableStateOf("")

    private var status by mutableStateOf<SettingsStatus?>(null)

    /** Set by a release on this screen and cleared by a pick: [FolderRow.Released]'s one source. */
    private var released = false

    private val picker = FolderPicker(this, { notes }) {
        released = false
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaiaTheme {
                CompositionLocalProvider(LocalMaiaColours provides maiaColours()) {
                    BackHandler(enabled = sheetOpen) { sheetOpen = false }
                    val downloads by EngineHolder.downloads.collectAsState()
                    MaiaOrbHost(ApertureState.Dormant) {
                        SettingsScreen(
                            row = row,
                            sheetOpen = sheetOpen,
                            onChoose = { sheetOpen = true },
                            onRelease = ::release,
                            onSheetChoose = {
                                sheetOpen = false
                                picker.launch()
                            },
                            onSheetDismiss = { sheetOpen = false },
                            eventsTo = eventsTo,
                            onEventsTo = {
                                prefs.eventsTo = it
                                eventsTo = it
                            },
                            homeCity = homeCity,
                            onHomeCity = {
                                homeCity = it
                                prefs.homeCity = it
                            },
                            status = status,
                            downloads = downloads,
                            onDownload = ::download,
                            onPermissions = {
                                startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    /** Re-read on every return: the folder may have been deleted, or the grant cleared, meanwhile. */
    override fun onStart() {
        super.onStart()
        eventsTo = prefs.eventsTo
        homeCity = prefs.homeCity
        lifecycleScope.launch { refresh() }
        lifecycleScope.launch { readStatus() }
        // A finished download changes a row from downloading to ready; re-read then.
        lifecycleScope.launch {
            var before = EngineHolder.downloads.value.keys
            EngineHolder.downloads.collect { now ->
                if ((before - now.keys).isNotEmpty()) readStatus()
                before = now.keys
            }
        }
    }

    private suspend fun readStatus() {
        status = withContext(Dispatchers.IO) { runCatching { readSettingsStatus(this@SettingsActivity) }.getOrNull() }
    }

    private fun download(kind: ModelKind) {
        when (kind) {
            ModelKind.OfflineAnswers -> EngineHolder.fetchLocalModel(this, anyNetwork = true)
            ModelKind.SharperSpeech -> EngineHolder.warmRescorer(this)
            ModelKind.Speech -> EngineHolder.warmEngine(this)
        }
    }

    private fun release() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { notes.forget() } }
            released = true
            refresh()
        }
    }

    private suspend fun refresh() {
        val folder = runCatching { notes.folder() }.getOrNull()
        val tree = folder?.let { runCatching { it.uri.toUri() }.getOrNull() }
        val documentId = tree?.let { runCatching { DocumentsContract.getTreeDocumentId(it) }.getOrNull() }
        val present = tree != null && treeIsThere(contentResolver, tree)
        row = folderRow(folder, documentId, present, released)
    }
}
