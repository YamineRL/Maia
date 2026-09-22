package dev.maia.app.agent

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.maia.app.feel.Haptics
import dev.maia.transport.PermissionReply

/**
 * What keeps a four-minute turn alive with the screen off.
 *
 * The stream is not held here. It is held by `AgentDriver`, on its own
 * threads, in this process, and that is the whole mechanism: a foreground
 * service holds **the process** at foreground-service importance, which keeps
 * it off the low-memory killer's list and out of Doze's network restrictions.
 * A screen-off turn then changes nothing, because nothing about the stream was
 * ever tied to a window. Without this the same turn is a background process
 * with an open socket, which is the exact shape Android reclaims first.
 *
 * `dataSync` is the type, declared in the manifest and repeated at
 * `startForeground` because API 34 requires the call and the manifest to
 * agree. It is the honest one: this synchronises a running job's output over
 * the network. `shortService` was the alternative and is wrong, because it is
 * capped at three minutes and `docs/M8-copy.md` section 1.4 states plainly
 * that a four-minute run is a supported case.
 *
 * Nothing in here is exported and nothing in here binds. The service is
 * started and stopped by [follow], from the same process that owns the driver.
 *
 * **The six-hour budget, and why it is handled here rather than recorded as a
 * limit.** From Android 15 a `dataSync` foreground service has a cumulative
 * budget of roughly six hours in any twenty-four, and when it runs out the
 * system calls [onTimeout] and then crashes the app with
 * `ForegroundServiceDidNotStopInTimeException` if the service is still up a
 * few seconds later. The default [Service.onTimeout] does nothing, so not
 * overriding it is a decision to crash. That is the whole argument: a single
 * four-minute turn is nowhere near the cap, but the budget is cumulative
 * across the day and only counts time this service is actually up, which is
 * exactly the time a turn is live. Six hours of live agent turns in one day is
 * unusual and is not impossible for the person this product is built for, and
 * the failure it would produce is the app dying mid-reply.
 */
class RunService : Service() {

    private val notifier: AgentNotifier by lazy { AgentNotifier(this, Haptics(this)) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            // The user pressed `Stop` on the row. That reaches the driver, not
            // just this service: removing the notification and leaving the
            // turn streaming would be the row lying about what it did. The
            // driver's own `stop` then takes the state to `Stopped`, which
            // brings [follow] back here with ACTION_STOP to take the row down.
            ACTION_CANCEL -> AgentHost.current()?.stop()
            // The two answer controls on the blocked row. They reach the
            // driver for the same reason `Stop` does: the row is not the
            // answer, the reply to the far end is, and a row that took itself
            // down without one would be the notification lying about what it
            // did. The shade has already made the user unlock the phone.
            //
            // `Allow` is once here, exactly as it is on the run screen. The
            // session choice needs its caption, and a caption does not fit on
            // a notification action, so the word means the smaller thing in
            // both places rather than two different things in two places.
            ACTION_ALLOW -> AgentHost.current()?.answer(PermissionReply.ONCE)
            ACTION_REFUSE -> AgentHost.current()?.answer(PermissionReply.REJECT)
            ACTION_STOP -> stop()
            else -> start(shown(intent))
        }
        // Not sticky on purpose. A process that was killed mid-turn has lost
        // the stream, the session cursor and the reply, and restarting this
        // service would produce a foreground notification over nothing
        // running. Section 5.5 already says what happens when the user comes
        // back to a killed turn: the screen fetches the turn from the session
        // on the devbox, which is where it still is.
        return START_NOT_STICKY
    }

    private fun start(shown: Shown) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        // A foreground service cannot be started from the background on API 31
        // and later, and the throw is a crash rather than an error. Losing the
        // process protection is survivable: the turn keeps streaming while the
        // process lives. Crashing the app at the moment the user spoke is not.
        runCatching {
            ServiceCompat.startForeground(
                this,
                AgentAlerts.ONGOING_ID,
                notifier.ongoing(shown.status, shown.number, shown.name),
                type,
            )
        }.onFailure { stopSelf() }
    }

    /**
     * The `dataSync` budget for the day is spent.
     *
     * The service goes and the run does not. The stream is the driver's, in
     * this process, and losing foreground importance does not close a socket:
     * it makes the process reclaimable, which is a risk and not an ending.
     * Stopping quietly and letting the turn continue is strictly better than
     * the two alternatives, which are crashing the app mid-reply and killing a
     * turn the user is waiting on because a quota elsewhere ran out.
     *
     * `STOP_FOREGROUND_DETACH` and not `REMOVE`: the ongoing row stays where
     * it is. Taking it down would tell the user the run ended, which is the
     * one thing that has not happened, and rule 12 is that nothing invents an
     * ending. Nothing is said and nothing is felt, because nothing happened
     * that the user did or needs to answer.
     *
     * There is no copy for this and none is needed. The user sees the run they
     * were already watching, in the row it was already in.
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        stopSelf(startId)
    }

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * The row's content, rebuilt from three extras rather than read off a
     * static.
     *
     * A started service is delivered an Intent and nothing else, and the
     * driver lives in the same process, so reaching for it here would work
     * until the day the process is restarted by the system with a pending
     * Intent and no driver. Three primitives the phone already knew survive
     * that: a status name, a project number and a project name, all of them
     * the user's own facts and none of them a word any agent produced.
     */
    private data class Shown(val status: RunStatus, val number: Int, val name: String?)

    private fun shown(intent: Intent?): Shown = Shown(
        status = intent?.getStringExtra(EXTRA_STATUS)
            ?.let { name -> RunStatus.entries.firstOrNull { it.name == name } }
            ?: RunStatus.Working,
        number = intent?.getIntExtra(EXTRA_NUMBER, 0) ?: 0,
        name = intent?.getStringExtra(EXTRA_NAME),
    )

    companion object {
        const val ACTION_RUN = "dev.maia.app.agent.RUN"
        const val ACTION_STOP = "dev.maia.app.agent.STOP"

        /** The ongoing row's `Stop`, which is the user, not the run ending. */
        const val ACTION_CANCEL = "dev.maia.app.agent.CANCEL"

        /** The blocked row's `Allow`, which is once. Section 5.3. */
        const val ACTION_ALLOW = "dev.maia.app.agent.ALLOW"

        /**
         * The blocked row's `Refuse and stop`, which carries no note: the
         * field is a run screen control, and a refusal from the shade is a
         * bare one rather than one with an empty sentence attached.
         */
        const val ACTION_REFUSE = "dev.maia.app.agent.REFUSE"
        const val EXTRA_STATUS = "status"
        const val EXTRA_NUMBER = "number"
        const val EXTRA_NAME = "name"

        /**
         * Start, update or stop, from one [RunState].
         *
         * The state decides, not the caller. It reads [RunState.holding] and
         * not [RunState.live], and the difference between those two is the
         * whole of this paragraph. `live` is the screen's question: a refused
         * turn is over in the footer, which says `Ask again`. The wire's
         * question is not the same one. A refusal ends the turn and leaves the
         * stream open, because `m8_notif_blocked_answered_refuse` promises
         * Maia says when the run has ended and only the stream produces that,
         * so reading `live` here took the foreground service down inside the
         * one window that promise has to be kept in. A process without
         * foreground importance is the first thing Android reclaims, and on a
         * handset in a pocket the ending would then simply never arrive.
         *
         * The row keeps `m8_notif_open_body_working` through that window,
         * because [RunStatus.Stopped] falls to `RunCopy.ongoingBody`'s
         * default. That is the truthful one of the three the copy defines:
         * the far end has not finished, which is the only reason this row is
         * still up, and the blocked row beside it is already saying in the
         * copy's own words that the run ends there and Maia will say when it
         * has.
         */
        fun follow(context: Context, state: RunState) {
            val intent = Intent(context, RunService::class.java)
            if (state.holding) {
                intent.action = ACTION_RUN
                intent.putExtra(EXTRA_STATUS, state.status.name)
                state.project?.let {
                    intent.putExtra(EXTRA_NUMBER, it.number)
                    intent.putExtra(EXTRA_NAME, it.name)
                }
                runCatching { ContextCompat.startForegroundService(context, intent) }
            } else {
                intent.action = ACTION_STOP
                runCatching { context.startService(intent) }
            }
        }
    }
}
