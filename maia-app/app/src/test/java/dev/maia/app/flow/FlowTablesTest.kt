package dev.maia.app.flow

import dev.maia.app.flow.Fixtures.dateless
import dev.maia.app.flow.Fixtures.everyState
import dev.maia.app.flow.Fixtures.heard
import dev.maia.app.flow.Fixtures.personal
import dev.maia.app.flow.Fixtures.readOnly
import dev.maia.app.flow.Fixtures.thursdayEight
import dev.maia.nlu.Edit
import dev.maia.nlu.Intent
import dev.maia.orb.ApertureState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * The rules that hold for every state at once: what each state ignores, which
 * pose it shows, and a random walk that tries to reach a write or a delete by a
 * road the brief does not allow.
 */
class FlowTablesTest {

    private val everyEvent: List<FlowEvent> = listOf(
        FlowEvent.DownloadRequested,
        FlowEvent.DownloadProgress("f", 1, 2),
        FlowEvent.DownloadFailed("x"),
        FlowEvent.ModelsReady,
        FlowEvent.Press,
        FlowEvent.CaptureStarted,
        FlowEvent.PartialHeard("dinner"),
        FlowEvent.FinalHeard("dinner with sam"),
        FlowEvent.CaptureFailed("x"),
        FlowEvent.Cancel,
        FlowEvent.Parsed(Intent.CreateEvent(heard)),
        FlowEvent.Tick,
        FlowEvent.TargetLoaded(personal),
        FlowEvent.TargetUnreadable("x"),
        FlowEvent.Edited(Edit.Title("supper")),
        FlowEvent.DatePicked(thursdayEight),
        FlowEvent.Hold(0.5f),
        FlowEvent.WriteSucceeded(42),
        FlowEvent.WriteFailed(denied = false, "x"),
        FlowEvent.Undo,
        FlowEvent.Deleted(42, existed = true),
        FlowEvent.DeleteFailed(42),
        FlowEvent.SpeechStarted,
        FlowEvent.SpeechEnded,
    )

    /** Which events each state listens for, by event class. Everything else must be ignored. */
    private val applies: Map<Class<out FlowState>, Set<Class<out FlowEvent>>> = mapOf(
        FlowState.FirstRun::class.java to setOf(
            FlowEvent.DownloadRequested::class.java, FlowEvent.DownloadProgress::class.java,
            FlowEvent.DownloadFailed::class.java, FlowEvent.ModelsReady::class.java,
        ),
        FlowState.Idle::class.java to setOf(FlowEvent.Press::class.java),
        FlowState.Invoking::class.java to setOf(
            FlowEvent.CaptureStarted::class.java, FlowEvent.PartialHeard::class.java, FlowEvent.FinalHeard::class.java,
            FlowEvent.CaptureFailed::class.java, FlowEvent.Cancel::class.java,
        ),
        FlowState.Listening::class.java to setOf(
            FlowEvent.PartialHeard::class.java, FlowEvent.FinalHeard::class.java,
            FlowEvent.CaptureFailed::class.java, FlowEvent.Cancel::class.java,
        ),
        FlowState.Understanding::class.java to setOf(
            FlowEvent.Parsed::class.java, FlowEvent.Cancel::class.java, FlowEvent.Tick::class.java,
        ),
        FlowState.Preview::class.java to setOf(
            FlowEvent.TargetLoaded::class.java, FlowEvent.TargetUnreadable::class.java, FlowEvent.Edited::class.java,
            FlowEvent.Hold::class.java, FlowEvent.Cancel::class.java,
        ),
        FlowState.NoCalendar::class.java to setOf(FlowEvent.TargetLoaded::class.java, FlowEvent.Cancel::class.java),
        FlowState.Committing::class.java to setOf(FlowEvent.WriteSucceeded::class.java, FlowEvent.WriteFailed::class.java),
        FlowState.Confirmed::class.java to setOf(
            FlowEvent.Undo::class.java, FlowEvent.SpeechStarted::class.java, FlowEvent.Cancel::class.java,
            FlowEvent.Press::class.java,
        ),
        FlowState.Fault::class.java to setOf(
            FlowEvent.DatePicked::class.java, FlowEvent.Press::class.java, FlowEvent.Cancel::class.java,
        ),
    )

    @Test
    fun `every event a state does not take leaves it unchanged with no effects`() {
        everyState.forEach { state ->
            val taken = applies.getValue(state.javaClass)
            everyEvent.forEach { event ->
                val step = reduce(state, event, now = 100)
                if (event.javaClass in taken) {
                    assertTrue("$state should act on $event", step != Step(state))
                } else {
                    assertEquals("$state should ignore $event", Step(state), step)
                }
            }
        }
    }

    @Test
    fun `the table covers every state`() {
        // Ten is the handoff's nine stages plus Idle. The `when` in `aperture` is
        // exhaustive, so a new state is a compile error there before it is a gap here.
        assertEquals(10, everyState.map { it.javaClass }.toSet().size)
        assertEquals(applies.keys, everyState.map { it.javaClass }.toSet())
    }

    @Test
    fun `the pose is a function of the screen state alone`() {
        val expected = listOf(
            ApertureState.Dormant, ApertureState.Dormant, ApertureState.Listening, ApertureState.Listening,
            ApertureState.Thinking, ApertureState.Dormant, ApertureState.Dormant, ApertureState.Thinking,
            ApertureState.Dormant, ApertureState.Fault,
        )
        assertEquals(expected, everyState.map { it.aperture })
        val confirmed = everyState.filterIsInstance<FlowState.Confirmed>().single()
        assertEquals(ApertureState.Speaking, confirmed.copy(speaking = true).aperture)
    }

    /**
     * A seeded random walk over every event, many times. Whatever the order, a
     * write only ever follows a completed hold on a writable target, and a delete
     * only ever names an id a write returned, inside its window.
     */
    @Test
    fun `no road reaches a write or a delete the rules forbid`() {
        val random = Random(20260913)
        val alphabet = everyEvent + listOf(
            FlowEvent.Hold(1f),
            FlowEvent.Hold(0f),
            FlowEvent.TargetLoaded(null),
            FlowEvent.TargetLoaded(readOnly),
            FlowEvent.Parsed(Intent.CreateEvent(dateless)),
            FlowEvent.Parsed(Intent.Unparsed(dateless)),
            FlowEvent.WriteSucceeded(77),
            FlowEvent.Deleted(77, existed = false),
        )
        var writes = 0
        repeat(2_000) {
            var state: FlowState = FlowState.Idle()
            var now = 0L
            val returned = mutableMapOf<Long, Long>()
            repeat(60) {
                now += random.nextLong(0, 3_000)
                val event = alphabet[random.nextInt(alphabet.size)]
                val before = state
                val step = reduce(state, event, now)
                step.effects.forEach { effect ->
                    when (effect) {
                        is Effect.WriteEvent -> {
                            writes++
                            if (before !is FlowState.Preview || event != FlowEvent.Hold(1f)) fail("write from $before on $event")
                            val target = (before as FlowState.Preview).target
                            if (target == null || !target.calendar.writable) fail("write without a writable calendar")
                        }
                        is Effect.DeleteEvent -> {
                            val confirmed = before as? FlowState.Confirmed ?: return fail("delete from $before")
                            if (effect.eventId != confirmed.eventId) fail("delete of a foreign id")
                            val deadline = returned[confirmed.eventId] ?: return fail("delete of an id no write returned")
                            if (now >= deadline) fail("delete after the window")
                        }
                        else -> Unit
                    }
                }
                if (before is FlowState.Committing && event is FlowEvent.WriteSucceeded) {
                    returned[event.eventId] = now + UNDO_WINDOW_MS
                }
                state = step.state
            }
        }
        assertTrue("the walk never reached a write, so it proved nothing", writes > 0)
    }

    @Test
    fun `nothing in the flow package imports android`() {
        val dir = listOf("src/main/java/dev/maia/app/flow", "app/src/main/java/dev/maia/app/flow")
            .map(::File).firstOrNull { it.isDirectory } ?: return fail("flow sources not found from ${File(".").absolutePath}")
        val offenders = dir.listFiles { f -> f.extension == "kt" }.orEmpty()
            .filter { f -> f.readLines().any { it.startsWith("import android.") || it.startsWith("import androidx.") } }
        assertEquals(emptyList<File>(), offenders)
    }
}
