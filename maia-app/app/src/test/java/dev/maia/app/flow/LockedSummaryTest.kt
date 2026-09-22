package dev.maia.app.flow

import dev.maia.actions.CalendarTarget
import dev.maia.app.flow.Fixtures.dateless
import dev.maia.app.flow.Fixtures.heard
import dev.maia.app.flow.Fixtures.personal
import dev.maia.app.flow.Fixtures.readOnly
import dev.maia.app.flow.Fixtures.zone
import dev.maia.nlu.Edit
import dev.maia.nlu.EventDraft
import dev.maia.nlu.Field
import dev.maia.nlu.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.ZonedDateTime

/**
 * Criterion J10: what the lock screen is allowed to know.
 *
 * The claim is not that this function is careful. It is that a calendar cannot
 * arrive here at all: the type has three string fields and the builder has one
 * parameter, so there is no argument to pass a target as and no field to put
 * one in. Both halves are asserted, because both are load bearing and either
 * could be widened by an edit that looked harmless.
 */
class LockedSummaryTest {

    private val allDay = heard.with(Edit.AllDay(true))

    private val unparsed = heard.copy(
        title = Field(Fixtures.SENTENCE, Provenance.Heard, 0..6),
        transcript = Fixtures.SENTENCE,
    )

    /** A title that reads like a calendar, so an echo cannot be mistaken for a leak. */
    private val awkward = heard.copy(
        title = Field("clinic", Provenance.Heard, 0..0),
        transcript = "clinic thursday at eight p m",
    )

    private val zurichMidnight = EventDraft(
        title = Field("flight", Provenance.Heard, 0..0),
        start = Field(ZonedDateTime.of(2026, 12, 31, 0, 5, 0, 0, zone), Provenance.Inferred),
        duration = Field(Duration.ofHours(2), Provenance.Inferred),
        transcript = "flight new year",
    )

    private val cards = listOf(heard, dateless, allDay, unparsed, awkward, zurichMidnight)

    /** Everything a provider read could put on a screen, from M2's own fixtures. */
    private val secrets: List<String> = listOf(personal, readOnly)
        .flatMap { listOf(it.calendar.displayName, it.calendar.accountName) }

    @Test
    fun `a summary carries only what the draft carries`() {
        for (draft in cards) {
            val summary = lockedSummary(draft)
            assertEquals(draft.title.value, summary.title)
            assertEquals(draft.transcript, summary.transcript)
            assertTrue("the when must not be empty for a draft: $draft", summary.whenText.isNotBlank())
        }
    }

    @Test
    fun `no summary of any card contains a calendar or an account`() {
        for (draft in cards) {
            val summary = lockedSummary(draft)
            val shown = listOf(summary.title, summary.whenText, summary.transcript)
            for (secret in secrets) {
                for (text in shown) {
                    assertFalse(
                        "the lock screen would show \"$secret\" in \"$text\"",
                        text.lowercase().contains(secret.lowercase()),
                    )
                }
            }
        }
    }

    @Test
    fun `the summary is a pure function of the draft, and takes nothing else`() {
        // The builder's signature, from the class file rather than from a
        // reading of the source: one parameter, and it is the draft.
        val method = Class.forName("dev.maia.app.flow.LockedSummaryKt")
            .declaredMethods.single { it.name == "lockedSummary" }
        assertEquals(listOf(EventDraft::class.java), method.parameterTypes.toList())
        assertFalse(
            "no overload may take a calendar",
            Class.forName("dev.maia.app.flow.LockedSummaryKt").declaredMethods
                .any { m -> m.parameterTypes.any { it == CalendarTarget::class.java } },
        )

        // And the type it returns has nowhere to put one.
        // Instance fields only: the Compose compiler adds a static `$stable` to
        // every class it sees, and it holds an int, not a calendar.
        val fields = LockedSummary::class.java.declaredFields
            .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
        assertEquals(listOf("title", "whenText", "transcript"), fields.map { it.name })
        assertEquals(listOf(String::class.java), fields.map { it.type }.distinct())

        // Same draft, same answer, every time.
        assertEquals(lockedSummary(heard), lockedSummary(heard.copy()))
    }

    @Test
    fun `a question shows the words back and claims no when`() {
        val summary = questionSummary("what do i have tomorrow")
        assertEquals("what do i have tomorrow", summary.title)
        assertEquals("what do i have tomorrow", summary.transcript)
        assertEquals("", summary.whenText)
    }

    @Test
    fun `an all-day draft says so rather than inventing an hour`() {
        assertTrue(lockedSummary(allDay).whenText.endsWith("all day"))
        assertTrue(lockedSummary(heard).whenText.endsWith("20:00"))
    }

    @Test
    fun `the said-at note appears only once the day has turned`() {
        val evening = ZonedDateTime.of(2026, 9, 16, 23, 50, 0, 0, zone).toInstant().toEpochMilli()
        val tenPast = ZonedDateTime.of(2026, 9, 17, 0, 10, 0, 0, zone).toInstant().toEpochMilli()
        val fiveMinutesLater = evening + 5 * 60_000

        assertNull(saidAtNote(evening, fiveMinutesLater, zone))
        assertEquals("said at 23:50", saidAtNote(evening, tenPast, zone))
        // A week later is still the same one sentence: the note says when, not
        // how long ago, because "6 days ago" is a different and worse claim.
        assertEquals("said at 23:50", saidAtNote(evening, evening + 7 * 86_400_000L, zone))
    }
}
