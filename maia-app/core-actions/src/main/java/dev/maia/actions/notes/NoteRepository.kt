package dev.maia.actions.notes

import java.time.LocalDate

/**
 * Everything Maia writes to a notes folder, which at M4 is one line at a time
 * and nothing else.
 *
 * The sibling of [dev.maia.actions.CalendarRepository] and the same shape for
 * the same reason: an interface, so the card and the empty states can be
 * driven with no Android classes involved, and so the thing that cannot be
 * tested on this box is one named class rather than a habit spread through the
 * UI. [FakeNotes] is the other implementation and it lives in the main source
 * set, not the test one, so Compose previews and the debug build reach it too.
 *
 * Suspending because the real one talks to a `DocumentsProvider`, which is
 * disk plus a binder round trip and has no business on the main thread. The
 * fake honours the same signature and so cannot let a caller forget that.
 *
 * What is deliberately absent, and stays absent: reading, listing, searching,
 * editing and deleting. Brief section 13's two standing defaults are that
 * notes cannot be edited, listed or deleted from inside Maia at M4, and that
 * the card offers no undo, because undo means rewriting a file Maia does not
 * own. An interface with a `delete` on it is an invitation to build one.
 */
interface NoteRepository {

    /**
     * The folder the user picked, or null when they have not picked one or the
     * grant has gone away.
     *
     * Null is a screen and not an error (brief section 2.2), the same way
     * [dev.maia.actions.CalendarRepository.defaultTarget] returning null is
     * the no-calendars screen. The transcript is held either way and the note
     * is written once a folder exists.
     *
     * Asked on every note rather than cached in the caller, because on
     * GrapheneOS a persisted grant can be revoked between two invocations and
     * a cached "yes" would turn that into a crash instead of a screen.
     */
    suspend fun folder(): NotesFolder?

    /**
     * Get ready to write [date]'s file, without writing anything and without
     * creating anything.
     *
     * A no-op by default, and [FakeNotes] leaves it that way, because for
     * everything except a `DocumentsProvider` there is nothing to get ready.
     * [SafNotes] resolves today's file and caches its `Uri` here, and the
     * reason is a measurement: the spike found the append itself at a median of
     * 15 ms, and finding the file by a children query at about 345 ms in a
     * folder of 100 files and up to 2.5 s at 1000. So the resolve happens when
     * the card opens, while the user is reading it, and the hold that commits
     * pays for the append alone.
     *
     * Called on the way to the note card, never on the way out of it. It is a
     * warm and not a step: a failure here costs the resolve happening later
     * after all, so callers do not report it.
     */
    suspend fun prepare(date: LocalDate) = Unit

    /**
     * Append [note] to today's file, creating the file and its heading if this
     * is the day's first note.
     *
     * Never a read-modify-write (brief section 3.1): the implementation may
     * create a file and may append to one, and may do nothing else to it.
     *
     * @throws NotesFolderGone when the grant has been revoked or the folder
     *   deleted, which is a screen that keeps the note (criterion J12), not a
     *   fault to show a stack trace for.
     * @throws NoteWriteFailed for everything else that stopped the write. The
     *   flow turns it into a fault that still carries the transcript verbatim.
     */
    suspend fun append(note: Note): NoteRef
}

/**
 * The folder is no longer writable: the grant was released, the user cleared
 * it from storage settings, or the directory was deleted.
 *
 * Its own type because the UI has to tell it apart from a real fault. Gate G9
 * says this path must raise `SecurityException` or `FileNotFoundException` and
 * nothing else, and be recoverable by picking again; this is what those become
 * once they cross out of the storage layer.
 */
class NotesFolderGone(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The write did not happen, for a reason that is not [NotesFolderGone]. */
class NoteWriteFailed(message: String, cause: Throwable? = null) : Exception(message, cause)
