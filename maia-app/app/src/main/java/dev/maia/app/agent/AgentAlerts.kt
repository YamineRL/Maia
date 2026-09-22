package dev.maia.app.agent

import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import dev.maia.app.R
import dev.maia.app.feel.Pattern
import dev.maia.app.feel.Schedule

/**
 * The three things that reach the user when the phone is in a pocket.
 *
 * `docs/M8-copy.md` section 4.2 lists six haptic events and section 4.1 splits
 * them in two. Three are the hand's, now: sent, queued and stopped, played
 * through `Haptics` as `USAGE_TOUCH` exactly as everything before M8 was.
 * These three are the other half: they arrive minutes after the user last
 * touched the phone, so they are the notification's own vibration, set on the
 * channel, and Do Not Disturb governs them.
 */
enum class RunAlert {
    /** `session.idle`. The turn ended. */
    Ended,

    /** `permission.asked` or `question.asked`. A person is needed. */
    Blocked,

    /** The turn ended without a `session.idle`. */
    Failed,
}

/**
 * A notification channel, as data.
 *
 * Android's own `NotificationChannel` cannot be built on the JVM, so what the
 * two channels are is decided here and a JVM test pins it. [create] turns one
 * of these into the platform object and is the only line that needs a device.
 */
data class ChannelSpec(
    val id: String,
    val name: Int,
    val description: Int,
    val importance: Int,
    /**
     * The channel's own vibration. Only [Pattern.timings] can be carried: a
     * channel takes a `long[]` of on and off durations and nothing else, so
     * the composition rung of `rung()` is unreachable here by construction and
     * these always play as the waveform. That is a platform limit and not a
     * choice, and it is why the pattern is named rather than inlined: the
     * timings a channel plays are the same timings the hand would have felt.
     */
    val vibration: Pattern?,
    val lockscreenVisibility: Int,
)

/**
 * Which channel each alert uses, what it vibrates, and what is drawn on a
 * lock screen.
 *
 * Nothing in this file takes a string. Every field is a resource id, an
 * integer or a [Pattern], which is the same discipline `RunCopy` keeps on the
 * screen and `AgentAck` keeps on the voice: an agent's words have no type
 * here they could travel in. `AgentNotifierTest` holds the Android half to the
 * same rule.
 */
object AgentAlerts {

    /** Section 5.1. Two channels and not one, so the user can give the second a sound. */
    val runs = ChannelSpec(
        id = "agent_runs",
        name = R.string.m8_notif_channel_runs_name,
        description = R.string.m8_notif_channel_runs_description,
        // A finished run is not an interruption. Same reasoning as the drafts
        // channel: something ended, elsewhere, and it will keep.
        importance = NotificationManager.IMPORTANCE_DEFAULT,
        vibration = Schedule.agentEnded,
        lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE,
    )

    /**
     * The one thing Maia interrupts for, in the channel description's own
     * words, so `IMPORTANCE_HIGH`: an agent that has stopped and is waiting is
     * the only state in this product where a person is actually needed.
     */
    val needsYou = ChannelSpec(
        id = "agent_needs_you",
        name = R.string.m8_notif_channel_needs_you_name,
        description = R.string.m8_notif_channel_needs_you_description,
        importance = NotificationManager.IMPORTANCE_HIGH,
        vibration = Schedule.agentBlocked,
        lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE,
    )

    /**
     * Section 5.14's standing row, on its own channel because Android demands
     * a channel and the two alerting ones are not it.
     *
     * `IMPORTANCE_LOW`: no sound, no vibration, no heads-up, no badge. It
     * spends nothing from the interruption budget the other two spend, which
     * is section 5.1's whole argument for why a third row in the user's
     * settings does not contradict section 4.5's refusal of a third alerting
     * channel. [vibration] is null and that is the type saying so: there is no
     * pattern to carry, rather than a pattern set to silence.
     *
     * `VISIBILITY_SECRET`, and section 5.14 gives the reason rather than the
     * default: this row is not news. It says a thing the user knew when they
     * spoke, and it says it continuously, so a lock screen carrying it for
     * four minutes is a disclosure traded for nothing. It also settles `Stop`:
     * a row that is absent from the lock screen has no button a stranger can
     * press.
     */
    val open = ChannelSpec(
        id = "agent_open",
        name = R.string.m8_notif_channel_open_name,
        description = R.string.m8_notif_channel_open_description,
        importance = NotificationManager.IMPORTANCE_LOW,
        vibration = null,
        lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET,
    )

    val channels = listOf(runs, needsYou, open)

    /**
     * The channel an alert posts on.
     *
     * [RunAlert.Failed] shares the runs channel with [RunAlert.Ended], which
     * means it cannot have its own channel vibration: a channel has exactly
     * one. Section 4.2 gives failed the fault pattern and ended a pattern of
     * its own, so the two cannot both be honoured.
     *
     * This was reported as a gap and section 4.5 has since decided it, the
     * same way round: failed rides the runs channel and shares its vibration,
     * and the channel's own name and description now say so in the user's
     * terms ("finishes, or stops without finishing") so the settings row is
     * not a promise the feature breaks. `AgentAlertsTest` still pins the
     * consequence, because a decision that costs something should keep saying
     * what it cost.
     */
    fun channel(alert: RunAlert): ChannelSpec = when (alert) {
        RunAlert.Ended, RunAlert.Failed -> runs
        RunAlert.Blocked -> needsYou
    }

    /** The pattern section 4.2 gives the alert, whether or not a channel can carry it. */
    fun pattern(alert: RunAlert): Pattern = when (alert) {
        RunAlert.Ended -> Schedule.agentEnded
        RunAlert.Blocked -> Schedule.agentBlocked
        RunAlert.Failed -> Schedule.fault
    }

    /** The alert a pattern from the run machine belongs to, or null if it is the hand's. */
    fun alertFor(felt: Pattern): RunAlert? =
        RunAlert.entries.firstOrNull { pattern(it) == felt }

    /**
     * Whether `Haptics` plays this pattern itself.
     *
     * False for the three above, because playing them would send them as
     * `USAGE_TOUCH` and the whole point of section 4.1 is that they are not
     * the hand's. They vibrate because the notification vibrates.
     *
     * This is asked at the agent's haptic sink and nowhere else, and it has to
     * be: `Schedule.fault` is M4's fault as well as M8's failed, and M4's
     * arrives while the user's thumb is still on the screen. The same pattern
     * is the hand's on one surface and the notification's on another, so the
     * split cannot live on the pattern alone.
     */
    fun byHand(felt: Pattern): Boolean = alertFor(felt) == null

    /**
     * How long after the blocked row goes up its pattern is played once more.
     * `docs/M8-copy.md` section 4.2.
     *
     * The only repeat in the product, and one repeat only: section 4.3 says a
     * third would be nagging, and a pattern that nags gets the channel turned
     * off, which costs the user every other notification on it. It is the
     * channel that plays it, through the same row re-posted without
     * `setOnlyAlertOnce`, so Do Not Disturb still governs it and nothing here
     * ever reaches the vibrator directly.
     */
    const val REPEAT_MS = 20_000L

    /** Ids. One ongoing notification, and one per outcome. `DraftNotifier` holds 1. */
    const val ONGOING_ID = 2
    const val ENDED_ID = 3
    const val BLOCKED_ID = 4
    const val FAILED_ID = 5

    fun idFor(alert: RunAlert): Int = when (alert) {
        RunAlert.Ended -> ENDED_ID
        RunAlert.Blocked -> BLOCKED_ID
        RunAlert.Failed -> FAILED_ID
    }
}
