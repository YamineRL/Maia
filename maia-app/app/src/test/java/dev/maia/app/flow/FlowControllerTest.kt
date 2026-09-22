package dev.maia.app.flow

import dev.maia.app.feel.Schedule
import dev.maia.app.flow.Fixtures.heard
import dev.maia.app.flow.Fixtures.personal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Records what it was asked to do, and can answer back.
 *
 * The point of the runner being an interface is here: everything the controller
 * promises (one machine, effects in order, host effects never reaching Android)
 * is checked against this on the JVM, and the one class that cannot be checked
 * is the one with the microphone in it.
 */
private class Recorder(val reply: (Effect, (FlowEvent) -> Unit) -> Unit = { _, _ -> }) : EffectRunner {
    val effects = mutableListOf<Effect>()
    val origins = mutableListOf<Origin?>()

    override suspend fun run(effect: Effect, origin: Origin?, send: (FlowEvent) -> Unit) {
        effects += effect
        origins += origin
        reply(effect, send)
    }
}

/** Counts the three things a process cannot do for itself. */
private class Window : FlowHost {
    var unlocks = 0
    var hides = 0
    val posted = mutableListOf<Int>()

    override fun requestUnlock() { unlocks++ }
    override fun hideSession() { hides++ }
    override fun postDraftWaiting(count: Int) { posted += count }
}

@OptIn(ExperimentalCoroutinesApi::class)
class FlowControllerTest {

    private val summary = lockedSummary(heard)

    /**
     * A locked screen showing the oldest of [queue] waiting drafts.
     *
     * The entries are given distinct `heardAt` values on purpose. The reducer
     * discards by (`heardAt`, draft) rather than by position, so two entries that
     * are equal in both fields are one draft as far as it is concerned and a
     * single cancel drops them together. Two drafts heard at different moments is
     * also the only thing a queue of two can actually be.
     */
    private fun locked(queue: Int = 1) = FlowSession(
        state = FlowState.Queued(heard, heardAt = 0, summary = summary),
        locked = true,
        queue = List(queue) { QueuedDraft(heard, heardAt = it.toLong(), summary = summary) },
        origin = Origin.Assistant,
    )

    @Test
    fun `an invocation reaches the runner as the reducer's effects, in order`() = runTest {
        val runner = Recorder()
        val controller = FlowController(runner, backgroundScope, { 1_000 })

        controller.send(FlowEvent.Invoke(Origin.Tile, locked = false))
        runCurrent()

        assertTrue(controller.state.value is FlowState.Invoking)
        assertEquals(listOf(Effect.Haptic(Schedule.invoke), Effect.StartCapture), runner.effects)
    }

    @Test
    fun `the origin travels with every effect, so D3 can suppress one haptic`() = runTest {
        val runner = Recorder()
        val controller = FlowController(runner, backgroundScope, { 0 })

        controller.send(FlowEvent.Invoke(Origin.Assistant, locked = false))
        runCurrent()

        // Not the suppression itself, which is Android and lives in
        // AndroidEffects. This is the part that has to be true for it to be
        // possible at all: the runner is told which door the pulse is for.
        assertEquals(listOf(Origin.Assistant, Origin.Assistant), runner.origins)
    }

    @Test
    fun `an event sent from inside an effect waits for the step that produced it`() = runTest {
        // The reentrancy the queue in `send` exists for. The haptic answers
        // immediately with a cancel, which must not interleave with the
        // StartCapture the same step asked for: capture is started and then
        // stopped, never stopped before it was started.
        val runner = Recorder { effect, send ->
            if (effect is Effect.Haptic && effect.pattern == Schedule.invoke) send(FlowEvent.Cancel)
        }
        val controller = FlowController(
            runner,
            backgroundScope + UnconfinedTestDispatcher(testScheduler),
            { 0 },
        )

        controller.send(FlowEvent.Invoke(Origin.Launcher, locked = false))
        runCurrent()

        assertEquals(
            listOf(
                Effect.Haptic(Schedule.invoke),
                Effect.StartCapture,
                Effect.StopCapture(discardAudio = true),
            ),
            runner.effects,
        )
        assertTrue(controller.state.value is FlowState.Idle)
    }

    @Test
    fun `the unlock request goes to the window, not to the runner`() = runTest {
        val runner = Recorder()
        val controller = FlowController(runner, backgroundScope, { 0 }, locked())
        val window = Window()
        controller.attach(window)

        controller.send(FlowEvent.UnlockRequested)
        runCurrent()

        assertEquals(1, window.unlocks)
        assertEquals(emptyList<Effect>(), runner.effects)
    }

    @Test
    fun `discarding a locked draft hides the window and updates the notification`() = runTest {
        val runner = Recorder()
        val controller = FlowController(runner, backgroundScope, { 0 }, locked(queue = 2))
        val window = Window()
        controller.attach(window)

        controller.send(FlowEvent.Cancel)
        runCurrent()

        assertEquals(1, window.hides)
        // Two drafts were waiting and the discard drops the one on screen, so
        // one is left and the notification is updated rather than removed.
        assertEquals(listOf(1), window.posted)
        assertEquals(emptyList<Effect>(), runner.effects)
    }

    @Test
    fun `with no window attached the effect is dropped and the flow carries on`() = runTest {
        val runner = Recorder()
        val controller = FlowController(runner, backgroundScope, { 0 }, locked())

        controller.send(FlowEvent.UnlockRequested)
        runCurrent()

        // Exactly what a cancelled unlock looks like: nothing comes back and the
        // locked screen is still there.
        assertTrue(controller.state.value is FlowState.Queued)
    }

    @Test
    fun `a late detach cannot steal the live window`() = runTest {
        val runner = Recorder()
        val controller = FlowController(runner, backgroundScope, { 0 }, locked())
        val first = Window()
        val second = Window()
        controller.attach(first)
        controller.attach(second)
        controller.detach(first)

        controller.send(FlowEvent.UnlockRequested)
        runCurrent()

        assertEquals(0, first.unlocks)
        assertEquals(1, second.unlocks)
    }

    @Test
    fun `the calendar written to is remembered as the flow passes through Committing`() = runTest {
        val runner = Recorder()
        val controller = FlowController(
            runner,
            backgroundScope,
            { 0 },
            FlowSession(state = FlowState.Preview(heard, target = personal)),
        )
        assertNull(controller.writtenTo.value)

        controller.send(FlowEvent.Hold(1f))
        runCurrent()
        controller.send(FlowEvent.WriteSucceeded(42))
        runCurrent()

        // Confirmed carries no target, so without this the confirmation line
        // would have nothing to name. It has to survive the state that knew it.
        assertTrue(controller.state.value is FlowState.Confirmed)
        assertEquals(personal.calendar.displayName, controller.writtenTo.value)
    }

    @Test
    fun `state and session never disagree`() = runTest {
        val runner = Recorder()
        val controller = FlowController(runner, backgroundScope, { 0 })

        controller.send(FlowEvent.Invoke(Origin.Shortcut, locked = true))
        runCurrent()

        assertEquals(controller.session.value.state, controller.state.value)
        assertTrue(controller.session.value.locked)
        assertEquals(Origin.Shortcut, controller.session.value.origin)
    }

    // Open item A (docs/M4-status.md, 2026-09-14): the pure parse pass and the
    // Reduce.kt backstop are both cleared, leaving an unexercised race in
    // send/perform/pending as one of two remaining hypotheses. Every prior test
    // here drives the controller from one thread with a test dispatcher; this
    // is the one that hammers `send` from real OS threads at once, the shape a
    // recogniser callback racing a finger on the glass would actually take. If
    // the mutex around `pending`/`draining` had a hole, this is what would find
    // it -- as a thrown exception, a stuck `draining` flag (this test times
    // out), or `state` and `session` disagreeing once the dust settles.
    @Test(timeout = 10_000)
    fun `concurrent sends from many real threads never corrupt state or hang`() {
        val runner = Recorder()
        val controller = FlowController(runner, CoroutineScope(Dispatchers.Default), { 0 })

        runBlocking {
            val jobs = List(8) {
                launch(Dispatchers.Default) {
                    repeat(200) {
                        controller.send(FlowEvent.Invoke(Origin.Tile, locked = false))
                        controller.send(FlowEvent.Cancel)
                    }
                }
            }
            jobs.joinAll()
        }

        // Every thread's last word is a Cancel, and Cancel is Idle from any
        // state, so whichever thread finishes last, the machine settles here.
        assertTrue(controller.state.value is FlowState.Idle)
        assertEquals(controller.session.value.state, controller.state.value)
    }
}
