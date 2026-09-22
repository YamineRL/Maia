package dev.maia.app.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Criterion J11: the table in M3 brief section 3.1, every row.
 *
 * The table is the whole of the warmth decision and it is six lines long, so the
 * test is six tests long too, written out one row at a time rather than as a
 * loop over a map. A loop would be the same data twice and would pass if both
 * copies were wrong together.
 *
 * The sixth row, `HideWhileRecording`, arrived after the assistant-role spike
 * ran on the Pixel. It is a row and a test of its own rather than a widening of
 * `HideBeforeRecording` because the two moments do opposite things to the
 * recorder.
 */
class WarmthPolicyTest {

    @Test
    fun `onReady loads the engine and does not reserve the recorder`() {
        // Named as criterion J11 names it, because this is the row the open
        // question U4 turned on and the one that costs 70 to 100 MB resident
        // for as long as the role is held.
        val plan = warmthFor(WarmMoment.RoleReady)
        assertTrue(plan.loadEngine)
        assertFalse(plan.reserveRecorder)
        assertEquals(RecorderMove.Leave, plan.recorder)
    }

    @Test
    fun `onPrepareShow loads the engine, no-op if loaded, and reserves the recorder`() {
        val plan = warmthFor(WarmMoment.PrepareShow)
        assertTrue(plan.loadEngine)
        assertTrue(plan.reserveRecorder)
    }

    @Test
    fun `onShow starts recording and does not reserve`() {
        val plan = warmthFor(WarmMoment.Show)
        assertEquals(RecorderMove.Start, plan.recorder)
        assertFalse(plan.reserveRecorder)
        // The engine was loaded two moments ago at the latest. Loading here
        // would be a multi-second load with the user already talking.
        assertFalse(plan.loadEngine)
    }

    @Test
    fun `onHide before recording releases the reservation`() {
        val plan = warmthFor(WarmMoment.HideBeforeRecording)
        assertEquals(RecorderMove.Release, plan.recorder)
        assertFalse(plan.reserveRecorder)
        assertFalse(plan.loadEngine)
    }

    @Test
    fun `onHide while recording stops the recorder`() {
        // G8, read on the phone: the platform hides the session on screen off
        // and leaves capture running. The row says the microphone closes at this
        // moment, and `RecorderMove.Stop` is the flow's own `Effect.StopCapture`
        // carrying it out, the same way `Start` is `Effect.StartCapture`.
        val plan = warmthFor(WarmMoment.HideWhileRecording)
        assertEquals(RecorderMove.Stop, plan.recorder)
        assertFalse(plan.reserveRecorder)
        // Nothing is warmed on the way out of a session that was already
        // recording: the engine is loaded by then, and this is the moment the
        // user stopped asking for anything.
        assertFalse(plan.loadEngine)
    }

    @Test
    fun `hiding while the microphone is open is a different moment from hiding in silence`() {
        // The two hide rows are chosen by state, not by the caller's guess, and
        // the recording states are exactly the states `FlowEvent.Hidden` stops
        // capture from.
        assertEquals(WarmMoment.HideWhileRecording, hideMoment(FlowState.Invoking(pressedAt = 0L)))
        assertEquals(WarmMoment.HideWhileRecording, hideMoment(FlowState.Listening(pressedAt = 0L)))
        assertEquals(WarmMoment.HideBeforeRecording, hideMoment(FlowState.Idle()))
        assertEquals(WarmMoment.HideBeforeRecording, hideMoment(FlowState.FirstRun()))
        // Understanding is the one that looks like recording and is not: the
        // sentence has ended, capture is already closed, and asking for a second
        // stop would misdescribe the moment.
        assertEquals(
            WarmMoment.HideBeforeRecording,
            hideMoment(FlowState.Understanding(transcript = "note to self", since = 0L)),
        )
    }

    @Test
    fun `the tile does both halves, as at M1`() {
        val plan = warmthFor(WarmMoment.TileListening)
        assertTrue(plan.loadEngine)
        assertTrue(plan.reserveRecorder)
        // The tile's row and the session's prepare row are the same answer, and
        // that is worth asserting rather than noticing: if they ever differ, the
        // tile has quietly stopped being EngineHolder.warm.
        assertEquals(warmthFor(WarmMoment.PrepareShow), plan)
    }

    @Test
    fun `only one moment is a reservation`() {
        // The property behind the derived boolean: reserving is one row, and
        // starting and releasing are not reserving, however the recorder column
        // of the table is read.
        val reserving = WarmMoment.entries.filter { warmthFor(it).reserveRecorder }
        assertEquals(listOf(WarmMoment.PrepareShow, WarmMoment.TileListening), reserving)
    }

    @Test
    fun `every moment has an answer`() {
        // The enum and the table cannot drift: a moment added without a row is
        // a compile error in `warmthFor`, and this asserts the other direction,
        // that no row answers by accident with a default.
        WarmMoment.entries.forEach { moment ->
            val plan = warmthFor(moment)
            assertEquals(
                "the engine is loaded at every moment before speech and at no moment after",
                moment == WarmMoment.RoleReady ||
                    moment == WarmMoment.PrepareShow ||
                    moment == WarmMoment.TileListening,
                plan.loadEngine,
            )
        }
    }
}
