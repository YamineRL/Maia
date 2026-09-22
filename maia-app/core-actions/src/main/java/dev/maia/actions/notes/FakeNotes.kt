package dev.maia.actions.notes

import java.time.LocalDate

/**
 * A [NoteRepository] with no `DocumentsProvider` behind it: a map from file
 * name to file contents, appended to exactly the way the real one will
 * append.
 *
 * In the **main** source set on purpose, beside
 * [dev.maia.actions.FakeCalendars] and for the same stated reason: Compose
 * previews cannot see test source, and a screen that can only be looked at by
 * attaching a phone is a screen nobody looks at until it is too late to
 * change. PRD principle 5 puts beauty in the acceptance criteria, and that
 * needs the screens to be cheap to open.
 *
 * It is also the only thing criterion J14 can be proved against: the whole
 * note flow, from sentence to confirmation, with no Android class involved.
 * There is no `android.` import in this file.
 *
 * It is not a simulator of SAF. It cannot tell anyone whether `"wa"` appends
 * or truncates (gate G5), what `createDocument` does with a name that already
 * exists (G4), or what Syncthing does to a file being appended to (G7). It
 * models the contract the brief decided on, so that everything above the
 * storage seam can be finished and tested while the phone is missing. What
 * the phone settles, it settles; this class never becomes the evidence.
 */
class FakeNotes(
    folder: NotesFolder? = defaultFolder,
    files: Map<String, String> = emptyMap(),
) : NoteRepository {

    private var folder: NotesFolder? = folder
    private val contents = LinkedHashMap<String, String>().apply { putAll(files) }

    /** Flip to have [append] raise [NotesFolderGone], for the revoked path. */
    var folderRevoked: Boolean = false

    /** Flip to have [append] raise [NoteWriteFailed], for the fault path. */
    var denyWrites: Boolean = false

    /**
     * Flip to have [prepare] throw, which nothing above it may notice: the warm
     * is a cache warm and its failure costs a slower write and nothing else.
     */
    var prepareFails: Boolean = false

    private val warmed = mutableListOf<LocalDate>()

    /**
     * The dates [prepare] was asked to get ready for, in order.
     *
     * There is nothing to get ready here, so this exists only so that a test
     * can see that the caller asked at all: `SafNotes` doing its resolve on the
     * card rather than under the hold is a measurement-driven rule (the spike's
     * G6) and it is worth an assertion that the call site still makes the call.
     */
    val prepared: List<LocalDate> get() = warmed.toList()

    /** Every file in the folder, by name, in the order they were created. */
    val files: Map<String, String> get() = LinkedHashMap(contents)

    /** One file's whole contents, or null if it was never created. */
    fun fileNamed(name: String): String? = contents[name]

    /** The file a note on [date] would have gone into. */
    fun fileOn(date: LocalDate): String? = contents[NoteMarkdown.fileName(date)]

    /** Simulate the user picking a folder, or picking a different one. */
    fun choose(chosen: NotesFolder?) {
        folder = chosen
        folderRevoked = false
    }

    override suspend fun folder(): NotesFolder? = folder.takeIf { !folderRevoked }

    override suspend fun prepare(date: LocalDate) {
        warmed += date
        if (prepareFails) throw NoteWriteFailed("the folder could not be read ahead")
    }

    override suspend fun append(note: Note): NoteRef {
        if (folderRevoked || folder == null) {
            throw NotesFolderGone("the notes folder is no longer available")
        }
        if (denyWrites) throw NoteWriteFailed("the provider refused the write")

        // Criterion J5, enforced at the seam rather than only in the UI: an
        // empty body never reaches a file. The flow turns this into a fault
        // that keeps the transcript on screen.
        val plan = NoteMarkdown.plan(note)
            ?: throw NoteWriteFailed("a note with an empty body is never written")

        val existing = contents[plan.fileName]
        // The two writes the real implementation is allowed to make, in the
        // same order and with nothing between them: create with the heading,
        // then append one line. Never a read-modify-write, so `existing` is
        // only ever tested for presence and is never rewritten.
        contents[plan.fileName] = if (existing == null) {
            plan.headerIfCreated + plan.line
        } else {
            existing + plan.line
        }

        return NoteRef(
            fileName = plan.fileName,
            documentUri = "fake://notes/" + plan.fileName,
            createdFile = existing == null,
        )
    }

    companion object {
        /**
         * A plausible folder, named the way the card is allowed to name one:
         * a folder name, never a path (privacy item V2).
         */
        val defaultFolder = NotesFolder(uri = "fake://tree/notes", name = "Notes")
    }
}
