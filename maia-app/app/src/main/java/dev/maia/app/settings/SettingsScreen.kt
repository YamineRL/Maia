package dev.maia.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import dev.maia.app.R
import dev.maia.app.card.LoudButton
import dev.maia.app.notes.ChooseWeight
import dev.maia.app.notes.FolderRow
import dev.maia.app.notes.ValueInk
import dev.maia.app.notes.folderRowCopy
import dev.maia.app.screens.InkAction
import dev.maia.app.ui.ButtonKind
import dev.maia.app.ui.DockSide
import dev.maia.app.ui.Maia
import dev.maia.app.ui.MaiaButton
import dev.maia.app.ui.ModelDownload
import dev.maia.app.ui.OrbDock
import dev.maia.app.ui.dockInset
import dev.maia.app.ui.raisedEdge

private val SheetShape = RoundedCornerShape(topStart = Maia.radius.sheet, topEnd = Maia.radius.sheet)

/**
 * D5: the settings screen. The notes folder (M4 copy section 5, row 7), then
 * where events go.
 *
 * Stateless. [row] is null while the grant and the tree are still being read,
 * and then only the label is drawn, so a gone folder is never shown as held
 * for a frame. The strings come from [folderRowCopy], where they are tested.
 *
 * **The sheet.** Every choose on this screen opens [sheetOpen] first: the V1
 * body and "Choose a folder", and only its button opens the picker. The copy
 * asks for this on "Choose a different folder"; privacy V1 asks for it on any
 * screen that launches the picker, and this row's other two choose actions
 * launch it too. A tap outside the sheet, or back, closes it.
 *
 * **Release** is one tap and no dialog: the copy's reasoning is that choosing
 * the folder again undoes it and nothing is deleted.
 */
@Composable
fun SettingsScreen(
    row: FolderRow?,
    sheetOpen: Boolean,
    onChoose: () -> Unit,
    onRelease: () -> Unit,
    onSheetChoose: () -> Unit,
    onSheetDismiss: () -> Unit,
    eventsTo: EventsTo = EventsTo.Phone,
    onEventsTo: (EventsTo) -> Unit = {},
    homeCity: String = "",
    onHomeCity: (String) -> Unit = {},
    status: SettingsStatus? = null,
    downloads: Map<String, ModelDownload> = emptyMap(),
    onDownload: (ModelKind) -> Unit = {},
    onPermissions: () -> Unit = {},
) {
    val colours = Maia.colours
    Box(Modifier.fillMaxSize().background(colours.groundBase).imePadding()) {
        // The orb's seat, in the corner and out of the scroll so it stays put;
        // the host draws it there. The row starts under it.
        OrbDock(Modifier.padding(start = Maia.space.gutter))
        Column(
            Modifier
                .fillMaxSize()
                .dockInset()
                .padding(top = DockSide)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Maia.space.gutter)
                .padding(top = Maia.space.lg, bottom = Maia.space.xxl),
            verticalArrangement = Arrangement.spacedBy(Maia.space.lg),
        ) {
            NotesFolderRow(row, onChoose, onRelease)
            EventsRow(eventsTo, onEventsTo)
            HomeCityRow(homeCity, onHomeCity)
            StatusSections(status, downloads, onDownload, onPermissions)
        }
        if (sheetOpen) FolderSheet(onSheetChoose, onSheetDismiss)
    }
}

@Composable
private fun NotesFolderRow(row: FolderRow?, onChoose: () -> Unit, onRelease: () -> Unit) {
    val colours = Maia.colours
    val label = stringResource(R.string.m4_settings_notes_label)
    if (row == null) {
        Text(label, style = Maia.type.label, color = colours.inkMid)
        return
    }
    val copy = folderRowCopy(row)
    val rootName = stringResource(R.string.m4_settings_notes_value_root)
    val name = when (row) {
        is FolderRow.Held -> if (row.atRoot) rootName else row.name
        is FolderRow.Gone -> if (row.atRoot) rootName else row.name
        FolderRow.None, FolderRow.Released -> null
    }
    val value = if (copy.valueIsName) stringResource(copy.value, name.orEmpty()) else stringResource(copy.value)
    val caption = stringResource(copy.caption)
    // Held and gone read as the copy's one sentence about the grant; the gone
    // caption follows it, because that is the news. None and released read
    // their own lines, which say all there is.
    val rowCd = name?.let { stringResource(R.string.m4_settings_notes_row_cd, it) }
    val releaseCd = name?.let { stringResource(R.string.m4_settings_notes_release_cd, it) }

    Column(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = when {
                    rowCd == null -> "$label. $value. $caption"
                    row is FolderRow.Gone -> "$rowCd $caption"
                    else -> rowCd
                }
            },
        verticalArrangement = Arrangement.spacedBy(Maia.space.sm),
    ) {
        Text(label, style = Maia.type.label, color = colours.inkMid)
        Text(
            value,
            style = Maia.type.body,
            color = if (copy.valueInk == ValueInk.High) colours.inkHigh else colours.inkLow,
        )
        Text(caption, style = Maia.type.caption, color = colours.inkMid)
    }
    Column(verticalArrangement = Arrangement.spacedBy(Maia.space.sm)) {
        val choose = stringResource(copy.choose)
        when (copy.chooseWeight) {
            ChooseWeight.Loud -> LoudButton(choose, Modifier.fillMaxWidth(), onClick = onChoose)
            ChooseWeight.Mid -> InkAction(choose, colours.inkMid, Modifier.fillMaxWidth(), onClick = onChoose)
        }
        if (copy.release) {
            InkAction(
                stringResource(R.string.m4_settings_notes_release_action),
                colours.inkLow,
                Modifier
                    .fillMaxWidth()
                    .semantics { releaseCd?.let { contentDescription = it } },
                onClick = onRelease,
            )
        }
    }
}

/**
 * Where events go. One value, one line on what it means, one action that
 * flips it, drawn like the folder row above it so the screen reads as a list.
 */
@Composable
private fun EventsRow(eventsTo: EventsTo, onEventsTo: (EventsTo) -> Unit) {
    val colours = Maia.colours
    val proton = eventsTo == EventsTo.Proton
    Column(
        Modifier.fillMaxWidth().padding(top = Maia.space.lg),
        verticalArrangement = Arrangement.spacedBy(Maia.space.sm),
    ) {
        Text(stringResource(R.string.settings_events_label), style = Maia.type.label, color = colours.inkMid)
        Text(
            stringResource(if (proton) R.string.settings_events_proton else R.string.settings_events_phone),
            style = Maia.type.body,
            color = colours.inkHigh,
        )
        Text(
            stringResource(
                if (proton) R.string.settings_events_proton_caption else R.string.settings_events_phone_caption,
            ),
            style = Maia.type.caption,
            color = colours.inkMid,
        )
    }
    MaiaButton(
        stringResource(if (proton) R.string.settings_events_to_phone else R.string.settings_events_to_proton),
        ButtonKind.Quiet,
        Modifier.fillMaxWidth(),
    ) { onEventsTo(if (proton) EventsTo.Phone else EventsTo.Proton) }
}

/**
 * The home city, for weather asked without a place. A field rather than a
 * spoken setting because it is typed once, and the recogniser mishears
 * place names more than anything else. Saved as it is typed.
 */
@Composable
private fun HomeCityRow(city: String, onCity: (String) -> Unit) {
    val colours = Maia.colours
    val label = stringResource(R.string.settings_city_label)
    val focus = LocalFocusManager.current
    Column(
        Modifier.fillMaxWidth().padding(top = Maia.space.lg),
        verticalArrangement = Arrangement.spacedBy(Maia.space.sm),
    ) {
        Text(label, style = Maia.type.label, color = colours.inkMid)
        Box {
            if (city.isEmpty()) {
                Text(
                    stringResource(R.string.settings_city_hint),
                    style = Maia.type.body,
                    color = colours.inkLow,
                    modifier = Modifier.padding(Maia.space.md).clearAndSetSemantics { },
                )
            }
            BasicTextField(
                value = city,
                onValueChange = { onCity(it.take(MAX_CITY)) },
                singleLine = true,
                textStyle = Maia.type.body.copy(color = colours.inkHigh),
                cursorBrush = SolidColor(colours.accentAperture),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colours.surfaceField)
                    .heightIn(min = Maia.space.touchTarget)
                    .padding(Maia.space.md)
                    .semantics { contentDescription = label },
            )
        }
        Text(stringResource(R.string.settings_city_caption), style = Maia.type.caption, color = colours.inkMid)
    }
}

private const val MAX_CITY = 60

@Composable
private fun FolderSheet(onChoose: () -> Unit, onDismiss: () -> Unit) {
    val colours = Maia.colours
    val chooseCd = stringResource(R.string.m4_no_folder_choose_cd)
    Box(
        Modifier
            .fillMaxSize()
            .background(colours.groundVoid.copy(alpha = 0.6f))
            .clickable(indication = null, interactionSource = null, onClick = onDismiss),
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .raisedEdge(colours, SheetShape)
                .clip(SheetShape)
                .background(colours.surfaceRaised)
                // Swallow taps on the sheet itself, so only the scrim dismisses.
                .clickable(indication = null, interactionSource = null) { }
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Maia.space.gutter, vertical = Maia.space.xl),
            verticalArrangement = Arrangement.spacedBy(Maia.space.lg),
        ) {
            Text(stringResource(R.string.m4_no_folder_body), style = Maia.type.body, color = colours.inkStrong)
            LoudButton(
                stringResource(R.string.m4_no_folder_choose_action),
                Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = chooseCd },
                onClick = onChoose,
            )
        }
    }
}
