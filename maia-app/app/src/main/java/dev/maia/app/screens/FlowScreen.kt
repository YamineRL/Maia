package dev.maia.app.screens

import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import dev.maia.app.ui.MaiaButton
import dev.maia.app.ui.ButtonKind
import dev.maia.app.ui.ButtonFace
import dev.maia.app.ui.InvokeFace
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.maia.app.card.CardField
import dev.maia.app.card.NoCalendars
import dev.maia.app.card.PreviewCard
import dev.maia.app.card.cardRows
import dev.maia.app.card.isStorageRoot
import dev.maia.app.card.noteTime
import dev.maia.app.card.openInProtonCalendar
import dev.maia.app.R
import dev.maia.app.flow.FaultReason
import dev.maia.app.flow.FlowEvent
import dev.maia.app.flow.FlowState
import dev.maia.app.ui.Maia
import dev.maia.app.ui.OrbStage
import dev.maia.app.ui.raisedEdge
import dev.maia.audio.Word
import java.time.ZonedDateTime

/**
 * The one sentence loop, 4a to 4i, as a function of [FlowState].
 *
 * Stateless: everything drawn comes from [state] or from a parameter, and
 * everything the user does leaves as one [FlowEvent] through [onEvent]. The
 * orb is not drawn here: every stage seats it with [OrbStage] and the cards
 * with their dock, and the host's `OrbHost` draws it with the pose
 * [dev.maia.app.flow.aperture] gives, so it cannot disagree with the screen.
 *
 * The live inputs are parameters rather than state because they change far
 * faster than the flow does. [words], [partial], [bytesDone], [bytesTotal] and
 * [bytesPerSecond] win over what the state last kept when they are given.
 * [now] is the clock the reducer runs on, and the host recomposes with it once
 * a second while an undo is offered.
 *
 * Three things have no event, because they are the host's to do: opening the
 * calendar chooser, re-reading the provider from 4i, and the date picker on
 * 4g. They arrive as [onOpenChooser], [onCheckCalendars] and [onPickDate].
 *
 * Every control sits in the lower half, per the handoff's "nothing critical
 * above 620 dp", except on the card, which is [PreviewCard] as M1 built it.
 */
@Composable
fun FlowScreen(
    state: FlowState,
    onEvent: (FlowEvent) -> Unit,
    now: Long,
    modifier: Modifier = Modifier,
    words: List<Word>? = null,
    partial: String? = null,
    bytesDone: Long? = null,
    bytesTotal: Long? = null,
    bytesPerSecond: Double = 0.0,
    writtenTo: String? = null,
    onOpenChooser: () -> Unit = {},
    onCheckCalendars: () -> Unit = {},
    onPickDate: () -> Unit = {},
    // D2, M4 row 7: which no-folder screen, and the folder picker. The host's,
    // like the three above: `NoFolder` carries no gone flag, and the picker's
    // answer is an activity result, not an effect.
    folderGone: Boolean = false,
    onChooseFolder: () -> Unit = {},
) {
    Box(modifier.fillMaxSize().background(Maia.colours.groundBase)) {
        when (state) {
            is FlowState.FirstRun -> OrbStage {
                FirstRun(firstRunLine(state.download, bytesDone, bytesTotal, bytesPerSecond), onEvent)
            }
            is FlowState.Idle -> OrbStage { Idle(state.transcript, onEvent) }
            is FlowState.Invoking -> OrbStage {
                Listening(inkedWords(words.orEmpty(), partial.orEmpty()), onEvent)
            }
            is FlowState.Listening -> OrbStage {
                Listening(inkedWords(words ?: state.words, partial ?: state.partial), onEvent)
            }
            is FlowState.Understanding -> OrbStage { Understanding(state.transcript, onEvent) }
            is FlowState.Preview -> Card(state, onEvent, onOpenChooser)
            is FlowState.Queued -> OrbStage { Queued(state, onEvent) }
            is FlowState.NoCalendar -> NoCalendars(
                draft = state.draft,
                onCheckAgain = onCheckCalendars,
                onDiscard = { onEvent(FlowEvent.Cancel) },
            )
            is FlowState.Committing -> OrbStage {
                Committing(state.target.calendar.displayName)
            }
            is FlowState.Confirmed -> OrbStage {
                Confirmed(state, undoLine(state.undo, undoSecondsLeft(now, state.undoDeadline)), writtenTo, onEvent)
            }
            is FlowState.Fault -> OrbStage { Fault(state, onEvent, onPickDate) }
            // M4: the note card is a full screen like the event card (D1, row 8).
            is FlowState.NotePreview -> dev.maia.app.card.NoteCard(
                note = state.note,
                folder = state.folder,
                error = state.error,
                holdProgress = state.hold,
                onEditBody = { onEvent(FlowEvent.NoteBodyEdited(it)) },
                onDiscard = { onEvent(FlowEvent.Cancel) },
                onHold = { onEvent(FlowEvent.Hold(it)) },
            )
            is FlowState.NoFolder -> NoFolderScreen(
                note = state.note,
                gone = folderGone,
                onChoose = onChooseFolder,
                onDiscard = { onEvent(FlowEvent.Cancel) },
            )
            is FlowState.Writing -> OrbStage { Writing(state) }
            is FlowState.NoteConfirmed -> OrbStage { NoteConfirmed(state, onEvent) }
        }
    }
}

@Composable
private fun Idle(transcript: String?, onEvent: (FlowEvent) -> Unit) {
    val colours = Maia.colours
    val heard = quoted(transcript)
    if (heard != null) {
        Column(verticalArrangement = Arrangement.spacedBy(Maia.space.sm)) {
            Eyebrow(FlowCopy.IDLE_LAST_HEARD)
            Text(heard, style = Maia.type.read, color = colours.inkMid)
        }
    }
    InvokeControl(FlowCopy.SPEAK) { onEvent(LAUNCHER_INVOKE) }
}

/** 4a and 4b: "go ahead" until there is a word, then the words as they settle. */
@Composable
private fun Listening(words: List<InkedWord>, onEvent: (FlowEvent) -> Unit) {
    val colours = Maia.colours
    if (words.isEmpty()) {
        Text(FlowCopy.GO_AHEAD, style = Maia.type.read, color = colours.inkLow)
    } else {
        val line = buildAnnotatedString {
            words.forEachIndexed { i, word ->
                if (i > 0) append(' ')
                val ink = if (word.confirmed) {
                    SpanStyle(color = colours.inkHigh, fontWeight = FontWeight.Medium)
                } else {
                    SpanStyle(color = colours.inkLow, fontWeight = FontWeight.Normal)
                }
                withStyle(ink) { append(word.text) }
            }
        }
        Text(line, style = Maia.type.read)
    }
    Quiet(FlowCopy.CANCEL) { onEvent(FlowEvent.Cancel) }
}

/** 4c: the sentence stays, dimmed, for the understanding floor. */
@Composable
private fun Understanding(transcript: String, onEvent: (FlowEvent) -> Unit) {
    Text(quoted(transcript).orEmpty(), style = Maia.type.read, color = Maia.colours.inkMid)
    Quiet(FlowCopy.CANCEL) { onEvent(FlowEvent.Cancel) }
}

@Composable
private fun Card(state: FlowState.Preview, onEvent: (FlowEvent) -> Unit, onOpenChooser: () -> Unit) {
    val context = LocalContext.current
    // The status bar is taken here rather than by the card's header, so the
    // heard note sits under the bar and the card's orb seat under the note.
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        state.heardNote?.let {
            Text(
                it,
                style = Maia.type.dataSmall,
                color = Maia.colours.inkLow,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Maia.colours.groundBase)
                    .padding(horizontal = Maia.space.gutter, vertical = Maia.space.sm),
            )
        }
        Box(Modifier.weight(1f)) {
            PreviewCard(
                draft = state.draft,
                target = state.target,
                error = state.error,
                holdProgress = state.hold,
                onEdit = { onEvent(FlowEvent.Edited(it)) },
                onOpenChooser = onOpenChooser,
                onDiscard = { onEvent(FlowEvent.Cancel) },
                onHold = { onEvent(FlowEvent.Hold(it)) },
                // The reducer commits on Hold(1f). A second commit path would
                // be a second road to a write.
                onCommit = {},
                // U2: the same words, kept as a note instead. The tap stamps the
                // note's time; the reducer decides whether a note may open.
                onKeepAsNote = { onEvent(FlowEvent.KeepAsNote(ZonedDateTime.now())) },
                // M4-R9 2d: fire-and-forget, same shape as NoCalendars.kt's
                // DAVx5 launch. Not routed through the reducer -- there is no
                // id, no confirmation, nothing for FlowEvent to carry back.
                onOpenInProton = { openInProtonCalendar(context, state.draft) },
            )
        }
    }
}

/**
 * Held while locked. Takes the summary and nothing else, so nothing a provider
 * read produced can reach this screen.
 */
@Composable
private fun Queued(state: FlowState.Queued, onEvent: (FlowEvent) -> Unit) {
    val colours = Maia.colours
    val summary = state.summary
    Column(verticalArrangement = Arrangement.spacedBy(Maia.space.sm)) {
        Eyebrow(FlowCopy.QUEUED_EYEBROW)
        Text(summary.title, style = Maia.type.read, color = colours.inkHigh)
        if (summary.whenText.isNotBlank()) {
            Text(summary.whenText, style = Maia.type.dataSmall, color = colours.inkStrong)
        }
        if (summary.transcript != summary.title) {
            quoted(summary.transcript)?.let { Text(it, style = Maia.type.body, color = colours.inkMid) }
        }
    }
    Buttons {
        Loud(FlowCopy.UNLOCK) { onEvent(FlowEvent.UnlockRequested) }
        Quiet(FlowCopy.DISCARD) { onEvent(FlowEvent.Cancel) }
    }
}

/** 4e. The write is already on its way, so there is nothing left to press. */
@Composable
private fun Committing(calendarName: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Maia.space.sm)) {
        Text(FlowCopy.WRITING, style = Maia.type.read, color = Maia.colours.inkHigh)
        Text("to $calendarName", style = Maia.type.body, color = Maia.colours.inkMid)
    }
}

/** 4f. */
@Composable
private fun Confirmed(
    state: FlowState.Confirmed,
    undo: UndoLine,
    calendarName: String?,
    onEvent: (FlowEvent) -> Unit,
) {
    val colours = Maia.colours
    val rows = cardRows(state.draft, target = null).filter { it.field == CardField.When || it.field == CardField.Duration }
    Column(verticalArrangement = Arrangement.spacedBy(Maia.space.sm)) {
        Eyebrow(writtenTo(calendarName))
        Text(state.draft.title.value, style = Maia.type.read, color = colours.inkHigh)
        rows.forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.label.uppercase(), style = Maia.type.label, color = colours.inkLow, modifier = Modifier.weight(1f))
                Text(
                    row.value,
                    style = if (row.mono) Maia.type.dataSmall else Maia.type.body,
                    color = colours.inkStrong,
                )
            }
        }
    }
    undo.note?.let { Text(it, style = Maia.type.caption, color = colours.inkMid) }
    Row(horizontalArrangement = Arrangement.spacedBy(Maia.space.sm)) {
        if (undo.action != null) {
            UndoButton(undo.action, undo.countdown.orEmpty(), Modifier.weight(1f)) { onEvent(FlowEvent.Undo) }
        }
        Loud(FlowCopy.DONE, Modifier.weight(1f)) { onEvent(FlowEvent.Cancel) }
    }
}

/** 4g, the capture failure, and the full queue. The orb is the fault; no colour says so. */
@Composable
private fun Fault(state: FlowState.Fault, onEvent: (FlowEvent) -> Unit, onPickDate: () -> Unit) {
    val colours = Maia.colours
    val copy = faultCopy(state.reason)
    val noteWrite = state.reason is FaultReason.NoteWriteFailed
    Column(verticalArrangement = Arrangement.spacedBy(Maia.space.sm)) {
        // M4 row 8: the note write fault takes the design seat's words.
        if (noteWrite) Eyebrow(stringResource(R.string.m4_note_write_fault_label))
        Text(
            if (noteWrite) stringResource(R.string.m4_note_write_fault_title) else copy.title,
            style = Maia.type.heading,
            color = colours.inkHigh,
        )
        quoted(state.transcript)?.let { Text(it, style = Maia.type.body, color = colours.inkStrong) }
        (if (noteWrite) stringResource(R.string.m4_note_write_fault_body) else copy.body)
            ?.let { Text(it, style = Maia.type.caption, color = colours.inkMid) }
    }
    val draft = state.draft
    when (state.reason) {
        FaultReason.NoDateHeard -> {
            if (draft != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(Maia.space.sm)) {
                    dateChips(draft.start.value).forEach { chip ->
                        Chip(chip, Modifier.weight(1f)) { onEvent(FlowEvent.DatePicked(chip.start)) }
                    }
                }
            }
            Buttons {
                Quiet(FlowCopy.PICK_DATE, onClick = onPickDate)
                InvokeControl(FlowCopy.SAY_DAY, loud = false) { onEvent(LAUNCHER_INVOKE) }
                Quiet(FlowCopy.LEAVE) { onEvent(FlowEvent.Cancel) }
            }
        }
        is FaultReason.CaptureFailed -> Buttons {
            InvokeControl(FlowCopy.TRY_AGAIN) { onEvent(LAUNCHER_INVOKE) }
            Quiet(FlowCopy.LEAVE) { onEvent(FlowEvent.Cancel) }
        }
        is FaultReason.NoteWriteFailed -> Buttons {
            // Try again reopens the note card; the session keeps the note
            // ([dev.maia.app.flow.FlowSession.retryNote]), not the fault.
            Loud(stringResource(R.string.m4_note_write_fault_retry_action)) { onEvent(FlowEvent.RetryNote) }
            Quiet(FlowCopy.LEAVE) { onEvent(FlowEvent.Cancel) }
        }
        FaultReason.QueueFull, FaultReason.FolderGone -> Buttons {
            Quiet(FlowCopy.LEAVE) { onEvent(FlowEvent.Cancel) }
        }
    }
}

/**
 * The note going into its file (M4 row 8). The folder is named unless it is the
 * top of a volume, where the only name there is is a volume id the copy rules
 * out, so the detail line is left off rather than reworded.
 */
@Composable
private fun Writing(state: FlowState.Writing) {
    Column(verticalArrangement = Arrangement.spacedBy(Maia.space.sm)) {
        Text(FlowCopy.WRITING, style = Maia.type.read, color = Maia.colours.inkHigh)
        if (!isStorageRoot(state.folder)) {
            Text(
                stringResource(R.string.m4_note_writing_detail, state.folder.name),
                style = Maia.type.body,
                color = Maia.colours.inkMid,
            )
        }
    }
}

/**
 * D4, the note confirmation. No undo, by design, and the description says so.
 *
 * The eyebrow is not [Eyebrow]: the string is already capitals, and the file
 * name inside it is a file name, which uppercasing would change. The line is
 * the note's time in the data face, then its body. The spoken "Noted." is the
 * reducer's [dev.maia.app.flow.Effect.SpeakNote], not this screen's.
 */
@Composable
private fun NoteConfirmed(state: FlowState.NoteConfirmed, onEvent: (FlowEvent) -> Unit) {
    val colours = Maia.colours
    val time = noteTime(state.note)
    val body = state.note.body.value
    val description = stringResource(R.string.m4_note_confirmed_cd, state.ref.fileName, time, body)
    val line = stringResource(R.string.m4_note_confirmed_line, time, body)
    Column(
        Modifier.clearAndSetSemantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(Maia.space.sm),
    ) {
        Text(
            stringResource(R.string.m4_note_confirmed_eyebrow, state.ref.fileName),
            style = Maia.type.label,
            color = colours.inkMid,
        )
        Text(
            buildAnnotatedString {
                withStyle(Maia.type.dataSmall.toSpanStyle()) { append(line.take(time.length)) }
                append(line.drop(time.length))
            },
            style = Maia.type.read,
            color = colours.inkHigh,
        )
        Text(stringResource(R.string.m4_note_confirmed_caption), style = Maia.type.caption, color = colours.inkLow)
    }
    Buttons {
        Loud(stringResource(R.string.m4_note_done_action)) { onEvent(FlowEvent.Cancel) }
    }
}

/** 4h. */
@Composable
private fun FirstRun(line: FirstRunLine, onEvent: (FlowEvent) -> Unit) {
    val colours = Maia.colours
    Column(verticalArrangement = Arrangement.spacedBy(Maia.space.md)) {
        Eyebrow(FlowCopy.FIRST_RUN_EYEBROW)
        Text(FlowCopy.FIRST_RUN_TITLE, style = Maia.type.heading, color = colours.inkHigh)
        Text(FlowCopy.FIRST_RUN_BODY, style = Maia.type.body, color = colours.inkMid)
        line.numbers?.let { Text(it, style = Maia.type.dataSmall, color = colours.inkStrong) }
        line.note?.let {
            Text(it, style = Maia.type.caption, color = if (line.failed) colours.faultNeutral else colours.inkLow)
        }
    }
    line.action?.let { Loud(it) { onEvent(FlowEvent.DownloadRequested) } }
}

// ------------------------------------------------------------------ pieces

@Composable
private fun Eyebrow(text: String) {
    Text(text.uppercase(), style = Maia.type.label, color = Maia.colours.inkMid)
}

@Composable
private fun Buttons(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Maia.space.sm),
        content = content,
    )
}

/**
 * The invoke control. It fires on the press, not the release, because the
 * invoke haptic is on the press and a capture that starts late loses the first
 * syllable. TalkBack gets an ordinary click action with the same label.
 */
@Composable
private fun InvokeControl(label: String, loud: Boolean = true, onInvoke: () -> Unit) {
    val latest by rememberUpdatedState(onInvoke)
    var pressed by remember { mutableStateOf(false) }
    val input = Modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    latest()
                    waitForUpOrCancellation()
                    pressed = false
                }
            }
            .semantics {
                role = Role.Button
                onClick(label = label) {
                    latest()
                    true
                }
            }
    // Loud is the round lit microphone; a quiet invoke sits in a stack of
    // pills ("Say the day") and is drawn as one of them.
    if (loud) InvokeFace(label, pressed, input) else ButtonFace(label, ButtonKind.Quiet, pressed, input)
}

@Composable
private fun Loud(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) =
    MaiaButton(text, ButtonKind.Loud, modifier, onClick = onClick)

@Composable
private fun Quiet(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) =
    MaiaButton(text, ButtonKind.Quiet, modifier, onClick = onClick)

@Composable
private fun UndoButton(text: String, countdown: String, modifier: Modifier, onClick: () -> Unit) {
    val colours = Maia.colours
    val shape = RoundedCornerShape(Maia.radius.button)
    Row(
        modifier
            .raisedEdge(colours, shape)
            .clip(shape)
            .border(BorderStroke(1.dp, colours.lineStrong), shape)
            .background(colours.surfaceRaised)
            .clickable(indication = null, interactionSource = null, onClick = onClick)
            .heightIn(min = Maia.space.touchTarget)
            .padding(horizontal = Maia.space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Maia.space.sm, Alignment.CenterHorizontally),
    ) {
        Text(text, style = Maia.type.body, color = colours.inkStrong)
        Text(countdown, style = Maia.type.dataSmall, color = colours.inkLow)
    }
}

@Composable
private fun Chip(chip: DateChip, modifier: Modifier, onClick: () -> Unit) {
    val colours = Maia.colours
    val shape = RoundedCornerShape(Maia.radius.chip)
    Column(
        modifier
            .raisedEdge(colours, shape)
            .clip(shape)
            .border(BorderStroke(1.dp, colours.lineStrong), shape)
            .background(colours.surfaceRaised)
            .clickable(indication = null, interactionSource = null, onClick = onClick)
            .heightIn(min = Maia.space.touchTarget)
            .padding(Maia.space.sm),
        verticalArrangement = Arrangement.spacedBy(Maia.space.xs),
    ) {
        Text(chip.label, style = Maia.type.body, color = colours.inkHigh)
        Text(chip.detail, style = Maia.type.label, color = colours.inkMid)
    }
}
