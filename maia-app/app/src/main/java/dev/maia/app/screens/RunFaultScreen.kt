package dev.maia.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import dev.maia.app.R
import dev.maia.app.agent.RunFault
import dev.maia.app.card.LoudButton
import dev.maia.app.ui.LocalMaiaColours
import dev.maia.app.ui.Maia
import dev.maia.app.ui.MaiaTheme
import dev.maia.app.ui.maiaColours
import dev.maia.transport.ProjectEntry
import dev.maia.transport.ProjectState

/**
 * An instruction that was never sent, sections 5.6 to 5.9.
 *
 * One layout over [RunFaultCopy.Panel], for the same reason every other screen
 * in this package is: nothing here decides what the user is told, so the
 * deciding is all in a pure object a JVM test can drive.
 *
 * **`NOT SENT` is first and is not read aloud.** It answers the user's first
 * question, which is where their sentence went, before the explanation says
 * why. It is a label, so a screen reader skips it and reads the title, which
 * says the same thing in a sentence.
 *
 * **The mono line is a thing to type or a thing to open**, never a full
 * command line for a file, and it sits on `surface.sunken` so it reads as
 * quoted rather than as more prose. The mono face is required to have a
 * slashed zero and a `1` that cannot be read as `l`, which is the whole
 * reason a path or a command is set in it.
 *
 * **The passphrase field appears on one of the four.** It has no reveal
 * control, here or anywhere, and the passphrase is never printed into a
 * content description. Section 5.8.
 *
 * @param retired the registry row behind [RunFault.Retired], which is the only
 *   fault whose strings take arguments. It comes from the phone's own copy of
 *   the registry and never from anything the far end said.
 */
@Composable
fun RunFaultScreen(
    panel: RunFaultCopy.Panel,
    retired: ProjectEntry?,
    onAction: () -> Unit,
    onPassphrase: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colours = Maia.colours
    var passphrase by remember { mutableStateOf("") }
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
                stringResource(panel.label),
                style = Maia.type.label,
                color = colours.inkLow,
                modifier = Modifier.clearAndSetSemantics { },
            )
            Text(
                faultTitle(panel, retired),
                style = Maia.type.title,
                color = colours.inkHigh,
                modifier = Modifier.semantics { heading() },
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Maia.space.md),
        ) {
            Text(faultBody(panel, retired), style = Maia.type.body, color = colours.inkMid)
            panel.data?.let { line ->
                Text(
                    stringResource(line),
                    style = Maia.type.dataSmall,
                    color = colours.inkStrong,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colours.surfaceSunken)
                        .padding(Maia.space.md),
                )
            }
            // Section 5.15's one condition. "Your machine stopped answering"
            // is the exact symptom of a key that was never added, and until a
            // run has succeeded there is no way to tell the two apart, so the
            // screen says where to put it rather than being a dead end.
            if (panel.keyHint) {
                Column(
                    Modifier.padding(top = Maia.space.sm),
                    verticalArrangement = Arrangement.spacedBy(Maia.space.sm),
                ) {
                    Text(
                        stringResource(R.string.m8_pair_key_body),
                        style = Maia.type.body,
                        color = colours.inkMid,
                    )
                    Text(
                        stringResource(R.string.m8_pair_key_path),
                        style = Maia.type.dataSmall,
                        color = colours.inkStrong,
                    )
                }
            }
            if (panel.passphrase) {
                Field(
                    label = stringResource(R.string.m8_auth_entry_label),
                    caption = stringResource(R.string.m8_auth_entry_caption),
                    value = passphrase,
                    onValue = { passphrase = it },
                    entry = true,
                    secret = true,
                    onSubmit = { onPassphrase(passphrase) },
                )
            }
        }
        // Section 5.9 asks for an action on the retired screen too and defines
        // no string for one, so that screen has no button rather than an
        // invented label. See RunFaultCopy.panel.
        panel.action?.let { action ->
            LoudButton(stringResource(action), Modifier.fillMaxWidth()) {
                if (panel.passphrase) onPassphrase(passphrase) else onAction()
            }
        }
    }
}

/**
 * The title, formatted when the fault has a subject.
 *
 * `m8_retired_project_title` takes the number. A missing row cannot happen on
 * the retired screen, because the row is what decided the fault, but the
 * fallback is the number-free branch rather than a crash: a screen that throws
 * is worse than a screen that is slightly less specific.
 */
@Composable
private fun faultTitle(panel: RunFaultCopy.Panel, retired: ProjectEntry?): String =
    if (panel.title == R.string.m8_retired_project_title && retired != null) {
        stringResource(panel.title, retired.number)
    } else {
        stringResource(panel.title)
    }

/** The body, and the sentence that keeps section 8's promise out loud. */
@Composable
private fun faultBody(panel: RunFaultCopy.Panel, retired: ProjectEntry?): String =
    if (panel.body == R.string.m8_retired_project_body && retired != null) {
        stringResource(panel.body, retired.name, retired.number)
    } else {
        stringResource(panel.body)
    }

private val retiredRow =
    ProjectEntry(number = 4, name = "tailcat", path = "/home/u/tailcat", state = ProjectState.RETIRED)

@Preview(showBackground = true, heightDp = 780)
@Composable
private fun TunnelOffPreview() = MaiaTheme {
    CompositionLocalProvider(LocalMaiaColours provides maiaColours()) {
        RunFaultScreen(RunFaultCopy.panel(RunFault.TunnelOff)!!, null, {}, {})
    }
}

@Preview(showBackground = true, heightDp = 780)
@Composable
private fun NoServerWithKeyHintPreview() = MaiaTheme {
    CompositionLocalProvider(LocalMaiaColours provides maiaColours()) {
        RunFaultScreen(
            RunFaultCopy.panel(RunFault.NoServer, everSucceeded = false)!!,
            null,
            {},
            {},
        )
    }
}

@Preview(showBackground = true, heightDp = 780)
@Composable
private fun RefusedPreview() = MaiaTheme {
    CompositionLocalProvider(LocalMaiaColours provides maiaColours()) {
        RunFaultScreen(RunFaultCopy.panel(RunFault.Refused)!!, null, {}, {})
    }
}

@Preview(showBackground = true, heightDp = 780)
@Composable
private fun RetiredPreview() = MaiaTheme {
    CompositionLocalProvider(LocalMaiaColours provides maiaColours()) {
        RunFaultScreen(RunFaultCopy.panel(RunFault.Retired)!!, retiredRow, {}, {})
    }
}
