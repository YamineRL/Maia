package dev.maia.app.assist

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import dev.maia.app.MaiaFlow
import dev.maia.app.MainActivity

/**
 * The non-exported trampoline every non-assistant door goes through: the static
 * launcher shortcuts and the Quick Settings tile. Brief section 5, row 9.
 *
 * **Why it exists: item I4, and it is a deliberate breaking change.** M1's tile
 * started capture by launching the **exported** `MainActivity` with
 * `EXTRA_START_LISTENING`. Any installed app could fire that intent and put
 * Maia into listening, which lights the microphone indicator and records the
 * room until the endpointer closes. It wrote nothing without a hold and it was
 * visible, and it was still a microphone another app could switch on. That is
 * privacy item V4. From M3 the extra is ignored by `MainActivity` and capture
 * starts only through this Activity, which no other app can reach.
 *
 * **Why a non-exported Activity can be a shortcut target.** Settled by
 * `docs/research/R5.md` (read 2026-09-13), from AOSP `platform_frameworks_base`
 * branch `android16-release`, head `99b01a65cc4c104933788b3143285ab6bae65827`
 * (2025-05-07): a launcher never starts a shortcut itself. It calls
 * `LauncherApps.startShortcut`, and `LauncherAppsService.startShortcutInner`
 * hands the intents to
 * `mActivityTaskManagerInternal.startActivitiesAsPackage(publisherPackage, ...)`,
 * so the Activity is started **as Maia**, which makes it an ordinary same-uid
 * start. The framework states the conclusion outright on the line above, as a
 * comment: `// Note the target activity doesn't have to be exported.`
 * `ShortcutParser` checks only that the Activity the `android.app.shortcuts`
 * meta-data hangs from is an exported launcher Activity, which is
 * `MainActivity`; the `<intent>` inside the shortcut gets no such check. Pinned
 * shortcuts take the same path, so a pin keeps working. The one route that
 * would not work is the legacy `ACTION_CREATE_SHORTCUT`, where a launcher
 * stores a raw intent and fires it under its own identity. M3 does not use it.
 *
 * **What it does.** Route, read the keyguard once, post the event, hand over,
 * finish. It renders nothing: its theme is translucent and it is gone before
 * anything could be drawn. An unrecognised action finishes without posting
 * anything at all.
 */
class InvokeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    /**
     * `noHistory` and `excludeFromRecents` in the manifest mean a second tap
     * arrives as a fresh `onCreate` rather than here, but `launchMode` is not
     * this Activity's to guarantee for every launcher, so the case is handled.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        // G9's check, taken at a door that still works when the assistant door
        // does not: the role can be held while
        // Settings.Secure.voice_interaction_service names nothing, and then the
        // power gesture reaches nothing while every settings screen still shows
        // Maia as the assistant. Two binder reads, no work, and no decision here
        // beyond storing the answer. See AssistantRole.
        AssistantRole.refresh(this)

        val routed = route(intent?.action, intent.extraMap())
        if (routed == null) {
            // Not a door Maia knows. No event, no capture, no window, and no
            // log naming whoever asked.
            finish()
            return
        }

        // The single read of the keyguard for this invocation, in the host,
        // exactly as brief section 2.2 requires. From here it is a value the
        // pure reducer carries, and nothing downstream asks Android again.
        val locked = (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)
            ?.isKeyguardLocked == true

        MaiaFlow.controller(this).send(routed.copy(locked = locked))

        // The flow is process scoped from M3 (brief section 2.1), so this does
        // not tell MainActivity what to do. It opens the host and MainActivity
        // renders whatever the flow is already doing. Deliberately carrying no
        // extra: an Activity that acts on an extra is the shape item I4 exists
        // to remove.
        //
        // Locked, there is nothing to open: a launcher shortcut cannot fire
        // over a keyguard, and the tile's locked path never reaches this
        // Activity because it asks MaiaVoiceService for a session instead.
        if (!locked) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
        finish()
    }
}

/**
 * A `Bundle` as the plain map [route] takes.
 *
 * Only the extras the router names are read, which is one string, and it is
 * read as a string rather than as an object. The router never sees a key Maia
 * did not ask for, so an intent carrying anything else carries it nowhere.
 */
private fun Intent?.extraMap(): Map<String, Any?> {
    val origin = this?.getStringExtra(InvokeActions.EXTRA_ORIGIN) ?: return emptyMap()
    return mapOf(InvokeActions.EXTRA_ORIGIN to origin)
}
