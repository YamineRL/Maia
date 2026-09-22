package dev.maia.app.card

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.maia.app.settings.EventsTo
import dev.maia.app.settings.MaiaPrefs
import dev.maia.app.ui.Maia
import dev.maia.nlu.EventDraft

/**
 * No calendar on this phone accepts new events.
 *
 * Section 5.4 of the M1 brief: a screen, not a toast, because on GrapheneOS
 * with no account configured this is the ordinary first run. The draft is
 * held, shown in one line so the user can see nothing was lost, and "Check
 * again" re-reads the provider after they come back from setting one up.
 *
 * The handoff's `4i` also offers creating a local-only calendar. That needs
 * an account-less sync adapter insert and is not in the M1 brief, so it is
 * left for M2 rather than improvised here.
 */
@Composable
fun NoCalendars(
    draft: EventDraft?,
    onCheckAgain: () -> Unit,
    onDiscard: () -> Unit,
) {
    val colours = Maia.colours
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .background(colours.groundBase)
            // The bars first: this screen can open edge to edge, handed over
            // from the voice overlay, and then the eyebrow sat under the clock.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = Maia.space.gutter, vertical = Maia.space.xxl),
        verticalArrangement = Arrangement.spacedBy(Maia.space.lg),
    ) {
        ScreenTitle(
            eyebrow = "Nowhere to write",
            title = "No calendar on this phone accepts new events.",
            body = "Maia writes to a calendar the system already syncs. " +
                "GrapheneOS ships with none configured, so this is where most phones start. " +
                "DAVx5 connects one from any CalDAV server.",
        )
        draft?.let {
            Text(
                "Held: ${it.title.value}",
                style = Maia.type.caption,
                color = colours.inkLow,
            )
        }
        Spacer(Modifier.weight(1f))
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Maia.space.sm),
        ) {
            LoudButton("Set up a calendar with DAVx5", Modifier.fillMaxWidth()) {
                openDavx5(context)
            }
            // M4-R9 2d: Proton Calendar never syncs through CalendarContract
            // (M4-R9-proton-calendar.md), so it never fixes this screen the
            // way setting up DAVx5 does -- it only takes the held draft
            // somewhere else. Offered only when there is a draft to send.
            // Choosing it is remembered: a phone with no calendar here will
            // have none next time either, so the next event goes straight to
            // Proton (MainActivity) and Settings is where to change it back.
            draft?.let { held ->
                LoudButton("Use Proton Calendar from now on", Modifier.fillMaxWidth()) {
                    MaiaPrefs(context).eventsTo = EventsTo.Proton
                    openInProtonCalendar(context, held)
                    onDiscard()
                }
            }
            QuietButton("Check again", Modifier.fillMaxWidth(), onCheckAgain)
            QuietButton("Discard", Modifier.fillMaxWidth(), onDiscard)
        }
    }
}

/**
 * Launch DAVx5 without looking for it first.
 *
 * Looking needs a `<queries>` element on API 30 and above, and package
 * queries are on PRD section 14's never-requested list. So fire the intent
 * and catch. The label cannot say whether DAVx5 is installed, which is the
 * price of not asking the system what else is on the phone.
 */
private fun openDavx5(context: Context) {
    val launch = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setPackage(DAVX5)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(launch)
    } catch (_: ActivityNotFoundException) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(FDROID_LISTING))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

private const val DAVX5 = "at.bitfire.davdroid"
private const val FDROID_LISTING = "https://f-droid.org/packages/at.bitfire.davdroid/"
