package dev.maia.app.screens

import dev.maia.app.flow.Download
import dev.maia.app.flow.FaultReason
import dev.maia.app.flow.FlowEvent
import dev.maia.app.flow.LockedCopy
import dev.maia.app.flow.Origin
import dev.maia.app.flow.UNDO_WINDOW_MS
import dev.maia.app.flow.UndoStatus
import dev.maia.app.ui.DownloadCopy
import dev.maia.audio.Word
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.ceil

/**
 * What the flow screens say, decided without Compose.
 *
 * The same split as [dev.maia.app.card.cardRows]: the composables in
 * FlowScreen.kt only draw, and every choice about which words appear, which
 * ink they take and which control is offered is made here, where a JVM test
 * can pin it.
 */

/** What the invoke control on these screens sends: a launcher press on an unlocked phone. */
val LAUNCHER_INVOKE = FlowEvent.Invoke(Origin.Launcher, locked = false)

/**
 * The words the handoff gives, and placeholders where it gives none.
 *
 * Idle, the locked screen and the capture failure are not drawn in the handoff.
 * Their copy is a proposal and, like [LockedCopy], each line is one constant.
 */
object FlowCopy {
    const val IDLE_LAST_HEARD = "last heard"
    const val SPEAK = "Speak"
    const val GO_AHEAD = "go ahead"
    const val CANCEL = "Cancel"
    const val LEAVE = "Leave"
    const val WRITING = "Writing…"
    const val DONE = "Done"
    const val UNDO = "Undo"
    const val UNDOING = "Undoing…"
    const val UNDONE = "Undone. The event is gone from the calendar."
    const val ALREADY_GONE = "The event was already gone from the calendar."
    const val UNDO_FAILED = "Undo failed. The event may still be in the calendar."

    const val NO_DATE_TITLE = "I did not hear a day"
    const val NO_DATE_BODY = "Everything else is kept. Pick a day and it goes straight to the preview."
    const val PICK_DATE = "Pick a date"
    const val SAY_DAY = "Say the day instead"
    const val CAPTURE_TITLE = "The microphone stopped"
    const val CAPTURE_BODY = "Nothing was written."
    const val TRY_AGAIN = "Try again"

    const val FIRST_RUN_EYEBROW = "first run"
    const val FIRST_RUN_TITLE = "Bringing speech onto the phone"
    const val FIRST_RUN_BODY = "One 70 MB download, once. After this the microphone never needs a " +
        "network again: recognition and parsing both run here."
    const val RESUMES = "Resumes if interrupted"
    const val DOWNLOAD = "Download"

    // ------------------------------------------- the rescorer, asked for once
    //
    // PRD section 18, answered by the user on 2026-09-20: ask once on first
    // run. `OfflineModelStore.APPROXIMATE_BYTES` is 670,478,772 and
    // `EngineHolder.warmRescorer` starts fetching it the first time the app
    // hears anything, with failures swallowed and no progress on screen. Until
    // this screen exists, "no network call the user did not configure" is
    // false in the shipped tree.
    //
    // The words sit here rather than in a copy document because the screen
    // they belong to is the sibling of the one three constants above, and the
    // zipformer screen's words have always lived here. `FlowCopyTest` is the
    // guard. Reasoning that a Markdown document would carry is in these
    // comments instead, which is the cost of the choice and is accepted for
    // one screen of nine strings.
    //
    // Two economies rather than new strings: while it is running the screen
    // shows `firstRunLine`'s mono counter with RESUMES under it, and a failure
    // shows TRY_AGAIN, both exactly as the first download does. It is the same
    // act twice and it should not be two vocabularies.
    //
    // When it is asked: straight after the first download finishes, before the
    // first sentence is ever spoken. Any later moment either interrupts an
    // utterance or arrives with the microphone live. The cost is real and is
    // named here rather than hidden: the user is deciding about accuracy they
    // have not experienced yet, which is why RESCORER_BODY says which words it
    // fixes rather than claiming "better accuracy".
    const val RESCORER_EYEBROW = "second model"
    const val RESCORER_TITLE = "A second model, for the hard words"
    const val RESCORER_BODY = "640 MB, nine times the first download, once. It re-reads what " +
        "the first model heard and corrects names, places and unusual words. Maia works without " +
        "it and gets more of those wrong."
    const val RESCORER_SOURCE = "It comes from huggingface.co, where the first one came from, and " +
        "then it stays on this phone."
    // Shown only while the connection is metered. There is no gate anywhere in
    // the tree today, so this line is the whole of the protection: the phone
    // says what the download would cost and the user decides. A gate that
    // waits for an unmetered network was not asked for and is not invented
    // here, but it is the thing this line is standing in for.
    const val RESCORER_METERED = "You are on mobile data. This would take 640 MB of it."
    const val RESCORER_LATER = "You can turn it on or off later in Maia's settings."
    const val RESCORER_SKIP = "Skip it"

    // Why 640 and not 670. `APPROXIMATE_BYTES` is 670,478,772 bytes, which is
    // 670 MB counted in millions and 639.4 MB counted the way this product
    // counts: [DownloadCopy] uses 1024 x 1024 and labels it MB, deliberately,
    // so that the counter and the sentence above it agree. 670 here would put
    // a number on the consent screen that the progress line never reaches,
    // which is the exact lie that comment was written to prevent. The PRD
    // quotes the byte count; the word on the screen is this seat's, and it is
    // the one the user will watch count up. That the product calls a mebibyte
    // an MB at all is a separate and older question, routed rather than
    // flipped here, because flipping it changes the first screen's words too.
    //
    // The settings row the line above promises. It does not exist yet, and a
    // promise with nothing behind it is the pairing screen's mistake repeated,
    // so the words are written here at the same time as the screen that makes
    // the promise.
    //
    // Off means gone: 670 MB sitting unused on a phone is the thing nobody
    // wants, so there is one control and it says both halves of what it does.
    const val RESCORER_SETTING_TITLE = "The second speech model"
    const val RESCORER_SETTING_ON = "On. 640 MB on this phone."
    const val RESCORER_SETTING_OFF = "Off. Maia uses the first model on its own."
    const val RESCORER_SETTING_REMOVE = "Turn it off and delete it"
    const val RESCORER_SETTING_REMOVE_CAPTION = "The 640 MB goes. Turning it back on downloads it again."

    const val QUEUED_EYEBROW = "kept until you unlock"
    const val UNLOCK = "Unlock"
    const val DISCARD = "Discard"

    // M4 placeholders still in use: the folder-gone title and the note write
    // title back [faultCopy], whose `when` must stay exhaustive. The screens
    // draw `m4_folder_gone_title` (row 7) and `m4_note_write_fault_title`
    // (row 8) instead.
    const val NOTE_WRITE_TITLE = "The note was not written"
    const val FOLDER_GONE_TITLE = "The notes folder is gone"
}

/** One word of the live line, and whether it takes the confirmed ink. */
data class InkedWord(val text: String, val confirmed: Boolean)

/**
 * 4b's line. Scored words are inked by [Word.confirmed]. A partial with no
 * scores at all is drawn confirmed, because nothing says any of it is doubtful
 * and drawing it faint would be the screen inventing a doubt.
 */
fun inkedWords(words: List<Word>, partial: String): List<InkedWord> =
    if (words.isNotEmpty()) {
        words.map { InkedWord(it.text, it.confirmed) }
    } else {
        partial.split(' ').filter { it.isNotBlank() }.map { InkedWord(it, confirmed = true) }
    }

/** Whole seconds left on 4f's undo, rounded up, so it reads 8 the instant it appears and 0 only when it is over. */
fun undoSecondsLeft(now: Long, deadline: Long): Int {
    val left = ceil((deadline - now) / 1000.0).toInt()
    return left.coerceIn(0, (UNDO_WINDOW_MS / 1000).toInt())
}

/** The undo slot on 4f: an action with its mono countdown, or a note, or nothing. */
data class UndoLine(val action: String?, val countdown: String?, val note: String?)

fun undoLine(status: UndoStatus, secondsLeft: Int): UndoLine = when (status) {
    // At zero the reducer is one tick from Expired and a tap would only expire
    // it, so the affordance goes with the last second rather than lingering.
    UndoStatus.Offered ->
        if (secondsLeft > 0) UndoLine(FlowCopy.UNDO, "$secondsLeft s", null) else UndoLine(null, null, null)
    UndoStatus.Undoing -> UndoLine(null, null, FlowCopy.UNDOING)
    UndoStatus.Undone -> UndoLine(null, null, FlowCopy.UNDONE)
    UndoStatus.AlreadyGone -> UndoLine(null, null, FlowCopy.ALREADY_GONE)
    UndoStatus.Failed -> UndoLine(null, null, FlowCopy.UNDO_FAILED)
    UndoStatus.Expired -> UndoLine(null, null, null)
}

/** 4f's eyebrow. [dev.maia.app.flow.FlowState.Confirmed] does not carry the target, so the host passes the name it wrote to. */
fun writtenTo(calendarName: String?): String =
    if (calendarName.isNullOrBlank()) "Written to the calendar" else "Written to $calendarName"

/** One chip on 4g: the word, the date and time in mono, and the start it picks. */
data class DateChip(val label: String, val detail: String, val start: ZonedDateTime)

/**
 * 4g's chips, from the draft's inferred start, which is the moment the sentence
 * was parsed.
 *
 * The handoff shows Tonight, Tomorrow and a named day without a rule for the
 * times, so this is a proposal: tonight is 20:00 and only while it is still
 * before 20:00; tomorrow and the day after, by name, keep the hour the sentence
 * was said in, rounded up to the hour and never past 23:00.
 */
fun dateChips(inferredStart: ZonedDateTime, locale: Locale = Locale.getDefault()): List<DateChip> {
    val base = inferredStart.truncatedTo(ChronoUnit.HOURS)
    val roundedHour = if (base == inferredStart) base.hour else base.hour + 1
    val hour = minOf(roundedHour, LAST_HOUR)
    val tonight = base.withHour(TONIGHT_HOUR)
    val tomorrow = base.plusDays(1).withHour(hour)
    val after = base.plusDays(2).withHour(hour)
    return listOfNotNull(
        if (inferredStart.isBefore(tonight)) chip("Tonight", tonight, locale) else null,
        chip("Tomorrow", tomorrow, locale),
        chip(after.dayOfWeek.getDisplayName(TextStyle.FULL, locale).replaceFirstChar { it.titlecase(locale) }, after, locale),
    )
}

private const val TONIGHT_HOUR = 20
private const val LAST_HOUR = 23
private val chipDay = DateTimeFormatter.ofPattern("EEE d MMM")
private val chipTime = DateTimeFormatter.ofPattern("HH:mm")

private fun chip(label: String, start: ZonedDateTime, locale: Locale) =
    DateChip(label, "${chipDay.withLocale(locale).format(start)}  ${chipTime.format(start)}", start)

/** 4h below the fold: the mono numbers, the one action, and the note under it. */
data class FirstRunLine(val numbers: String?, val action: String?, val note: String?, val failed: Boolean)

/**
 * [bytesDone] and [bytesTotal] are the live counts when the host has them and
 * fall back to the last progress the reducer kept.
 */
fun firstRunLine(
    download: Download,
    bytesDone: Long? = null,
    bytesTotal: Long? = null,
    bytesPerSecond: Double = 0.0,
): FirstRunLine {
    val done = bytesDone ?: download.bytesDone
    val total = bytesTotal ?: download.bytesTotal ?: -1
    val numbers = if (download.running || done > 0) {
        DownloadCopy.line(done, total, if (download.running) bytesPerSecond else 0.0)
    } else {
        null
    }
    return when {
        download.running -> FirstRunLine(numbers, null, FlowCopy.RESUMES, failed = false)
        download.error != null -> FirstRunLine(numbers, FlowCopy.TRY_AGAIN, download.error, failed = true)
        else -> FirstRunLine(numbers, FlowCopy.DOWNLOAD, FlowCopy.RESUMES, failed = false)
    }
}

/** The words over a fault. */
data class FaultCopy(val title: String, val body: String?)

fun faultCopy(reason: FaultReason): FaultCopy = when (reason) {
    FaultReason.NoDateHeard -> FaultCopy(FlowCopy.NO_DATE_TITLE, FlowCopy.NO_DATE_BODY)
    is FaultReason.CaptureFailed -> FaultCopy(FlowCopy.CAPTURE_TITLE, FlowCopy.CAPTURE_BODY)
    FaultReason.QueueFull -> FaultCopy(LockedCopy.QUEUE_FULL.replaceFirstChar { it.uppercase() }, null)
    is FaultReason.NoteWriteFailed -> FaultCopy(FlowCopy.NOTE_WRITE_TITLE, FlowCopy.CAPTURE_BODY)
    FaultReason.FolderGone -> FaultCopy(FlowCopy.FOLDER_GONE_TITLE, null)
}

/** A transcript as the screens quote it, or null when nothing was heard. */
fun quoted(transcript: String?): String? =
    transcript?.trim()?.takeIf { it.isNotEmpty() }?.let { "“$it”" }
