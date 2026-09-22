package dev.maia.app.card

import dev.maia.app.ui.MaiaButton
import dev.maia.app.ui.ButtonKind
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.window.Dialog
import dev.maia.app.ui.Maia
import dev.maia.app.ui.raisedEdge
import dev.maia.nlu.Edit
import dev.maia.nlu.EventDraft
import java.time.Duration

/**
 * How a field is changed.
 *
 * Platform pickers for the date and the time, per section 6 of the M1 brief:
 * no custom pickers at M1, because a bespoke wheel is exactly the sort of
 * thing that eats a milestone and is thrown away at the next one. The handoff
 * does specify custom editors and M2 owns them.
 *
 * Every path out of here is one [Edit] through the draft's reducer, which is
 * what makes the correction stick: an edited field becomes
 * [dev.maia.nlu.Provenance.Corrected] and a later re-parse is forbidden from
 * overwriting it.
 */
@Composable
fun FieldEditor(
    field: CardField,
    draft: EventDraft,
    onEdit: (Edit) -> Unit,
    onDismiss: () -> Unit,
) {
    when (field) {
        CardField.Title -> TextEditor(
            label = "Title",
            initial = draft.title.value,
            hint = "what the event is called",
            onDone = { onEdit(Edit.Title(it)); onDismiss() },
            onDismiss = onDismiss,
        )

        CardField.Location -> TextEditor(
            label = "Location",
            initial = draft.location?.value.orEmpty(),
            hint = "leave it empty and the event still writes",
            onDone = { onEdit(Edit.Location(it)); onDismiss() },
            onDismiss = onDismiss,
        )

        CardField.Duration -> DurationEditor(
            allDay = draft.allDay,
            onPick = { onEdit(it); onDismiss() },
            onDismiss = onDismiss,
        )

        CardField.When -> WhenEditor(draft = draft, onEdit = onEdit, onDismiss = onDismiss)

        // The calendar is a screen, not a dialog: it is the one field whose
        // choice is remembered past this event.
        CardField.Calendar -> onDismiss()
    }
}

/** Internal so the note card's body row edits the way the event card's title does (M4 row 8). */
@Composable
internal fun TextEditor(
    label: String,
    initial: String,
    hint: String,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colours = Maia.colours
    var text by remember { mutableStateOf(initial) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .raisedEdge(colours, RoundedCornerShape(Maia.radius.sheet))
                .clip(RoundedCornerShape(Maia.radius.sheet))
                .background(colours.surfaceRaised)
                .padding(Maia.space.lg),
            verticalArrangement = Arrangement.spacedBy(Maia.space.md),
        ) {
            Text(label.uppercase(), style = Maia.type.label, color = colours.inkMid)
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = Maia.type.heading.copy(color = colours.inkHigh),
                cursorBrush = SolidColor(colours.accentAperture),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = { onDone(text) },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colours.surfaceField)
                    .padding(Maia.space.md),
            )
            Text(hint, style = Maia.type.caption, color = colours.inkLow)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Maia.space.sm),
            ) {
                QuietButton("Cancel", Modifier.weight(1f), onDismiss)
                LoudButton("Keep", Modifier.weight(2f)) { onDone(text) }
            }
        }
    }
}

/**
 * A short list, which is the brief's instruction and also the right one: the
 * durations people actually say are a handful, and a wheel that can express
 * 1 hour 37 minutes is a wheel that makes 1 hour slower to pick.
 */
@Composable
private fun DurationEditor(allDay: Boolean, onPick: (Edit) -> Unit, onDismiss: () -> Unit) {
    val colours = Maia.colours
    val choices = listOf(
        "0:15" to Duration.ofMinutes(15),
        "0:30" to Duration.ofMinutes(30),
        "0:45" to Duration.ofMinutes(45),
        "1:00" to Duration.ofHours(1),
        "1:30" to Duration.ofMinutes(90),
        "2:00" to Duration.ofHours(2),
        "3:00" to Duration.ofHours(3),
    )

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .raisedEdge(colours, RoundedCornerShape(Maia.radius.sheet))
                .clip(RoundedCornerShape(Maia.radius.sheet))
                .background(colours.surfaceRaised)
                .padding(vertical = Maia.space.md),
        ) {
            Text(
                "DURATION",
                style = Maia.type.label,
                color = colours.inkMid,
                modifier = Modifier.padding(horizontal = Maia.space.lg, vertical = Maia.space.sm),
            )
            choices.forEach { (text, duration) ->
                ListRow(text, mono = true) { onPick(Edit.Length(duration)) }
            }
            ListRow(
                if (allDay) "All day (already)" else "All day",
                mono = false,
            ) { onPick(Edit.AllDay(!allDay)) }
        }
    }
}

/**
 * Date then time, in that order, both from the platform.
 *
 * An all-day draft gets the date picker alone: asking for a time and then
 * throwing it away is the interface wasting the user's attention on a
 * decision it had already made.
 */
@Composable
private fun WhenEditor(draft: EventDraft, onEdit: (Edit) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val start = draft.start.value

    DisposableEffect(draft.start.value, draft.allDay) {
        val date = DatePickerDialog(
            context,
            { _, year, month, day ->
                val moved = start.withYear(year).withMonth(month + 1).withDayOfMonth(day)
                if (draft.allDay) {
                    onEdit(Edit.Start(moved))
                    onDismiss()
                } else {
                    TimePickerDialog(
                        context,
                        { _, hour, minute ->
                            onEdit(Edit.Start(moved.withHour(hour).withMinute(minute)))
                            onDismiss()
                        },
                        start.hour,
                        start.minute,
                        true,
                    ).apply { setOnCancelListener { onDismiss() } }.show()
                }
            },
            start.year,
            start.monthValue - 1,
            start.dayOfMonth,
        )
        date.setOnCancelListener { onDismiss() }
        date.show()
        onDispose { date.dismiss() }
    }
}

// ------------------------------------------------------------------ pieces

@Composable
internal fun ListRow(text: String, mono: Boolean, onClick: () -> Unit) {
    val colours = Maia.colours
    Text(
        text,
        style = if (mono) Maia.type.data else Maia.type.body,
        color = colours.inkHigh,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(indication = null, interactionSource = null, onClick = onClick)
            .heightIn(min = Maia.space.touchTarget)
            .padding(horizontal = Maia.space.lg, vertical = Maia.space.md),
    )
}

@Composable
internal fun QuietButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) =
    MaiaButton(text, ButtonKind.Quiet, modifier, onClick = onClick)

@Composable
internal fun LoudButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) =
    MaiaButton(text, ButtonKind.Loud, modifier, onClick = onClick)

@Composable
internal fun ScreenTitle(eyebrow: String, title: String, body: String? = null) {
    val colours = Maia.colours
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Maia.space.md),
    ) {
        Text(eyebrow.uppercase(), style = Maia.type.label, color = colours.inkMid)
        Text(title, style = Maia.type.read, color = colours.inkHigh)
        body?.let { Text(it, style = Maia.type.body, color = colours.inkMid) }
    }
}

@Composable
internal fun Aligned(content: @Composable () -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically) { content() }
