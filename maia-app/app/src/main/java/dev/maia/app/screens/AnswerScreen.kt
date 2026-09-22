package dev.maia.app.screens

import dev.maia.app.ui.MaiaButton
import dev.maia.app.ui.ButtonKind
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.maia.app.answer.AnswerFault
import dev.maia.app.answer.AnswerState
import dev.maia.app.answer.AnswerStatus
import dev.maia.app.answer.Exchange
import dev.maia.app.answer.HandoffSpec
import dev.maia.app.answer.PermNeeded
import dev.maia.app.ui.DockTop
import dev.maia.app.ui.Maia
import dev.maia.app.ui.OrbDock

/**
 * What the voice offer draws, decided by the host. Null means no card.
 *
 * The three cases are one type because the card never changes shape: an
 * offer ([downloading] false, [error] null), a download in flight
 * ([downloading] true, [progress] the mono line), and a failed download,
 * which is the offer again with [error] under it and still dismissable.
 * Whether the card exists at all is the host's call: the screen has no
 * `Context` and cannot stat the voice directory, which is the same reason
 * [FlowScreen] takes its byte counts rather than measuring them.
 */
data class VoiceCard(
    val downloading: Boolean = false,
    val progress: String? = null,
    val error: String? = null,
)

/**
 * The conversation screen. M9 PRD section 10.
 *
 * Stateless in the sense [RunScreen] is: everything drawn comes from [state],
 * [voice] or a constant in [AnswerCopy], and everything the user does leaves
 * through one of the callbacks. The driver behind those callbacks is the
 * host's business; this file never names it.
 *
 * **Layout is the run screen's, shrunk.** The orb keeps the header rather
 * than the upper half for the same reason it does there: an answer can be
 * 1,500 characters and a full-size orb would push it off the fold. `YOU
 * ASKED` sits between the header and the scrolling body and never scrolls
 * away, because the question is what a returning user needs.
 *
 * **Speech is a state, not a control.** `Stop speaking` exists only while
 * [AnswerState.spokenText] says the voice is live, and the orb's `Speaking`
 * pose is read off the same field through [aperture], so the two cannot
 * disagree about whether anything is being said.
 */
@Composable
fun AnswerScreen(
    state: AnswerState,
    onAskAnother: () -> Unit,
    onStop: () -> Unit,
    onConfirm: () -> Unit,
    onRetry: () -> Unit,
    onClear: () -> Unit,
    onTyped: (String) -> Unit,
    onGrant: (PermNeeded) -> Unit,
    voice: VoiceCard?,
    onVoiceDownload: () -> Unit,
    onVoiceDecline: () -> Unit,
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
        Asked(state.spoken)
        Body(state, Modifier.weight(1f), onGrant)
        Footer(
            state = state,
            voice = voice,
            onAskAnother = onAskAnother,
            onStop = onStop,
            onConfirm = onConfirm,
            onRetry = onRetry,
            onClear = onClear,
            onTyped = onTyped,
            onVoiceDownload = onVoiceDownload,
            onVoiceDecline = onVoiceDecline,
        )
    }
}

/**
 * The orb at 64 dp, the status label as the one live region, and the one
 * honest line under it when the state carries [AnswerState.note].
 */
@Composable
private fun Header(state: AnswerState) {
    val colours = Maia.colours
    Row(
        Modifier.fillMaxWidth().padding(bottom = Maia.space.md),
        verticalAlignment = Alignment.Top,
    ) {
        // The orb's seat, below widget size, which is why the status label
        // beside it is mandatory. The host draws the orb there, decorative
        // and out of the tree: the label says the same thing in words.
        OrbDock()
        Column(
            Modifier.weight(1f).windowInsetsPadding(WindowInsets.statusBars).padding(top = DockTop, start = Maia.space.lg),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(Maia.space.xs),
        ) {
            Text(
                AnswerCopy.status(state.status),
                style = Maia.type.label,
                color = colours.inkMid,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            val note = state.note
            if (note != null) {
                // "waiting behind other work", and never an estimate: section
                // 10 item 4 names the queue without promising a time.
                Text(note, style = Maia.type.caption, color = colours.inkLow)
            }
        }
    }
}

/** `YOU ASKED`, above the scroll and outside it. */
@Composable
private fun Asked(spoken: String) {
    if (spoken.isBlank()) return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = Maia.space.md)
            .semantics(mergeDescendants = true) {
                contentDescription = "${AnswerCopy.YOU_ASKED}: $spoken"
            },
        verticalArrangement = Arrangement.spacedBy(Maia.space.xs),
    ) {
        Text(AnswerCopy.YOU_ASKED.uppercase(), style = Maia.type.label, color = Maia.colours.inkMid)
        Text(spoken, style = Maia.type.read, color = Maia.colours.inkHigh)
    }
}

/**
 * Earlier exchanges, then the handoff card, then the answer or the fault,
 * then the source row. Everything in here scrolls; nothing in here
 * recomposes without a state change.
 */
@Composable
private fun Body(state: AnswerState, modifier: Modifier, onGrant: (PermNeeded) -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Maia.space.md),
    ) {
        state.earlier.forEach { Earlier(it) }
        val handoff = state.handoff
        if (handoff != null) HandoffCard(handoff)
        val fault = state.fault
        if (state.status == AnswerStatus.Failed && fault != null) {
            val copy = AnswerCopy.fault(fault)
            Column(verticalArrangement = Arrangement.spacedBy(Maia.space.sm)) {
                Text(copy.title, style = Maia.type.heading, color = Maia.colours.inkHigh)
                Text(copy.body, style = Maia.type.body, color = Maia.colours.inkMid)
                if (fault is AnswerFault.Permission) {
                    // The system prompt, not a workaround: the grant is the
                    // user's to give and the same "Try again" afterwards
                    // re-runs the command against it.
                    Control(AnswerCopy.GRANT, filled = false) { onGrant(fault.which) }
                }
            }
        }
        if (state.answer.isNotBlank()) {
            // The whole answer, even when the voice says less of it: section
            // 3.1's "the complete answer is shown" is this line.
            Text(state.answer, style = Maia.type.answer, color = Maia.colours.inkStrong)
        }
        val source = AnswerCopy.source(state)
        if (source != null) {
            // A disclosure, not a badge: a mono label in the quiet ink, the
            // same treatment the run screen gives its plan line.
            Text(
                source,
                style = Maia.type.label,
                color = Maia.colours.inkLow,
                modifier = Modifier.padding(bottom = Maia.space.md),
            )
        }
    }
}

/**
 * One collapsed exchange, oldest at the top: the question and the answer it
 * got, one line each in the quiet inks.
 */
@Composable
private fun Earlier(exchange: Exchange) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Maia.space.xs),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Maia.space.sm)) {
            Text(
                AnswerCopy.EARLIER_ASKED.uppercase(),
                style = Maia.type.label,
                color = Maia.colours.inkFaint,
            )
            Text(
                exchange.asked,
                style = Maia.type.body,
                color = Maia.colours.inkLow,
                maxLines = 1,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Maia.space.sm)) {
            Text(
                AnswerCopy.EARLIER_SAID.uppercase(),
                style = Maia.type.label,
                color = Maia.colours.inkFaint,
            )
            Text(
                exchange.answered,
                style = Maia.type.body,
                color = Maia.colours.inkLow,
                maxLines = 1,
            )
        }
    }
}

/**
 * The populated action, whether it is being previewed, is firing, or has
 * already opened. Section 11 keeps it on screen through every outcome; the
 * confirm control is the footer's, because it has to stay where the thumb
 * is.
 */
@Composable
private fun HandoffCard(spec: HandoffSpec) {
    val colours = Maia.colours
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Maia.radius.card))
            .border(BorderStroke(1.dp, colours.lineHair), RoundedCornerShape(Maia.radius.card))
            .background(colours.surfaceSunken)
            .padding(Maia.space.md),
        verticalArrangement = Arrangement.spacedBy(Maia.space.xs),
    ) {
        Text(spec.label, style = Maia.type.action, color = colours.inkHigh)
        Text(AnswerCopy.opensTarget(spec), style = Maia.type.caption, color = colours.inkMid)
    }
}

/**
 * Everything the thumb can reach: the voice offer, the typed follow-up, the
 * transient controls, and the two that are always there.
 *
 * `Ask another` is the primary on every state the screen can be in (section
 * 10 item 6). `Try again` sits above it only while the state is `Failed`,
 * because it is the same question sent again rather than a new one.
 */
@Composable
private fun Footer(
    state: AnswerState,
    voice: VoiceCard?,
    onAskAnother: () -> Unit,
    onStop: () -> Unit,
    onConfirm: () -> Unit,
    onRetry: () -> Unit,
    onClear: () -> Unit,
    onTyped: (String) -> Unit,
    onVoiceDownload: () -> Unit,
    onVoiceDecline: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(top = Maia.space.md, bottom = Maia.space.xl),
        verticalArrangement = Arrangement.spacedBy(Maia.space.sm),
    ) {
        if (voice != null) {
            VoiceOffer(voice, onVoiceDownload, onVoiceDecline)
        }
        FollowUp(onTyped)
        if (state.spokenText != null) {
            Control(AnswerCopy.STOP_SPEAKING, filled = false) { onStop() }
        }
        if (state.status == AnswerStatus.Previewing) {
            Control(AnswerCopy.CONFIRM, filled = true) { onConfirm() }
        }
        if (state.status == AnswerStatus.Failed) {
            Control(AnswerCopy.TRY_AGAIN, filled = true) { onRetry() }
        }
        Control(AnswerCopy.ASK_ANOTHER, filled = true) { onAskAnother() }
        Control(AnswerCopy.CLEAR, filled = false, quiet = true) { onClear() }
    }
}

/**
 * The typed follow-up: the microphone's quiet sibling.
 *
 * Same field shape as the run screen's refusal note, with send on the
 * keyboard action. What is typed goes through [onTyped] and the field is
 * cleared; the answer machine decides what the sentence is, exactly as it
 * does for a spoken one.
 */
@Composable
private fun FollowUp(onTyped: (String) -> Unit) {
    val colours = Maia.colours
    var text by remember { mutableStateOf("") }
    fun submit() {
        val sent = text.trim()
        if (sent.isEmpty()) return
        text = ""
        onTyped(sent)
    }
    Column(verticalArrangement = Arrangement.spacedBy(Maia.space.xs)) {
        Text(
            AnswerCopy.FOLLOW_UP_LABEL.uppercase(),
            style = Maia.type.label,
            color = colours.inkMid,
            modifier = Modifier.clearAndSetSemantics { },
        )
        Box {
            if (text.isEmpty()) {
                Text(
                    AnswerCopy.FOLLOW_UP_HINT,
                    style = Maia.type.body,
                    color = colours.inkLow,
                    modifier = Modifier
                        .padding(Maia.space.md)
                        .clearAndSetSemantics { },
                )
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = Maia.type.body.copy(color = colours.inkHigh),
                cursorBrush = SolidColor(colours.accentAperture),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colours.surfaceField)
                    .heightIn(min = Maia.space.touchTarget)
                    .padding(Maia.space.md)
                    .semantics { contentDescription = AnswerCopy.FOLLOW_UP_LABEL },
            )
        }
    }
}

/**
 * Section 9's first-use offer. Name, size, purpose and licence line before a
 * byte moves; progress in mono while it does; and a dismissal that only ever
 * means "not this session", because refusing the voice leaves show-only
 * conversation fully working and the offer may come back next session.
 */
@Composable
private fun VoiceOffer(card: VoiceCard, onDownload: () -> Unit, onDecline: () -> Unit) {
    val colours = Maia.colours
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Maia.radius.card))
            .background(colours.surfaceSunken)
            .padding(Maia.space.md),
        verticalArrangement = Arrangement.spacedBy(Maia.space.sm),
    ) {
        Text(AnswerCopy.VOICE_TITLE, style = Maia.type.action, color = colours.inkHigh)
        Text(AnswerCopy.VOICE_BODY, style = Maia.type.caption, color = colours.inkMid)
        when {
            card.downloading -> {
                Text(
                    card.progress.orEmpty(),
                    style = Maia.type.dataSmall,
                    color = colours.inkStrong,
                )
                Text(
                    AnswerCopy.VOICE_DOWNLOADING,
                    style = Maia.type.caption,
                    color = colours.inkLow,
                )
            }
            else -> {
                val error = card.error
                if (error != null) {
                    // The download's own words, once, and the offer stays:
                    // a failed fetch is a reason, not a refusal.
                    Text(
                        error,
                        style = Maia.type.caption,
                        color = colours.faultNeutral,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Maia.space.lg)) {
                    Control(AnswerCopy.VOICE_DOWNLOAD, filled = false, modifier = Modifier.weight(1f)) {
                        onDownload()
                    }
                    Control(AnswerCopy.VOICE_NOT_NOW, filled = false, quiet = true, modifier = Modifier.weight(1f)) {
                        onDecline()
                    }
                }
            }
        }
    }
}

/** One footer control, the run screen's shape: filled for the primary, outlined otherwise. */
@Composable
private fun Control(
    label: String,
    filled: Boolean,
    quiet: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) = MaiaButton(
    label,
    when {
        filled -> ButtonKind.Loud
        quiet -> ButtonKind.Faint
        else -> ButtonKind.Quiet
    },
    modifier,
    description = label,
    onClick = onClick,
)
