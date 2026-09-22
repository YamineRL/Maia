package dev.maia.actions

import dev.maia.nlu.EventDraft
import dev.maia.nlu.Field
import dev.maia.nlu.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The half of the M4-R9 2d handoff that can be proved without a phone: the
 * extras, not the `Intent` they end up in. [ProtonHandoff.extrasFor] delegates
 * the time arithmetic to [EventWriter.timesFor], already pinned by
 * [EventWriterTest]; what is pinned here is that the handoff carries the
 * right fields at all, and the right package.
 *
 * No android import in this file, same rule as [EventWriterTest].
 */
class ProtonHandoffTest {

    private val zurich = ZoneId.of("Europe/Zurich") // UTC+2 in September

    private fun draft(
        start: ZonedDateTime,
        duration: Duration,
        allDay: Boolean = false,
        title: String = "dentist",
        location: String? = null,
    ) = EventDraft(
        title = Field(title, Provenance.Heard),
        start = Field(start, Provenance.Heard),
        duration = Field(duration, Provenance.Heard),
        allDay = allDay,
        location = location?.let { Field(it, Provenance.Corrected) },
    )

    private fun millis(text: String): Long = Instant.parse(text).toEpochMilli()

    @Test
    fun `the package is pinned to Proton Calendar exactly`() {
        assertEquals("me.proton.android.calendar", ProtonHandoff.PACKAGE)
    }

    @Test
    fun `a timed draft carries its own instants and title`() {
        val start = ZonedDateTime.of(LocalDateTime.of(2026, 9, 13, 12, 0), zurich)
        val extras = ProtonHandoff.extrasFor(draft(start, Duration.ofHours(1)))

        assertEquals("dentist", extras.title)
        assertEquals(millis("2026-09-13T10:00:00Z"), extras.beginMillis)
        assertEquals(millis("2026-09-13T11:00:00Z"), extras.endMillis)
        assertEquals(false, extras.allDay)
        assertNull(extras.location)
    }

    @Test
    fun `an all day draft matches EventWriter's UTC midnight boundary exactly`() {
        // The one place this could quietly diverge from the provider write is
        // if the handoff recomputed the all-day boundary itself instead of
        // going through EventWriter.timesFor. It does not: same input, same
        // millis as EventWriterTest's own all-day case.
        val start = ZonedDateTime.of(LocalDateTime.of(2026, 9, 13, 0, 0), zurich)
        val extras = ProtonHandoff.extrasFor(draft(start, Duration.ofDays(1), allDay = true))

        assertEquals(millis("2026-09-13T00:00:00Z"), extras.beginMillis)
        assertEquals(millis("2026-09-14T00:00:00Z"), extras.endMillis)
        assertEquals(true, extras.allDay)
    }

    @Test
    fun `a location on the draft reaches the extras, and its absence stays null`() {
        val start = ZonedDateTime.of(LocalDateTime.of(2026, 9, 13, 12, 0), zurich)
        val withLocation = ProtonHandoff.extrasFor(
            draft(start, Duration.ofHours(1), location = "Rue du Rhone 1"),
        )
        assertEquals("Rue du Rhone 1", withLocation.location)

        val withoutLocation = ProtonHandoff.extrasFor(draft(start, Duration.ofHours(1)))
        assertNull(withoutLocation.location)
    }
}
