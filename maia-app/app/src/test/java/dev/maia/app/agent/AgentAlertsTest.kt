package dev.maia.app.agent

import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import dev.maia.app.feel.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two channels and the three alerts, which together are `docs/M8-copy.md`
 * section 4.1's correction to PRD section 11 made into data.
 */
class AgentAlertsTest {

    @Test
    fun `the split is exactly the three that arrive unprompted`() {
        val notification = listOf(Schedule.agentEnded, Schedule.agentBlocked, Schedule.fault)
        val hand = listOf(Schedule.agentSent, Schedule.agentQueued, Schedule.agentStopped)
        notification.forEach { assertFalse(AgentAlerts.byHand(it)) }
        hand.forEach { assertTrue(AgentAlerts.byHand(it)) }
        // The same three `Schedule.notification` names, from the other side.
        // Two lists that must agree is a bug waiting; this is the check that
        // they do.
        (notification + hand).forEach {
            assertEquals(Schedule.notification(it), !AgentAlerts.byHand(it))
        }
    }

    /** Every pattern the run machine can emit belongs to one side or the other. */
    @Test
    fun `an alert is found for each of the three and for nothing else`() {
        assertEquals(RunAlert.Ended, AgentAlerts.alertFor(Schedule.agentEnded))
        assertEquals(RunAlert.Blocked, AgentAlerts.alertFor(Schedule.agentBlocked))
        assertEquals(RunAlert.Failed, AgentAlerts.alertFor(Schedule.fault))
        assertNull(AgentAlerts.alertFor(Schedule.agentSent))
        assertNull(AgentAlerts.alertFor(Schedule.commit))
    }

    /**
     * Blocked is the only thing Maia interrupts for, in the channel
     * description's own words, and the importance is what makes that true on
     * the phone rather than only in the copy.
     */
    @Test
    fun `blocked has its own channel and a higher importance`() {
        assertEquals(AgentAlerts.needsYou, AgentAlerts.channel(RunAlert.Blocked))
        assertEquals(AgentAlerts.runs, AgentAlerts.channel(RunAlert.Ended))
        assertTrue(AgentAlerts.needsYou.importance > AgentAlerts.runs.importance)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, AgentAlerts.needsYou.importance)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, AgentAlerts.runs.importance)
    }

    /**
     * The channel vibrations are section 4.2's two patterns, and the pocket
     * test's discriminator survives the trip: ended puts its THUD last and
     * rises into it, blocked puts its THUD first and refuses to land. Low
     * frequency is the only thing denim transmits, so where the THUD sits is
     * the whole of the difference.
     */
    @Test
    fun `each channel carries its own pattern's timings`() {
        assertEquals(Schedule.agentEnded, AgentAlerts.runs.vibration)
        assertEquals(Schedule.agentBlocked, AgentAlerts.needsYou.vibration)
        assertEquals(6, AgentAlerts.runs.vibration?.timings?.size)
        assertEquals(6, AgentAlerts.needsYou.vibration?.timings?.size)
    }

    /**
     * The third channel, section 5.1, and the reason it does not contradict
     * section 4.5's refusal of a third.
     *
     * The refusal was about alerting channels: rows that spend the user's
     * interruption budget. This one spends none. `IMPORTANCE_LOW` is no sound,
     * no heads-up and no badge, its vibration is null rather than empty (there
     * is no pattern to carry, rather than a pattern set to silence), and
     * `VISIBILITY_SECRET` keeps it off the lock screen entirely, which is also
     * what stops a stranger pressing its `Stop`.
     */
    @Test
    fun `the ongoing channel is low, silent and never on the lock screen`() {
        assertEquals(NotificationManager.IMPORTANCE_LOW, AgentAlerts.open.importance)
        assertNull(AgentAlerts.open.vibration)
        assertEquals(NotificationCompat.VISIBILITY_SECRET, AgentAlerts.open.lockscreenVisibility)
        assertTrue(AgentAlerts.open.importance < AgentAlerts.runs.importance)
        // It carries no alert: the three outcomes go to the two alerting
        // channels, exactly as they did before this one existed.
        assertTrue(RunAlert.entries.none { AgentAlerts.channel(it) == AgentAlerts.open })
        assertEquals(listOf(AgentAlerts.runs, AgentAlerts.needsYou, AgentAlerts.open), AgentAlerts.channels)
    }

    /**
     * The gap, pinned so it cannot be mistaken for an oversight.
     *
     * Section 4.2 gives failed the fault pattern and ended a pattern of its
     * own. Section 5.1 gives two channels for three events. A channel carries
     * one vibration, so failed vibrates as ended does, and no amount of care
     * in this file changes that. Reported rather than resolved by inventing a
     * third channel: a channel is a row in the user's settings.
     */
    @Test
    fun `failed shares the runs channel and therefore ended's vibration`() {
        assertEquals(AgentAlerts.runs, AgentAlerts.channel(RunAlert.Failed))
        assertEquals(Schedule.fault, AgentAlerts.pattern(RunAlert.Failed))
        assertTrue(AgentAlerts.channel(RunAlert.Failed).vibration != AgentAlerts.pattern(RunAlert.Failed))
    }

    @Test
    fun `the ids are distinct and none of them is the drafts notification`() {
        val ids = RunAlert.entries.map { AgentAlerts.idFor(it) } + AgentAlerts.ONGOING_ID
        assertEquals(ids.size, ids.toSet().size)
        assertFalse(dev.maia.app.DraftNotifier.ID in ids)
    }

    @Test
    fun `the channel ids are stable`() {
        // A channel id is user-visible state: changing one loses whatever the
        // user did to the old channel and silently creates a fresh one at the
        // default importance. They are pinned for the same reason a database
        // column name is.
        assertEquals("agent_runs", AgentAlerts.runs.id)
        assertEquals("agent_needs_you", AgentAlerts.needsYou.id)
        assertEquals("agent_open", AgentAlerts.open.id)
    }
}
