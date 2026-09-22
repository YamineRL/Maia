package dev.maia.app.answer

import android.provider.AlarmClock
import android.provider.Settings
import android.view.KeyEvent
import android.media.AudioManager
import dev.maia.nlu.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The act intents, as the plans Android will be asked to run.
 *
 * Every URI, extra and package name is checked here rather than on a phone,
 * which is the whole reason [planFor] builds data instead of
 * `android.content.Intent`: the day a string is wrong this test is wrong
 * with it, and the phone was never asked.
 */
class HandoffPlanTest {

    private fun launch(plan: HandoffPlan?): HandoffPlan.Launch =
        plan as? HandoffPlan.Launch ?: error("expected a Launch, got $plan")

    @Test
    fun `a timer is ACTION_SET_TIMER with seconds and never skip-UI`() {
        val plan = launch(planFor(Intent.SetTimer(durationMs = 12 * 60_000, label = "tea")))
        assertEquals(AlarmClock.ACTION_SET_TIMER, plan.action)
        assertNull(plan.uri)
        assertEquals(Extra(AlarmClock.EXTRA_LENGTH, 720), plan.extras[0])
        assertEquals(Extra(AlarmClock.EXTRA_MESSAGE, "tea"), plan.extras[1])
        // Section 3.2: skip-UI only after hardware verification. The first
        // pass is always the clock's own confirm screen, so the extra is
        // absent rather than false.
        assertTrue(plan.extras.none { it.key == AlarmClock.EXTRA_SKIP_UI })
    }

    @Test
    fun `an alarm carries hour, minute, label, and tomorrow's day when asked`() {
        val today = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
        val tomorrow = if (today == java.util.Calendar.SATURDAY) {
            java.util.Calendar.SUNDAY
        } else {
            today + 1
        }
        val plan = launch(planFor(Intent.SetAlarm(hour = 7, minute = 30, label = "run", tomorrow = true)))
        assertEquals(AlarmClock.ACTION_SET_ALARM, plan.action)
        assertEquals(Extra(AlarmClock.EXTRA_HOUR, 7), plan.extras[0])
        assertEquals(Extra(AlarmClock.EXTRA_MINUTES, 30), plan.extras[1])
        assertEquals(Extra(AlarmClock.EXTRA_MESSAGE, "run"), plan.extras[2])
        assertEquals(Extra(AlarmClock.EXTRA_DAYS, listOf(tomorrow)), plan.extras[3])
    }

    @Test
    fun `an alarm without a day carries no EXTRA_DAYS`() {
        val plan = launch(planFor(Intent.SetAlarm(hour = 7, minute = 0)))
        assertTrue(plan.extras.none { it.key == AlarmClock.EXTRA_DAYS })
    }

    @Test
    fun `settings panels open and never claim a toggle`() {
        assertEquals(
            Settings.ACTION_WIFI_SETTINGS,
            launch(planFor(Intent.OpenSettings(Intent.OpenSettings.Panel.WIFI))).action,
        )
        assertEquals(
            Settings.ACTION_BLUETOOTH_SETTINGS,
            launch(planFor(Intent.OpenSettings(Intent.OpenSettings.Panel.BLUETOOTH))).action,
        )
        assertEquals(
            Settings.ACTION_SETTINGS,
            launch(planFor(Intent.OpenSettings(Intent.OpenSettings.Panel.MAIN))).action,
        )
    }

    @Test
    fun `dial and message are reaches, because a target may be a name`() {
        val dial = planFor(Intent.Dial("mum")) as HandoffPlan.Reach
        assertEquals(ReachKind.DIAL, dial.kind)
        assertEquals("mum", dial.target)
        val message = planFor(Intent.ComposeMessage(to = "sam", body = "i am late")) as HandoffPlan.Reach
        assertEquals(ReachKind.MESSAGE, message.kind)
        assertEquals("sam", message.target)
        assertEquals("i am late", message.body)
        // "Text" alone has no recipient and still deserves a compose screen.
        assertNull((planFor(Intent.ComposeMessage(null, "hi")) as HandoffPlan.Reach).target)
    }

    @Test
    fun `a spoken number is dialable and a name is not`() {
        assertTrue(dialable("555 1234"))
        assertTrue(dialable("+44 20 7946 0958"))
        assertTrue(dialable("123"))
        assertFalse(dialable("mum"))
        assertFalse(dialable("sam at work"))
        assertFalse(dialable(""))
        // Only the URI-safe pieces of a spoken number survive.
        assertEquals("+442079460958", digits("+44 20 7946 0958"))
        assertEquals("5551234", digits("555 1234"))
    }

    @Test
    fun `navigation is geo without a mode and google navigation with one`() {
        assertEquals(
            "geo:0,0?q=the+airport",
            launch(planFor(Intent.Navigate("the airport"))).uri,
        )
        assertEquals(
            "google.navigation:q=home&mode=w",
            launch(planFor(Intent.Navigate("home", mode = "walk"))).uri,
        )
        assertEquals(
            "google.navigation:q=office&mode=d",
            launch(planFor(Intent.Navigate("office", mode = "drive"))).uri,
        )
    }

    @Test
    fun `a domain gets a scheme and a query goes to the search handoff`() {
        assertEquals("https://github.com", webUri("github.com"))
        assertEquals("https://github.com/maia/issues", webUri("https://github.com/maia/issues"))
        assertEquals(
            "https://duckduckgo.com/?q=espresso+machines",
            webUri("espresso machines"),
        )
        assertTrue(domainLike("duckduckgo.com"))
        assertFalse(domainLike("espresso machines"))
        assertFalse(domainLike("nodot"))
        // A numeric tail is a version or a typo, not a TLD.
        assertFalse(domainLike("example.123"))
    }

    @Test
    fun `open app is the curated table or an honest miss`() {
        assertEquals(
            listOf("com.spotify.music"),
            (planFor(Intent.OpenApp("spotify")) as HandoffPlan.App).packages,
        )
        // The same spoken name can cover several builds; candidates are
        // tried in order.
        assertTrue((planFor(Intent.OpenApp("camera")) as HandoffPlan.App).packages.size > 1)
        assertEquals(
            HandoffPlan.App(listOf("com.android.settings")),
            planFor(Intent.OpenApp("settings")),
        )
        assertNull(planFor(Intent.OpenApp("some app nobody tabled")))
        // Case and surrounding space do not change the answer.
        assertEquals(appPackages("spotify"), appPackages("  Spotify "))
    }

    @Test
    fun `media commands split into key events and volume directions`() {
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY, keyCodeFor(Intent.Media.Command.PLAY))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PAUSE, keyCodeFor(Intent.Media.Command.PAUSE))
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, keyCodeFor(Intent.Media.Command.NEXT))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PREVIOUS, keyCodeFor(Intent.Media.Command.PREVIOUS))
        assertEquals(AudioManager.ADJUST_RAISE, volumeFor(Intent.Media.Command.VOLUME_UP))
        assertEquals(AudioManager.ADJUST_LOWER, volumeFor(Intent.Media.Command.VOLUME_DOWN))
        assertEquals(AudioManager.ADJUST_MUTE, volumeFor(Intent.Media.Command.MUTE))
        assertNull(keyCodeFor(Intent.Media.Command.MUTE))
        assertNull(volumeFor(Intent.Media.Command.PLAY))
    }

    @Test
    fun `reads and conversation are never handoffs`() {
        assertNull(planFor(Intent.DeviceFact(Intent.DeviceFact.Kind.TIME)))
        assertNull(planFor(Intent.Calculate("2+2")))
        assertNull(planFor(Intent.Conversation("why is the sky blue")))
        assertNull(planFor(Intent.AgentStop()))
    }

    @Test
    fun `every spec has a plan and every plan has a spec`() {
        // The screen describes a handoff through specFor and the executor
        // fires it through planFor, and the two are written by hand. If one
        // ever covers an intent the other does not, the user is shown a card
        // for something that cannot run, or asked nothing about something
        // that does. One table asserts they agree on the whole union.
        val acts = listOf(
            Intent.SetTimer(60_000),
            Intent.SetAlarm(7, 30),
            Intent.OpenSettings(Intent.OpenSettings.Panel.WIFI),
            Intent.SetTorch(true),
            Intent.Dial("mum"),
            Intent.ComposeMessage("sam", "hi"),
            Intent.Navigate("the airport"),
            Intent.OpenWeb("github.com"),
            Intent.OpenApp("spotify"),
            Intent.Media(Intent.Media.Command.PAUSE),
        )
        val notActs = listOf<Intent>(
            Intent.DeviceFact(Intent.DeviceFact.Kind.TIME),
            Intent.Calculate("2+2"),
            Intent.Conversation("why"),
            Intent.AgentStop(),
        )
        acts.forEach { intent ->
            assertTrue("specFor missing for $intent", specFor(intent) != null)
            assertTrue("planFor missing for $intent", planFor(intent) != null)
        }
        notActs.forEach { intent ->
            assertNull("specFor should not cover $intent", specFor(intent))
            assertNull("planFor should not cover $intent", planFor(intent))
        }
    }
}
