package dev.maia.tunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while the tunnel is on.
 *
 * Android will happily kill a backgrounded app, and a killed app means the
 * loopback listener disappears halfway through an SSH session. A foreground
 * service with a visible notification is the supported way to say "this is
 * still doing something", and it doubles as the WireGuard-style at-a-glance
 * status: the notification shows the port and the round trip time.
 *
 * Note what this is not: a VpnService. Nothing here claims the system VPN slot
 * or touches other apps' traffic. Termius reaches the devbox because Android's
 * 127.0.0.1 is device-wide rather than per-app.
 */
class TunnelService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Tunnel.disconnect(this)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification(Tunnel.state.value))
        Tunnel.connect(this)

        watcher?.cancel()
        watcher = scope.launch {
            Tunnel.state.collectLatest { state ->
                if (state.link == LinkState.OFF && state.error.isNotEmpty()) {
                    // Connect failed. Show why, then let the user dismiss it.
                    notificationManager().notify(NOTIFICATION_ID, buildNotification(state))
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf()
                    return@collectLatest
                }
                notificationManager().notify(NOTIFICATION_ID, buildNotification(state))
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        watcher?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun notificationManager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Tunnel status",
            // Low, because this notification is a status line, not an event.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows whether the devbox tunnel is up"
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun buildNotification(state: TunnelState): Notification {
        val open = Intent(this, MainActivity::class.java).let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val stop = Intent(this, TunnelService::class.java).setAction(ACTION_STOP).let {
            PendingIntent.getService(this, 1, it, PendingIntent.FLAG_IMMUTABLE)
        }

        val title = when {
            state.link == LinkState.ON -> "Devbox connected"
            state.link == LinkState.CONNECTING -> "Connecting to devbox"
            state.error.isNotEmpty() -> "Devbox tunnel failed"
            else -> "Devbox tunnel off"
        }

        val text = when {
            state.error.isNotEmpty() && state.link != LinkState.ON -> state.error
            state.link == LinkState.ON -> {
                val ports = state.forwards.joinToString(", ") { "${it.name} 127.0.0.1:${it.localPort}" }
                val rtt = if (state.pingMs > 0) "  ${state.pingMs} ms" else ""
                if (ports.isEmpty()) "Tunnel up$rtt" else "$ports$rtt"
            }
            else -> "Bringing the tunnel up"
        }

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .setOngoing(state.link != LinkState.OFF)
            .addAction(
                Notification.Action.Builder(null, "Disconnect", stop).build()
            )
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "tunnel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "dev.maia.tunnel.STOP"

        fun start(context: Context) {
            val i = Intent(context, TunnelService::class.java)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, TunnelService::class.java).setAction(ACTION_STOP)
            context.startService(i)
        }
    }
}
