package dev.maia.app.flow

import dev.maia.app.flow.Fixtures.thursdayEight
import dev.maia.nlu.Field
import dev.maia.nlu.Intent
import dev.maia.nlu.Provenance
import dev.maia.nlu.agent.ProjectRef
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M9's routing table, section 3 as a test: every assistant intent leaves the
 * sentence flow as one `Effect.Assist` carrying the command kind the grammar
 * chose, and the four M8 agent sentences still leave as `Effect.RunAgent`.
 * The two surfaces never share a door, which is the never-spoken rule's first
 * half: an agent command can only ever reach the run surface from here.
 *
 * Unlocked only, and only the parse is exercised: what the answer surface does
 * with a command is `AnswerMachineTest`'s, what it does with a locked one is
 * `LockedFlowTest`'s plus the cases at the bottom of this file.
 */
class AssistRoutingTest {

    private val range = thursdayEight..thursdayEight.plusHours(2)

    /** What `Parsed(intent)` should produce unlocked: idle with the transcript, one Assist. */
    private fun assertAssist(transcript: String, intent: Intent, command: AssistantCommand) {
        val step = reduce(FlowState.Understanding(transcript, since = 0), FlowEvent.Parsed(intent), now = 500)
        assertEquals(Step(FlowState.Idle(transcript), listOf(Effect.Assist(command))), step)
    }

    // ------------------------------------------------------------- reads

    @Test
    fun `reads arrive as Read commands`() {
        listOf(
            Intent.Agenda(range),
            Intent.Availability(range),
            Intent.Calculate("15/100*200"),
            Intent.DeviceFact(Intent.DeviceFact.Kind.TIME),
            Intent.DeviceFact(Intent.DeviceFact.Kind.DATE),
            Intent.DeviceFact(Intent.DeviceFact.Kind.BATTERY),
        ).forEach { intent ->
            assertAssist("read it", intent, AssistantCommand.Read(intent, "read it"))
        }
    }

    // -------------------------------------------------------------- acts

    @Test
    fun `acts arrive as Act commands`() {
        listOf(
            Intent.SetTimer(durationMs = Duration.ofMinutes(12).toMillis()),
            Intent.SetAlarm(hour = 7, minute = 30),
            Intent.OpenSettings(Intent.OpenSettings.Panel.WIFI),
            Intent.OpenSettings(Intent.OpenSettings.Panel.BLUETOOTH),
            Intent.OpenSettings(Intent.OpenSettings.Panel.MAIN),
            Intent.SetTorch(on = true),
            Intent.SetTorch(on = false),
            Intent.SetTorch(on = null),
            Intent.Dial("mum"),
            Intent.ComposeMessage(to = "sam", body = "i am late"),
            Intent.ComposeMessage(to = null, body = null),
            Intent.Navigate(destination = "the airport", mode = "drive"),
            Intent.Navigate(destination = "home", mode = null),
            Intent.OpenWeb("espresso machines"),
            Intent.OpenApp("spotify"),
            Intent.Media(Intent.Media.Command.PLAY),
            Intent.Media(Intent.Media.Command.PAUSE),
            Intent.Media(Intent.Media.Command.NEXT),
            Intent.Media(Intent.Media.Command.PREVIOUS),
            Intent.Media(Intent.Media.Command.VOLUME_UP),
            Intent.Media(Intent.Media.Command.VOLUME_DOWN),
            Intent.Media(Intent.Media.Command.MUTE),
        ).forEach { intent ->
            assertAssist("do it", intent, AssistantCommand.Act(intent, "do it"))
        }
    }

    // --------------------------------------------------------------- ask

    @Test
    fun `a conversation arrives as an Ask carrying the verbatim text`() {
        val intent = Intent.Conversation("why is the sky blue")
        assertAssist(
            "why is the sky blue",
            intent,
            AssistantCommand.Ask("why is the sky blue", "why is the sky blue"),
        )
    }

    // ------------------------------------------------------- the M8 road

    @Test
    fun `agent sentences still take the run surface, never the answer surface`() {
        listOf(
            Triple(
                "project seven, reply with pong",
                Intent.AgentInstruction(
                    ProjectRef.Numbered(7),
                    Field("reply with pong", Provenance.Heard),
                ),
                AgentCommand.Instruct(
                    ProjectRef.Numbered(7),
                    "reply with pong",
                    "project seven, reply with pong",
                ),
            ),
            Triple(
                "project seven",
                Intent.AgentFocus(ProjectRef.Numbered(7)),
                AgentCommand.Focus(ProjectRef.Numbered(7), "project seven"),
            ),
            Triple("stop", Intent.AgentStop(), AgentCommand.Stop),
            Triple("what is it doing", Intent.AgentStatus(), AgentCommand.Status),
        ).forEach { (transcript, intent, command) ->
            val step = reduce(
                FlowState.Understanding(transcript, since = 0),
                FlowEvent.Parsed(intent),
                now = 500,
            )
            assertEquals(Step(FlowState.Idle(transcript), listOf(Effect.RunAgent(command))), step)
        }
    }

    // -------------------------------------------------------- the locked fork

    @Test
    fun `a locked act queues the command and fires it on unlock`() {
        val intent = Intent.SetTimer(durationMs = Duration.ofMinutes(5).toMillis())
        val run = lockedCapture(intent, transcript = "set a timer for five minutes")
        val kept = run.session.queue.single().draft
        assertTrue(kept is Pending.Command)
        assertEquals(AssistantCommand.Act(intent, "set a timer for five minutes"), (kept as Pending.Command).command)

        run.send(FlowEvent.Unlocked, at = 9_000)
        assertEquals(
            listOf(Effect.Assist(AssistantCommand.Act(intent, "set a timer for five minutes"))),
            run.all<Effect.Assist>(),
        )
    }

    @Test
    fun `a locked question queues the ask and fires it on unlock`() {
        val intent = Intent.Conversation("why is the sky blue")
        val run = lockedCapture(intent, transcript = "why is the sky blue")
        val kept = run.session.queue.single().draft
        assertTrue(kept is Pending.Command)
        assertEquals(
            AssistantCommand.Ask("why is the sky blue", "why is the sky blue"),
            (kept as Pending.Command).command,
        )

        run.send(FlowEvent.Unlocked, at = 9_000)
        assertEquals(
            listOf(Effect.Assist(AssistantCommand.Ask("why is the sky blue", "why is the sky blue"))),
            run.all<Effect.Assist>(),
        )
    }

    @Test
    fun `a queued command waits behind an older draft in the order they were kept`() {
        // A draft, then a timer: the queue is oldest first and the command
        // answers only once the draft ahead of it has had its screen.
        var run = lockedCapture(Intent.CreateEvent(Fixtures.heard))
        run.send(FlowEvent.Hidden, at = 5_000)
        run = lockedCapture(
            Intent.SetTimer(durationMs = Duration.ofMinutes(5).toMillis()),
            transcript = "set a timer for five minutes",
            at = 10_000,
            session = run.session,
        )
        assertEquals(2, run.session.queue.size)
        assertTrue(run.session.queue[0].draft is Pending.Event)
        assertTrue(run.session.queue[1].draft is Pending.Command)

        run.send(FlowEvent.Unlocked, at = 20_000)
        // The draft opens for review; the command has not fired yet.
        assertTrue(run.all<Effect.Assist>().isEmpty())
        assertEquals(FlowState.Preview(Fixtures.heard), run.state)

        // Discarding the card moves to the next waiting thing, which is the
        // command: it fires without any further screen.
        run.send(FlowEvent.Cancel, at = 21_000)
        assertEquals(
            listOf(
                Effect.Assist(
                    AssistantCommand.Act(
                        Intent.SetTimer(durationMs = Duration.ofMinutes(5).toMillis()),
                        "set a timer for five minutes",
                    ),
                ),
            ),
            run.all<Effect.Assist>(),
        )
        assertEquals(FlowState.Idle("set a timer for five minutes"), run.state)
    }

    @Test
    fun `a full queue refuses a command exactly as it refuses a draft`() {
        // Invoke refuses before the microphone opens, so this state is
        // unreachable through the door; settle's guard is driven directly,
        // the same way LockedFlowTest drives the full-queue draft case.
        val full = (1..QUEUE_CAP).map {
            QueuedDraft(namedDraft("kept $it"), it * 1_000L, lockedSummary(namedDraft("kept $it")))
        }
        val session = FlowSession(
            state = FlowState.Understanding("what is the time", since = 60_000),
            locked = true,
            queue = full,
        )
        val run = Drive(session, now = 61_000)
            .send(
                FlowEvent.Parsed(
                    Intent.DeviceFact(Intent.DeviceFact.Kind.TIME),
                ),
            )
        assertEquals(QUEUE_CAP, run.session.queue.size)
        val fault = run.state as FlowState.Fault
        assertEquals(FaultReason.QueueFull, fault.reason)
        assertTrue(run.all<Effect.Assist>().isEmpty())
    }

    // ------------------------------------------------- the assistant window

    @Test
    fun `an unlocked assistant run makes way for the surface that draws the reply`() {
        listOf(Intent.Calculate("10*8"), Intent.AgentStatus()).forEach { intent ->
            val run = lockedCapture(intent, locked = false)
            assertEquals(listOf(Effect.ShowSurface), run.all<Effect.ShowSurface>())
            assertEquals(emptyList<Effect>(), run.all<Effect.HideSession>())
        }
    }

    @Test
    fun `a launcher run has no window to give up`() {
        val run = lockedCapture(Intent.Calculate("10*8"), locked = false, origin = Origin.Launcher)
        assertEquals(emptyList<Effect>(), run.all<Effect.ShowSurface>())
    }
}
