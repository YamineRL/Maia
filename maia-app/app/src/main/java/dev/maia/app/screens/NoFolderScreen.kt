package dev.maia.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import dev.maia.actions.notes.Note
import dev.maia.app.R
import dev.maia.app.card.LoudButton
import dev.maia.app.notes.noFolderCopy
import dev.maia.app.ui.LocalMaiaColours
import dev.maia.app.ui.Maia
import dev.maia.app.ui.MaiaTheme
import dev.maia.app.ui.maiaColours
import dev.maia.nlu.Field
import dev.maia.nlu.Provenance
import java.time.ZonedDateTime

/**
 * D2: nowhere to keep notes, and the notes folder gone. M4 copy section 2,
 * brief section 2.2, row 7.
 *
 * **One screen, two first lines**, modelled on M1's `NoCalendars` and drawn
 * the same way: the whole screen, not a stage under the orb, because the V1
 * sentence is long and needs the room. [gone] picks the title, puts the gone
 * body in front of the V1 body, and says "Choose a folder again".
 *
 * **The V1 sentence is on it both times.** Privacy V1 puts the three facts on
 * the screen that launches `ACTION_OPEN_DOCUMENT_TREE`, before it opens, and
 * both versions of this screen launch it. The copy's "said once" rule is per
 * screen; the gone body says what happened, V1 says what picking means.
 *
 * **The held note stays in view.** The title and the two actions do not move;
 * the bodies and the held lines scroll between them, so at the largest font
 * scale (P6, owed to the phone) a long sentence pushes nothing off the screen
 * that the user has to act on.
 *
 * **TalkBack** reads title, body, held, held note, choose, discard (copy
 * section 2). The eyebrow is a label and is not read; the two held lines are
 * one node with `m4_no_folder_held_cd`.
 *
 * **What choosing does is the host's.** [onChoose] opens the picker
 * (`FolderPicker`), and a cancel there changes nothing here, with no fault.
 * A picked folder is re-read by the host and reaches the reducer as the same
 * `NotesFolderLoaded` the note card waits for, which returns to the note card
 * with the folder named. See the row 7 entry in `docs/M4-status.md` for why it
 * does not write on its own.
 */
@Composable
fun NoFolderScreen(
    note: Note,
    gone: Boolean,
    onChoose: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colours = Maia.colours
    val copy = noFolderCopy(gone)
    val held = note.body.value
    val heldCd = stringResource(R.string.m4_no_folder_held_cd, held)
    val chooseCd = stringResource(R.string.m4_no_folder_choose_cd)
    Column(
        modifier
            .fillMaxSize()
            .background(colours.groundBase)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = Maia.space.gutter, vertical = Maia.space.xxl)
            .semantics { isTraversalGroup = true },
        verticalArrangement = Arrangement.spacedBy(Maia.space.lg),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Maia.space.md)) {
            Text(
                stringResource(R.string.m4_no_folder_eyebrow),
                style = Maia.type.label,
                color = colours.inkMid,
                modifier = Modifier.clearAndSetSemantics { },
            )
            Text(
                stringResource(copy.title),
                style = Maia.type.title,
                color = colours.inkHigh,
                modifier = Modifier.semantics {
                    heading()
                    traversalIndex = 0f
                },
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .semantics { traversalIndex = 1f }
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Maia.space.md),
        ) {
            copy.bodies.forEach { body ->
                Text(stringResource(body), style = Maia.type.body, color = colours.inkMid)
            }
            Column(
                Modifier
                    .padding(top = Maia.space.sm)
                    .clearAndSetSemantics { contentDescription = heldCd },
                verticalArrangement = Arrangement.spacedBy(Maia.space.xs),
            ) {
                Text(
                    stringResource(R.string.m4_no_folder_held, held),
                    style = Maia.type.caption,
                    color = colours.inkStrong,
                )
                Text(
                    stringResource(R.string.m4_no_folder_held_note),
                    style = Maia.type.caption,
                    color = colours.inkMid,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Maia.space.sm)) {
            LoudButton(
                stringResource(copy.choose),
                Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = chooseCd
                        traversalIndex = 2f
                    },
                onClick = onChoose,
            )
            InkAction(
                stringResource(R.string.m4_no_folder_discard_action),
                colours.inkLow,
                Modifier
                    .fillMaxWidth()
                    .semantics { traversalIndex = 3f },
                onClick = onDiscard,
            )
        }
    }
}

/**
 * An action drawn as ink and nothing else: the copy's `action, ink.low` and
 * `action, ink.mid` lines, which are neither the loud button nor the bordered
 * quiet one. Full touch target, no ripple, as `LoudButton` does.
 */
@Composable
internal fun InkAction(text: String, colour: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text,
        style = Maia.type.action,
        color = colour,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clickable(indication = null, interactionSource = null, onClick = onClick)
            .heightIn(min = Maia.space.touchTarget)
            .padding(vertical = Maia.space.md),
    )
}

private val previewNote = Note(
    body = Field("call the vet", Provenance.Heard, 1..3),
    at = ZonedDateTime.of(2026, 9, 13, 10, 0, 0, 0, java.time.ZoneId.of("Europe/Zurich")),
)

@Preview(showBackground = true, heightDp = 780)
@Composable
private fun NoFolderPreview() = MaiaTheme {
    CompositionLocalProvider(LocalMaiaColours provides maiaColours()) {
        NoFolderScreen(previewNote, gone = false, onChoose = {}, onDiscard = {})
    }
}

@Preview(showBackground = true, heightDp = 780)
@Composable
private fun FolderGonePreview() = MaiaTheme {
    CompositionLocalProvider(LocalMaiaColours provides maiaColours()) {
        NoFolderScreen(previewNote, gone = true, onChoose = {}, onDiscard = {})
    }
}
