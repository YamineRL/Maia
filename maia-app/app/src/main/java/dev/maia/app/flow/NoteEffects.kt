package dev.maia.app.flow

import dev.maia.actions.notes.Note
import dev.maia.actions.notes.NoteRepository
import dev.maia.actions.notes.NotesFolderGone
import kotlinx.coroutines.CancellationException
import java.time.LocalDate

/*
 * The note effects' answers, as functions of a [NoteRepository] and nothing
 * Android. `AndroidEffects` launches these and sends what they return, so the
 * one decision in the runner, which exception becomes which event, is tested
 * against `FakeNotes` on the JVM rather than on a phone.
 */

/**
 * [Effect.LoadNotesFolder]. A folder that cannot even be asked about is not
 * held: a screen, not a fault.
 *
 * [warmFor] is the day the note would go into, and passing it is what keeps the
 * expensive half of the write off the confirmation path. The spike measured the
 * append at a median of 15 ms and the children query that finds today's file at
 * about 345 ms in a folder of 100 files, rising to seconds at 1000, so
 * `SafNotes` resolves and caches here, while the card is being read, instead of
 * under the 600 ms hold. Null skips the warm, which is what every test with a
 * `FakeNotes` behind it wants.
 *
 * The warm is deliberately unable to fail the effect. It is a cache warm: if it
 * throws, the write pays for the resolve itself and the user sees nothing.
 */
suspend fun loadNotesFolder(notes: NoteRepository, warmFor: LocalDate? = null): FlowEvent = try {
    val folder = notes.folder()
    if (folder != null && warmFor != null) {
        try {
            notes.prepare(warmFor)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A warm that failed is not news. The append resolves for itself.
        }
    }
    FlowEvent.NotesFolderLoaded(folder)
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    FlowEvent.NotesFolderLoaded(null)
}

/** [Effect.WriteNote]. A folder that went away keeps the note (criterion J12); anything else is a fault. */
suspend fun writeNote(notes: NoteRepository, note: Note): FlowEvent = try {
    FlowEvent.NoteWritten(notes.append(note))
} catch (e: CancellationException) {
    throw e
} catch (e: NotesFolderGone) {
    FlowEvent.NoteWriteFailed(e.message ?: "the notes folder is no longer available", folderGone = true)
} catch (e: Exception) {
    FlowEvent.NoteWriteFailed(e.message ?: "the note could not be written")
}
