package dev.maia.app.screens

import dev.maia.app.card.Mark
import dev.maia.app.flow.FaultReason
import dev.maia.app.flow.Fixtures
import dev.maia.app.flow.FlowSession
import dev.maia.app.flow.FlowState
import dev.maia.app.flow.QueuedDraft
import dev.maia.app.flow.lockedSummary
import dev.maia.app.flow.questionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which locked screen, and what is allowed on it.
 *
 * These are the assertions that make row 8 provable without a phone. The
 * screens themselves are Compose and are checked by eye in the previews; the
 * decision that picks one, and the rule about what may appear on it, are
 * ordinary functions and are checked here.
 */
class LockedSceneTest {

    private fun queued(draft: dev.maia.nlu.EventDraft) = FlowSession(
        state = FlowState.Queued(draft, heardAt = 0, summary = lockedSummary(draft)),
        locked = true,
    )

    /**
     * J3: an invocation that arrives locked shows a locked screen and nothing
     * else. Unlocked, the same value shows the ordinary flow, which is what
     * `null` means here.
     */
    @Test
    fun `the lock is what selects a locked screen`() {
        val session = queued(Fixtures.heard)
        assertTrue(lockedScene(session) is LockedScene.Kept)
        assertNull(lockedScene(session.copy(locked = false)))
    }

    /** J3, the other half: a question while locked is refused, not answered. */
    @Test
    fun `a queued question is the refusal screen, not the kept screen`() {
        val session = FlowSession(
            state = FlowState.Queued(null, heardAt = 0, summary = questionSummary("what is on tomorrow")),
            locked = true,
        )
        assertTrue(lockedScene(session) is LockedScene.ReadBack)
    }

    /**
     * J10. The refusal screen draws no part of the summary it is handed: the
     * composable takes no text from it at all, which is why this test can be
     * about the scene rather than about pixels. The summary is carried only so
     * the question is still in the flow after an unlock.
     *
     * Stated as a property of the type: [LockedScene.ReadBack] is the same
     * screen whatever is in it, on an empty day and a full one, because
     * nothing downstream reads it.
     */
    @Test
    fun `the refusal carries the question but the screen is one constant`() {
        val empty = questionSummary("what is on today")
        val full = questionSummary("what is on today")
        assertEquals(LockedScene.ReadBack(empty), LockedScene.ReadBack(full))
    }

    /** Section 4.2: the sixth capture, with the count of what is waiting and nothing else. */
    @Test
    fun `a full queue is its own screen and carries only the count`() {
        val waiting = List(5) { QueuedDraft(Fixtures.heard, heardAt = 0, summary = lockedSummary(Fixtures.heard)) }
        val session = FlowSession(
            state = FlowState.Fault(FaultReason.QueueFull, transcript = ""),
            locked = true,
            queue = waiting,
        )
        assertEquals(LockedScene.QueueFull(5), lockedScene(session))
    }

    /**
     * A locked capture failure is not a locked screen: the words came out of
     * the user's mouth a second ago and the screen says only that they were
     * not caught. So the ordinary fault screen stands, locked or not.
     */
    @Test
    fun `an ordinary fault is not turned into a locked screen`() {
        val session = FlowSession(
            state = FlowState.Fault(FaultReason.CaptureFailed("nothing heard"), transcript = ""),
            locked = true,
        )
        assertNull(lockedScene(session))
    }

    /**
     * The blockers the host measures win over everything, in the order a user
     * is blocked. Before the first unlock nothing else is answerable.
     */
    @Test
    fun `a measured block wins over the flow state`() {
        val session = queued(Fixtures.heard)
        assertEquals(LockedScene.BeforeFirstUnlock, lockedScene(session, LockedBlock.BeforeFirstUnlock))
        assertEquals(LockedScene.NoMicrophone, lockedScene(session, LockedBlock.MicrophoneDenied))
    }

    /** Unlocked, a blocker is not a locked screen either: the app can ask properly. */
    @Test
    fun `a measured block does nothing when the phone is unlocked`() {
        val session = queued(Fixtures.heard).copy(locked = false)
        assertNull(lockedScene(session, LockedBlock.MicrophoneDenied))
    }

    /** Section 2.3: a 70 MB download is not begun from a lock screen. */
    @Test
    fun `first run while locked is its own screen`() {
        val session = FlowSession(state = FlowState.FirstRun(), locked = true)
        assertEquals(LockedScene.FirstRun, lockedScene(session))
    }

    /**
     * `docs/M3-copy.md` section 9 item 2. The brackets are the one guessed cue
     * the locked screen keeps, and they appear only when Maia supplied the
     * day. A heard day is plain text, per brief section 4.1.
     */
    @Test
    fun `the when text is bracketed only when the day was guessed`() {
        val heard = lockedSummary(Fixtures.heard)
        val guessed = lockedSummary(Fixtures.dateless)
        assertFalse(whenGuessed(Fixtures.heard))
        assertTrue(whenGuessed(Fixtures.dateless))
        assertEquals(heard.whenText, lockedWhen(heard, guessed = false))
        assertEquals("⟨${guessed.whenText}⟩", lockedWhen(guessed, guessed = true))
    }

    /** One definition of the cue: the locked screen's bracket is the card's bracket. */
    @Test
    fun `the bracket is the card's bracket`() {
        val summary = lockedSummary(Fixtures.dateless)
        assertEquals(
            dev.maia.app.card.bracketed(summary.whenText, Mark.Guessed),
            lockedWhen(summary, guessed = true),
        )
    }

    /** An empty when text stays empty rather than becoming a pair of empty brackets. */
    @Test
    fun `nothing is bracketed when there is no when text`() {
        assertEquals("", lockedWhen(questionSummary("what is on today"), guessed = true))
    }

    /**
     * The spoken description for the KEPT AS block, echoing the card's own
     * phrasing so a guess sounds the same wherever it is met, and carrying no
     * punctuation that TalkBack would read out as punctuation.
     */
    @Test
    fun `the kept-as description says a guess in words, not in brackets`() {
        // As the resource formats it: "Kept as: %1$s, %2$s".
        val parsed = "Kept as: dinner with sam, Thursday 17 September at 20:00"
        assertEquals(parsed, keptAsDescription(parsed, guessed = false))
        assertEquals("$parsed, guessed by Maia", keptAsDescription(parsed, guessed = true))
        val guessed = keptAsDescription(parsed, guessed = true)
        assertFalse(guessed.contains('⟨'))
    }

    /**
     * Brief section 1.4, closed by `docs/M2-status.md` section 0.1: the card's
     * editors open platform dialogs, which a voice interaction session window
     * cannot host, so those states move to an Activity. Everything else stays
     * in whatever window is already up.
     */
    @Test
    fun `only the states that open a dialog need an Activity`() {
        assertTrue(needsActivityHost(FlowState.Preview(Fixtures.heard)))
        assertTrue(needsActivityHost(FlowState.NoCalendar(Fixtures.heard)))
        assertTrue(needsActivityHost(FlowState.Fault(FaultReason.NoDateHeard, "dinner with sam", Fixtures.dateless)))
        assertFalse(needsActivityHost(FlowState.Idle()))
        assertFalse(needsActivityHost(FlowState.Listening(pressedAt = 0)))
        assertFalse(needsActivityHost(FlowState.Queued(Fixtures.heard, 0, lockedSummary(Fixtures.heard))))
        assertFalse(needsActivityHost(FlowState.Fault(FaultReason.QueueFull, transcript = "")))
    }

    /**
     * M4 row 7: the no-folder screen launches the folder picker, whose answer
     * is an activity result, so it is drawn by an Activity and never by the
     * session window.
     */
    @Test
    fun `the no-folder screen needs an Activity, for the picker's result`() {
        val note = dev.maia.actions.notes.Note(
            body = dev.maia.nlu.Field("call the vet", dev.maia.nlu.Provenance.Heard, 1..3),
            at = java.time.ZonedDateTime.of(2026, 9, 13, 10, 0, 0, 0, Fixtures.zone),
        )
        assertTrue(needsActivityHost(FlowState.NoFolder(note)))
    }

    /**
     * J10 stated over every state the flow has: no state produces a locked
     * scene carrying anything but a [dev.maia.app.flow.LockedSummary] or a
     * count. Written as a `when` with no `else`, so a scene added later cannot
     * pass this test by being ignored.
     */
    @Test
    fun `no locked scene can carry a value a calendar read produced`() {
        Fixtures.everyState.forEach { state ->
            when (val scene = lockedScene(FlowSession(state = state, locked = true))) {
                null -> Unit
                is LockedScene.Kept -> assertEquals(lockedSummary(Fixtures.heard).javaClass, scene.summary.javaClass)
                is LockedScene.ReadBack -> assertEquals(lockedSummary(Fixtures.heard).javaClass, scene.summary.javaClass)
                is LockedScene.QueueFull -> assertTrue(scene.waiting >= 0)
                LockedScene.BeforeFirstUnlock, LockedScene.NoMicrophone, LockedScene.FirstRun -> Unit
            }
        }
    }
}
