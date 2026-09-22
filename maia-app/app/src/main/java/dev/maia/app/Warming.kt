package dev.maia.app

import android.content.Context
import android.os.UserManager
import dev.maia.app.flow.RecorderMove
import dev.maia.app.flow.WarmMoment
import dev.maia.app.flow.warmthFor

/**
 * The one call site of the warmth table. M3 brief section 3.1.
 *
 * Every service that has a moment calls this with the moment it is in and does
 * nothing else about warmth. That is the point of splitting the policy out: a
 * host says where it is, not what to do, so the answer cannot drift between
 * `MaiaVoiceService`, `MaiaSession` and the tile, and changing it is one table
 * and its tests rather than four call sites and a hope.
 *
 * Thin on purpose, because none of it is provable on this box: everything below
 * either loads a 70 MB model or builds an `AudioRecord`.
 */
object Warming {

    fun at(moment: WarmMoment, context: Context) {
        // Before the first unlock there is nothing to warm and no safe way to
        // try. `MaiaVoiceService` is direct boot aware since G9, so `RoleReady`
        // can now arrive while the user is still locked, and the engine half of
        // every plan reads credential-encrypted storage: `EngineHolder.load`
        // opens `filesDir/models`, which does not exist for a locked user and
        // whose absence would be read as "models missing" rather than "not yet
        // readable". The recorder half is no better a bargain: reserving an
        // `AudioRecord` hours before an unlock holds a device resource for a
        // session that cannot be created yet. So the whole table waits.
        if (!unlocked(context)) return

        val plan = warmthFor(moment)
        when (plan.recorder) {
            // Both halves at once is exactly [EngineHolder.warm], which the tile
            // has called since M1. Reusing it rather than writing the pair out
            // again keeps the tile's behaviour literally the same code.
            RecorderMove.Reserve -> EngineHolder.warm(context)

            RecorderMove.Release -> {
                if (plan.loadEngine) EngineHolder.warmEngine(context)
                // Research R2: the microphone capability is held for as long as
                // the role is held, session or no session. A reservation is
                // therefore the only thing between a dismissed session and a
                // recorder object that lives until the phone reboots.
                EngineHolder.capture.unreserve()
            }

            // Stopping an open microphone is the flow's `Effect.StopCapture`,
            // reduced from `FlowEvent.Hidden`, and the host sends that event
            // before it asks for this moment. Nothing to add here: a second,
            // out-of-band stop from the warmth path would race the effect runner
            // for the same recorder. The row is a statement, not an action.
            RecorderMove.Stop -> Unit

            RecorderMove.Leave, RecorderMove.Start ->
                if (plan.loadEngine) EngineHolder.warmEngine(context)
        }
    }

    /**
     * False only in Direct Boot, before the user has unlocked once since the
     * reboot. Unknown counts as unlocked: every caller before G9 ran unlocked,
     * and a missing `UserManager` must not turn warmth off for the whole life of
     * a process that is in fact perfectly able to record.
     */
    private fun unlocked(context: Context): Boolean =
        runCatching { context.getSystemService(UserManager::class.java)?.isUserUnlocked }
            .getOrNull() ?: true
}
