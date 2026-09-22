package dev.maia.app.screens

import dev.maia.app.answer.AnswerFault
import dev.maia.app.answer.AnswerSource
import dev.maia.app.answer.AnswerState
import dev.maia.app.answer.AnswerStatus
import dev.maia.app.answer.HandoffSpec
import dev.maia.app.answer.PermNeeded
import dev.maia.audio.speech.VoiceStore
import dev.maia.orb.ApertureState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The answer screen's decisions, M9 PRD section 10, pinned without Compose.
 * There is no Robolectric in this repo and no androidTest, so a decision
 * left in the composable is a decision nothing checks.
 */
class AnswerCopyTest {

    @Test
    fun `every status has a label and they are all different`() {
        val labels = AnswerStatus.entries.map(AnswerCopy::status)
        assertEquals(AnswerStatus.entries.size, labels.toSet().size)
        assertTrue(labels.all { it.isNotBlank() })
    }

    @Test
    fun `the source row is the three honest values and nothing else`() {
        assertEquals(
            "ON THIS PHONE",
            AnswerCopy.source(AnswerState(status = AnswerStatus.Answered, source = AnswerSource.Phone)),
        )
        assertEquals(
            "ON THIS PHONE",
            AnswerCopy.source(AnswerState(status = AnswerStatus.Answered, source = AnswerSource.Calendar)),
        )
        assertEquals(
            "YOUR DEVBOX",
            AnswerCopy.source(AnswerState(status = AnswerStatus.Answered, source = AnswerSource.Devbox)),
        )
        assertEquals(
            "UNAVAILABLE",
            AnswerCopy.source(AnswerState(status = AnswerStatus.Failed, fault = AnswerFault.Busy)),
        )
        assertNull("nothing to disclose while the ask is still in flight",
            AnswerCopy.source(AnswerState(status = AnswerStatus.Working)))
    }

    @Test
    fun `every fault names a different fix`() {
        val faults = listOf(
            AnswerFault.NotSetUp,
            AnswerFault.Unreachable,
            AnswerFault.Busy,
            AnswerFault.Unusable,
            AnswerFault.NoTarget,
            AnswerFault.Permission(PermNeeded.Calendar),
            AnswerFault.Permission(PermNeeded.Contacts),
            AnswerFault.Permission(PermNeeded.Camera),
        )
        val titles = faults.map { AnswerCopy.fault(it).title }
        assertEquals("each fault gets its own first line", faults.size, titles.toSet().size)
        faults.forEach { fault ->
            val copy = AnswerCopy.fault(fault)
            assertTrue("$fault has a title", copy.title.isNotBlank())
            assertTrue("$fault has an explanation", copy.body.isNotBlank())
        }
    }

    @Test
    fun `the permission faults name the missing grant`() {
        assertTrue(AnswerCopy.fault(AnswerFault.Permission(PermNeeded.Calendar)).title.contains("Calendar"))
        assertTrue(AnswerCopy.fault(AnswerFault.Permission(PermNeeded.Contacts)).title.contains("Contacts"))
        assertTrue(AnswerCopy.fault(AnswerFault.Permission(PermNeeded.Camera)).title.contains("Camera"))
        // The torch explanation says what the camera permission is not:
        // section 3.2's promise, kept where the user reads it.
        assertTrue(AnswerCopy.fault(AnswerFault.Permission(PermNeeded.Camera)).body.contains("no image"))
    }

    @Test
    fun `the orb is section 10's four poses`() {
        assertEquals(ApertureState.Thinking, AnswerState(status = AnswerStatus.Working).aperture)
        assertEquals(ApertureState.Thinking, AnswerState(status = AnswerStatus.Previewing).aperture)
        assertEquals(ApertureState.Dormant, AnswerState(status = AnswerStatus.Answered).aperture)
        assertEquals(ApertureState.Dormant, AnswerState(status = AnswerStatus.HandedOff).aperture)
        assertEquals(ApertureState.Dormant, AnswerState(status = AnswerStatus.Idle).aperture)
        assertEquals(ApertureState.Fault, AnswerState(status = AnswerStatus.Failed).aperture)
    }

    @Test
    fun `speech earns the Speaking pose over a quiet answer`() {
        assertEquals(
            ApertureState.Speaking,
            AnswerState(status = AnswerStatus.Answered, spokenText = "A short answer.").aperture,
        )
        // A fault beats the voice flag: a failed state should never hold
        // spokenText, but if it did the pose would still be the honest one.
        assertEquals(
            ApertureState.Fault,
            AnswerState(status = AnswerStatus.Failed, spokenText = "leftover").aperture,
        )
    }

    @Test
    fun `the handoff card names where it goes`() {
        val spec = HandoffSpec(label = "Timer for 12 minutes", target = "Clock", needsConfirm = true)
        assertEquals("Opens Clock", AnswerCopy.opensTarget(spec))
    }

    @Test
    fun `the voice body names the model the store actually fetches`() {
        assertTrue(
            "the offer names ${VoiceStore.VOICE}, the voice VoiceStore downloads",
            AnswerCopy.VOICE_BODY.contains(VoiceStore.VOICE),
        )
        assertTrue("the size is shown before fetching", AnswerCopy.VOICE_BODY.contains("64 MB"))
        assertTrue("the licence is pointed at, not summarised", AnswerCopy.VOICE_BODY.contains("licence"))
    }

    @Test
    fun `the download line counts files and bytes`() {
        assertEquals(
            "file 2 of 13 · tokens.txt · 32.0 / 64.0 MB",
            AnswerCopy.voiceLine(2, 13, "tokens.txt", 32L * 1024 * 1024, 64L * 1024 * 1024, 0.0),
        )
    }

    @Test
    fun `the answer copy carries no em dash`() {
        val emDash = '\u2014'
        AnswerCopy::class.java.declaredFields
            .filter { it.type == String::class.java }
            .forEach { field ->
                assertEquals(
                    "${field.name} contains an em dash",
                    -1,
                    (field.get(null) as String).indexOf(emDash),
                )
            }
        // And the built sentences.
        AnswerStatus.entries.forEach { assertTrue(!AnswerCopy.status(it).contains(emDash)) }
    }
}
