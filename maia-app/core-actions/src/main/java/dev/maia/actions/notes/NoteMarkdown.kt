package dev.maia.actions.notes

import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The bytes, and nothing else.
 *
 * Every decision about what a note file looks like lives here, in one pure
 * object with no dispatcher, no `ContentResolver` and no clock of its own. It
 * is the whole of M4 brief section 3, and criteria J2 to J6 are assertions
 * about this file alone.
 *
 * The format, settled as decision U6 (`docs/M4-status.md` section 1):
 *
 * ```markdown
 * # 2026-09-13
 *
 * - 09:41 call the dentist about the crown
 * - [ ] 12:07 book the car in for its service (due 2026-09-14 09:00)
 * ```
 *
 * UTF-8 with no BOM, LF endings, the file always ending in a newline. No YAML
 * front matter: it is a second thing to keep consistent across appends and no
 * reader needs it. 24-hour times. `- [ ] ` for a note that carried a time,
 * because an unticked task box is what Obsidian, Logseq and every Markdown
 * task plugin already read, and it costs nothing to anyone who does not use
 * one.
 *
 * Two rules this object exists to enforce, from brief sections 3.1 and 3.3:
 *
 * 1. **One note is exactly one line, produced in one call.** A note that
 *    cannot be split across two appends cannot be half-written by a process
 *    death. Hence [line] returning a single string that already ends in `\n`,
 *    rather than a builder anybody could flush twice.
 * 2. **Nothing escapes the user's words.** Not `*`, not `_`, not `` ` ``, not
 *    `#`. [sanitise] is the complete list of transformations and it contains
 *    no escaping. A body containing `*` renders as emphasis in somebody's
 *    viewer, and that is the user's file rendering the user's words; the bytes
 *    are right, which is the only thing M4 owes them. The instinct is to
 *    escape, and escaping means the grep that finds the note shows backslashes
 *    the user never said.
 */
object NoteMarkdown {

    /**
     * `Locale.ROOT` on both formatters, so a device set to a locale with its
     * own numbering system still writes ASCII digits into a file that syncs to
     * a laptop.
     */
    private val FILE_DATE: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)

    private val CLOCK_TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    /** What `createDocument` is asked for. Brief section 4 step 3. */
    const val MIME = "text/markdown"

    /** The extension, separate so the per-note fallback in [plan] can reuse it. */
    const val EXTENSION = ".md"

    // ----------------------------------------------------------------- names

    /**
     * `2026-09-13.md`, from the note's own local date.
     *
     * The note's date, never the device's date at write time: a note spoken at
     * 23:59 lands in that day's file and not the next one, even if the append
     * itself happens a minute later. Criterion J2.
     */
    fun fileName(date: LocalDate): String = FILE_DATE.format(date) + EXTENSION

    /** The same, for the note that is about to be written. */
    fun fileName(note: Note): String = fileName(note.date)

    // ---------------------------------------------------------------- header

    /**
     * The heading and the blank line under it, written once, in the same call
     * that creates the file.
     *
     * It is never checked or repaired afterwards. Repairing a heading means
     * reading the file and writing it back, and read-modify-write is the one
     * operation brief section 3.1 forbids outright: it destroys whatever
     * another device wrote between the read and the write, turning a mild
     * failure (a Syncthing `.sync-conflict` copy, with both versions kept)
     * into silent data loss.
     */
    fun header(date: LocalDate): String = "# " + FILE_DATE.format(date) + "\n\n"

    // ------------------------------------------------------------------ body

    /**
     * The three transformations of brief section 3.3, and they are the whole
     * list.
     *
     * 1. Any CR or LF inside the body becomes a single space. The recogniser
     *    produces none (`:core-audio` emits one unpunctuated line); a card edit
     *    can.
     * 2. Runs of whitespace collapse to one space.
     * 3. Leading and trailing whitespace is trimmed.
     *
     * No escaping, no case change, no punctuation added. Returns an empty
     * string for a body that was empty or whitespace only, which [line] turns
     * into a refusal to write anything at all (criterion J5).
     */
    fun sanitise(body: String): String = body
        .replace('\r', ' ')
        .replace('\n', ' ')
        .replace(WHITESPACE_RUN, " ")
        .trim()

    private val WHITESPACE_RUN = Regex("\\s+")

    // ------------------------------------------------------------------ line

    /**
     * One note, one line, ending in `\n`, or null when there is nothing to
     * write.
     *
     * Null is not a fault this object can report properly, so it does not try:
     * the flow turns it into a fault that keeps the transcript on screen
     * (criterion J5). Appending an empty bullet would be worse than failing,
     * because the user would never notice it and the line would sync anyway.
     *
     * @param at the capture time, in its own zone. Supplies `HH:MM` and, via
     *   its date, decides whether [remindAt] needs a date of its own.
     * @param remindAt the resolved reminder time, in the zone `:core-nlu`
     *   resolved it in. Never re-zoned here: this object does no zone
     *   arithmetic of its own, because the only zone that is certainly right
     *   is the one already attached to the value.
     */
    fun line(body: String, at: ZonedDateTime, remindAt: ZonedDateTime? = null): String? {
        val text = sanitise(body)
        if (text.isEmpty()) return null

        val box = if (remindAt != null) "- [ ] " else "- "
        val due = remindAt?.let { " (due " + dueText(it, at.toLocalDate()) + ")" }

        // The timestamp prefix is always in front of the body, which is why a
        // body starting with "-" or "#" cannot break the line even though
        // nothing escapes it.
        return box + CLOCK_TIME.format(at) + " " + text + due.orEmpty() + "\n"
    }

    /** The same, for the note that is about to be written. */
    fun line(note: Note): String? =
        line(note.body.value, note.at, note.remindAt?.value)

    /**
     * `(due 09:00)` when the reminder falls on the day of the file it is being
     * written into, `(due 2026-09-14 09:00)` when it does not.
     *
     * Repeating the file's own date on every line would be noise; omitting a
     * different date would be a lie.
     */
    private fun dueText(remindAt: ZonedDateTime, fileDate: LocalDate): String =
        if (remindAt.toLocalDate() == fileDate) {
            CLOCK_TIME.format(remindAt)
        } else {
            FILE_DATE.format(remindAt) + " " + CLOCK_TIME.format(remindAt)
        }

    // ------------------------------------------------------------------ plan

    /**
     * Where this note goes and what gets written, which is the **one** place
     * the file-shape decision lives.
     *
     * Decision U1 is a daily file, appended, one note per line. Gate G5
     * (whether `openOutputStream(uri, "wa")` appends rather than truncates) and
     * gate G7 (whether a Syncthing conflict loses bytes) can each force one
     * file per note instead, per brief section 0.3, and neither has been run
     * because there is no phone on this box.
     *
     * That switch is this function and nothing else. Per-note files mean:
     * [fileName] becomes `"2026-09-13 0941 <slug>" + EXTENSION` built from the
     * note's own time and body, [header] is written into every file instead of
     * once a day, and [line] becomes the whole file body. Every caller keeps
     * asking for a [NotePlan] and keeps writing [NotePlan.headerIfCreated]
     * followed by [NotePlan.line]; the storage layer's create-or-resolve step
     * stops finding an existing file, which is exactly what makes per-note
     * files conflict-free. No other signature in the module moves.
     *
     * Null propagates [line]'s null: an empty body writes no file and creates
     * none either.
     */
    fun plan(note: Note): NotePlan? {
        val line = line(note) ?: return null
        return NotePlan(
            fileName = fileName(note),
            headerIfCreated = header(note.date),
            line = line,
        )
    }
}

/**
 * The result of [NoteMarkdown.plan]: a file name, the bytes to write if that
 * file has to be created, and the bytes to append either way.
 *
 * Deliberately not "the file contents". Under decision U1 the writer never
 * holds a whole file, never reads one, and therefore can never overwrite what
 * it did not write.
 */
data class NotePlan(
    val fileName: String,
    val headerIfCreated: String,
    val line: String,
)
