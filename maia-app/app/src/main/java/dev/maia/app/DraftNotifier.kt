package dev.maia.app

import android.Manifest
import android.annotation.SuppressLint
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
import dev.maia.app.flow.DraftNotice
import dev.maia.app.flow.QueuedDraft
import dev.maia.app.flow.draftNotice

/**
 * The waiting-draft notification, for a host that is allowed to post one.
 *
 * Not a [dev.maia.app.flow.FlowHost] itself, and not wired to the controller
 * here. `Effect.PostDraftWaiting` goes to whatever window is on screen, and only
 * a host running on an unlocked screen may implement it: the session window,
 * which is the host that is up while the phone is locked, must not. So this is
 * the implementation an Activity host delegates to, and the rule about which
 * host delegates lives in [dev.maia.app.flow.FlowHost] where it can be read.
 *
 * [queue] rather than a title argument, because the effect carries only a count
 * (deliberately: an effect with a title in it would be a title travelling
 * towards a lock screen) and the words have to come from somewhere. The
 * controller's queue is that somewhere, read at the moment of posting.
 *
 * Nothing here decides what the notification says. [draftNotice] does, on the
 * JVM, and this turns its answer into resources and a builder.
 */
class DraftNotifier(
    context: Context,
    private val queue: () -> List<QueuedDraft>,
) {

    private val context = context.applicationContext

    /**
     * Post, update or remove, whichever the queue calls for.
     *
     * The count the effect carried is not read: the queue is the truth and the
     * count is derived from it, so the two cannot disagree after a race between
     * a commit and a notification update.
     */
    fun post() {
        val manager = NotificationManagerCompat.from(context)
        when (val notice = draftNotice(queue(), permitted())) {
            DraftNotice.None -> manager.cancel(ID)
            is DraftNotice.One -> show(manager, notice.title, context.getString(R.string.m3_notif_private_body, notice.whenText))
            is DraftNotice.Several -> show(
                manager,
                context.resources.getQuantityString(R.plurals.m3_notif_private_title_many, notice.count, notice.count),
                context.getString(R.string.m3_notif_private_body_many, notice.oldestTitle),
            )
        }
    }

    /**
     * Granted, or not asked for on a version that never asks.
     *
     * False is not a failure and produces no prompt from here. The permission is
     * asked for by the host, the first time a queued draft is reviewed after an
     * unlock, which is the only moment at which the reason can be stated
     * truthfully; until then there is simply no notification.
     */
    private fun permitted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    // MissingPermission: [permitted] is checked on every path into this
    // function and returns false unless POST_NOTIFICATIONS is granted, but lint
    // cannot follow a permission check across a function boundary. The notify
    // itself is wrapped below for the case the check cannot cover, which is the
    // permission being revoked between the two lines. Scoped to this function.
    @SuppressLint("MissingPermission")
    private fun show(manager: NotificationManagerCompat, title: String, body: String) {
        channel()
        // The public version is the whole privacy design of this notification.
        // It is what a stranger holding the phone reads, so it is a constant:
        // byte-identical at one waiting draft and at five, with no count, no
        // body, no time and no action. See docs/M3-copy.md section 9 item 1.
        val public = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile_maia)
            .setContentTitle(context.getString(R.string.m3_notif_public_title_countless))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile_maia)
            .setContentTitle(title)
            .setContentText(body)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setOnlyAlertOnce(true)
            .setContentIntent(open())
            .setAutoCancel(false)
            .build()

        // The permission was checked a few lines above; the throw can still
        // happen if it is revoked in between, and losing a notification is not
        // worth taking the process down for.
        runCatching { manager.notify(ID, notification) }
    }

    private fun open(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun channel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // Default importance rather than high: a draft that is already kept is
        // not an interruption, it is a reminder that something is waiting.
        val channel = NotificationChannel(
            CHANNEL,
            context.getString(R.string.m3_notif_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.m3_notif_channel_description)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL = "drafts_waiting"

        /** One id, because there is one notification however many drafts are waiting. */
        const val ID = 1
    }
}
