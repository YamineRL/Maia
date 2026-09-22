package dev.maia.app.card

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.dp
import dev.maia.actions.notes.Note
import dev.maia.actions.notes.NoteMarkdown
import dev.maia.actions.notes.NotesFolder
import dev.maia.app.R
import dev.maia.app.ui.Maia
import dev.maia.app.ui.OrbDock
import dev.maia.app.ui.dockInset

/**
 * The note card, D1. `docs/M4-copy.md` section 1, M4 row 8.
 *
 * The `3b` ledger the event card uses, with fewer rows: eyebrow, Note, Due
 * (only when the note has a due time), Goes into, the hold, one caption,
 * Discard. Same gutter, same row height, same four guessed cues on a guessed
 * Due row, and the same [HoldToWrite] rather than a copy of it, so a note's
 * 600 ms and its haptic ladder cannot drift from an event's.
 *
 * Stateless like [PreviewCard]: the one piece of local state is whether the
 * body's editor is open, and the edit itself leaves through [onEditBody].
 *
 * **What the rows can change.** The Note row edits, through the event card's
 * own text editor. The Due row does not yet: there is no reducer event that
 * changes a note's due time, and the event card's When editor edits an
 * `EventDraft`. Its description still carries the copy's "Double tap to
 * change", verbatim, and the gap is written down in `docs/M4-status.md` rather
 * than hidden by rewording the design seat's string. The Goes into row is not
 * a control: the folder is changed where it was picked.
 *
 * The folder row waits for [folder]. It is null only for the moment between
 * the card opening and the folder read answering (no folder at all is the
 * separate no-folder screen), and the hold is disabled for that moment, which
 * matches the reducer: a hold at 1 with no folder resets and writes nothing.
 *
 * P16 read order: eyebrow, body, due, folder, hold, Discard. The caption is
 * skipped because the hold's description already carries it.
 */
@Composable
fun NoteCard(
    note: Note,
    folder: NotesFolder?,
    error: String?,
    holdProgress: Float,
    onEditBody: (String) -> Unit,
    onDiscard: () -> Unit,
    onHold: (Float) -> Unit,
) {
    val colours = Maia.colours
    var editing by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(colours.groundBase)
            .semantics { isTraversalGroup = true },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .dockInset()
                .padding(start = Maia.space.gutter, end = Maia.space.gutter, bottom = Maia.space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The orb's seat, in the corner; the host draws it there.
            OrbDock(inset = false)
            Text(
                stringResource(R.string.m4_note_card_eyebrow),
                style = Maia.type.label,
                color = colours.inkMid,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = Maia.space.lg)
                    .semantics { traversalIndex = 0f },
            )
        }
        NoteHairline()

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            NoteRow(
                label = stringResource(R.string.m4_note_row_body_label),
                value = note.body.value,
                mark = Mark.Heard,
                description = stringResource(R.string.m4_note_row_body_cd, note.body.value),
                order = 1f,
                onClick = { editing = true },
            )

            val due = note.remindAt
            val mark = dueMark(due)
            if (due != null && mark != null) {
                val guessed = mark == Mark.Guessed
                val spoken = dueSpoken(due.value)
                NoteRow(
                    label = stringResource(R.string.m4_note_row_due_label),
                    value = dueValue(due.value),
                    mark = mark,
                    reason = if (guessed) stringResource(R.string.m4_note_due_guessed_reason) else null,
                    mono = true,
                    description = if (guessed) {
                        stringResource(R.string.m4_note_row_due_guessed_cd, spoken)
                    } else {
                        stringResource(R.string.m4_note_row_due_cd, spoken)
                    },
                    order = 2f,
                    onClick = null,
                )
            }

            if (folder != null) {
                val label = stringResource(R.string.m4_note_row_folder_label)
                val root = isStorageRoot(folder)
                val value = if (root) {
                    stringResource(R.string.m4_note_row_folder_value_root)
                } else {
                    stringResource(R.string.m4_note_row_folder_value, folder.name)
                }
                NoteRow(
                    label = label,
                    value = value,
                    mark = Mark.Heard,
                    detail = stringResource(R.string.m4_note_row_folder_detail, NoteMarkdown.fileName(note)),
                    // `m4_note_row_folder_cd` names the folder, and at the top
                    // of a volume the only name there is is the volume id the
                    // copy rules out. The row's own two strings say it instead,
                    // label then value, which is what the eye reads too.
                    description = if (root) "$label $value." else stringResource(R.string.m4_note_row_folder_cd, folder.name),
                    order = 3f,
                    onClick = null,
                )
            }

            error?.let {
                Text(
                    it,
                    style = Maia.type.caption,
                    color = colours.inkMid,
                    modifier = Modifier.padding(horizontal = Maia.space.gutter, vertical = Maia.space.lg),
                )
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(colours.groundVoid)
                .padding(
                    start = Maia.space.gutter,
                    end = Maia.space.gutter,
                    top = 18.dp,
                    bottom = Maia.space.footer,
                ),
            verticalArrangement = Arrangement.spacedBy(Maia.space.md),
        ) {
            val hold = stringResource(R.string.m4_note_hold_action)
            val holdDescription = stringResource(R.string.m4_note_hold_cd)
            HoldToWrite(
                progress = holdProgress,
                enabled = folder != null,
                onHold = onHold,
                // The reducer writes on Hold(1f). A second commit path would be
                // a second road to a write.
                onCommit = {},
                modifier = Modifier.semantics { traversalIndex = 4f },
                label = hold,
                description = holdDescription,
                disabledLabel = hold,
                disabledDescription = holdDescription,
            )
            Text(
                stringResource(R.string.m4_note_hold_caption),
                style = Maia.type.caption,
                color = colours.inkLow,
                modifier = Modifier.clearAndSetSemantics { },
            )
            val discard = stringResource(R.string.m4_note_discard_cd)
            Text(
                stringResource(R.string.m4_note_discard_action),
                style = Maia.type.action,
                color = colours.inkLow,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(indication = null, interactionSource = null, onClick = onDiscard)
                    .heightIn(min = Maia.space.touchTarget)
                    .padding(vertical = Maia.space.sm)
                    .semantics {
                        role = Role.Button
                        contentDescription = discard
                        traversalIndex = 5f
                    },
            )
        }
    }

    if (editing) {
        TextEditor(
            label = stringResource(R.string.m4_note_row_body_label),
            initial = note.body.value,
            // The copy gives the note editor no hint line, and inventing one
            // here would be engineering writing copy.
            hint = "",
            onDone = {
                onEditBody(it)
                editing = false
            },
            onDismiss = { editing = false },
        )
    }
}

/**
 * One row of the note card: the event card's `FieldRow`, drawn from a label, a
 * value and a [Mark] instead of a `CardRow`, because a note's rows are not
 * `CardField`s and their words come from string resources.
 */
@Composable
private fun NoteRow(
    label: String,
    value: String,
    mark: Mark,
    description: String,
    order: Float,
    onClick: (() -> Unit)?,
    reason: String? = null,
    detail: String? = null,
    mono: Boolean = false,
) {
    val colours = Maia.colours
    val guessed = mark == Mark.Guessed

    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(indication = null, interactionSource = null, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .hatched(colours.hatch, on = guessed)
            .heightIn(min = Maia.space.rowMinHeight)
            .padding(horizontal = Maia.space.gutter, vertical = 22.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                traversalIndex = order
            },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        GutterDiamond(mark, Modifier.padding(top = 6.dp))

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label.uppercase(), style = Maia.type.label, color = colours.inkLow)
                if (guessed) GuessChip()
            }
            Text(
                text = bracketed(value, mark),
                style = if (mono) Maia.type.data else Maia.type.heading,
                color = colours.inkHigh,
                modifier = Modifier
                    .padding(bottom = 3.dp)
                    .then(if (guessed) Modifier.dashedUnderline(colours.inkLow) else Modifier),
            )
            detail?.let { Text(it, style = Maia.type.dataSmall, color = colours.inkLow) }
            reason?.let { Text(it, style = Maia.type.caption, color = colours.inkLow) }
        }

        if (onClick != null) {
            Text(
                "›",
                style = Maia.type.heading,
                color = if (guessed) colours.inkMid else colours.inkFaint,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clearAndSetSemantics { },
            )
        }
    }
    NoteHairline()
}

@Composable
private fun NoteHairline() {
    Box(
        Modifier
            .fillMaxWidth()
            .size(1.dp)
            .background(Maia.colours.lineHair),
    )
}
