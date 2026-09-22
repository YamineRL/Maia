package dev.maia.app.card

import dev.maia.actions.notes.Note
import dev.maia.actions.notes.NotesFolder
import dev.maia.nlu.Field
import dev.maia.nlu.Provenance
import java.net.URLDecoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The note card's decisions, away from Compose. M4 row 8, `docs/M4-copy.md`
 * section 1 and section 4.
 *
 * The same split [cardRows] makes for the event card: the words themselves are
 * the design seat's and live in `strings_m4_notes.xml`, and which of them a
 * note gets, and what goes into their `%1$s`, is decided here, in pure Kotlin,
 * where a JVM test can pin it.
 */

/**
 * How the Due row is marked, or null when the note has no due time and so no
 * Due row at all.
 *
 * The event card's rule, stated through the same [Mark]: a time Maia filled in
 * is guessed and gets all four cues, one the user said or changed is heard.
 * The Note and Goes into rows have no function like this because the copy
 * rules them never guessed: the body is what was heard or typed, and the folder
 * is the user's own pick.
 */
fun dueMark(remindAt: Field<ZonedDateTime>?): Mark? = when (remindAt?.provenance) {
    null -> null
    Provenance.Inferred -> Mark.Guessed
    Provenance.Heard, Provenance.Corrected -> Mark.Heard
}

/** The Due row's value, set in mono like the event card's When row: `Thu 17 Sept  20:00`. */
fun dueValue(remindAt: ZonedDateTime, locale: Locale = Locale.getDefault()): String =
    "${DateTimeFormatter.ofPattern("EEE d MMM").withLocale(locale).format(remindAt)}  ${clockTime(remindAt)}"

/**
 * The due time as it goes into `m4_note_row_due_cd`: `Thursday 17 September at 20:00`.
 *
 * **Short of the copy, in the same place M3 was.** The copy asks for "the long
 * spoken form, as M3-copy section 2 specified", and no spoken formatter exists
 * (see `keptAsDescription` in `LockedScreens.kt`, which writes the same
 * shortfall down). This is [dev.maia.app.flow.noteSummary]'s shape, which at
 * least spells the day and the month, so TalkBack reads words and not
 * abbreviations. The digits of the time remain.
 */
fun dueSpoken(remindAt: ZonedDateTime, locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofPattern("EEEE d MMMM 'at' HH:mm").withLocale(locale).format(remindAt)

/** `HH:mm`, the confirmation line's time and the one the file's line starts with. */
fun clockTime(at: ZonedDateTime): String = DateTimeFormatter.ofPattern("HH:mm").format(at)

/** The confirmation's time, `%1$s` of `m4_note_confirmed_line`: when the note was filed. */
fun noteTime(note: Note): String = clockTime(note.at)

/**
 * The document id inside a tree `Uri` string, decoded, or null when [uri] is
 * not a tree uri at all.
 *
 * `DocumentsContract.getTreeDocumentId` does this on the phone. It is repeated
 * here, as string work, because [NotesFolder.uri] is deliberately a `String`
 * and this file has to be reachable from a JVM test. A tree uri is
 * `content://<authority>/tree/<encoded id>[/document/<encoded id>]`, and the id
 * is percent-encoded by `Uri.encode`, which encodes `+` too, so a literal `+`
 * is protected before decoding rather than read as a space.
 */
fun treeDocumentId(uri: String): String? {
    val raw = uri.substringAfter("/tree/", missingDelimiterValue = "").substringBefore('/')
    if (raw.isEmpty()) return null
    return runCatching { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrNull()
}

/**
 * Whether the folder is the top of a storage volume, which is when the Goes
 * into row says `m4_note_row_folder_value_root` instead of a name.
 *
 * **The detection, and why it is this one.** `nameFromDocumentId` turns
 * `primary:` into `primary`, the volume id, and [NotesFolder] carries only that
 * name. Matching the name against `primary` would miss every other volume (an
 * SD card's id is its serial, `1A2B-3C4D:`) and would misfire on a real folder
 * someone called "primary". So the name is not read at all: the tree's own
 * document id is decoded from [NotesFolder.uri], and a root is an id with a
 * colon and nothing after it, which is exactly the copy's rule ("the document
 * id has nothing after the colon"). An id with no colon (the fakes'
 * `fake://tree/notes`, or a provider with opaque ids) and a uri with no
 * `/tree/` segment are never called a root, because the folder name is the
 * less wrong thing to show for a provider this has not seen.
 */
fun isStorageRoot(folder: NotesFolder): Boolean {
    val id = treeDocumentId(folder.uri) ?: return false
    return dev.maia.app.notes.isStorageRoot(id)
}
