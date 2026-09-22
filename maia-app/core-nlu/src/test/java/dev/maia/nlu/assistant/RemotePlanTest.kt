package dev.maia.nlu.assistant

import dev.maia.nlu.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone-side half of M9 PRD section 8.4: a decoded devbox reply becomes
 * an [Intent] only through the allowlist, and everything else is rejected.
 */
class RemotePlanTest {

    private fun act(reply: Map<String, Any?>): Intent =
        (RemotePlan.validate(reply) as RemotePlan.Act).intent

    // ------------------------------------------------------------- answers

    @Test
    fun `a well formed answer passes through`() {
        val plan = RemotePlan.validate(mapOf("type" to "answer", "text" to "The sky is blue."))
        assertEquals(RemotePlan.Answer("The sky is blue."), plan)
    }

    @Test
    fun `an answer with missing blank or oversized text is rejected`() {
        assertEquals(RemotePlan.Rejected, RemotePlan.validate(mapOf("type" to "answer")))
        assertEquals(RemotePlan.Rejected, RemotePlan.validate(mapOf("type" to "answer", "text" to "  ")))
        assertEquals(
            RemotePlan.Rejected,
            RemotePlan.validate(mapOf("type" to "answer", "text" to "x".repeat(2_001))),
        )
        // The schema is strict: a key nobody asked for rejects the reply.
        assertEquals(
            RemotePlan.Rejected,
            RemotePlan.validate(
                mapOf("type" to "answer", "text" to "fine", "surprise" to "extra"),
            ),
        )
    }

    @Test
    fun `anything that is not answer or action is rejected`() {
        assertEquals(RemotePlan.Rejected, RemotePlan.validate(emptyMap()))
        assertEquals(RemotePlan.Rejected, RemotePlan.validate(mapOf("type" to "unavailable")))
        assertEquals(RemotePlan.Rejected, RemotePlan.validate(mapOf("type" to "shell")))
        assertEquals(RemotePlan.Rejected, RemotePlan.validate(mapOf("action" to "timer")))
    }

    // ------------------------------------------------------------- actions

    @Test
    fun `a timer maps seconds to the intent's milliseconds`() {
        val intent = act(mapOf("type" to "action", "action" to "timer", "duration_seconds" to 720))
        assertEquals(Intent.SetTimer(720_000), intent)

        val labelled = act(
            mapOf(
                "type" to "action", "action" to "timer",
                "duration_seconds" to 300.0, "label" to "pasta",
            ),
        ) as Intent.SetTimer
        assertEquals(300_000L, labelled.durationMs)
        assertEquals("pasta", labelled.label)
    }

    @Test
    fun `a timer with a bad duration is rejected`() {
        for (seconds in listOf(0, -5, "ten", null, 90.5)) {
            val reply = mapOf<String, Any?>(
                "type" to "action", "action" to "timer", "duration_seconds" to seconds,
            )
            assertEquals("$seconds should reject", RemotePlan.Rejected, RemotePlan.validate(reply))
        }
        assertEquals(
            RemotePlan.Rejected,
            RemotePlan.validate(mapOf("type" to "action", "action" to "timer")),
        )
    }

    @Test
    fun `an alarm maps hour minute and label`() {
        val intent = act(
            mapOf("type" to "action", "action" to "alarm", "hour" to 7, "minute" to 30),
        )
        assertEquals(Intent.SetAlarm(7, 30), intent)

        val labelled = act(
            mapOf(
                "type" to "action", "action" to "alarm",
                "hour" to 19, "minute" to 0, "label" to "gym",
            ),
        ) as Intent.SetAlarm
        assertEquals("gym", labelled.label)
    }

    @Test
    fun `an alarm out of range is rejected`() {
        for (hour in listOf(24, -1, "seven")) {
            val reply = mapOf<String, Any?>(
                "type" to "action", "action" to "alarm", "hour" to hour, "minute" to 0,
            )
            assertEquals("hour=$hour should reject", RemotePlan.Rejected, RemotePlan.validate(reply))
        }
        val reply = mapOf<String, Any?>(
            "type" to "action", "action" to "alarm", "hour" to 7, "minute" to 60,
        )
        assertEquals(RemotePlan.Rejected, RemotePlan.validate(reply))
    }

    @Test
    fun `a calculate action must evaluate before it maps`() {
        assertEquals(
            Intent.Calculate("12*8"),
            act(mapOf("type" to "action", "action" to "calculate", "expression" to "12*8")),
        )
        for (expr in listOf("hello", "1/0", "", "1+", "x".repeat(65))) {
            val reply = mapOf<String, Any?>(
                "type" to "action", "action" to "calculate", "expression" to expr,
            )
            assertEquals("[$expr] should reject", RemotePlan.Rejected, RemotePlan.validate(reply))
        }
    }

    @Test
    fun `settings maps the allowlisted panels`() {
        assertEquals(
            Intent.OpenSettings(Intent.OpenSettings.Panel.WIFI),
            act(mapOf("type" to "action", "action" to "open_settings", "panel" to "wifi")),
        )
        assertEquals(
            Intent.OpenSettings(Intent.OpenSettings.Panel.BLUETOOTH),
            act(mapOf("type" to "action", "action" to "open_settings", "panel" to "BLUETOOTH")),
        )
        val reply = mapOf<String, Any?>(
            "type" to "action", "action" to "open_settings", "panel" to "developer",
        )
        assertEquals(RemotePlan.Rejected, RemotePlan.validate(reply))
    }

    @Test
    fun `handoffs map their target fields`() {
        assertEquals(
            Intent.Dial("555 1234"),
            act(mapOf("type" to "action", "action" to "dial", "number" to "555 1234")),
        )
        assertEquals(
            Intent.ComposeMessage("sam", "running late"),
            act(
                mapOf(
                    "type" to "action", "action" to "message",
                    "to" to "sam", "body" to "running late",
                ),
            ),
        )
        // Body is optional; the recipient is not.
        assertEquals(
            Intent.ComposeMessage("sam", null),
            act(mapOf("type" to "action", "action" to "message", "to" to "sam")),
        )
        assertEquals(
            RemotePlan.Rejected,
            RemotePlan.validate(
                mapOf("type" to "action", "action" to "message", "body" to "hi"),
            ),
        )
        assertEquals(
            Intent.Navigate("the airport"),
            act(mapOf("type" to "action", "action" to "navigate", "destination" to "the airport")),
        )
        assertEquals(
            Intent.Navigate("the park", "walk"),
            act(
                mapOf(
                    "type" to "action", "action" to "navigate",
                    "destination" to "the park", "mode" to "walk",
                ),
            ),
        )
        assertEquals(
            Intent.OpenWeb("github.com"),
            act(mapOf("type" to "action", "action" to "open_web", "target" to "github.com")),
        )
        assertEquals(
            Intent.OpenApp("spotify"),
            act(mapOf("type" to "action", "action" to "open_app", "name" to "spotify")),
        )
    }

    @Test
    fun `media maps only the closed command set`() {
        assertEquals(
            Intent.Media(Intent.Media.Command.PAUSE),
            act(mapOf("type" to "action", "action" to "media", "command" to "pause")),
        )
        assertEquals(
            Intent.Media(Intent.Media.Command.VOLUME_UP),
            act(mapOf("type" to "action", "action" to "media", "command" to "volume_up")),
        )
        val reply = mapOf<String, Any?>(
            "type" to "action", "action" to "media", "command" to "eject",
        )
        assertEquals(RemotePlan.Rejected, RemotePlan.validate(reply))
    }

    @Test
    fun `unknown kinds bad types and extra fields are rejected`() {
        assertEquals(
            RemotePlan.Rejected,
            RemotePlan.validate(
                mapOf("type" to "action", "action" to "shell", "cmd" to "rm -rf /"),
            ),
        )
        // A field of the wrong type.
        assertEquals(
            RemotePlan.Rejected,
            RemotePlan.validate(
                mapOf("type" to "action", "action" to "open_app", "name" to 42),
            ),
        )
        // A field nobody declared.
        assertEquals(
            RemotePlan.Rejected,
            RemotePlan.validate(
                mapOf(
                    "type" to "action", "action" to "timer",
                    "duration_seconds" to 60, "exec" to "reboot",
                ),
            ),
        )
        // An oversized string.
        assertEquals(
            RemotePlan.Rejected,
            RemotePlan.validate(
                mapOf("type" to "action", "action" to "dial", "number" to "5".repeat(65)),
            ),
        )
        // A mode outside the closed set.
        assertEquals(
            RemotePlan.Rejected,
            RemotePlan.validate(
                mapOf(
                    "type" to "action", "action" to "navigate",
                    "destination" to "work", "mode" to "teleport",
                ),
            ),
        )
    }

    @Test
    fun `the rejected cases never produce an intent`() {
        val replies = listOf(
            mapOf("type" to "action", "action" to "alarm", "hour" to 7),
            mapOf("type" to "action", "action" to "dial"),
            mapOf("type" to "action", "action" to "navigate", "destination" to null),
        )
        for (reply in replies) {
            val plan = RemotePlan.validate(reply)
            assertTrue("$reply should reject", plan is RemotePlan.Rejected)
            assertNull((plan as? RemotePlan.Act)?.intent)
        }
    }
}
