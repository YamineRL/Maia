package dev.maia.app.card

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.maia.actions.MaiaCalendar
import dev.maia.app.ui.Maia

/**
 * The short list of calendars, reached from the card's calendar row.
 *
 * A screen rather than a dialog because this is the one choice on the card
 * that outlives the event: [dev.maia.actions.CalendarRepository.chooseTarget]
 * remembers it, and the next card opens on it with the guess marks gone.
 *
 * Read-only calendars are listed and not offered. Hiding them would leave a
 * user with a subscribed holiday calendar wondering where it went; offering
 * them would be worse, because the provider accepts the insert and the sync
 * adapter reverts it later, so the event would appear written and then
 * quietly vanish. The calendar's own colour is not drawn: the aperture is the
 * only saturated thing in Maia, and the name is enough to tell them apart.
 */
@Composable
fun CalendarChooser(
    calendars: List<MaiaCalendar>,
    currentId: Long?,
    onChoose: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val colours = Maia.colours
    Column(
        Modifier
            .fillMaxSize()
            .background(colours.groundBase),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Maia.space.gutter, vertical = Maia.space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Calendar", style = Maia.type.body, color = colours.inkHigh)
                Text(
                    "Maia remembers this for next time",
                    style = Maia.type.label,
                    color = colours.inkLow,
                )
            }
            Text(
                "Back",
                style = Maia.type.caption,
                color = colours.inkMid,
                modifier = Modifier
                    .clickable(indication = null, interactionSource = null, onClick = onBack)
                    .heightIn(min = Maia.space.touchTarget)
                    .padding(top = Maia.space.md, start = Maia.space.md),
            )
        }
        ChooserHairline()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            chooserOrder(calendars).forEach { calendar ->
                CalendarRow(
                    calendar = calendar,
                    current = calendar.id == currentId,
                    onClick = { onChoose(calendar.id) },
                )
            }
            if (calendars.isEmpty()) {
                Text(
                    "No calendars could be read.",
                    style = Maia.type.body,
                    color = colours.inkMid,
                    modifier = Modifier.padding(Maia.space.gutter),
                )
            }
        }
    }
}

/**
 * Writable first, read-only after, each group in the provider's order.
 *
 * Pulled out of the composable so criterion 7 can pin it on the JVM. The sort
 * is stable, which is what keeps the account's own order inside each group.
 */
internal fun chooserOrder(calendars: List<MaiaCalendar>): List<MaiaCalendar> =
    calendars.sortedByDescending { it.writable }

@Composable
private fun CalendarRow(calendar: MaiaCalendar, current: Boolean, onClick: () -> Unit) {
    val colours = Maia.colours
    val clickable = if (calendar.writable) {
        Modifier.clickable(indication = null, interactionSource = null, onClick = onClick)
    } else {
        Modifier
    }
    Row(
        Modifier
            .fillMaxWidth()
            .then(clickable)
            .background(if (current) colours.surfaceRaised else colours.groundBase)
            .heightIn(min = Maia.space.rowMinHeight)
            .padding(horizontal = Maia.space.gutter, vertical = Maia.space.lg)
            .semantics {
                contentDescription = buildString {
                    append(calendar.displayName)
                    append(", ")
                    append(calendar.accountName)
                    if (current) append(", selected")
                    if (!calendar.writable) append(", read only, cannot be chosen")
                }
            },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Filled for the one in use, dashed for one that cannot be used. The
        // same diamond language as the card, so nothing new has to be learnt.
        GutterDiamond(
            when {
                !calendar.writable -> Mark.Empty
                current -> Mark.Heard
                else -> Mark.Guessed
            },
            Modifier.padding(top = 6.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                calendar.displayName,
                style = Maia.type.heading,
                color = if (calendar.writable) colours.inkHigh else colours.inkLow,
            )
            Text(
                if (calendar.writable) calendar.accountName else "${calendar.accountName} · read only",
                style = Maia.type.caption,
                color = colours.inkLow,
            )
        }
    }
    ChooserHairline()
}

@Composable
private fun ChooserHairline() {
    androidx.compose.foundation.layout.Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 1.dp, max = 1.dp)
            .background(Maia.colours.lineHair),
    )
}
