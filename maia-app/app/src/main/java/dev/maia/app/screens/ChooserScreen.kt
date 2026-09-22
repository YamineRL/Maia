package dev.maia.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import dev.maia.app.R
import dev.maia.app.agent.Chooser
import dev.maia.app.agent.ListReach
import dev.maia.app.ui.LocalMaiaColours
import dev.maia.app.ui.Maia
import dev.maia.app.ui.MaiaTheme
import dev.maia.app.ui.maiaColours
import dev.maia.transport.ProjectEntry
import dev.maia.transport.ProjectState

/**
 * The project list, sections 5.9, 5.10 and 5.11.
 *
 * One screen with three headings and one list, which is [ChooserCopy]'s whole
 * argument: three different mistakes deserve three different sentences and all
 * three have the same answer.
 *
 * **The microphone is open here.** Maia asked a question, so the orb is
 * `Listening` and "seven" answers it without a tap. That is the entire reason
 * a number is the primary key, and it is why this screen is not a dead end
 * even when the list is stale or empty.
 *
 * **The number is the one thing that has to be legible across a desk.** It is
 * `data.lg` mono against the name in `body` on the same line, both in one
 * `Text` so their baselines align by construction rather than by a modifier.
 * At the largest font scale the line wraps and the number keeps its size: it
 * is the last thing that should shrink.
 *
 * **Rows are ordered by number, always** (rule 11), which is the registry's
 * order and is not re-sorted here. Nothing highlights a match: the shortlist
 * on the ambiguous screen is lifted into its own labelled group above a
 * divider and is otherwise drawn exactly like every other row, because a
 * ranking is a guess with a disclaimer on it.
 */
@Composable
fun ChooserScreen(
    chooser: Chooser,
    reach: ListReach,
    onPick: (ProjectEntry) -> Unit,
    onDiscard: () -> Unit,
    onSync: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colours = Maia.colours
    val heading = ChooserCopy.heading(chooser)
    val groups = ChooserCopy.groups(chooser)
    val held = ChooserCopy.held(chooser)
    val empty = ListCopy.empty(reach, held = chooser.all.isNotEmpty())
    Column(
        modifier
            .fillMaxSize()
            .background(colours.groundBase)
            .padding(horizontal = Maia.space.gutter, vertical = Maia.space.xxl)
            .semantics { isTraversalGroup = true },
        verticalArrangement = Arrangement.spacedBy(Maia.space.lg),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Maia.space.md)) {
            Text(
                stringResource(heading.eyebrow),
                style = Maia.type.label,
                color = colours.inkMid,
                modifier = Modifier.clearAndSetSemantics { },
            )
            // The heard words, under M3's own label and in M3's own shape, so
            // the user can see what the recogniser made of the name and judge
            // for themselves whether to say it again or reach for the number.
            // Names are an accelerator that is allowed to fail, and a failure
            // the user can see is one they can work around.
            if (heading.youSaid && chooser.spoken.isNotBlank()) {
                YouSaid(chooser.spoken)
            }
            Text(
                headingTitle(heading),
                style = Maia.type.title,
                color = colours.inkHigh,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                heading.bodyNumber?.let { stringResource(heading.body, it) }
                    ?: stringResource(heading.body),
                style = Maia.type.body,
                color = colours.inkMid,
            )
            if (held != null) Held(held)
            if (empty == null) StaleNote(reach)
        }
        if (empty != null) {
            // No list to choose from. Section 5.16's own screens say which of
            // the three silences this is, and they are drawn here rather than
            // in their own surface because the user arrived asking for a
            // project and the answer is still about the list.
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Maia.space.md),
            ) {
                Text(
                    stringResource(empty.title),
                    style = Maia.type.heading,
                    color = colours.inkHigh,
                    modifier = Modifier.semantics { heading() },
                )
                Text(stringResource(empty.body), style = Maia.type.body, color = colours.inkMid)
                empty.path?.let {
                    Text(
                        stringResource(it),
                        style = Maia.type.dataSmall,
                        color = colours.inkStrong,
                    )
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                groups.forEach { group ->
                    group.label?.let { label ->
                        item(key = "label-$label") {
                            Text(
                                stringResource(label),
                                style = Maia.type.label,
                                color = colours.inkMid,
                                modifier = Modifier
                                    .padding(top = Maia.space.md, bottom = Maia.space.sm)
                                    .clearAndSetSemantics { },
                            )
                        }
                    }
                    items(group.rows, key = { "row-${group.label}-${it.number}" }) { entry ->
                        Row(entry, onPick)
                    }
                }
            }
        }
        // Refresh above Discard, and Discard the quieter of the two. The copy
        // lists them the other way round in its table, which is the order the
        // ids were defined in and not a layout; the rule that decides a pair
        // like this is 5.15's, where the control that throws work away is the
        // second and quieter one, so a thumb travelling to the obvious place
        // lands on the one that keeps the sentence.
        val discardDescription = stringResource(R.string.m8_list_discard_cd)
        Column(verticalArrangement = Arrangement.spacedBy(Maia.space.sm)) {
            InkAction(
                stringResource(R.string.m8_list_sync_action),
                colours.inkMid,
                Modifier.fillMaxWidth(),
                onClick = onSync,
            )
            InkAction(
                stringResource(R.string.m8_list_discard_action),
                colours.inkLow,
                Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = discardDescription },
                onClick = onDiscard,
            )
        }
    }
}

/**
 * The title, which is a plain string, a numbered one, or a plurals resource.
 *
 * The three cases are [ChooserCopy]'s and are read off the heading rather than
 * decided again here. The `two` case is a separate string and not a plurals
 * entry because English in Android has only `one` and `other`.
 */
@Composable
private fun headingTitle(heading: ChooserCopy.Heading): String = when {
    heading.quantity != null ->
        pluralStringResource(heading.title, heading.quantity, heading.quantity)
    heading.titleNumber != null -> stringResource(heading.title, heading.titleNumber)
    else -> stringResource(heading.title)
}

/** M3's `YOU SAID`, the same block in the same place. */
@Composable
private fun YouSaid(spoken: String) {
    val colours = Maia.colours
    val description = stringResource(R.string.m3_locked_queued_transcript_cd, spoken)
    Column(
        Modifier.clearAndSetSemantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(Maia.space.xs),
    ) {
        Text(
            stringResource(R.string.m3_locked_queued_transcript_label),
            style = Maia.type.label,
            color = colours.inkMid,
        )
        Text(spoken, style = Maia.type.body, color = colours.inkStrong)
    }
}

/**
 * The held instruction, in M4's no-folder shape.
 *
 * "It goes as soon as you choose" and not a second confirmation: nothing is
 * being written on the phone, the user already said the instruction out loud,
 * and asking them to confirm twice for something they can interrupt by voice
 * is friction with no safety in it.
 */
@Composable
private fun Held(held: String) {
    val colours = Maia.colours
    val description = stringResource(R.string.m8_list_held_cd, held)
    Column(
        Modifier
            .padding(top = Maia.space.sm)
            .clearAndSetSemantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(Maia.space.xs),
    ) {
        Text(
            stringResource(R.string.m8_list_held, held),
            style = Maia.type.caption,
            color = colours.inkStrong,
        )
        Text(
            stringResource(R.string.m8_list_held_note),
            style = Maia.type.caption,
            color = colours.inkMid,
        )
    }
}

/**
 * The note over a list that could not be refreshed, section 5.16.
 *
 * Two notes and one path. The path is drawn under the 503 note alone, because
 * that note ends in a colon and the other does not: after a 503 the fix is on
 * the user's machine and naming the script is the only useful thing left to
 * say.
 */
@Composable
private fun StaleNote(reach: ListReach) {
    val note = ListCopy.staleNote(reach, held = true) ?: return
    val colours = Maia.colours
    Column(
        Modifier.padding(top = Maia.space.sm),
        verticalArrangement = Arrangement.spacedBy(Maia.space.xs),
    ) {
        Text(stringResource(note), style = Maia.type.caption, color = colours.inkMid)
        ListCopy.stalePath(reach, held = true)?.let {
            Text(stringResource(it), style = Maia.type.dataSmall, color = colours.inkStrong)
        }
    }
}

/**
 * One project, number first.
 *
 * Both halves are one `Text` built from `m8_list_row`, with the mono style
 * ending where the name begins. One text node rather than two means the
 * baselines align without a modifier, the whole line wraps as a unit at the
 * largest font scale, and a screen reader reads one node, which is what
 * `m8_list_row_cd` describes.
 */
@Composable
private fun Row(entry: ProjectEntry, onPick: (ProjectEntry) -> Unit) {
    val colours = Maia.colours
    val line = stringResource(R.string.m8_list_row, entry.number, entry.name)
    val split = line.lastIndexOf(entry.name).takeIf { it > 0 } ?: line.length
    val description = stringResource(R.string.m8_list_row_cd, entry.number, entry.name)
    val text = buildAnnotatedString {
        append(line)
        addStyle(Maia.type.dataLarge.toSpanStyle().copy(color = colours.inkHigh), 0, split)
        addStyle(Maia.type.body.toSpanStyle().copy(color = colours.inkHigh), split, line.length)
    }
    Text(
        text,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(indication = null, interactionSource = null) { onPick(entry) }
            .heightIn(min = Maia.space.rowMinHeight)
            .padding(vertical = Maia.space.md)
            .clearAndSetSemantics { contentDescription = description },
    )
}

private fun entry(number: Int, name: String) =
    ProjectEntry(number, name, "/home/u/$name", ProjectState.ACTIVE)

private val previewAll = listOf(
    entry(3, "openbrowser"),
    entry(7, "maia"),
    entry(11, "openbrowser-ai"),
)

@Preview(showBackground = true, heightDp = 780)
@Composable
private fun MissPreview() = MaiaTheme {
    CompositionLocalProvider(LocalMaiaColours provides maiaColours()) {
        ChooserScreen(
            Chooser("open browser", emptyList(), previewAll, "run the tests"),
            ListReach.Fresh,
            {},
            {},
            {},
        )
    }
}

@Preview(showBackground = true, heightDp = 780)
@Composable
private fun AmbiguousPreview() = MaiaTheme {
    CompositionLocalProvider(LocalMaiaColours provides maiaColours()) {
        ChooserScreen(
            Chooser(
                "openbrowser",
                listOf(previewAll[0], previewAll[2]),
                previewAll,
                "run the tests",
            ),
            ListReach.Stale,
            {},
            {},
            {},
        )
    }
}

@Preview(showBackground = true, heightDp = 780)
@Composable
private fun NoSuchNumberPreview() = MaiaTheme {
    CompositionLocalProvider(LocalMaiaColours provides maiaColours()) {
        ChooserScreen(
            Chooser("41", emptyList(), previewAll, null, badNumber = 41),
            ListReach.StaleNoRegistry,
            {},
            {},
            {},
        )
    }
}
