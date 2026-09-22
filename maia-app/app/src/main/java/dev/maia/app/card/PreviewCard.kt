package dev.maia.app.card

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextAlign
import dev.maia.app.R
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import dev.maia.actions.CalendarTarget
import dev.maia.app.ui.Maia
import dev.maia.app.ui.OrbDock
import dev.maia.app.ui.dockInset
import dev.maia.nlu.Edit
import dev.maia.nlu.EventDraft
import kotlinx.coroutines.launch

/**
 * The trust screen, structure `3b` from the handoff.
 *
 * Three structures were offered and this is the recommended one, and it is
 * also the only one of the three that does not fight section 6 of the M1
 * brief: a ledger of labelled rows is what the brief already specified, so
 * choosing it costs nothing that has to be undone at M2. `3a`'s tappable
 * spans inside a sentence and `3c`'s one-field-at-a-time queue are both real
 * designs and both are a bigger build than a screen this milestone throws
 * away is worth.
 *
 * What the card must never do is present something Maia invented as something
 * the user said. Everything below exists for that: see [CardRows], which is
 * where it is decided, and [Marks], which is where it is drawn four ways.
 */
@Composable
fun PreviewCard(
    draft: EventDraft,
    target: CalendarTarget?,
    error: String?,
    holdProgress: Float,
    onEdit: (Edit) -> Unit,
    onOpenChooser: () -> Unit,
    onDiscard: () -> Unit,
    onHold: (Float) -> Unit,
    onCommit: () -> Unit,
    /**
     * U2, amended (M4 row 8): "Keep as a note instead", under the hold. Null
     * draws no button, so a caller that says nothing keeps M3's card exactly.
     */
    onKeepAsNote: (() -> Unit)? = null,
    /**
     * M4-R9 2d, acted on: "Create in Proton Calendar instead", under the
     * hold, alongside [onKeepAsNote]. Same reasoning: null draws nothing, so
     * a caller that says nothing keeps the card exactly as it was.
     */
    onOpenInProton: (() -> Unit)? = null,
) {
    val colours = Maia.colours
    val rows = cardRows(draft, target)
    var editing by remember { mutableStateOf<CardField?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(colours.groundBase),
    ) {
        Header(tally = tally(rows), onDiscard = onDiscard)

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            rows.forEach { row ->
                FieldRow(row) {
                    if (row.field == CardField.Calendar) onOpenChooser() else editing = row.field
                }
            }
            error?.let { Fault(it) }
        }

        Commit(
            progress = holdProgress,
            enabled = target != null,
            onHold = onHold,
            onCommit = onCommit,
            onKeepAsNote = onKeepAsNote,
            onOpenInProton = onOpenInProton,
        )
    }

    editing?.let { field ->
        FieldEditor(
            field = field,
            draft = draft,
            onEdit = onEdit,
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun Header(tally: String, onDiscard: () -> Unit) {
    val colours = Maia.colours
    Row(
        Modifier
            .fillMaxWidth()
            .background(colours.groundBase)
            .dockInset()
            .padding(start = Maia.space.gutter, end = Maia.space.gutter, bottom = Maia.space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The orb's seat, in the corner; the host draws it there.
        OrbDock(inset = false)
        Column(
            Modifier.weight(1f).padding(start = Maia.space.lg),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text("New event", style = Maia.type.body, color = colours.inkHigh)
            Text(tally, style = Maia.type.label, color = colours.inkLow)
        }
        Text(
            "Discard",
            style = Maia.type.caption,
            color = colours.inkMid,
            modifier = Modifier
                .clickable(indication = null, interactionSource = null, onClick = onDiscard)
                .heightIn(min = Maia.space.touchTarget)
                .padding(top = Maia.space.md, start = Maia.space.md),
        )
    }
    Hairline()
}

@Composable
private fun Hairline() {
    Box(
        Modifier
            .fillMaxWidth()
            .size(1.dp)
            .background(Maia.colours.lineHair),
    )
}

/**
 * One field. The gutter mark, the label with its chip, the value, and the
 * account of why it looks that way.
 */
@Composable
private fun FieldRow(row: CardRow, onClick: () -> Unit) {
    val colours = Maia.colours
    val guessed = row.mark == Mark.Guessed
    val value = bracketed(row.value, row.mark)

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(indication = null, interactionSource = null, onClick = onClick)
            .hatched(colours.hatch, on = guessed)
            .heightIn(min = Maia.space.rowMinHeight)
            .padding(horizontal = Maia.space.gutter, vertical = 22.dp)
            .semantics {
                // The four visual cues say "guessed" to a person looking at
                // the screen. This is the same statement for a person who is
                // not, and it says the word rather than describing a shape.
                contentDescription = buildString {
                    append("${row.label}, ${row.value}")
                    when (row.mark) {
                        Mark.Guessed -> append(", guessed by Maia")
                        Mark.Empty -> append(", not set")
                        Mark.Heard -> Unit
                    }
                    row.reason?.let { append(". $it") }
                }
            },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        GutterDiamond(row.mark, Modifier.padding(top = 6.dp))

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(row.label.uppercase(), style = Maia.type.label, color = colours.inkLow)
                if (guessed) GuessChip()
            }

            val style = if (row.mono) Maia.type.data else Maia.type.heading
            Text(
                text = value,
                style = style,
                color = if (row.mark == Mark.Empty) colours.inkLow else colours.inkHigh,
                modifier = Modifier
                    .padding(bottom = 3.dp)
                    .then(
                        when (row.mark) {
                            Mark.Guessed -> Modifier.dashedUnderline(colours.inkLow)
                            Mark.Empty -> Modifier.dashedUnderline(colours.lineStrong)
                            Mark.Heard -> Modifier
                        },
                    ),
            )

            row.reason?.let {
                Text(it, style = Maia.type.caption, color = colours.inkLow)
            }
        }

        Text(
            "›",
            style = Maia.type.heading,
            // The chevron is the one place ink.faint is allowed, and only
            // because it never carries meaning on its own: the whole row is
            // the target and the row says what it does.
            color = if (guessed) colours.inkMid else colours.inkFaint,
            modifier = Modifier
                .padding(top = 6.dp)
                .clearAndSetSemantics { },
        )
    }
    Hairline()
}

@Composable
private fun Fault(message: String) {
    val colours = Maia.colours
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Maia.space.gutter, vertical = Maia.space.lg),
        verticalArrangement = Arrangement.spacedBy(Maia.space.sm),
    ) {
        // No red. Fault in this product is the absence of chroma, never the
        // arrival of a second hue, so the fault colour is a neutral and the
        // weight of the message is carried by the words.
        Text("COULD NOT WRITE", style = Maia.type.label, color = colours.faultNeutral)
        Text(message, style = Maia.type.body, color = colours.inkMid)
    }
}

/**
 * Press and hold for 600 ms, with a fill sweeping left to right.
 *
 * A hold rather than a tap because this is the one action in the product that
 * leaves something behind, and it should cost deliberate pressure. Releasing
 * early does nothing and says nothing: the absent commit is the feedback, and
 * an error message for a gesture the user chose not to finish would be the
 * app arguing with them.
 */
@Composable
private fun Commit(
    progress: Float,
    enabled: Boolean,
    onHold: (Float) -> Unit,
    onCommit: () -> Unit,
    onKeepAsNote: (() -> Unit)?,
    onOpenInProton: (() -> Unit)?,
) {
    val colours = Maia.colours
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
        HoldToWrite(progress = progress, enabled = enabled, onHold = onHold, onCommit = onCommit)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "nothing is written by talking alone",
                style = Maia.type.label,
                color = colours.inkLow,
            )
            Text(
                "${(progress * 100).toInt()}%",
                style = Maia.type.label,
                color = colours.inkLow,
                modifier = Modifier
                    .width(44.dp)
                    .alpha(if (progress > 0f) 1f else 0f),
            )
        }
        onKeepAsNote?.let { KeepAsNote(it) }
        onOpenInProton?.let { OpenInProton(it) }
    }
}

/**
 * "Keep as a note instead", `docs/M4-copy.md` section 1 (U2, amended): one
 * quiet action under the hold, in `action` style and `ink.mid`. It opens the
 * note card and writes nothing, which the visible label cannot fit and the
 * description says. Quiet on purpose: the hold is the card's one loud thing.
 */
@Composable
private fun KeepAsNote(onClick: () -> Unit) {
    val description = stringResource(R.string.m4_event_keep_as_note_cd)
    Text(
        stringResource(R.string.m4_event_keep_as_note_action),
        style = Maia.type.action,
        color = Maia.colours.inkMid,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(indication = null, interactionSource = null, onClick = onClick)
            .heightIn(min = Maia.space.touchTarget)
            .padding(vertical = Maia.space.sm)
            .semantics {
                role = Role.Button
                contentDescription = description
            },
    )
}

/**
 * "Create in Proton Calendar instead", M4-R9 2d: the plain intent handoff,
 * offered the same quiet way as [KeepAsNote] and for the same reason -- it
 * is a side door, not the card's one loud thing.
 */
@Composable
private fun OpenInProton(onClick: () -> Unit) {
    val description = stringResource(R.string.m4_r9_open_in_proton_cd)
    Text(
        stringResource(R.string.m4_r9_open_in_proton_action),
        style = Maia.type.action,
        color = Maia.colours.inkMid,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(indication = null, interactionSource = null, onClick = onClick)
            .heightIn(min = Maia.space.touchTarget)
            .padding(vertical = Maia.space.sm)
            .semantics {
                role = Role.Button
                contentDescription = description
            },
    )
}

/**
 * 600 ms, and a fill that reaches the far edge exactly once.
 *
 * **No haptics here any more, as of M3 row 15.** This bar used to play the
 * press, the four ladder steps and the commit itself, because at M1 and M2 the
 * running app had no effect runner and removing them would have silenced the
 * commit. The reducer has emitted `Effect.Haptic` for all of it since M2 step
 * 5, and from row 15 an Activity host runs those effects, so playing them here
 * as well is one press felt twice. The ladder that survives is the reducer's,
 * driven by [onHold]: a step on the first non-zero fraction, then one at each
 * quarter, then [dev.maia.app.feel.Schedule.commit] on the write. See
 * `docs/M2-status.md` section 0.1 item 3.
 */
private const val HOLD_MS = 600f

/**
 * Internal from M4 row 8 so the note card holds with this exact control: the
 * same 600 ms, the same fill, and the same [onHold] fractions, which is what
 * makes the reducer's haptic ladder the same too. The words are parameters
 * because only they differ; the defaults are the event card's.
 */
@Composable
internal fun HoldToWrite(
    progress: Float,
    enabled: Boolean,
    onHold: (Float) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Hold to write",
    description: String = "Hold to write the event to the calendar",
    disabledLabel: String = "Nowhere to write",
    disabledDescription: String = "No calendar can accept this event yet",
) {
    val colours = Maia.colours
    val scope = rememberCoroutineScope()

    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(Maia.radius.button))
            .background(colours.surfaceRaised)
            .border(BorderStroke(1.dp, colours.lineStrong), RoundedCornerShape(Maia.radius.button))
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        var fired = false
                        val ticker = scope.launch {
                            val started = withFrameNanos { it }
                            while (true) {
                                val now = withFrameNanos { it }
                                val fraction =
                                    ((now - started) / 1_000_000f / HOLD_MS).coerceIn(0f, 1f)
                                onHold(fraction)
                                if (fraction >= 1f) {
                                    fired = true
                                    onCommit()
                                    break
                                }
                            }
                        }
                        tryAwaitRelease()
                        ticker.cancel()
                        // Released early. Silence, and the fill goes back to
                        // nothing: the commit that did not happen is the whole
                        // of the feedback.
                        if (!fired) onHold(0f)
                    },
                )
            }
            .drawWithContent {
                drawContent()
                // Difference, so the label inverts exactly where the fill has
                // reached it. One label, never two, so there is no seam to
                // land on a letter.
                drawRect(
                    color = colours.inkHigh,
                    size = Size(size.width * progress, size.height),
                    blendMode = BlendMode.Difference,
                )
            }
            .semantics {
                contentDescription = if (enabled) description else disabledDescription
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (enabled) label else disabledLabel,
            style = Maia.type.action,
            color = if (enabled) colours.inkHigh else colours.inkFaint,
        )
    }
}
