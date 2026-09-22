package dev.maia.app.screens

import dev.maia.app.ui.MaiaButton
import dev.maia.app.ui.ButtonKind
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import dev.maia.app.R
import dev.maia.app.flow.FlowEvent
import dev.maia.app.ui.Maia
import dev.maia.app.ui.OrbStage
import dev.maia.orb.ApertureState

/**
 * The locked screens, M3 brief section 13 row 8, drawn from [LockedScene].
 *
 * Stateless in exactly the sense [FlowScreen] is: everything drawn comes from
 * [scene] or from a string resource, and everything the user does leaves as one
 * [FlowEvent] through [onEvent]. It is a separate function rather than a branch
 * inside [FlowScreen] because the locked screens have a different rule about
 * what may be on them, and a rule enforced by a type is worth more than a rule
 * written in a comment: [LockedScene] carries no provider value, no calendar
 * and no account, so neither can this.
 *
 * No screen here has a colour that means anything. A locked fault is the same
 * ink as a locked success, and what marks a fault is [ApertureState.Fault]'s
 * closed, desaturated, doubled pose and the words NOTHING RECORDED, per brief
 * section 6.
 *
 * The read order is the order the copy asks for (`docs/M3-copy.md` section 2):
 * each block below is one accessibility node with the description the copy
 * gives it, laid out top to bottom in the order TalkBack should meet them, so
 * the traversal order is the layout order and there is no index to keep in
 * sync. The orb is decorative: it is seated with [OrbStage], drawn by the
 * host's `OrbHost` with the pose [aperture] gives, and is out of the tree
 * entirely.
 */
@Composable
fun LockedScreen(
    scene: LockedScene,
    onEvent: (FlowEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(Maia.colours.groundBase)) {
        OrbStage {
            when (scene) {
                is LockedScene.Kept -> Kept(scene, onEvent)
                is LockedScene.ReadBack -> ReadBack(onEvent)
                LockedScene.BeforeFirstUnlock -> BeforeFirstUnlock(onEvent)
                LockedScene.NoMicrophone -> NoMicrophone(onEvent)
                LockedScene.FirstRun -> FirstRunLocked(onEvent)
                is LockedScene.QueueFull -> QueueFull(scene.waiting, onEvent)
            }
        }
    }
}

/**
 * The orb's pose on each locked screen, for the host's `OrbHost`: resting
 * while a sentence is kept or refused, [ApertureState.Fault] wherever nothing
 * could be recorded.
 */
val LockedScene.aperture: ApertureState
    get() = when (this) {
        is LockedScene.Kept, is LockedScene.ReadBack -> ApertureState.Dormant
        LockedScene.BeforeFirstUnlock, LockedScene.NoMicrophone, LockedScene.FirstRun, is LockedScene.QueueFull ->
            ApertureState.Fault
    }

/**
 * A sentence heard and kept. `docs/M3-copy.md` section 2.
 *
 * The blocks are in the copy's read order: title, body, YOU SAID, KEPT AS,
 * Unlock, Discard. What is on screen is the summary's three strings and
 * nothing else; the when text carries the angle brackets when Maia guessed the
 * day ([lockedWhen]), and the description says so in words, because a bracket
 * is not a thing TalkBack reads.
 */
@Composable
private fun Kept(scene: LockedScene.Kept, onEvent: (FlowEvent) -> Unit) {
    val colours = Maia.colours
    val summary = scene.summary
    val whenText = lockedWhen(summary, scene.whenGuessed)
    Column(
        Modifier.semantics { isTraversalGroup = true },
        verticalArrangement = Arrangement.spacedBy(Maia.space.md),
    ) {
        Text(
            stringResource(R.string.m3_locked_queued_title),
            style = Maia.type.heading,
            color = colours.inkHigh,
            modifier = Modifier.semantics { traversalIndex = 0f },
        )
        Text(
            stringResource(R.string.m3_locked_queued_body),
            style = Maia.type.body,
            color = colours.inkMid,
            modifier = Modifier.semantics { traversalIndex = 1f },
        )
        Block(stringResource(R.string.m3_locked_queued_transcript_cd, summary.transcript), order = 2f) {
            Label(stringResource(R.string.m3_locked_queued_transcript_label))
            Text(summary.transcript, style = Maia.type.read, color = colours.inkHigh)
        }
        // M4 D3 (`docs/M4-copy.md` section 3): a note says so, in the label
        // and in the description, and names no folder and no file. The body
        // and the due time are the summary's, exactly as an event's title and
        // when text are.
        val parsed = when {
            !scene.note -> stringResource(R.string.m3_locked_queued_parsed_cd, summary.title, summary.whenText)
            summary.whenText.isBlank() -> stringResource(R.string.m4_locked_queued_note_cd, summary.title)
            else -> stringResource(R.string.m4_locked_queued_note_due_cd, summary.title, summary.whenText)
        }
        Block(keptAsDescription(parsed, scene.whenGuessed), order = 3f) {
            Label(
                stringResource(if (scene.note) R.string.m4_locked_queued_note_label else R.string.m3_locked_queued_parsed_label),
            )
            Text(summary.title, style = Maia.type.body, color = colours.inkStrong)
            if (whenText.isNotBlank()) {
                Text(whenText, style = Maia.type.dataSmall, color = colours.inkStrong)
            }
        }
    }
    Buttons {
        Loud(
            stringResource(R.string.m3_locked_unlock_action),
            stringResource(
                if (scene.note) R.string.m4_locked_unlock_action_cd_note else R.string.m3_locked_unlock_action_cd_queued,
            ),
            order = 4f,
        ) { onEvent(FlowEvent.UnlockRequested) }
        Quiet(
            stringResource(R.string.m3_locked_discard_action),
            stringResource(R.string.m3_locked_discard_action_cd),
            order = 5f,
        ) { onEvent(FlowEvent.Cancel) }
    }
}

/**
 * The read-back refusal, brief section 4.3.
 *
 * Constant. It takes the summary it is given and prints none of it: there is
 * no branch here on anything, because a refusal that is one line longer on a
 * busy day has answered the question it refused. The question itself is not
 * echoed either, for the same reason the lock screen never shows what was
 * asked of a calendar.
 */
@Composable
private fun ReadBack(onEvent: (FlowEvent) -> Unit) {
    val colours = Maia.colours
    Column(verticalArrangement = Arrangement.spacedBy(Maia.space.md)) {
        Text(
            stringResource(R.string.m3_locked_readback_title),
            style = Maia.type.heading,
            color = colours.inkHigh,
        )
        Text(
            stringResource(R.string.m3_locked_readback_body),
            style = Maia.type.body,
            color = colours.inkMid,
        )
    }
    Buttons {
        Loud(
            stringResource(R.string.m3_locked_unlock_action),
            stringResource(R.string.m3_locked_unlock_action_cd_readback),
        ) { onEvent(FlowEvent.UnlockRequested) }
        Quiet(
            stringResource(R.string.m3_locked_discard_action),
            stringResource(R.string.m3_locked_discard_action_cd),
        ) { onEvent(FlowEvent.Cancel) }
    }
}

/**
 * Before the first unlock, brief section 4.4.
 *
 * Unlock, with the plain label and no bespoke description: `docs/M3-copy.md`
 * section 4 says pressing it is exactly the fix, so there is nothing to
 * explain. No Discard, because nothing was recorded and there is nothing to
 * discard.
 */
@Composable
private fun BeforeFirstUnlock(onEvent: (FlowEvent) -> Unit) {
    LockedFault(
        title = stringResource(R.string.m3_locked_before_unlock_title),
        body = stringResource(R.string.m3_locked_before_unlock_body),
        note = stringResource(R.string.m3_locked_before_unlock_note),
        description = stringResource(R.string.m3_locked_before_unlock_cd),
    )
    Unlock(onEvent)
}

/** Microphone permission, `docs/M3-copy.md` section 5. Android will not ask from here. */
@Composable
private fun NoMicrophone(onEvent: (FlowEvent) -> Unit) {
    LockedFault(
        title = stringResource(R.string.m3_locked_no_mic_title),
        body = stringResource(R.string.m3_locked_no_mic_body),
        description = stringResource(R.string.m3_locked_no_mic_cd),
    )
    OpenApp(onEvent)
}

/** First run with no model, `docs/M3-copy.md` section 6. 70 MB is not a lock screen's business. */
@Composable
private fun FirstRunLocked(onEvent: (FlowEvent) -> Unit) {
    LockedFault(
        title = stringResource(R.string.m3_locked_first_run_title),
        body = stringResource(R.string.m3_locked_first_run_body),
        description = stringResource(R.string.m3_locked_first_run_cd),
    )
    OpenApp(onEvent)
}

/**
 * The sixth locked capture, brief section 4.2.
 *
 * [waiting] is a count of unreviewed drafts and never a word of any of them,
 * which is the one number the locked policy allows out: it says how much work
 * is waiting, not what the work is.
 */
@Composable
private fun QueueFull(waiting: Int, onEvent: (FlowEvent) -> Unit) {
    LockedFault(
        title = null,
        body = pluralStringResource(R.plurals.m3_locked_queue_full_body, waiting, waiting),
        description = pluralStringResource(R.plurals.m3_locked_queue_full_cd, waiting, waiting),
    )
    Unlock(onEvent)
}

// ------------------------------------------------------------------ pieces

/**
 * The shape every locked fault shares: NOTHING RECORDED, then what happened,
 * as one accessibility node reading the copy's own sentence.
 *
 * One node rather than four is deliberate. The copy writes each of these
 * screens out as a single description ending in a full stop, and a screen
 * reader user who is standing at a lock screen wants the whole of a short
 * refusal in one gesture rather than four stops that each say a fragment.
 */
@Composable
private fun LockedFault(title: String?, body: String, description: String, note: String? = null) {
    val colours = Maia.colours
    Block(description) {
        Label(stringResource(R.string.m3_fault_label_nothing_recorded))
        if (title != null) {
            Text(title, style = Maia.type.heading, color = colours.inkHigh)
        }
        Text(body, style = Maia.type.body, color = colours.inkMid)
        if (note != null) {
            Text(note, style = Maia.type.caption, color = colours.inkLow)
        }
    }
}

/** The plain keyguard control, where the copy asks for the label alone. */
@Composable
private fun Unlock(onEvent: (FlowEvent) -> Unit) {
    Buttons {
        val label = stringResource(R.string.m3_locked_unlock_action)
        Loud(label, label) { onEvent(FlowEvent.UnlockRequested) }
    }
}

@Composable
private fun OpenApp(onEvent: (FlowEvent) -> Unit) {
    Buttons {
        Loud(
            stringResource(R.string.m3_locked_open_app_action),
            stringResource(R.string.m3_locked_open_app_action_cd),
        ) { onEvent(FlowEvent.UnlockRequested) }
    }
}

/**
 * One block of text the screen reader meets once, with the description the
 * copy wrote for it rather than the concatenation of its parts. An eyebrow
 * label like YOU SAID reads as a heading to the eye and as noise to the ear.
 */
@Composable
private fun Block(description: String, order: Float? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = description
                if (order != null) traversalIndex = order
            },
        verticalArrangement = Arrangement.spacedBy(Maia.space.xs),
        content = content,
    )
}

@Composable
private fun Label(text: String) {
    Text(text, style = Maia.type.label, color = Maia.colours.inkMid)
}

/**
 * The KEPT AS description: the copy's own `m3_locked_queued_parsed_cd`, with
 * the guess said in words after it.
 *
 * `docs/M3-copy.md` section 9 item 2: the brackets stay on screen and become
 * words in the ear, in the card's phrasing (`PreviewCard`'s field rows say the
 * same four words), so a guess sounds the same wherever it is met. The when
 * text goes into the format string unbracketed. Two angle brackets read aloud
 * are two pieces of punctuation, not a fact.
 *
 * **Short of the copy in one place, deliberately and visibly.**
 * `docs/M3-copy.md` section 2 asks for the when text here in a long spoken
 * form, "Thursday the seventeenth of September at eight in the evening", so
 * that a screen reader does not spell out digits. No such formatter exists:
 * `LockedSummary.whenText` is the mono string and it is produced by
 * `lockedSummary`, whose shape is pinned by an existing test. Writing a spoken
 * formatter is a real piece of work with its own copy decisions (ordinals,
 * "in the evening" against "at twenty hundred", midnight, all-day) and it is
 * not this row's. So the mono string goes in for now and the shortfall is
 * written down rather than papered over.
 */
internal fun keptAsDescription(parsed: String, guessed: Boolean): String =
    if (guessed) "$parsed, guessed by Maia" else parsed

@Composable
private fun Buttons(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Maia.space.sm),
        content = content,
    )
}

@Composable
private fun Loud(text: String, description: String, order: Float? = null, onClick: () -> Unit) =
    LockedButton(text, description, loud = true, order = order, onClick = onClick)

@Composable
private fun Quiet(text: String, description: String, order: Float? = null, onClick: () -> Unit) =
    LockedButton(text, description, loud = false, order = order, onClick = onClick)

/**
 * [FlowScreen]'s button, redrawn here with a description of its own. Loud is
 * ink on inverted ground; quiet is a hairline box. Neither is a colour that
 * means anything.
 */
@Composable
private fun LockedButton(text: String, description: String, loud: Boolean, order: Float?, onClick: () -> Unit) =
    MaiaButton(text, if (loud) ButtonKind.Loud else ButtonKind.Quiet, description = description, order = order, onClick = onClick)
