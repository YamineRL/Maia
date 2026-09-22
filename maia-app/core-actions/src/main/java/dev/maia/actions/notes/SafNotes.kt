package dev.maia.actions.notes

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.UserManager
import android.provider.DocumentsContract
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.time.LocalDate

/**
 * [NoteRepository] over a real `ContentResolver` and `DocumentsContract`, which
 * is M4 brief section 4 and nothing else.
 *
 * The sibling of [dev.maia.actions.ProviderCalendars], including running every
 * provider call on an injected [io] dispatcher: a `DocumentsProvider` call is
 * disk plus a binder round trip and has no business on the main thread.
 *
 * **Nothing in this class has run against a real provider.** It is written
 * against the `spike/saf-notes` readings taken on the Pixel on 2026-09-13 and
 * against `docs/research/M4-R1.md` to `M4-R8.md`, and it is JVM-unreachable
 * exactly like `ProviderCalendars`. What can be pulled out and proved has been:
 * [SafFaults] and [nameFromDocumentId] are pure and are tested.
 *
 * The three rules the spike came back with, and where each one lives:
 *
 * 1. **Query for the name before every `createDocument`.** Gate G4 on the phone
 *    created `2026-09-13 (1).md` beside the existing file rather than returning
 *    it, and a silent duplicate splits the day's notes in two. [findChild] runs
 *    first, always, and [append] only creates when it missed. The pair is not
 *    atomic (M4-R4), so `createDocument` returning null is handled too and the
 *    name it actually produced is what [NoteRef.fileName] reports.
 * 2. **Cache the child `Uri`, and keep the resolve off the confirmation path.**
 *    G6 measured the append itself at a median of 15 ms and 4 to 5 ms cold,
 *    well inside PRD section 13's 150 ms target, while resolving the child by a
 *    children query costs about 345 ms in a folder of 100 files and 0.7 to
 *    2.5 s at 1000, because the provider ignores `selection` and returns every
 *    child. A daily-file folder passes 100 files in four months. So [append]
 *    tries the cached child first and never queries when it hits, and
 *    [prepare] does the resolve when the card opens, while the user is reading
 *    it, rather than under the hold. G8 proved a cached child `Uri` survives
 *    process death (4.32 ms cold through one).
 * 3. **Catch `IllegalArgumentException` beside `FileNotFoundException` and
 *    `SecurityException`.** G8: a cached `Uri` to a deleted file throws
 *    `IllegalArgumentException` wrapping `FileNotFoundException` from the
 *    provider's child check, not a bare `FileNotFoundException`. That is
 *    [SafFaults], and it is where the "re-resolve or say the folder is gone"
 *    decision is made.
 *
 * Two more things the readings forbid, and this class does not do:
 *
 * - It never filters the children query by `_display_name`. `DocumentsProvider`
 *   rejects a selection-style query and `FileSystemProvider` lists every child
 *   regardless; G6 logged `selection IGNORED` at 100 and at 1000 files. The
 *   cursor is scanned here instead. Code that took the first row of a
 *   "filtered" query would append to whichever file the provider listed first.
 * - It never uses `DocumentFile`. `listFiles()` swallows every exception and
 *   returns what it has, so a transient failure reads as "no file" and the next
 *   step creates a duplicate (M4-R5). That is a correctness reason, on top of
 *   G6's 1.6 s and 14 s misses.
 *
 * @param userUnlocked whether the user's credential-encrypted storage is
 *   available. Before the first unlock after a reboot `ExternalStorageProvider`
 *   cannot run, and yet the grant is still listed (M4-R1), so a write that
 *   fails there is a failed write and must never be reported as "folder gone".
 *   [SafFaults] is given this and refuses to say gone while it is false.
 */
class SafNotes(
    private val resolver: ContentResolver,
    private val store: NotesFolderStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val userUnlocked: () -> Boolean = { true },
) : NoteRepository {

    constructor(context: Context) : this(
        context.applicationContext.contentResolver,
        NotesFolderStore.sharedPreferences(context),
        Dispatchers.IO,
        {
            context.applicationContext
                .getSystemService(UserManager::class.java)
                ?.isUserUnlocked ?: true
        },
    )

    // -------------------------------------------------------------- the grant

    /**
     * The folder the user picked, or null when there is none to write to.
     *
     * Read off `getPersistedUriPermissions()`, which is `system_server` state
     * and needs no provider, so this answers before the first unlock too and
     * costs no binder round trip to `ExternalStorageProvider`.
     *
     * **A listed grant does not mean the folder exists.** G9(b) deleted the
     * folder out from under a live grant and the entry stayed listed, with
     * `read=true write=true`, until it was re-picked. A missing folder is
     * therefore discovered by the write and never inferred here.
     *
     * The name is derived from the tree's own document id rather than queried,
     * which keeps this free and, more importantly, keeps it a folder name and
     * never a path (brief section 11, privacy item V2).
     */
    override suspend fun folder(): NotesFolder? = onIo {
        val stored = store.treeUri() ?: return@onIo null
        val uri = runCatching { stored.toUri() }.getOrNull() ?: return@onIo null
        val held = runCatching {
            resolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        }.getOrDefault(false)

        if (!held) {
            // The grant went away: "Clear storage", a release, or the 512 cap
            // pruning it (M4-R1). Keeping the string would leave a folder named
            // on a card that no write can reach.
            store.clearTree()
            return@onIo null
        }

        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return@onIo null
        NotesFolder(uri = stored, name = nameFromDocumentId(docId))
    }

    /**
     * Take the grant the picker returned, and keep it.
     *
     * Called by the folder picker screen (brief row 7) with the `Uri` from
     * `ACTION_OPEN_DOCUMENT_TREE`. It lives here, beside the write, because the
     * rule it carries is a storage rule: **the previous grant is released**, so
     * Maia holds exactly one. There is a per-uid cap of 512 persisted grants
     * and the oldest is pruned when it is passed (M4-R1), and a grant nobody
     * uses is a folder Maia can still write to.
     *
     * Read as well as write: resolving today's file means querying the tree's
     * children, and the children query needs read.
     */
    fun useFolder(tree: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        resolver.takePersistableUriPermission(tree, flags)

        val previous = store.treeUri()
        if (previous != null && previous != tree.toString()) {
            runCatching { resolver.releasePersistableUriPermission(previous.toUri(), flags) }
        }
        store.setTreeUri(tree.toString())
        // A child cached under the old tree is not a shortcut, it is a way to
        // write into a folder the user has just stopped choosing.
        store.clearCachedChild()
    }

    /** Give the grant back and forget the folder. The user asking to stop. */
    fun forget() {
        val stored = store.treeUri()
        if (stored != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { resolver.releasePersistableUriPermission(stored.toUri(), flags) }
        }
        store.clearTree()
    }

    // ------------------------------------------------------------- the write

    /**
     * Rule 2, the half that happens before the user has decided anything:
     * resolve today's file and cache it while the card is on screen.
     *
     * Never creates. A note that is never committed must not leave a file
     * behind, and the create-and-header path is once a day on the commit.
     * Every failure is swallowed: this is a cache warm, and the only thing a
     * failure here costs is the resolve happening later after all.
     */
    override suspend fun prepare(date: LocalDate): Unit = onIo {
        if (store.cachedChild(date) != null) return@onIo
        val tree = tree() ?: return@onIo
        val child = runCatching { findChild(tree, NoteMarkdown.fileName(date)) }.getOrNull()
            ?: return@onIo
        store.setCachedChild(date, child.toString())
    }

    override suspend fun append(note: Note): NoteRef = onIo {
        val plan = NoteMarkdown.plan(note)
            ?: throw NoteWriteFailed("a note with an empty body is never written")
        val tree = tree() ?: throw NotesFolderGone("no notes folder has been chosen")

        // Rule 2. The whole hot path, when it hits: one open, one write, one
        // close, and not a single query.
        store.cachedChild(note.date)?.let { cached ->
            val child = runCatching { cached.toUri() }.getOrNull()
            if (child != null) {
                try {
                    write(child, plan.line)
                    return@onIo NoteRef(plan.fileName, child.toString(), createdFile = false)
                } catch (e: Exception) {
                    // Rule 3. A stale child is the one failure that is not a
                    // failure: drop it and fall through to the resolve.
                    when (SafFaults.after(e, cachedChild = true, userUnlocked = userUnlocked())) {
                        SafFault.RESOLVE_AGAIN -> store.clearCachedChild()
                        SafFault.FOLDER_GONE -> throw gone(e)
                        SafFault.WRITE_FAILED -> throw failed(e)
                    }
                }
            }
        }

        // Rule 1. The query always runs before the create, and the answer is
        // scanned out of the cursor rather than asked for in a selection.
        val existing = try {
            findChild(tree, plan.fileName)
        } catch (e: Exception) {
            throw mapped(e)
        }

        val created = existing == null
        val child = existing ?: try {
            // The parent is the tree's root as a document, never the tree Uri
            // itself: the provider reads a document id out of the parent and
            // refuses a tree Uri with "Invalid URI" (phone sitting, 2026-09-13).
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            DocumentsContract.createDocument(resolver, parent, NoteMarkdown.MIME, plan.fileName)
        } catch (e: Exception) {
            throw mapped(e)
        } ?: throw NoteWriteFailed("the folder would not take a file named ${plan.fileName}")

        // The heading and the note in one open, because a note that cannot be
        // split across two appends cannot be half-written by a process death
        // (brief section 3.1), and a file created without its heading would
        // never get one: repairing it means a read-modify-write.
        try {
            write(child, if (created) plan.headerIfCreated + plan.line else plan.line)
        } catch (e: Exception) {
            throw mapped(e)
        }

        store.setCachedChild(note.date, child.toString())

        // The name the provider actually used, not the one that was asked for.
        // They differ when a sync client created today's file in the window
        // between the query and the create, which G4 says yields a `(1)` file
        // (M4-R4: milliseconds once a day, and not closable through SAF). The
        // card then names the file the words are really in.
        val name = runCatching { nameFromDocumentId(DocumentsContract.getDocumentId(child)) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: plan.fileName

        NoteRef(fileName = name, documentUri = child.toString(), createdFile = created)
    }

    // ------------------------------------------------------------- the pieces

    private fun tree(): Uri? =
        store.treeUri()?.let { runCatching { it.toUri() }.getOrNull() }

    /**
     * One children query, scanned here for the name.
     *
     * No `selection`: the provider ignores it and returns every child (M4-R5,
     * and G6 logged `selection IGNORED` on the phone). The projection is kept
     * to the two columns that are read, which shrinks the cursor window but not
     * the provider's own per-child work.
     */
    private fun findChild(tree: Uri, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        val cursor = resolver.query(children, CHILD_PROJECTION, null, null, null)
            ?: throw NoteWriteFailed("the notes folder could not be listed")

        cursor.use {
            val idColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (idColumn < 0 || nameColumn < 0) {
                throw NoteWriteFailed("the folder listing is missing a column")
            }
            while (it.moveToNext()) {
                if (it.getString(nameColumn) == name) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, it.getString(idColumn))
                }
            }
        }
        return null
    }

    /**
     * `"wa"`, which G5 confirmed appends rather than truncates on this build,
     * and which M4-R3 traced to `O_WRONLY | O_CREAT | O_APPEND`. Never `"w"`:
     * its truncation is documented as provider-dependent.
     */
    private fun write(child: Uri, text: String) {
        val stream = resolver.openOutputStream(child, APPEND)
            ?: throw FileNotFoundException("no stream for $child")
        stream.use {
            it.write(text.toByteArray(Charsets.UTF_8))
            it.flush()
        }
    }

    private fun mapped(e: Exception): Exception =
        when (SafFaults.after(e, cachedChild = false, userUnlocked = userUnlocked())) {
            SafFault.FOLDER_GONE -> gone(e)
            SafFault.RESOLVE_AGAIN, SafFault.WRITE_FAILED -> failed(e)
        }

    private fun gone(e: Exception): NotesFolderGone =
        if (e is NotesFolderGone) e else NotesFolderGone("the notes folder is no longer available", e)

    private fun failed(e: Exception): NoteWriteFailed = when (e) {
        is NoteWriteFailed -> e
        else -> NoteWriteFailed(e.message ?: "the note could not be written", e)
    }

    private suspend fun <T> onIo(block: () -> T): T = withContext(io) { block() }

    private companion object {

        /** Mode `"wa"`. Brief section 4 step 4, gate G5, research R3. */
        const val APPEND = "wa"

        /** The two columns that are read. Nothing else is looked at. */
        val CHILD_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
    }
}

/**
 * What Maia does next when a provider call throws. Three answers, and there is
 * no fourth.
 */
enum class SafFault {

    /** The cached child is stale. Drop it, resolve again, and write. */
    RESOLVE_AGAIN,

    /** The grant or the folder is gone. A screen that keeps the note (J12). */
    FOLDER_GONE,

    /** Everything else. A fault that keeps the transcript on screen. */
    WRITE_FAILED,
}

/**
 * The one decision `SafNotes` makes that a JVM test can reach, pulled out for
 * exactly that reason, the way `EventWriter` is pulled out of
 * `ProviderCalendars`.
 *
 * Every exception class named here was seen on the phone on 2026-09-13 or read
 * out of AOSP, and each one is written down with the reading that produced it:
 *
 * - **`SecurityException`** on a released grant. G9(a): releasing the grant and
 *   appending gave `Permission Denial: opening provider
 *   com.android.externalstorage.ExternalStorageProvider`. The folder is gone.
 * - **`FileNotFoundException` on the tree.** G9(b): with the folder deleted,
 *   `Missing file for primary:Documents/... at /storage/emulated/0/...`. The
 *   folder is gone, even though the grant was still listed with read and write.
 * - **`IllegalArgumentException` wrapping `FileNotFoundException`, on a cached
 *   child.** G8: `Failed to determine if ... is child of ...:
 *   java.io.FileNotFoundException`. That wrapping is a string concatenation in
 *   the provider's child check, so the `FileNotFoundException` may be in the
 *   message and not in the cause chain, and both are looked for here.
 * - **Anything at all before the first unlock.** `ExternalStorageProvider` is
 *   not direct boot aware, so the write fails while the grant is still listed
 *   (M4-R1). Saying "folder gone" there would send the user to re-pick a folder
 *   that is perfectly fine.
 */
object SafFaults {

    /**
     * @param cachedChild true when the call that threw was made against a
     *   cached child `Uri` rather than against the tree. It is the whole
     *   difference between "this one file moved under us" and "the folder is
     *   gone": a missing child is recoverable by re-resolving, a missing tree
     *   is not.
     * @param userUnlocked false before the first unlock after a reboot, where
     *   no failure may be read as a revoked grant.
     */
    fun after(e: Throwable, cachedChild: Boolean, userUnlocked: Boolean = true): SafFault = when {
        !userUnlocked -> SafFault.WRITE_FAILED
        e is SecurityException -> SafFault.FOLDER_GONE
        cachedChild && missingFile(e) -> SafFault.RESOLVE_AGAIN
        !cachedChild && e is FileNotFoundException -> SafFault.FOLDER_GONE
        else -> SafFault.WRITE_FAILED
    }

    /**
     * A `FileNotFoundException` anywhere in the throwable: as the throwable
     * itself, in the cause chain, or named in a message because the provider
     * concatenated it into one (G8).
     */
    private fun missingFile(e: Throwable): Boolean {
        var seen: Throwable? = e
        var depth = 0
        while (seen != null && depth < 8) {
            if (seen is FileNotFoundException) return true
            if (seen.message?.contains(FileNotFoundException::class.java.name) == true) return true
            seen = seen.cause.takeIf { it !== seen }
            depth++
        }
        return false
    }
}

/**
 * The last segment of a document id, which for `ExternalStorageProvider` is
 * path-based (`primary:Documents/Notes`, `primary:Documents/Notes/2026-09-13.md`),
 * so this is a folder or file name and never a path.
 *
 * Deriving it beats querying `_display_name` twice over: it costs no binder
 * call, it works before the first unlock, and it cannot accidentally put a path
 * on a card. A provider with opaque ids (G11, never run) would give something
 * unreadable here rather than something wrong, and the card shows it only as a
 * name.
 */
fun nameFromDocumentId(documentId: String): String {
    val afterColon = documentId.substringAfterLast(':')
    val name = afterColon.substringAfterLast('/')
    return when {
        name.isNotEmpty() -> name
        afterColon.isNotEmpty() -> afterColon
        else -> documentId.substringBeforeLast(':')
    }
}
