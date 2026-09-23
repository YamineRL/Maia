package dev.maia.app.screens

import dev.maia.app.ui.MaiaButton
import dev.maia.app.ui.ButtonKind
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import dev.maia.app.R
import dev.maia.app.agent.Asking
import dev.maia.app.agent.Block
import dev.maia.app.agent.BlockKind
import dev.maia.app.agent.EndMarker
import dev.maia.app.agent.ReplyPiece
import dev.maia.app.agent.RunState
import dev.maia.app.agent.Turn
import dev.maia.app.agent.label
import dev.maia.app.agent.recommended
import dev.maia.transport.AnswerOption
import dev.maia.app.ui.DockTop
import dev.maia.app.ui.Maia
import dev.maia.app.ui.OrbDock

/**
 * What the user does on the run screen. Two controls and nothing else.
 *
 * `docs/M8-copy.md` section 1.6 lists what this screen never shows, and the
 * first row is "a speak button, a play button, or any route from this text to
 * a voice". There is no such member here and there must never be one: a type
 * with two cases is a stronger statement of that than a comment, for the same
 * reason `AgentAck` is an enum.
 */
sealed interface RunAction {
    /** `interrupt`. Not an undo, which is what `m8_run_stop_cd` carries. */
    data object Stop : RunAction

    /** The turn has ended and the user wants another. */
    data object AskAgain : RunAction

    /**
     * `Allow`, meaning once. Sections 5.3 and 5.17.
     *
     * On every surface, `Allow` is once. The shade cannot offer the session
     * choice, because that choice needs its caption and a notification action
     * is a word; so rather than the same word meaning two different things in
     * two places, it means the smaller one in both.
     */
    data object Allow : RunAction

    /**
     * `Stop asking about this`, meaning always, for the rest of the session
     * on the user's machine. Drawn only where there is something to remember.
     */
    data object AllowSession : RunAction

    /**
     * `Refuse and stop`, carrying the user's optional note.
     *
     * [note] is the one string on this type, and it is the user's own words
     * going out. Rule 12 is about the other direction: nothing of the agent's
     * comes back through it, and nothing that arrived on the stream can reach
     * it. It travels inside the refusal, so it lands or fails with it.
     */
    data class Refuse(val note: String) : RunAction

    // ------------------------------------------- section 5.18, the question

    /**
     * One option row, tapped. [index] is a position in the agent's own array
     * and not a label, which keeps every string the agent sent off this type.
     */
    data class Option(val index: Int) : RunAction

    /** `Send`, on the `CHOOSE ANY` case. */
    data object SendAnswer : RunAction

    /** `Decide without me`: no answer goes, and the whole request ends. */
    data object SkipQuestion : RunAction

    /** `Drop the question`: the agent is told it was turned down. */
    data object DropQuestion : RunAction

    /** `Send it again`, after a reply that did not leave the phone. */
    data object SendAgain : RunAction

    /** `Say it again`: the microphone reopens and those words are replaced. */
    data object SayAgain : RunAction
}

/**
 * The run screen. `docs/M8-copy.md` section 1.
 *
 * One screen carries the whole feature, and it is never called a chat.
 * Stateless in the sense [LockedScreen] is: everything drawn comes from
 * [state] or a string resource, everything the user does leaves as one
 * [RunAction], and nothing here reaches past its state into a flag somewhere
 * else.
 *
 * **The orb is in the header rather than the upper half**, which is a
 * deliberate exception to every other screen in Maia and is the one layout
 * decision on this screen that needed a reason. This is the only screen whose
 * content is unbounded: an agent reply can be forty lines and a full size orb
 * would push the thing the user came to read off the fold. It keeps the whole
 * header height so its poses stay legible, and it keeps the pose changes,
 * which is all it is doing here.
 *
 * **The instruction never scrolls away with the reply.** It is the one thing
 * the user needs when they come back four minutes later and cannot remember
 * what they asked, so it sits between the header and the scrolling reply, in
 * its own block, outside the scroll.
 *
 * **The reply is not a live region** (section 6.1). The status label is. A
 * live region on streaming text either interrupts itself every few hundred
 * milliseconds or queues minutes of speech behind the user's next gesture, and
 * the second is worse because it cannot be escaped.
 */
@Composable
fun RunScreen(
    state: RunState,
    onAction: (RunAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(Maia.colours.groundBase)
            // The keyboard shrinks the screen from below rather than pushing
            // the window up past the status bar; the body scrolls in what is left.
            .imePadding()
            .padding(horizontal = Maia.space.gutter),
    ) {
        Header(state)
        Instruction(state.turn)
        Reply(state, Modifier.weight(1f))
        FailedNote(state)
        Footer(state, onAction)
    }
    Announce(state)
}

/**
 * Sticky, and it does not scroll. The orb at 64 dp on the left, the project on
 * the right, the status label under it, then the two lines that live under the
 * status label and are usually absent: the plan and the ten-second caption.
 */
@Composable
private fun Header(state: RunState) {
    val colours = Maia.colours
    Row(
        Modifier.fillMaxWidth().padding(bottom = Maia.space.md),
        verticalAlignment = Alignment.Top,
    ) {
        // The orb's seat, small because the reply is what the user came for.
        // The host draws the orb there, decorative and out of the
        // accessibility tree entirely: the status label beside it says the
        // same thing in words, and a decorative thing a screen reader stops on
        // is one more stop between the user and the footer.
        OrbDock()
        Column(
            Modifier.weight(1f).windowInsetsPadding(WindowInsets.statusBars).padding(top = DockTop, start = Maia.space.lg),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(Maia.space.xs),
        ) {
            val project = state.project
            if (project != null) {
                // Two views and not one formatted string: the number is
                // data.lg mono and the name is body, and at the largest font
                // scales the name is what gives way while the number keeps its
                // size (section 6.4). The project's path is never on screen:
                // the number is what the path was replaced by.
                val projectCd = stringResource(R.string.m8_run_project_cd, project.number, project.name)
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(Maia.space.sm),
                    modifier = Modifier.semantics(mergeDescendants = true) {
                        contentDescription = projectCd
                    },
                ) {
                    Text("${project.number}", style = Maia.type.dataLarge, color = colours.inkHigh)
                    Text(project.name, style = Maia.type.body, color = colours.inkMid)
                }
            }
            // The screen's one live region, polite. Five utterances at most
            // across a four-minute run, each under four words.
            Text(
                stringResource(RunCopy.status(state.status)),
                style = Maia.type.label,
                color = colours.inkMid,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            val plan = state.plan
            if (plan != null) {
                // The one element on this screen allowed to rewrite itself,
                // because it is a status and not a transcript. Omitted
                // entirely when the payload carried no counts: a plan the user
                // cannot count is not worth a line.
                Text(
                    stringResource(R.string.m8_run_plan, plan.done, plan.total),
                    style = Maia.type.dataSmall,
                    color = colours.inkLow,
                )
            }
            val nothingYet = RunCopy.nothingYet(state)
            if (nothingYet != null) {
                Text(stringResource(nothingYet), style = Maia.type.caption, color = colours.inkLow)
            }
            val left = state.leftBehind
            if (left != null) {
                // The on-screen twin of `m8_spoken_sent_moved` (section 6.3).
                // The first agent was not stopped, and that is the one thing
                // the user could reasonably get wrong.
                Text(
                    stringResource(R.string.m8_run_left_behind, left),
                    style = Maia.type.caption,
                    color = colours.inkLow,
                )
            }
        }
    }
}

/** `YOU SAID`, reusing M3's label and block rather than duplicating either. */
@Composable
private fun Instruction(turn: Turn?) {
    if (turn == null) return
    val description = stringResource(R.string.m3_locked_queued_transcript_cd, turn.instruction)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = Maia.space.md)
            .semantics(mergeDescendants = true) {
                contentDescription = description
            },
        verticalArrangement = Arrangement.spacedBy(Maia.space.xs),
    ) {
        Text(
            stringResource(R.string.m3_locked_queued_transcript_label),
            style = Maia.type.label,
            color = Maia.colours.inkMid,
        )
        Text(turn.instruction, style = Maia.type.read, color = Maia.colours.inkHigh)
    }
}

/**
 * The reply, filling the rest and scrolling. Append only.
 *
 * Text that has already rendered is never re-laid-out, never re-wrapped and
 * never replaced, which is why each [ReplyPiece] is its own item keyed by its
 * index: a delta extends the tail [ReplyPiece.Prose] in the model, which is
 * appending to it rather than replacing it, and nothing above the tail moves.
 * Appended text that reflows moves a screen reader's focus, and a re-layout
 * during a read loses a user their place in a forty-line reply with no way
 * back.
 *
 * **Sticky tail, never a yank.** While the view is at the bottom, new text
 * keeps it there. The moment the user scrolls up, following stops and a quiet
 * control appears, because scrolling up in a streaming reply is how a user
 * re-reads the thing they just saw go past, and dragging them back to the tail
 * for it is the single most common way this kind of screen is made unusable.
 */
@Composable
private fun Reply(state: RunState, modifier: Modifier) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val turn = state.turn
    val pieces = turn?.pieces.orEmpty()
    val earlier = RunCopy.earlier(state)
    val atBottom by remember {
        derivedStateOf {
            val last = list.layoutInfo.visibleItemsInfo.lastOrNull()
            last == null || last.index >= list.layoutInfo.totalItemsCount - 1
        }
    }
    LaunchedEffect(pieces.size, turn?.end) {
        if (atBottom && list.layoutInfo.totalItemsCount > 0) {
            list.scrollToItem(list.layoutInfo.totalItemsCount - 1)
        }
    }
    val readFromStart = stringResource(R.string.m8_run_read_from_start)
    Box(modifier.fillMaxWidth()) {
        LazyColumn(
            Modifier
                .fillMaxSize()
                // So a user does not have to swipe back through a long turn to
                // re-hear it. The reply container carries it; the pieces stay
                // ordinary text, navigable by swipe and readable at any time,
                // including while it is still arriving.
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(readFromStart) {
                            scope.launch { list.scrollToItem(0) }
                            true
                        },
                    )
                },
            state = list,
            verticalArrangement = Arrangement.spacedBy(Maia.space.md),
        ) {
            earlierTurns(earlier)
            replyPieces(pieces)
            val marker = RunCopy.endMarker(turn?.end)
            if (marker != null) {
                item {
                    EndMark(marker, RunCopy.endMarkerDashed(turn?.end), turn?.end, turn?.refusal)
                }
            }
        }
        if (!atBottom) {
            Text(
                stringResource(R.string.m8_run_jump_to_latest),
                style = Maia.type.action,
                color = Maia.colours.inkMid,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .heightIn(min = Maia.space.touchTarget)
                    .clickable(indication = null, interactionSource = null) {
                        scope.launch { list.scrollToItem(maxOf(0, list.layoutInfo.totalItemsCount - 1)) }
                    }
                    .padding(Maia.space.sm)
                    .semantics { role = Role.Button },
            )
        }
    }
}

private fun LazyListScope.earlierTurns(earlier: List<Turn>) {
    // Oldest at the top, one line each: the local time in mono and the
    // instruction that started it. The instruction identifies the turn; a
    // reply's first words are rarely about anything.
    items(earlier.size) { i -> EarlierTurn(earlier[i]) }
}

private fun LazyListScope.replyPieces(pieces: List<ReplyPiece>) {
    items(pieces.size) { i ->
        when (val piece = pieces[i]) {
            is ReplyPiece.Prose -> Prose(piece)
            is ReplyPiece.Code -> Code(piece)
            is ReplyPiece.Tool -> ToolLine(piece)
            is ReplyPiece.Answered -> AnswerMarker(piece)
            is ReplyPiece.Asked -> AskedPiece(piece)
        }
    }
}

/**
 * Prose is Markdown. The agent writes it: headings, fences, pipe tables,
 * lists, and the inline marks inside them all arrive in this piece, since the
 * machine appends every text delta here verbatim. Rendering is [MarkdownBody];
 * a delta re-parses only this tail piece, which already recomposed for the
 * appended text anyway.
 */
@Composable
private fun Prose(piece: ReplyPiece.Prose) {
    MarkdownBody(piece.text)
}

/**
 * Anything the agent fenced. `data.sm` mono on `surface.sunken`, in its own
 * container that scrolls horizontally and is never wrapped. A wrapped shell
 * command at 200 percent font scale is neither readable nor copyable, and a
 * block that scrolls sideways is honest about being too wide for a phone.
 */
@Composable
private fun Code(piece: ReplyPiece.Code) {
    val description = stringResource(R.string.m8_run_code_block_cd, RunCopy.codeBlockLines(piece))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Maia.radius.card))
            .background(Maia.colours.surfaceSunken)
            .padding(Maia.space.md),
        verticalArrangement = Arrangement.spacedBy(Maia.space.xs),
    ) {
        // Announced once, then navigable by line rather than read as one
        // continuous utterance. The heading is a count of newlines; the lines
        // themselves are ordinary text and are never handed to a formatter.
        Text(
            description,
            style = Maia.type.label,
            color = Maia.colours.inkLow,
        )
        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            piece.text.lines().forEach { line ->
                Text(line, style = Maia.type.dataSmall, color = Maia.colours.inkStrong, softWrap = false)
            }
        }
    }
}

/** One tool call, in place, in the order it happened. Not a spinner, not expandable. */
@Composable
private fun ToolLine(piece: ReplyPiece.Tool) {
    val text = when {
        piece.count > 1 -> stringResource(RunCopy.toolLine(piece), piece.name, piece.count)
        piece.target != null -> stringResource(RunCopy.toolLine(piece), piece.name, piece.target)
        else -> stringResource(RunCopy.toolLine(piece), piece.name)
    }
    Text(
        text,
        style = Maia.type.dataSmall,
        color = Maia.colours.inkLow,
        maxLines = 1,
        softWrap = false,
    )
}

/**
 * One earlier turn, collapsed to a line.
 *
 * `m8_run_earlier_turn` is `%1$s  %2$s` and the copy gives its two halves
 * different type (`data.sm` then `body`), which one `Text` cannot carry. So
 * the two-space gap in that format string is drawn as the gap between two
 * views rather than as two characters, and the string itself supplies the
 * shape rather than the glyphs. The description is the copy's own, in full.
 */
@Composable
private fun EarlierTurn(turn: Turn) {
    val time = RunCopy.earlierTime(turn)
    val description = stringResource(R.string.m8_run_earlier_turn_cd, time, turn.instruction)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Maia.space.touchTarget)
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(Maia.space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(time, style = Maia.type.dataSmall, color = Maia.colours.inkLow)
        Text(turn.instruction, style = Maia.type.body, color = Maia.colours.inkLow, maxLines = 1)
    }
}

/**
 * The hairline that closes a reply, solid for an ending and dashed for one
 * that was cut. Rule 12 made visible: the stream died and what is above it is
 * what got through.
 */
@Composable
private fun EndMark(label: Int, dashed: Boolean, end: EndMarker?, refusal: String?) {
    Column(
        Modifier.fillMaxWidth().padding(top = Maia.space.sm),
        verticalArrangement = Arrangement.spacedBy(Maia.space.xs),
    ) {
        Hairline(dashed)
        Text(stringResource(label), style = Maia.type.label, color = Maia.colours.inkLow)
        val note = RunCopy.endMarkerNote(end)
        if (note != null) {
            // The same sentence for a stop and for a refusal. From the user's
            // side they are the same fact: what the agent had already done to
            // their files is still done, and neither control undid anything.
            Text(stringResource(note), style = Maia.type.caption, color = Maia.colours.inkLow)
        }
        val refusal = refusal
        if (refusal != null) {
            // Their own words, printed back at them, which is the same
            // treatment `YOU SAID` gives the instruction. It went out; nothing
            // of the agent's came back through it.
            Text(refusal, style = Maia.type.body, color = Maia.colours.inkMid)
        }
    }
}

/**
 * `YOU ALLOWED THIS` and `IT STOPPED WAITING`, section 5.17.
 *
 * No hairline, because the reply carries straight on past both of them, and
 * in the reply flow rather than above it: a user who answered from the shade
 * and opens the app a minute later needs to find the moment they answered,
 * with the reply running on from there.
 */
@Composable
private fun AnswerMarker(piece: ReplyPiece.Answered) {
    Column(
        Modifier.fillMaxWidth().padding(top = Maia.space.sm),
        verticalArrangement = Arrangement.spacedBy(Maia.space.xs),
    ) {
        Text(
            stringResource(RunCopy.answerMark(piece.mark)),
            style = Maia.type.label,
            color = Maia.colours.inkLow,
        )
        // What was answered, one line per question, in `body`: the marker
        // alone tells a user coming back that they answered and not what
        // they said, and on a request of four questions that is the half
        // they need.
        piece.lines.forEach { line ->
            Text(line, style = Maia.type.body, color = Maia.colours.inkMid)
        }
        val note = RunCopy.answerMarkNote(piece.mark)
        if (note != null) {
            Text(stringResource(note), style = Maia.type.caption, color = Maia.colours.inkLow)
        }
    }
}

/**
 * `IT ASKED`, and the question under it, at the point in the stream where it
 * arrived. Section 5.18.
 *
 * The header the frame carries is not drawn. Thirty characters of the agent
 * summarising its own question, directly above that question, is the agent
 * saying the same thing twice in a place where the shorter one would be read
 * first and the longer one skipped.
 */
@Composable
private fun AskedPiece(piece: ReplyPiece.Asked) {
    Column(
        Modifier.fillMaxWidth().padding(top = Maia.space.sm),
        verticalArrangement = Arrangement.spacedBy(Maia.space.xs),
    ) {
        Text(
            stringResource(R.string.m8_run_question_label),
            style = Maia.type.label,
            color = Maia.colours.inkLow,
        )
        Text(piece.text, style = Maia.type.body, color = Maia.colours.inkStrong)
    }
}

/**
 * The one caption above the footer when something the user pressed did not
 * leave the phone. Sections 5.12 and 5.17.
 *
 * Nothing else moves. The run is exactly where it was, so the controls stay
 * exactly where the thumb left them and the only new thing on the screen is
 * the sentence saying so.
 */
@Composable
private fun FailedNote(state: RunState) {
    val note = RunCopy.failedNote(state) ?: return
    Text(
        stringResource(note.first, stringResource(note.second)),
        style = Maia.type.caption,
        color = Maia.colours.inkLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Maia.space.sm)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun Hairline(dashed: Boolean) {
    // Dashed with no colour and no reading: the two ends of a turn are told
    // apart by the line itself, which is what makes the distinction survive a
    // grayscale screen and a screen reader alike.
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (dashed) Arrangement.spacedBy(3.dp) else Arrangement.Start,
    ) {
        val cells = if (dashed) DASH_CELLS else 1
        repeat(cells) {
            Box(
                Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(Maia.colours.lineHair),
            )
        }
    }
}

/**
 * 26 dp from the bottom edge, which is the one-handed rule and does not bend
 * for this screen: everything interactive is in the lower half.
 */
@Composable
private fun Footer(state: RunState, onAction: (RunAction) -> Unit) {
    val block = state.blocked
    // Section 5.17: while an agent is waiting, the footer is the answer and
    // `Stop` is not there. When the answer lands, the answer controls go and
    // `Stop` comes back, which is this condition and nothing else.
    //
    val asking = state.asking
    if (asking != null) {
        QuestionFooter(state, asking, onAction)
        return
    }
    if (block != null && block.kind == BlockKind.Permission) {
        AnswerFooter(block, onAction)
        return
    }
    val label = stringResource(RunCopy.footerAction(state))
    val description = RunCopy.footerDescription(state)?.let { stringResource(it) } ?: label
    MaiaButton(
        label,
        ButtonKind.Loud,
        Modifier.padding(top = Maia.space.md, bottom = Maia.space.xl),
        description = description,
    ) {
        onAction(if (state.live) RunAction.Stop else RunAction.AskAgain)
    }
}

/**
 * The agent's question, in the footer. Section 5.18.
 *
 * The options are tappable rows directly above the three controls, in the
 * agent's order, and the whole of the lower half is the answer: there is no
 * dialog, nothing is overlaid on the reply, and the reply behind it keeps
 * running. `CHOOSE ONE` sends on the tap, because a single-choice list that
 * still wants a confirm is two presses for one decision. `CHOOSE ANY` ticks
 * and waits for `Send`, because with more than one allowed there is no tap
 * that can be read as the last one.
 *
 * The microphone is offered only when the request says free text is allowed.
 * When it does not, there is no `YOUR ANSWER` block at all, rather than one
 * that is there and refuses: the agent's own `plan_exit` sends Yes and No,
 * and a microphone over two words would be a control that cannot help.
 */
@Composable
private fun QuestionFooter(state: RunState, asking: Asking, onAction: (RunAction) -> Unit) {
    val question = asking.current
    Column(
        Modifier.fillMaxWidth().padding(top = Maia.space.md, bottom = Maia.space.xl),
        verticalArrangement = Arrangement.spacedBy(Maia.space.sm),
    ) {
        if (question != null) {
            // Only when there is more than one. A screen that changes under
            // the thumb with no count on it is a screen the user thinks they
            // broke. Both numbers are the phone's count of an array, so
            // neither can carry agent text.
            if (asking.several) {
                Text(
                    stringResource(
                        R.string.m8_run_question_step,
                        asking.at + 1,
                        asking.questions.size,
                    ),
                    style = Maia.type.label,
                    color = Maia.colours.inkLow,
                )
            }
            Text(
                stringResource(RunCopy.optionsLabel(question.multiple)),
                style = Maia.type.label,
                color = Maia.colours.inkMid,
            )
            question.options.forEachIndexed { index, option ->
                OptionRow(
                    option = option,
                    ticked = question.multiple && index in asking.ticked,
                    multiple = question.multiple,
                ) { onAction(RunAction.Option(index)) }
            }
            // `Send` exists only on the `CHOOSE ANY` case and only once there
            // is something to send: a spoken sentence counts, because on this
            // case nothing goes until the user says so.
            if (question.multiple && (asking.ticked.isNotEmpty() || asking.heard != null)) {
                Control(
                    label = stringResource(R.string.m8_run_options_send),
                    description = stringResource(R.string.m8_run_options_send_cd),
                    filled = true,
                ) { onAction(RunAction.SendAnswer) }
            }
            if (question.custom) SpokenAnswer(asking)
        }
        // Only after a send that did not land, and both only when there is a
        // spoken answer to replace: `Say it again` on a tapped option would
        // open a microphone over a decision that was made with a thumb.
        if (asking.failed) {
            Control(
                label = stringResource(R.string.m8_run_answer_send_again),
                description = stringResource(R.string.m8_run_answer_send_again_cd),
                filled = true,
            ) { onAction(RunAction.SendAgain) }
            if (asking.answers.isNotEmpty() && asking.questions.last().custom) {
                Control(
                    label = stringResource(R.string.m8_run_answer_say_again),
                    description = stringResource(R.string.m8_run_answer_say_again_cd),
                    filled = false,
                ) { onAction(RunAction.SayAgain) }
            }
        }
        Control(
            label = stringResource(R.string.m8_run_question_skip),
            description = stringResource(R.string.m8_run_question_skip_cd),
            filled = false,
        ) { onAction(RunAction.SkipQuestion) }
        // Section 9 item 5 names this as the control to cut if three prove
        // unreadable. It is last and it is quiet, which is the shape that
        // makes cutting it cheap.
        Control(
            label = stringResource(R.string.m8_run_question_dismiss),
            description = stringResource(R.string.m8_run_question_dismiss_cd),
            filled = false,
            quiet = true,
        ) { onAction(RunAction.DropQuestion) }
    }
}

/**
 * One option. The chip above, the label, and the agent's sentence under it.
 *
 * `Recommended` is Maia's word, although the agent supplied the fact. The
 * agent marks its recommendation by appending the marker to the label, which
 * spends four of the five words a row has and puts interface furniture inside
 * a piece of content, so exactly that trailing marker is stripped and a chip
 * is drawn instead. The strip is exact and it is the only one: a label that
 * marks itself some other way keeps every word it has.
 */
@Composable
private fun OptionRow(
    option: AnswerOption,
    ticked: Boolean,
    multiple: Boolean,
    onClick: () -> Unit,
) {
    val colours = Maia.colours
    val shape = RoundedCornerShape(Maia.radius.button)
    val text = label(option.label)
    val chip = stringResource(R.string.m8_run_option_recommended)
    // The agent's own two sentences, read as one thing, with Maia's word in
    // front when there is one. The row is one target and therefore one
    // description: two stops for one option is a list twice as long.
    val description = buildString {
        if (recommended(option.label)) append("$chip. ")
        append(text)
        if (option.description.isNotBlank()) append(". ${option.description}")
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(
                if (ticked) {
                    Modifier.background(colours.surfaceField)
                } else {
                    Modifier.border(BorderStroke(1.dp, colours.lineHair), shape)
                },
            )
            .heightIn(min = Maia.space.touchTarget)
            .clickable(indication = null, interactionSource = null) { onClick() }
            .semantics(mergeDescendants = true) {
                role = if (multiple) Role.Checkbox else Role.Button
                contentDescription = description
                if (multiple) toggleableState = if (ticked) ToggleableState.On else ToggleableState.Off
            }
            .padding(Maia.space.md),
        verticalArrangement = Arrangement.spacedBy(Maia.space.xs),
    ) {
        if (recommended(option.label)) {
            Text(chip, style = Maia.type.caption, color = colours.inkLow)
        }
        Text(text, style = Maia.type.action, color = colours.inkHigh)
        if (option.description.isNotBlank()) {
            Text(option.description, style = Maia.type.caption, color = colours.inkMid)
        }
    }
}

/**
 * `YOUR ANSWER`: the microphone, and then the words.
 *
 * Drawn only where free text is allowed, and it says the microphone is open
 * only once the capture has actually started. Android hands a capture begun
 * from the background silence rather than an error, so a caption written from
 * intent rather than from the started event is a caption that can be wrong
 * with nothing on the screen to correct it.
 */
@Composable
private fun SpokenAnswer(asking: Asking) {
    val colours = Maia.colours
    Column(verticalArrangement = Arrangement.spacedBy(Maia.space.xs)) {
        Text(
            stringResource(R.string.m8_run_answer_label),
            style = Maia.type.label,
            color = colours.inkMid,
        )
        val heard = asking.heard
        when {
            // Their own words, and the one place on this screen where what is
            // drawn came from a microphone rather than from a string.
            heard != null -> Text(heard, style = Maia.type.body, color = colours.inkHigh)
            // A microphone that closed with nothing sent looks exactly like
            // one that sent nothing on purpose, so it says the agent is still
            // waiting and the answers are still there.
            asking.nothingHeard -> Text(
                stringResource(R.string.m8_run_answer_nothing_heard),
                style = Maia.type.caption,
                color = colours.inkLow,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )

            asking.listening -> Text(
                stringResource(R.string.m8_run_answer_listening),
                style = Maia.type.caption,
                color = colours.inkLow,
            )
        }
    }
}

/**
 * The answer, in the footer, section 5.3.
 *
 * The order is the order of risk, top to bottom, and it is the same order the
 * expanded notification reads out: allow, refuse, and only then the one that
 * changes what happens to requests the user has not seen yet.
 *
 * The note field is above the two controls, because it belongs to the refusal
 * and a field under the button that sends it is a field nobody fills in. It
 * is optional, it is cleared when the request changes, and it gets no status
 * line of its own: it travels inside the refusal, so it lands when the
 * refusal lands and fails when the refusal fails.
 */
@Composable
private fun AnswerFooter(block: Block, onAction: (RunAction) -> Unit) {
    val colours = Maia.colours
    // Keyed on the request, so a second block in the same run starts empty
    // rather than offering the words the user wrote about the first one.
    var note by remember(block.requestId) { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().padding(top = Maia.space.md, bottom = Maia.space.xl),
        verticalArrangement = Arrangement.spacedBy(Maia.space.sm),
    ) {
        RefusalNote(note) { note = it }
        Control(
            label = stringResource(R.string.m8_notif_action_allow),
            description = stringResource(R.string.m8_notif_action_allow_cd),
            filled = true,
        ) { onAction(RunAction.Allow) }
        Control(
            label = stringResource(R.string.m8_notif_action_refuse),
            description = stringResource(R.string.m8_notif_action_refuse_cd),
            filled = false,
        ) { onAction(RunAction.Refuse(note.trim())) }
        if (RunCopy.sessionControl(block)) {
            // Drawn only when the request carries something to remember. When
            // it does not, nothing marks its absence: no greyed control and
            // no line explaining what is missing.
            Control(
                label = stringResource(R.string.m8_run_allow_session),
                description = stringResource(R.string.m8_run_allow_session_cd),
                filled = false,
                quiet = true,
            ) { onAction(RunAction.AllowSession) }
            // Mandatory, and it does not collapse at any font scale. A
            // control that changes what happens to requests the user has not
            // seen yet has to say so before the press, not after it.
            Text(
                stringResource(R.string.m8_run_allow_session_caption),
                style = Maia.type.caption,
                color = colours.inkLow,
            )
        }
    }
}

/**
 * `IF YOU REFUSE`, optional, above the two controls.
 *
 * The user's own words going out. Rule 12 governs the other direction, and
 * there is no path by which anything of the agent's arrives here: the field
 * starts empty and only a keyboard fills it.
 */
@Composable
private fun RefusalNote(value: String, onValue: (String) -> Unit) {
    val colours = Maia.colours
    val description = stringResource(R.string.m8_run_refuse_note_cd)
    Column(verticalArrangement = Arrangement.spacedBy(Maia.space.xs)) {
        Text(
            stringResource(R.string.m8_run_refuse_note_label),
            style = Maia.type.label,
            color = colours.inkMid,
            modifier = Modifier.clearAndSetSemantics { },
        )
        Box {
            if (value.isEmpty()) {
                Text(
                    stringResource(R.string.m8_run_refuse_note_hint),
                    style = Maia.type.body,
                    color = colours.inkLow,
                    modifier = Modifier
                        .padding(Maia.space.md)
                        .clearAndSetSemantics { },
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValue,
                textStyle = Maia.type.body.copy(color = colours.inkHigh),
                cursorBrush = SolidColor(colours.accentAperture),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colours.surfaceField)
                    .heightIn(min = Maia.space.touchTarget)
                    .padding(Maia.space.md)
                    .semantics { contentDescription = description },
            )
        }
        Text(
            stringResource(R.string.m8_run_refuse_note_caption),
            style = Maia.type.caption,
            color = colours.inkLow,
        )
    }
}

/** One footer control. Filled for the primary, outlined otherwise. */
@Composable
private fun Control(
    label: String,
    description: String,
    filled: Boolean,
    quiet: Boolean = false,
    onClick: () -> Unit,
) {
    val colours = Maia.colours
    val shape = RoundedCornerShape(Maia.radius.button)
    Text(
        label,
        style = Maia.type.action,
        color = when {
            filled -> colours.groundVoid
            quiet -> colours.inkMid
            else -> colours.inkHigh
        },
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(
                if (filled) {
                    Modifier.background(colours.inkHigh)
                } else {
                    Modifier.border(BorderStroke(1.dp, colours.lineHair), shape)
                },
            )
            .heightIn(min = Maia.space.touchTarget)
            .clickable(indication = null, interactionSource = null) { onClick() }
            .semantics {
                role = Role.Button
                contentDescription = description
            }
            .padding(vertical = Maia.space.md),
    )
}

/**
 * The one announcement at the end of a turn, section 6.1, sent once.
 *
 * `announceForAccessibility` is a speech path, and section 2.1 covers every
 * speech path rather than only the one called speak. So what goes through here
 * is a string resource chosen by [RunCopy.announcement] and, for the blocked
 * case, a project number: the same discipline as `AgentAck`, on the other
 * surface it could have leaked from. `RunCopyTest` holds it to that.
 */
@Composable
private fun Announce(state: RunState) {
    val view = LocalView.current
    // The answer announcement wins when there is one: a request that stopped
    // being pending is not a block any more, and `m8_run_blocked_announce`
    // would send the user to answer something nobody is waiting on.
    val id = RunCopy.answerAnnouncement(state.announce)
        // Before the blocked announcement and not after it: a question is a
        // block, and `m8_run_blocked_announce` would send the user looking
        // for two controls that are not on this screen.
        ?: RunCopy.questionAnnouncement(state)
        ?: RunCopy.announcement(state.turn?.end, state.blocked != null)
    val number = state.project?.number ?: 0
    val text = when (id) {
        null -> null
        R.string.m8_run_blocked_announce,
        R.string.m8_run_question_announce,
        R.string.m8_run_question_announce_open,
        -> stringResource(id, number)

        else -> stringResource(id)
    }
    var said by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(text) {
        if (text != null && text != said) {
            said = text
            view.announceForAccessibility(text)
        }
    }
}

/** Enough cells that a dashed rule reads as dashed at every width. */
private const val DASH_CELLS = 28
