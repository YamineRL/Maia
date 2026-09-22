package dev.maia.app.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.maia.app.R
import dev.maia.app.agent.Pairing
import dev.maia.app.card.LoudButton
import dev.maia.app.ui.Maia
import dev.maia.app.ui.OrbDock
import dev.maia.app.ui.raisedEdge

private val SheetShape = RoundedCornerShape(topStart = Maia.radius.sheet, topEnd = Maia.radius.sheet)

/**
 * Copy section 5.15: the one screen that ends with a job on another computer.
 *
 * There is no decision in this file. What is on the screen comes from
 * [PairingCopy.panel] and whether `Copied.` is drawn comes from
 * [PairingCopy.showCopied], both of which are pure and both of which are
 * tested; this draws what they say. That matters more here than on other
 * screens because there is no instrumentation in this repo, so anything
 * decided inside a `@Composable` is decided where nothing can check it.
 *
 * **Nothing on this screen is ever read back.** The address and the passphrase
 * leave as the two arguments to [onContinue] and the fields are not
 * repopulated from storage on a return visit: the two captions say the values
 * are kept and not shown, which is section 5.8's rule and the reason there is
 * no reveal control anywhere on it. The node key is never handed to this
 * composable at all. [onCopyKey] is called and the host moves the text from
 * `AgentSecrets.node()` to the clipboard, so the key does not pass through
 * composition, recomposition, or a saved instance state bundle on the way.
 *
 * **Forgetting is confirmed, and it is the one thing in M8 that asks.** The
 * gap this file used to report is filled: section 5.15 now defines the title,
 * the two button labels and a longer body, so `m8_pair_forget_action` sits
 * alone at the foot of the screen with nothing under it and opens
 * [ForgetSheet]. The sheet's reading order is [PairingCopy.forgetSheet] and
 * the emphasis is M3's `m3_locked_discard_action` rule rather than a slip:
 * `m8_pair_forget_cancel` is `ink.high` and first, `m8_pair_forget_confirm` is
 * `ink.low` and second, so the control that changes nothing is the one a thumb
 * finds and the destructive one is the quieter of the two. Neither is labelled
 * `OK` or `Cancel`.
 *
 * What is felt on confirm is `Schedule.agentStopped`, section 4.2's
 * stop-landed pattern: one THUD at 0.35 and nothing after it, which is the
 * product's only single thump and already means "something was taken away and
 * nothing follows". It is deliberately **not** the commit pattern, because
 * nothing was written. That is the host's to play, for the same reason the
 * clipboard is: this file draws.
 */
/**
 * Both halves go together or neither does, which is why there is one callback
 * and not two. `Pairing.identity` refuses to build half an identity, so
 * storing an address on its own is not possible, and a host that tried would
 * be storing a credential the user would meet again as a 401 with nothing to
 * re-enter. [onContinue] is called with whatever is in the two fields and the
 * host stores them when both are there.
 */
@Composable
fun PairingScreen(
    panel: PairingCopy.Panel,
    showCopied: Boolean,
    onContinue: (address: String, passphrase: String) -> Unit,
    onAssistant: (passphrase: String) -> Unit,
    onCopyKey: () -> Unit,
    onDone: () -> Unit,
    onForget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colours = Maia.colours
    var address by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var assistant by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }

    /**
     * The loud button's one road out. The assistant phrase, when typed, is
     * stored first: it is an independent credential and storing it does not
     * depend on the agent pair landing, so a refused pair keeps what was
     * entered. An empty field sends nothing, which is how "optional" stays
     * true.
     */
    fun submit() {
        if (assistant.isNotBlank()) onAssistant(assistant)
        if (panel.step == Pairing.Step.Ready) onDone() else onContinue(address, passphrase)
    }

    // The keyboard shrinks the screen from below rather than panning the window.
    Box(modifier.fillMaxSize().imePadding()) {
        Column(
            Modifier
                .fillMaxSize()
                .background(colours.groundBase)
                .padding(start = Maia.space.gutter, end = Maia.space.gutter, bottom = Maia.space.xxl)
                .semantics { isTraversalGroup = true },
            verticalArrangement = Arrangement.spacedBy(Maia.space.lg),
        ) {
            // The orb's seat, in the corner; the host draws it there. The
            // title moves down under it.
            OrbDock()
            Text(
                stringResource(R.string.m8_pair_title),
                style = Maia.type.title,
                color = colours.inkHigh,
                modifier = Modifier.semantics { heading() },
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Maia.space.lg),
            ) {
                Text(
                    stringResource(R.string.m8_pair_intro),
                    style = Maia.type.body,
                    color = colours.inkMid,
                )

                Field(
                    label = stringResource(R.string.m8_pair_address_label),
                    caption = stringResource(panel.addressCaption),
                    value = address,
                    onValue = { address = it },
                    entry = panel.addressEntry,
                    secret = false,
                    onSubmit = { onContinue(address, passphrase) },
                    // `m8_pair_forgotten` is the only caption that arrives in
                    // answer to something the user just did, so it is the only
                    // one announced, as `m8_pair_key_copied` is. The other two
                    // are there when the screen opens and a screen reader
                    // reaches them by reading the screen.
                    announce = panel.addressCaption == R.string.m8_pair_forgotten,
                )

                if (panel.passphrase) {
                    Field(
                        label = stringResource(R.string.m8_auth_entry_label),
                        caption = stringResource(R.string.m8_auth_entry_caption),
                        value = passphrase,
                        onValue = { passphrase = it },
                        entry = panel.step == Pairing.Step.Passphrase,
                        secret = true,
                        onSubmit = { submit() },
                    )
                }

                if (panel.assistant) {
                    Column(verticalArrangement = Arrangement.spacedBy(Maia.space.sm)) {
                        Text(
                            PairingCopy.ASSISTANT_BODY,
                            style = Maia.type.body,
                            color = colours.inkMid,
                        )
                        Field(
                            label = PairingCopy.ASSISTANT_LABEL,
                            caption = if (panel.assistantStored) {
                                PairingCopy.ASSISTANT_STORED
                            } else {
                                PairingCopy.ASSISTANT_CAPTION
                            },
                            value = assistant,
                            onValue = { assistant = it },
                            entry = !panel.assistantStored,
                            secret = true,
                            onSubmit = { submit() },
                        )
                    }
                }

                if (panel.key) {
                    Column(verticalArrangement = Arrangement.spacedBy(Maia.space.sm)) {
                        Text(
                            stringResource(R.string.m8_pair_key_label),
                            style = Maia.type.label,
                            color = colours.inkMid,
                        )
                        Text(
                            stringResource(R.string.m8_pair_key_caption),
                            style = Maia.type.caption,
                            color = colours.inkLow,
                        )
                        Text(
                            stringResource(R.string.m8_pair_key_body),
                            style = Maia.type.body,
                            color = colours.inkMid,
                            modifier = Modifier.padding(top = Maia.space.sm),
                        )
                        Text(
                            stringResource(R.string.m8_pair_key_path),
                            style = Maia.type.dataSmall,
                            color = colours.inkStrong,
                        )
                        InkAction(
                            stringResource(R.string.m8_pair_key_copy),
                            colours.inkHigh,
                            Modifier.padding(top = Maia.space.xs),
                        ) {
                            copied = true
                            onCopyKey()
                        }
                        Copied(copied, showCopied)
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(Maia.space.sm)) {
                LoudButton(stringResource(panel.action), Modifier.fillMaxWidth()) { submit() }
                // Alone at the foot of the screen with nothing under it, and
                // the sentence that used to sit here is in the sheet now, where
                // it is read at the moment it is about to matter.
                if (panel.forget) {
                    InkAction(
                        stringResource(R.string.m8_pair_forget_action),
                        colours.inkLow,
                        Modifier.fillMaxWidth(),
                        onClick = { asking = true },
                    )
                }
            }
        }
        if (asking) {
            BackHandler { asking = false }
            ForgetSheet(
                onKeep = { asking = false },
                onForget = {
                    asking = false
                    onForget()
                },
            )
        }
    }
}

/**
 * The confirmation, section 5.15, from the bottom edge and within one-handed
 * reach.
 *
 * Drawn rather than taken from Material 3, for the reason the whole of
 * `dev.maia.app.card` is: the handoff forbids the stock components, and a
 * sheet is a scrim, a surface and three pieces of text.
 *
 * The scrim swallows taps rather than dismissing on them. A destructive
 * confirmation that a stray touch can answer is a confirmation that answers
 * itself, and the way out is `m8_pair_forget_cancel` or the back gesture,
 * both of which are the user saying no on purpose.
 */
@Composable
private fun ForgetSheet(onKeep: () -> Unit, onForget: () -> Unit) {
    val colours = Maia.colours
    Box(
        Modifier
            .fillMaxSize()
            .background(colours.groundVoid.copy(alpha = 0.72f))
            .clickable(indication = null, interactionSource = null) { }
            .semantics { isTraversalGroup = true },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .raisedEdge(colours, SheetShape)
                .clip(SheetShape)
                .background(colours.surfaceRaised)
                .padding(horizontal = Maia.space.gutter, vertical = Maia.space.xl),
            verticalArrangement = Arrangement.spacedBy(Maia.space.lg),
        ) {
            Text(
                stringResource(R.string.m8_pair_forget_title),
                style = Maia.type.title,
                color = colours.inkHigh,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.m8_pair_forget_body),
                style = Maia.type.body,
                color = colours.inkMid,
            )
            // Keep first and loud, forget second and quiet. The destructive
            // control is deliberately the harder of the two to hit, which is
            // `m3_locked_discard_action`'s rule and not an oversight.
            InkAction(
                stringResource(R.string.m8_pair_forget_cancel),
                colours.inkHigh,
                Modifier.fillMaxWidth(),
                onClick = onKeep,
            )
            InkAction(
                stringResource(R.string.m8_pair_forget_confirm),
                colours.inkLow,
                Modifier.fillMaxWidth(),
                onClick = onForget,
            )
        }
    }
}

/**
 * `Copied.`, which is visible only where the platform does not say it itself.
 *
 * From Android 13 the system shows its own clipboard confirmation and a second
 * one under it is the product talking over the platform, so above 13 this
 * leaves a node with the text as its description and nothing drawn. A screen
 * reader hears it either way: the system's confirmation is a visual popup and
 * announces nothing, so suppressing ours on 13 and up would take the feedback
 * away from exactly the user who cannot see the popup.
 */
@Composable
private fun Copied(copied: Boolean, visible: Boolean) {
    if (!copied) return
    val text = stringResource(R.string.m8_pair_key_copied)
    if (visible) {
        Text(
            text,
            style = Maia.type.caption,
            color = Maia.colours.inkLow,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    } else {
        Spacer(
            Modifier
                .height(0.dp)
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = text
                },
        )
    }
}

/**
 * A label, a field and a caption.
 *
 * [entry] false draws the label and the caption with no field at all, which is
 * the state a stored credential is in: there is nothing to show, and an empty
 * box the user cannot usefully type in would invite them to paste the value
 * again. [secret] turns on the password transformation and the password
 * keyboard, and there is no control anywhere that turns it back off.
 */
@Composable
internal fun Field(
    label: String,
    caption: String,
    value: String,
    onValue: (String) -> Unit,
    entry: Boolean,
    secret: Boolean,
    onSubmit: () -> Unit,
    announce: Boolean = false,
) {
    val colours = Maia.colours
    Column(verticalArrangement = Arrangement.spacedBy(Maia.space.sm)) {
        Text(
            label,
            style = Maia.type.label,
            color = colours.inkMid,
            modifier = Modifier.clearAndSetSemantics { },
        )
        if (entry) {
            BasicTextField(
                value = value,
                onValueChange = onValue,
                singleLine = true,
                textStyle = Maia.type.dataSmall.copy(color = colours.inkHigh),
                cursorBrush = SolidColor(colours.accentAperture),
                visualTransformation =
                    if (secret) PasswordVisualTransformation() else VisualTransformationNone,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colours.surfaceField)
                    .padding(Maia.space.md)
                    .semantics { contentDescription = label },
            )
        }
        Text(
            caption,
            style = Maia.type.caption,
            color = colours.inkLow,
            modifier = if (announce) {
                Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            } else {
                Modifier
            },
        )
    }
}

private val VisualTransformationNone = androidx.compose.ui.text.input.VisualTransformation.None
