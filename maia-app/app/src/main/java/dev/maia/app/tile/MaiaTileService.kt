package dev.maia.app.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import dev.maia.app.EngineHolder
import dev.maia.app.assist.InvokeActions
import dev.maia.app.assist.InvokeActivity
import dev.maia.app.assist.MaiaVoiceService
import dev.maia.app.flow.Origin

/**
 * The Quick Settings tile, which is one of the two entry points M1 ships.
 *
 * The interesting work here is not the tap. It is [onStartListening], which
 * fires when the user pulls the shade down, typically a second or two before
 * they tap anything. That is the only window in which the cost of getting
 * ready can be paid without the user watching a spinner, and PRD section 13's
 * 250 ms budget is only reachable if it has been used.
 */
class MaiaTileService : TileService() {

    /**
     * Called when the shade opens, and again whenever the tile becomes
     * visible. [EngineHolder.warm] is idempotent, so calling it on every open
     * costs nothing after the first.
     *
     * This does not start recording and does not light the system microphone
     * indicator. It builds the recogniser and reserves an unstarted
     * `AudioRecord`. Lighting the indicator because somebody opened the
     * notification shade would be the interface telling a lie about what the
     * app is doing.
     */
    override fun onStartListening() {
        super.onStartListening()
        EngineHolder.warm(this)
    }

    /**
     * Three cases, which is what PRD section 6's "Partial" now means: locked
     * capture from the tile exists exactly when Maia holds the assistant role.
     * M3 brief section 6.
     *
     * **Locked, with the role.** The tile asks the in-process
     * [MaiaVoiceService] to show its session, so the tile and the power gesture
     * enter the same locked path and the same queued rules. No unlock is asked
     * for, and nothing is launched. Per `docs/research/R7.md` (AOSP
     * `android16-release`, read 2026-09-13) the only gate on
     * `showSession` is `enforceIsCurrentVoiceInteractionService`: no keyguard
     * check and no check that a system gesture caused the call. What R7 could
     * not settle from source, and what G11 in the spike is for, is whether
     * SystemUI delivers `onClick` to a tile at all while locked.
     *
     * **Locked, without the role**, and the null case: `unlockAndRun`, exactly
     * as at M1. A tile cannot draw over the keyguard on its own, and
     * `showDialog` from a tile is not a capture surface.
     * [MaiaVoiceService.bound] being null right after a process start is a real
     * possibility rather than a theoretical one, so it falls into the same
     * branch rather than doing nothing.
     *
     * **Unlocked:** unchanged from M1. `unlockAndRun` runs its block
     * immediately when there is no keyguard, so this is the same code path it
     * always was, and the only difference is item I4's: the launch goes to the
     * non-exported [InvokeActivity] carrying an origin, rather than to the
     * exported `MainActivity` carrying an instruction to start the microphone.
     */
    override fun onClick() {
        super.onClick()

        val voice = MaiaVoiceService.bound
        if (isLocked && voice != null) {
            voice.showMaiaSession()
            return
        }

        val intent = Intent(this, InvokeActivity::class.java).apply {
            action = InvokeActions.INVOKE
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(InvokeActions.EXTRA_ORIGIN, Origin.Tile.name)
        }
        unlockAndRun { launch(intent) }
    }

    /**
     * Both overloads, and the reason the deprecated one is still here.
     *
     * The M1 brief says to write the `PendingIntent` form and carry no
     * deprecated branch, on the grounds that the `Intent` overload's throw is
     * gated by `@EnabledSince(targetSdkVersion = UPSIDE_DOWN_CAKE)` on change
     * id `START_ACTIVITY_NEEDS_PENDING_INTENT`, which keys off Maia's
     * targetSdk of 36 rather than the device's version. That part is correct
     * and confirmed.
     *
     * It is not the whole story. `startActivityAndCollapse(PendingIntent)` was
     * added in API 34 and minSdk here is 26, so on a device running 26 to 33
     * the method does not exist and the call is a `NoSuchMethodError` rather
     * than a collapse. On those devices the compat change does not exist
     * either, so the `Intent` overload is not merely allowed, it is the only
     * one there is.
     *
     * So the branch is on the device version, which is a different question
     * from the one the brief answered. Section 7 of the brief has been
     * corrected to say so.
     *
     * The lint suppression is the narrow kind. `StartActivityAndCollapseDeprecated`
     * fires because Maia targets 36, and its reasoning is sound: an app
     * targeting UpsideDownCake or higher gets an `UnsupportedOperationException`
     * from the `Intent` overload. What it cannot see is the `SDK_INT` guard one
     * line below, which means the branch it complains about only ever runs on a
     * device where the compat change does not exist and the call does not
     * throw. Removing the branch to satisfy the check would trade a lint error
     * for a `NoSuchMethodError` on every device below 34, which is the worse
     * end of that trade.
     */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launch(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // FLAG_IMMUTABLE because SystemUI has no business rewriting this,
            // and it is required for a PendingIntent handed across processes.
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
