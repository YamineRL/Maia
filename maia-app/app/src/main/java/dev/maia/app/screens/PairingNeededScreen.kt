package dev.maia.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import dev.maia.app.R
import dev.maia.app.card.LoudButton
import dev.maia.app.ui.Maia

/**
 * An agent sentence with nowhere to send it, copy section 5.15.
 *
 * **It does not say the tunnel is down.** Maia has not tried anything: it has
 * nowhere to try, and naming a cause nobody observed is the fault section 1.4
 * was rewritten to avoid. The body's second sentence is lifted word for word
 * from the tunnel-off screen on purpose, so a user who has met one of these
 * recognises the shape of the other, and because it is the reassurance that
 * matters most here: nothing else in Maia has stopped working.
 *
 * There is nothing to hold and nothing to discard. The instruction was not
 * queued, because queueing implies somewhere for it to go.
 */
@Composable
fun PairingNeededScreen(onSetUp: () -> Unit, modifier: Modifier = Modifier) {
    val colours = Maia.colours
    Column(
        modifier
            .fillMaxSize()
            .background(colours.groundBase)
            .padding(horizontal = Maia.space.gutter, vertical = Maia.space.xxl)
            .semantics { isTraversalGroup = true },
        verticalArrangement = Arrangement.spacedBy(Maia.space.lg),
    ) {
        Text(
            stringResource(R.string.m8_pair_needed_title),
            style = Maia.type.title,
            color = colours.inkHigh,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.m8_pair_needed_body),
            style = Maia.type.body,
            color = colours.inkMid,
            modifier = Modifier.weight(1f),
        )
        LoudButton(
            stringResource(R.string.m8_pair_needed_action),
            Modifier.fillMaxWidth(),
            onClick = onSetUp,
        )
    }
}
