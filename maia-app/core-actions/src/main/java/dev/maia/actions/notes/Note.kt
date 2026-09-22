package dev.maia.actions.notes

import dev.maia.nlu.EventDraft
import dev.maia.nlu.Field
import dev.maia.nlu.Intent
import dev.maia.nlu.Provenance
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * One thing the user asked to keep, as it stands just before it is written.
 *
 * The note equivalent of [dev.maia.nlu.EventDraft], and deliberately the same
 * shape: every value carries where it came from, because M4 brief section 5.1
 * gives a note a card and the card is the trust boundary. A time Maia
 * salvaged from a half-understood sentence has to look different on that card
 * from one the user actually said, and [Field.provenance] is the only thing
 * that can still tell them apart by the time the card is on screen.
 *
 * It is not the file. Nothing here knows what a line of Markdown looks like:
 * that is [NoteMarkdown], which is pure and is the only thing that decides
 * bytes.
 *
 * @param body what will be written, before [NoteMarkdown.sanitise] touches it.
 * @param at when the note was captured, in the zone it was captured in. This
 *   is what names the file and what prefixes the line, so it is a
 *   [ZonedDateTime] and never an `Instant`: a note spoken at 23:59 belongs to
 *   that day's file in the speaker's zone, not in UTC (brief section 3.2).
 * @param remindAt the time the sentence carried, already resolved by
 *   `:core-nlu` against its injected clock. M4 sets no alarm and writes no
 *   calendar entry for it (brief section 1, consequence 1); it renders into
 *   the line and nothing else.
 * @param transcript what was heard, verbatim, kept for the fault card the
 *   same way [EventDraft.transcript] is.
 */
data class Note(
    val body: Field<String>,
    val at: ZonedDateTime,
    val remindAt: Field<ZonedDateTime>? = null,
    val transcript: String = "",
) {
    /** The day this note belongs to, in its own zone. Names the file. */
    val date: LocalDate get() = at.toLocalDate()

    /** True when the line gets a `- [ ] ` task box rather than a plain `- `. */
    val isTask: Boolean get() = remindAt != null

    companion object {

        /** The ordinary path: the parser said this sentence was a note. */
        fun of(intent: Intent.CaptureNote, at: ZonedDateTime): Note = Note(
            body = intent.body,
            at = at,
            remindAt = intent.remindAt,
            transcript = intent.transcript,
        )

        /**
         * Decision U2: "keep as a note" on the card that opened for a sentence
         * Maia heard and did not understand.
         *
         * The body is the draft's title, which for [Intent.Unparsed] is the
         * raw transcript, and it keeps that field's provenance rather than
         * being rebuilt as [Provenance.Heard]: the user may already have
         * corrected it on the card, and flattening that would lose the one
         * fact the card was showing them.
         *
         * The draft's start does **not** become a [remindAt] unless it was
         * heard. An unparsed sentence always has a start, because
         * `Parser.event` falls back to `now`, marked [Provenance.Inferred], so
         * that the card opens on something pushable. Carrying that into the
         * file would put a `- [ ] ` task box and a `(due ...)` on every
         * unparsed note, dated to the second the user spoke, which the user
         * never asked for and would then have to delete by hand in a file
         * Maia does not own.
         */
        fun of(draft: EventDraft, at: ZonedDateTime): Note = Note(
            body = draft.title,
            at = at,
            remindAt = draft.start.takeIf { it.provenance != Provenance.Inferred },
            transcript = draft.transcript,
        )
    }
}

/**
 * The folder the user picked, as far as anything above the storage layer
 * needs to know about it.
 *
 * [uri] is a tree `Uri` string, opaque here on purpose: `:core-actions` has no
 * `android.net.Uri` in any signature that a test has to reach, for the same
 * reason [dev.maia.actions.FakeCalendars] exists in the main source set.
 * [name] is what the card is allowed to say out loud, which is a folder name
 * and never a path: brief section 11, privacy item V2, and decision U7's
 * "short and folder-free" spoken confirmation.
 */
data class NotesFolder(
    val uri: String,
    val name: String,
)

/**
 * What a successful append leaves behind.
 *
 * [createdFile] is separated out because the two halves have different costs
 * and different budgets (brief section 4): the create-and-header path happens
 * once a day and is recorded, the append happens every note and is budgeted
 * against PRD section 13. A caller that wants to report either honestly needs
 * to know which one it just did.
 */
data class NoteRef(
    val fileName: String,
    val documentUri: String?,
    val createdFile: Boolean,
)
