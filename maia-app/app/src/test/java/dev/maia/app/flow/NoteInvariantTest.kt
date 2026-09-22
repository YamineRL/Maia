package dev.maia.app.flow

import dev.maia.actions.notes.FakeNotes
import dev.maia.actions.notes.NoteRef
import dev.maia.app.flow.Fixtures.SENTENCE
import dev.maia.app.flow.Fixtures.heard
import dev.maia.app.flow.Fixtures.personal
import dev.maia.nlu.Field
import dev.maia.nlu.Intent
import dev.maia.nlu.Provenance
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.ZonedDateTime

/**
 * M4 criteria J8 and J16 by search: no note is written, no notes folder is
 * asked about and nothing is said while locked, over every event sequence up to
 * length eight. The same shape and the same three bounds as M3's
 * `LockedInvariantTest`, which stays as it is; this one widens the alphabet to
 * the note events and the queue to [Pending], which is interface I4's rerun.
 */
class NoteInvariantTest {

    private val at: ZonedDateTime = ZonedDateTime.of(2026, 9, 13, 10, 0, 0, 0, Fixtures.zone)
    private val ref = NoteRef("2026-09-13.md", null, createdFile = false)

    /** One representative per event shape, with a note and an event so the queue can hold both. */
    private val alphabet: List<FlowEvent> = listOf(
        FlowEvent.Invoke(Origin.Assistant, locked = true),
        FlowEvent.Invoke(Origin.Launcher, locked = false),
        // M4 row 9: the note door's hint widens the alphabet.
        FlowEvent.Invoke(Origin.Shortcut, locked = true, family = CaptureFamily.Note),
        FlowEvent.Invoke(Origin.Shortcut, locked = false, family = CaptureFamily.Note),
        FlowEvent.Unlocked,
        FlowEvent.Hidden,
        FlowEvent.UnlockRequested,
        FlowEvent.Cancel,
        FlowEvent.CaptureStarted,
        FlowEvent.FinalHeard(SENTENCE),
        FlowEvent.Parsed(Intent.CaptureNote(Field("call the vet", Provenance.Heard), transcript = SENTENCE), at),
        FlowEvent.Parsed(Intent.CreateEvent(heard), at),
        FlowEvent.Tick,
        FlowEvent.TargetLoaded(personal),
        FlowEvent.NotesFolderLoaded(FakeNotes.defaultFolder),
        FlowEvent.NotesFolderLoaded(null),
        FlowEvent.Hold(1f),
        FlowEvent.NoteWritten(ref),
        FlowEvent.NoteWriteFailed("revoked", folderGone = true),
        // M4 row 8: a plain write fault and its Try again.
        FlowEvent.NoteWriteFailed("disk full"),
        FlowEvent.RetryNote,
    )

    private val depth = 8

    @Test
    fun `no road reaches a note write, a folder read or a voice while locked`() {
        var frontier = setOf(FlowSession())
        var lockedSteps = 0
        var notesKept = 0
        var unlockedWrites = 0

        for (step in 1..depth) {
            val now = step * 1_000L
            val next = mutableSetOf<FlowSession>()
            for (session in frontier) {
                for (event in alphabet) {
                    val result = reduce(session, event, now)
                    if (session.locked) {
                        lockedSteps++
                        val hostSaysOpen = event == FlowEvent.Unlocked || (event is FlowEvent.Invoke && !event.locked)
                        if (!hostSaysOpen && !result.session.locked) fail("the lock fell on $event, from $session")
                    }
                    if (session.locked && event != FlowEvent.Unlocked) {
                        result.effects.forEach { effect ->
                            when (effect) {
                                // J8.
                                is Effect.WriteNote -> fail("note write while locked: $session on $event")
                                is Effect.WriteEvent -> fail("event write while locked: $session on $event")
                                // Which folder is held is a read too, and waits for the unlock.
                                Effect.LoadNotesFolder -> fail("folder read while locked: $session on $event")
                                Effect.LoadTarget -> fail("calendar read while locked: $session on $event")
                                // J16.
                                is Effect.Speak -> fail("speech while locked: $session on $event")
                                // M4 row 8: the note's "Noted." is speech too.
                                Effect.SpeakNote -> fail("note confirmation spoken while locked: $session on $event")
                                else -> Unit
                            }
                        }
                        val reached = result.session
                        val committable = reached.state is FlowState.NotePreview || reached.state is FlowState.Writing ||
                            reached.state is FlowState.NoFolder || reached.state is FlowState.Preview ||
                            reached.state is FlowState.Committing
                        if (reached.locked && committable) fail("a note or event card while locked: $session on $event")
                        if (reached.queue.any { it.draft is Pending.Note }) notesKept++
                    } else if (!session.locked) {
                        unlockedWrites += result.effects.count { it is Effect.WriteNote }
                    }
                    next += result.session
                }
            }
            frontier = next
            assertTrue("the frontier exploded at depth $step: ${frontier.size}", frontier.size < 100_000)
        }

        // A search that reached nothing proves nothing, so say what it reached.
        assertTrue("the search never locked the phone", lockedSteps > 1_000)
        assertTrue("the search never kept a note", notesKept > 0)
        assertTrue("the search never wrote a note even unlocked", unlockedWrites > 0)
    }
}
