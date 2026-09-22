package dev.maia.app.agent

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.maia.app.MainActivity
import dev.maia.app.R
import dev.maia.app.feel.Haptics
import dev.maia.app.feel.Pattern
import dev.maia.app.screens.RunCopy
import dev.maia.app.screens.RunDuration
import dev.maia.transport.ProjectEntry

/**
 * The agent's notifications, and the half of its haptics that a channel plays.
 *
 * Two jobs, and they are one job. `docs/M8-copy.md` section 4.1 says the three
 * unprompted patterns are "the notification's own vibration, set on the
 * channel", so the thing that posts the notification and the thing that
 * decides whether to vibrate cannot be two classes that might disagree. [feel]
 * is the agent's haptic sink: a hand pattern goes to [Haptics] as
 * `USAGE_TOUCH`, and the other three go nowhere, because the notification
 * carries them.
 *
 * **No agent text enters a notification, by any door.** [alert] takes a
 * [ProjectEntry] and a resource id and there is no parameter it could arrive
 * through, which is the same shape as `AgentAck` and `RunCopy`. The doors
 * worth naming, because they are the ones that are easy to leave open:
 *
 * - `setTicker` is an accessibility announcement in disguise: it was what a
 *   screen reader spoke when a notification arrived. Never set here.
 * - The body of a blocked notification names the **kind** of block and never
 *   its content, which is section 5.3's rule and is agent output.
 * - The public version is a constant per outcome, so a lock screen learns that
 *   an agent finished and nothing else, not even which project.
 *
 * `AgentNotifierTest` reads this file back and fails if any of those appear.
 */
class AgentNotifier(
    context: Context,
    private val haptics: Haptics,
) {

    private val context = context.applicationContext

    // ------------------------------------------------------------- haptics

    /**
     * The agent's haptic sink, for `RunEffect.Feel`.
     *
     * Section 4.1: sent, queued and stopped are a consequence of the hand and
     * stay `USAGE_TOUCH`; ended, blocked and failed arrive later and belong to
     * the channel. A user asleep with Do Not Disturb on is not woken by a test
     * suite passing, and that is only true if these never reach the vibrator
     * directly.
     */
    fun feel(pattern: Pattern) {
        if (AgentAlerts.byHand(pattern)) haptics.play(pattern)
    }

    // ------------------------------------------------------- notifications

    /**
     * The foreground service's notification, which is the price of the process
     * staying alive for four minutes with the screen off.
     *
     * Section 5.14, and every line of it is the document's. On
     * `AgentAlerts.open`, which is `IMPORTANCE_LOW`: no sound, no vibration,
     * no heads-up, no badge. `VISIBILITY_SECRET`, so it is not on the lock
     * screen at all, which is also why its `Stop` action cannot be pressed by
     * someone holding a locked phone.
     *
     * The title is the project pair and nothing else, because Android already
     * draws "Maia" in the header above it and spending the most valuable line
     * in the shade repeating the line above it buys the user nothing. The body
     * is one of three, because the row stands for the whole run and `Running.`
     * is a lie while the agent is blocked. No duration, no counter and no
     * progress bar: a number that climbs in the shade for four minutes is an
     * invitation to watch it, and the phone does not know how much is left.
     *
     * When the project is not known yet the pair has nothing to print, so the
     * title falls back to the app's own name. That is the one line here the
     * copy does not write, and it is a fallback for a window of a few hundred
     * milliseconds before the registry answers, not a design.
     */
    fun ongoing(status: RunStatus, number: Int, name: String?): Notification {
        ensureChannels()
        val builder = NotificationCompat.Builder(context, AgentAlerts.open.id)
            .setSmallIcon(R.drawable.ic_tile_maia)
            .setContentText(context.getString(RunCopy.ongoingBody(status)))
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(open())
        if (number <= 0 || name == null) {
            builder.setContentTitle(context.getString(R.string.app_name))
        } else {
            builder.setContentTitle(context.getString(R.string.m8_notif_open_title, number, name))
        }
        // `Stop` reuses `m8_run_stop_action` rather than taking an id of its
        // own, because it is the same word making the same promise and a
        // screen reader user should hear one promise for one word. It writes
        // nothing, which is PRD section 7's cancel rule reaching the one
        // surface in M8 that is not a screen.
        builder.addAction(
            NotificationCompat.Action.Builder(
                0,
                context.getString(R.string.m8_run_stop_action),
                cancel(),
            ).build(),
        )
        return builder.build()
    }

    /**
     * One outcome, posted to the channel that carries its vibration.
     *
     * [body] is a resource id chosen by the caller from the copy document.
     * [text] is one of the two bodies the document builds out of two of its
     * own strings, already assembled by [doneBody] or [failedBody] above; it
     * is a String because `getString` returns one, and every word in it came
     * from a resource. Both null is a notification with a title and no body,
     * which is honest and is what a finished run gets when the clock is
     * unusable.
     *
     * Neither parameter is a door for agent text. The callers are
     * [RunAlerts.Posting], which carries an enum, a [ProjectEntry] and a
     * [BlockKind] and has no String field at all, and the two builders above,
     * which take a [RunLoss] and two longs.
     *
     * [again] is section 4.2's repeat and is set by [knock] alone: it turns
     * `setOnlyAlertOnce` off for that one post, so the identical row plays the
     * channel's pattern a second time. It changes nothing the row says.
     */
    @SuppressLint("MissingPermission")
    fun alert(
        alert: RunAlert,
        project: ProjectEntry,
        body: Int? = null,
        text: String? = null,
        kind: BlockKind? = null,
        again: Boolean = false,
    ) {
        if (!permitted()) return
        ensureChannels()
        val spec = AgentAlerts.channel(alert)
        val title = when (alert) {
            RunAlert.Ended -> R.string.m8_notif_done_title
            RunAlert.Blocked -> R.string.m8_notif_blocked_title
            RunAlert.Failed -> R.string.m8_notif_failed_title
        }
        val public = NotificationCompat.Builder(context, spec.id)
            .setSmallIcon(R.drawable.ic_tile_maia)
            .setContentTitle(context.getString(publicTitle(alert)))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        val notification = NotificationCompat.Builder(context, spec.id)
            .setSmallIcon(R.drawable.ic_tile_maia)
            .setContentTitle(context.getString(title, project.number, project.name))
            .apply {
                val line = text ?: body?.let(context::getString)
                if (line != null) setContentText(line)
            }
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(open())
            .setAutoCancel(true)
            .apply { if (alert == RunAlert.Blocked && kind != null) blocked(this, kind, again) }
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(AgentAlerts.idFor(alert), notification) }
    }

    /**
     * The expanded row an agent gets while it is waiting, section 5.3.
     *
     * Two controls on a permission and one on a question, because a
     * notification action is a word and a question cannot be answered in one.
     * The question's control opens the run screen, which is where the words
     * it asked are, and on a locked phone that is a keyguard first.
     *
     * **Every one of them requires the device unlocked.** Allowing an agent
     * to go on, or ending its run, is not something a person holding somebody
     * else's phone gets to do from the shade, and
     * `setAuthenticationRequired` is the only thing that actually stops them.
     *
     * The second line is the honest half of this design: the user is being
     * offered `Allow` for a thing they cannot see, because rule 12 keeps the
     * agent's own words off the shade, so the line says where the words are
     * and puts the two choices in the order of their risk.
     *
     * `setOnlyAlertOnce` is set here rather than at the rewrite, because it
     * is a property of the row and the row is rewritten in place: it is what
     * makes every later state a correction the user finds rather than one
     * that interrupts them.
     */
    private fun blocked(builder: NotificationCompat.Builder, kind: BlockKind, again: Boolean = false) {
        builder.setOnlyAlertOnce(!again)
        when (kind) {
            BlockKind.Permission -> {
                builder.setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        context.getString(R.string.m8_notif_blocked_permission_body) +
                            "\n\n" +
                            context.getString(R.string.m8_notif_blocked_permission_unseen),
                    ),
                )
                builder.addAction(
                    guarded(R.string.m8_notif_action_allow, service(RunService.ACTION_ALLOW, ALLOW_CODE)),
                )
                builder.addAction(
                    guarded(R.string.m8_notif_action_refuse, service(RunService.ACTION_REFUSE, REFUSE_CODE)),
                )
            }
            // Not `Open`, which named the mechanism and was the same word as
            // tapping the row. `Answer` names the act, and the act happens on
            // the run screen because a sentence does not fit here.
            BlockKind.Question ->
                builder.addAction(guarded(R.string.m8_notif_action_answer, open()))
        }
    }

    /**
     * Section 4.2's one repeat, twenty seconds after the row went up.
     *
     * The same row, the same id and the same words, posted once more with
     * `setOnlyAlertOnce` off, which is the only way an Android notification
     * plays its channel's vibration a second time. Doing it through the
     * channel is the whole of section 4.5's argument: the pattern stays under
     * Do Not Disturb and under whatever the user has done to the channel, and
     * nothing here touches the vibrator directly.
     *
     * Nothing about the row changes, so a user who is already looking at it
     * sees no movement. What they get is the knock.
     */
    fun knock(project: ProjectEntry, kind: BlockKind) =
        alert(RunAlert.Blocked, project, body = blockedBody(kind), kind = kind, again = true)

    /**
     * One action the shade will not fire until the phone is unlocked.
     *
     * The label is the accessible name of a notification action, so the
     * screen-reader wording is the label and there is no second string to
     * keep in step with it.
     */
    private fun guarded(label: Int, intent: PendingIntent) =
        NotificationCompat.Action.Builder(0, context.getString(label), intent)
            .setAuthenticationRequired(true)
            .build()

    /**
     * The same row, rewritten in place: same id, same channel, silent.
     * Section 5.17.
     *
     * Rewritten and not replaced, and not cancelled either. The user is
     * holding a row that says an agent is waiting for them; the useful thing
     * is for that row to become true, under their thumb, before they press
     * something that cannot work. A cancel would leave them looking for a
     * notification that had quietly gone.
     *
     * Three of the four after-states drop the actions, because there is
     * nothing left to press. Only [BlockedNotice.Undelivered] keeps them: the
     * agent is still waiting, the phone simply failed to say so, and trying
     * again is the right thing to do.
     */
    @SuppressLint("MissingPermission")
    fun blockedRow(row: RunAlerts.BlockedRow) {
        if (!permitted()) return
        if (row.notice == BlockedNotice.Asking) return
        ensureChannels()
        val spec = AgentAlerts.channel(RunAlert.Blocked)
        val public = NotificationCompat.Builder(context, spec.id)
            .setSmallIcon(R.drawable.ic_tile_maia)
            .setContentTitle(context.getString(rowPublicTitle(row.notice)))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        val builder = NotificationCompat.Builder(context, spec.id)
            .setSmallIcon(R.drawable.ic_tile_maia)
            .setContentTitle(context.getString(rowTitle(row.notice), row.project.number, row.project.name))
            .setContentText(rowBody(row))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(open())
            .setAutoCancel(true)
            // No sound and no vibration on any of these. A correction is not
            // an alert, and the third case is a row that was already dead in
            // the user's hand: buzzing about it would be Maia interrupting
            // them over something they never did.
            .setOnlyAlertOnce(true)
            .setSilent(true)
        if (row.notice == BlockedNotice.Undelivered && row.kind != null) blocked(builder, row.kind)
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(AgentAlerts.idFor(RunAlert.Blocked), builder.build())
        }
    }

    private fun rowTitle(notice: BlockedNotice): Int = when (notice) {
        BlockedNotice.Allowed, BlockedNotice.Refused -> R.string.m8_notif_blocked_answered_title
        BlockedNotice.Undelivered -> R.string.m8_notif_blocked_undelivered_title
        // One title for both, because the far end is not waiting either way
        // and the only difference is whether the user pressed anything.
        BlockedNotice.Gone, BlockedNotice.Stale -> R.string.m8_notif_blocked_gone_title
        BlockedNotice.Asking -> R.string.m8_notif_blocked_title
    }

    private fun rowPublicTitle(notice: BlockedNotice): Int = when (notice) {
        BlockedNotice.Allowed, BlockedNotice.Refused -> R.string.m8_notif_public_blocked_answered
        BlockedNotice.Undelivered -> R.string.m8_notif_public_blocked_undelivered
        BlockedNotice.Gone, BlockedNotice.Stale -> R.string.m8_notif_public_blocked_gone
        BlockedNotice.Asking -> R.string.m8_notif_public_blocked
    }

    /**
     * The body, which is a resource id or two joined by the copy's own space.
     *
     * The undelivered case takes one of section 5.4's four reachable reason
     * sentences and invents no fifth. [RunLoss] is an enum, so there is no
     * parameter here an agent's error text could arrive through, which is
     * exactly the discipline [failedBody] one screen up already keeps.
     */
    private fun rowBody(row: RunAlerts.BlockedRow): String = when (row.notice) {
        BlockedNotice.Allowed -> context.getString(R.string.m8_notif_blocked_answered_allow)
        BlockedNotice.Refused -> context.getString(R.string.m8_notif_blocked_answered_refuse)
        BlockedNotice.Undelivered -> context.getString(
            R.string.m8_notif_blocked_undelivered_body,
            context.getString(RunCopy.reason(row.loss ?: RunLoss.Lost)),
        )
        BlockedNotice.Gone -> context.getString(R.string.m8_notif_blocked_gone_body)
        BlockedNotice.Stale -> context.getString(R.string.m8_notif_blocked_stale_body)
        BlockedNotice.Asking -> context.getString(R.string.m8_notif_blocked_permission_body)
    }

    /**
     * The body for a finished run: "Ran for four minutes. Tap to read the
     * reply." Null when the clock is unusable, and null means the caller posts
     * the title alone, which section 5.2 says in as many words is correct.
     */
    fun doneBody(startedAt: Long, endedAt: Long): String? {
        val words = RunDuration.words(startedAt, endedAt) ?: return null
        val duration = if (words.count == null) {
            context.getString(words.res)
        } else {
            context.getString(words.res, words.count)
        }
        return context.getString(R.string.m8_notif_done_body, duration)
    }

    /**
     * The body for a lost run: one of section 5.4's five reason sentences,
     * joined to "Open to see what arrived before it stopped." by the single
     * space `m8_notif_failed_body` already carries.
     *
     * [RunLoss] is an enum and [RunCopy.reason] returns an id, so there is no
     * parameter on this path an agent's error text could arrive through. That
     * is the same shape as [AgentAck], and it is deliberate: this is the body
     * an implementation is most tempted to be helpful in.
     */
    fun failedBody(loss: RunLoss): String =
        context.getString(R.string.m8_notif_failed_body, context.getString(RunCopy.reason(loss)))

    /** The body for a block, which names the kind and never the content (section 5.3). */
    fun blockedBody(kind: BlockKind): Int = when (kind) {
        BlockKind.Permission -> R.string.m8_notif_blocked_permission_body
        BlockKind.Question -> R.string.m8_notif_blocked_question_body
    }

    fun clear(alert: RunAlert) {
        NotificationManagerCompat.from(context).cancel(AgentAlerts.idFor(alert))
    }

    private fun publicTitle(alert: RunAlert): Int = when (alert) {
        RunAlert.Ended -> R.string.m8_notif_public_done
        RunAlert.Blocked -> R.string.m8_notif_public_blocked
        RunAlert.Failed -> R.string.m8_notif_public_failed
    }

    /**
     * Tapping opens the run screen for that project (section 5.5), which is
     * `MainActivity`: it is where the run screen is hosted, and the project
     * number rides as an extra rather than as a second Activity.
     */
    private fun open(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * The ongoing row's `Stop`, which reaches the driver and not just the
     * service. Stopping the service would remove the row and leave the turn
     * streaming, which is the one guess this feature cannot afford.
     */
    private fun cancel(): PendingIntent = service(RunService.ACTION_CANCEL, CANCEL_CODE)

    /**
     * An action that reaches the driver rather than only the service.
     *
     * Distinct request codes, because two `PendingIntent`s that differ only
     * in their action are the same `PendingIntent` to the system and the
     * second would quietly overwrite the first. `FLAG_IMMUTABLE`, so nothing
     * outside this process can fill anything in.
     */
    private fun service(action: String, code: Int): PendingIntent = PendingIntent.getService(
        context,
        code,
        Intent(context, RunService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun permitted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * All three channels, every time, because creating one that exists is a no-op
     * and a channel that does not exist swallows the notification silently.
     *
     * A channel keeps whatever the user has done to it, which is the point:
     * once created, the importance and the vibration below are a default the
     * user may overrule, and Do Not Disturb overrules both. That is section
     * 4.4's substitute for a haptic the user cannot feel, configured by the
     * user rather than guessed at by Maia.
     */
    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        AgentAlerts.channels.forEach { spec ->
            val channel = NotificationChannel(spec.id, context.getString(spec.name), spec.importance).apply {
                description = context.getString(spec.description)
                lockscreenVisibility = spec.lockscreenVisibility
                setShowBadge(false)
                // A null pattern is the ongoing channel, which never makes a
                // sound and never vibrates (section 5.1). The sound is cleared
                // explicitly rather than left to IMPORTANCE_LOW's default,
                // because the default is a behaviour and this is a promise
                // printed in the channel description the user is reading.
                if (spec.vibration == null) {
                    enableVibration(false)
                    setSound(null, null)
                } else {
                    enableVibration(true)
                }
                // A channel takes timings and no amplitudes, so this is the
                // waveform rung of `rung()` by platform limit rather than by
                // choice. The shape survives: the THUD is still last in ended
                // and first in blocked, which is the discrimination section
                // 4.3 asks the pocket to make.
                vibrationPattern = spec.vibration?.timings
            }
            manager.createNotificationChannel(channel)
        }
    }

    private companion object {
        const val CANCEL_CODE = 1
        const val ALLOW_CODE = 2
        const val REFUSE_CODE = 3
    }
}
