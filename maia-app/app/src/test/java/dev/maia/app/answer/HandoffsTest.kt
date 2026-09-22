package dev.maia.app.answer

import dev.maia.nlu.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Section 6's table, pinned: timers and alarms preview, everything else goes
 * straight to system UI, and only the torch needs a runtime grant first.
 */
class HandoffsTest {

    @Test
    fun `a timer spec says the duration and asks for confirm`() {
        val spec = specFor(Intent.SetTimer(durationMs = 12 * 60_000L))!!
        assertEquals("Timer for 12 minutes", spec.label)
        assertEquals("Clock", spec.target)
        assertTrue(spec.needsConfirm)
        assertFalse(spec.needsCamera)
    }

    @Test
    fun `timer durations read the way they were said`() {
        assertEquals("Timer for 1 minute 30 seconds", specFor(Intent.SetTimer(90_000))!!.label)
        assertEquals("Timer for 1 hour", specFor(Intent.SetTimer(3_600_000))!!.label)
        assertEquals("Timer for 45 seconds", specFor(Intent.SetTimer(45_000))!!.label)
        assertEquals("Timer for 2 hours 5 minutes", specFor(Intent.SetTimer(7_500_000))!!.label)
        assertEquals("Timer for 12 minutes: tea", specFor(Intent.SetTimer(720_000, label = "tea"))!!.label)
    }

    @Test
    fun `an alarm spec says the time, the day, and asks for confirm`() {
        assertEquals("Alarm for 07:00", specFor(Intent.SetAlarm(7, 0))!!.label)
        assertEquals("Alarm for 07:00 tomorrow", specFor(Intent.SetAlarm(7, 0, tomorrow = true))!!.label)
        assertTrue(specFor(Intent.SetAlarm(6, 30))!!.needsConfirm)
    }

    @Test
    fun `settings opens a panel and never claims a toggle`() {
        assertEquals("Wi-Fi settings", specFor(Intent.OpenSettings(Intent.OpenSettings.Panel.WIFI))!!.label)
        assertEquals("Settings", specFor(Intent.OpenSettings(Intent.OpenSettings.Panel.MAIN))!!.target)
        assertFalse(specFor(Intent.OpenSettings(Intent.OpenSettings.Panel.BLUETOOTH))!!.needsConfirm)
    }

    @Test
    fun `the torch is the only handoff that needs a grant`() {
        val spec = specFor(Intent.SetTorch(true))!!
        assertEquals("Torch on", spec.label)
        assertEquals("Torch", spec.target)
        // Section 12: CAMERA is asked first, after the explanation. Confirm
        // is not the mechanism; the permission is.
        assertTrue(spec.needsCamera)
        assertFalse(spec.needsConfirm)
        assertEquals("Torch off", specFor(Intent.SetTorch(false))!!.label)
        assertEquals("Torch", specFor(Intent.SetTorch(null))!!.label)
    }

    @Test
    fun `the rest of section 6 fires at once`() {
        for ((intent, label, target) in listOf(
            Triple(Intent.Dial("mum"), "Call mum", "Phone"),
            Triple(Intent.ComposeMessage(to = "sam", body = null), "Text sam", "Messages"),
            Triple(Intent.ComposeMessage(to = null, body = "late"), "Send a message", "Messages"),
            Triple(Intent.Navigate("the airport"), "Directions to the airport", "Maps"),
            Triple(Intent.OpenWeb("github.com"), "Open github.com", "Browser"),
            Triple(Intent.OpenWeb("train times"), "Search for train times", "Browser"),
            Triple(Intent.OpenApp("spotify"), "Open spotify", "App"),
            Triple(Intent.Media(Intent.Media.Command.PAUSE), "Pause", "Media"),
            Triple(Intent.Media(Intent.Media.Command.NEXT), "Next track", "Media"),
        )) {
            val spec = specFor(intent)!!
            assertEquals("$intent", label, spec.label)
            assertEquals("$intent", target, spec.target)
            assertFalse("$intent", spec.needsConfirm)
            assertFalse("$intent", spec.needsCamera)
        }
    }

    @Test
    fun `a read is not a handoff`() {
        assertNull(specFor(Intent.Calculate("12*8")))
        assertNull(specFor(Intent.DeviceFact(Intent.DeviceFact.Kind.TIME)))
        assertNull(specFor(Intent.Conversation("why")))
    }
}
