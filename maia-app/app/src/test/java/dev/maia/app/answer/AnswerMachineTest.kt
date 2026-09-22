package dev.maia.app.answer

import dev.maia.app.feel.Schedule
import dev.maia.app.flow.AssistantCommand
import dev.maia.nlu.Intent
import dev.maia.transport.Role
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The answer machine as a pure function, driven by moving a long.
 *
 * The session is injected so history and expiry are facts of the test rather
 * than of a clock, which is the same trick as the run machine's `now`.
 */
class AnswerMachineTest {

    private val zone: ZoneId = ZoneId.of("Europe/Paris")
    private fun at(hour: Int, minute: Int = 0): ZonedDateTime =
        ZonedDateTime.of(2026, 9, 23, hour, minute, 0, 0, zone)
    private fun day(): ClosedRange<ZonedDateTime> = at(0)..at(23, 59)

    private fun machine(session: ConversationSession = ConversationSession(id = "test-session")) =
        AnswerMachine(session)

    private fun askCommand(prompt: String = "why is the sky blue") =
        AnswerEvent.Command(AssistantCommand.Ask(prompt, prompt))

    private fun working(m: AnswerMachine, event: AnswerEvent = askCommand(), now: Long = 1_000) =
        m.reduceAnswer(AnswerState(), event, now)

    // ---------------------------------------------------------------- reads

    @Test
    fun `a read command works and the read is run`() {
        val intent = Intent.DeviceFact(Intent.DeviceFact.Kind.TIME)
        val step = working(machine(), AnswerEvent.Command(AssistantCommand.Read(intent, "what time is it")))
        assertEquals(AnswerStatus.Working, step.state.status)
        assertEquals("what time is it", step.state.spoken)
        assertEquals(listOf(AnswerEffect.RunRead(intent)), step.effects)
    }

    @Test
    fun `a read done answers, sources it, and speaks the short form`() {
        val m = machine()
        val working = working(m, AnswerEvent.Command(AssistantCommand.Read(
            Intent.DeviceFact(Intent.DeviceFact.Kind.TIME), "what time is it")))
        val step = m.reduceAnswer(working.state, AnswerEvent.ReadDone("It's 14:32", AnswerSource.Phone), 2_000)
        assertEquals(AnswerStatus.Answered, step.state.status)
        assertEquals(AnswerSource.Phone, step.state.source)
        assertEquals("It's 14:32", step.state.answer)
        assertEquals("It's 14:32", step.effects.filterIsInstance<AnswerEffect.Speak>().single().text)
    }

    @Test
    fun `a read done keeps the exchange in the session`() {
        val m = machine()
        var s = working(m, AnswerEvent.Command(AssistantCommand.Read(
            Intent.DeviceFact(Intent.DeviceFact.Kind.TIME), "what time is it"))).state
        s = m.reduceAnswer(s, AnswerEvent.ReadDone("It's 14:32", AnswerSource.Phone), 2_000).state
        assertEquals(listOf(Exchange("what time is it", "It's 14:32")), m.conversation.exchanges)
        // And the next remote ask carries it as wire turns.
        val ask = m.reduceAnswer(s, askCommand(), 3_000)
        val history = ask.effects.filterIsInstance<AnswerEffect.AskRemote>().single().history
        assertEquals(
            listOf(Role.USER, Role.ASSISTANT),
            history.map { it.role },
        )
    }

    @Test
    fun `a read failure keeps the transcript and is felt`() {
        val m = machine()
        val working = working(m, AnswerEvent.Command(AssistantCommand.Read(
            Intent.DeviceFact(Intent.DeviceFact.Kind.BATTERY), "how much battery")))
        val step = m.reduceAnswer(working.state, AnswerEvent.ReadFailed(AnswerFault.Permission(PermNeeded.Calendar)), 2_000)
        assertEquals(AnswerStatus.Failed, step.state.status)
        assertEquals(AnswerFault.Permission(PermNeeded.Calendar), step.state.fault)
        assertEquals("how much battery", step.state.spoken)
        assertEquals(Schedule.fault, step.effects.filterIsInstance<AnswerEffect.Feel>().single().pattern)
    }

    // ---------------------------------------------------- the preview rule

    @Test
    fun `a timer previews, confirms, fires, and reports the handoff`() {
        val m = machine()
        val timer = Intent.SetTimer(durationMs = 12 * 60_000L)
        val preview = working(m, AnswerEvent.Command(AssistantCommand.Act(timer, "set a timer for twelve minutes")))
        assertEquals(AnswerStatus.Previewing, preview.state.status)
        assertEquals("Timer for 12 minutes", preview.state.handoff!!.label)
        assertTrue(preview.state.handoff!!.needsConfirm)
        // Nothing has fired: the preview is a promise, not the act.
        assertTrue(preview.effects.none { it is AnswerEffect.RunHandoff })

        val confirmed = m.reduceAnswer(preview.state, AnswerEvent.Confirmed, 2_000)
        assertEquals(AnswerStatus.Working, confirmed.state.status)
        assertEquals(listOf(AnswerEffect.RunHandoff(timer)), confirmed.effects)

        val opened = m.reduceAnswer(confirmed.state, AnswerEvent.HandoffOpened, 3_000)
        assertEquals(AnswerStatus.HandedOff, opened.state.status)
    }

    @Test
    fun `an alarm previews too`() {
        val step = working(machine(), AnswerEvent.Command(AssistantCommand.Act(
            Intent.SetAlarm(hour = 7, minute = 0, tomorrow = true), "wake me at seven tomorrow")))
        assertEquals(AnswerStatus.Previewing, step.state.status)
        assertEquals("Alarm for 07:00 tomorrow", step.state.handoff!!.label)
        assertEquals("Clock", step.state.handoff!!.target)
    }

    @Test
    fun `a dial fires at once with no preview`() {
        val dial = Intent.Dial("mum")
        val step = working(machine(), AnswerEvent.Command(AssistantCommand.Act(dial, "call mum")))
        assertEquals(AnswerStatus.Working, step.state.status)
        assertEquals(listOf(AnswerEffect.RunHandoff(dial)), step.effects)
        assertEquals("Phone", step.state.handoff!!.target)
    }

    @Test
    fun `no target keeps the populated action and fails honestly`() {
        val m = machine()
        val dial = Intent.Dial("mum")
        val working = working(m, AnswerEvent.Command(AssistantCommand.Act(dial, "call mum")))
        val step = m.reduceAnswer(working.state, AnswerEvent.HandoffNoTarget, 2_000)
        assertEquals(AnswerStatus.Failed, step.state.status)
        assertEquals(AnswerFault.NoTarget, step.state.fault)
        // Section 11: the action stays populated; only the claim changes.
        assertEquals("Call mum", step.state.handoff!!.label)
    }

    // -------------------------------------------------------------- remote

    @Test
    fun `an ask works, carries the queue note, and sends only the kept history`() {
        val step = working(machine())
        assertEquals(AnswerStatus.Working, step.state.status)
        assertEquals(QUEUED_NOTE, step.state.note)
        val ask = step.effects.filterIsInstance<AnswerEffect.AskRemote>().single()
        assertEquals("why is the sky blue", ask.prompt)
        assertTrue(ask.history.isEmpty())
    }

    @Test
    fun `a remote answer is shown whole and said short`() {
        val m = machine()
        val working = working(m)
        val step = m.reduceAnswer(
            working.state,
            AnswerEvent.RemoteAnswer("Because of scattering. It is called Rayleigh scattering.", "Because of scattering."),
            2_000,
        )
        assertEquals(AnswerStatus.Answered, step.state.status)
        assertEquals(AnswerSource.Devbox, step.state.source)
        assertEquals("Because of scattering. It is called Rayleigh scattering.", step.state.answer)
        assertEquals("Because of scattering.", step.effects.filterIsInstance<AnswerEffect.Speak>().single().text)
        // The exchange the user saw is the history the next ask carries.
        assertEquals(
            listOf(Exchange("why is the sky blue", "Because of scattering. It is called Rayleigh scattering.")),
            m.conversation.exchanges,
        )
    }

    @Test
    fun `a remote answer without a short form is spoken whole`() {
        val m = machine()
        val step = m.reduceAnswer(
            working(m).state,
            AnswerEvent.RemoteAnswer("The whole answer."),
            2_000,
        )
        assertEquals("The whole answer.", step.effects.filterIsInstance<AnswerEffect.Speak>().single().text)
    }

    @Test
    fun `a local answer is labelled the phone's model and joins the history`() {
        val m = machine()
        val text = "First sentence. Second sentence. A third the voice never reaches."
        val step = m.reduceAnswer(working(m).state, AnswerEvent.LocalAnswer(text), 2_000)
        assertEquals(AnswerStatus.Answered, step.state.status)
        assertEquals(AnswerSource.PhoneModel, step.state.source)
        assertEquals(text, step.state.answer)
        // No sender short form exists for a local reply: the machine caps it.
        assertEquals(
            "First sentence. Second sentence.",
            step.effects.filterIsInstance<AnswerEffect.Speak>().single().text,
        )
        assertEquals(listOf(Exchange("why is the sky blue", text)), m.conversation.exchanges)
    }

    @Test
    fun `answering locally keeps working and only changes the note`() {
        val m = machine()
        val working = working(m)
        assertEquals(QUEUED_NOTE, working.state.note)
        val step = m.reduceAnswer(working.state, AnswerEvent.AnsweringLocally, 2_000)
        assertEquals(AnswerStatus.Working, step.state.status)
        assertEquals(LOCAL_NOTE, step.state.note)
    }

    @Test
    fun `answering locally on a quiet state changes nothing`() {
        val m = machine()
        val step = m.reduceAnswer(AnswerState(), AnswerEvent.AnsweringLocally, 2_000)
        assertEquals(AnswerState(), step.state)
    }

    @Test
    fun `a late local answer lands on nothing`() {
        val m = machine()
        val step = m.reduceAnswer(AnswerState(), AnswerEvent.LocalAnswer("too late"), 9_000)
        assertEquals(AnswerState(), step.state)
        assertTrue(m.conversation.exchanges.isEmpty())
    }

    @Test
    fun `a remote action previews a timer but fires a dial`() {
        val m = machine()
        val timer = m.reduceAnswer(
            working(m).state,
            AnswerEvent.RemoteAction(Intent.SetTimer(60_000)),
            2_000,
        )
        // The remote router cannot bypass the preview (section 5).
        assertEquals(AnswerStatus.Previewing, timer.state.status)
        assertTrue(timer.state.handoff!!.needsConfirm)
        assertEquals(AnswerSource.Devbox, timer.state.source)
        assertTrue(timer.effects.none { it is AnswerEffect.RunHandoff })

        val dial = Intent.Dial("555 1234")
        val fired = m.reduceAnswer(
            working(m).state,
            AnswerEvent.RemoteAction(dial),
            3_000,
        )
        assertEquals(listOf(AnswerEffect.RunHandoff(dial)), fired.effects)
    }

    @Test
    fun `a local action fires at once and is credited to the phone`() {
        val weather = Intent.OpenWeb("weather in Paris")
        val step = machine().reduceAnswer(working(machine()).state, AnswerEvent.LocalAction(weather), 2_000)
        assertEquals(listOf(AnswerEffect.RunHandoff(weather)), step.effects)
        assertEquals(AnswerSource.Phone, step.state.source)
        assertEquals("Weather in Paris", step.state.handoff!!.label)
    }

    @Test
    fun `a remote action for a read is read and not handed off`() {
        // Agenda is in the section 8.3 capability list, so the gateway may
        // propose it. It is a read; handing it to Android would be a lie.
        val agenda = Intent.Agenda(day())
        val step = machine().reduceAnswer(
            AnswerState(status = AnswerStatus.Working, spoken = "what do I have tomorrow"),
            AnswerEvent.RemoteAction(agenda),
            2_000,
        )
        assertEquals(listOf(AnswerEffect.RunRead(agenda)), step.effects)
        assertTrue(step.effects.none { it is AnswerEffect.RunHandoff })
    }

    @Test
    fun `busy unreachable and not-setup keep the question and its fault`() {
        for ((event, fault) in listOf(
            AnswerEvent.RemoteBusy to AnswerFault.Busy,
            AnswerEvent.RemoteUnreachable to AnswerFault.Unreachable,
            AnswerEvent.RemoteNotSetUp to AnswerFault.NotSetUp,
            AnswerEvent.RemoteUnusable to AnswerFault.Unusable,
        )) {
            val m = machine()
            val step = m.reduceAnswer(working(m).state, event, 2_000)
            assertEquals("$event", AnswerStatus.Failed, step.state.status)
            assertEquals("$event", fault, step.state.fault)
            assertEquals("$event", "why is the sky blue", step.state.spoken)
        }
    }

    @Test
    fun `retry after busy sends the same question again`() {
        val m = machine()
        val busy = m.reduceAnswer(working(m).state, AnswerEvent.RemoteBusy, 2_000)
        val retry = m.reduceAnswer(busy.state, AnswerEvent.Retry, 3_000)
        assertEquals(AnswerStatus.Working, retry.state.status)
        val ask = retry.effects.filterIsInstance<AnswerEffect.AskRemote>().single()
        assertEquals("why is the sky blue", ask.prompt)
    }

    @Test
    fun `retry after a failed read runs the read again`() {
        val m = machine()
        val intent = Intent.DeviceFact(Intent.DeviceFact.Kind.BATTERY)
        val working = working(m, AnswerEvent.Command(AssistantCommand.Read(intent, "how much battery")))
        val failed = m.reduceAnswer(working.state, AnswerEvent.ReadFailed(AnswerFault.Unreachable), 2_000)
        val retry = m.reduceAnswer(failed.state, AnswerEvent.Retry, 3_000)
        assertEquals(listOf(AnswerEffect.RunRead(intent)), retry.effects)
    }

    // --------------------------------------------------------- stop and clear

    @Test
    fun `stop while waiting cancels the remote ask and the voice`() {
        val m = machine()
        val step = m.reduceAnswer(working(m).state, AnswerEvent.StopAsked, 2_000)
        assertEquals(AnswerStatus.Idle, step.state.status)
        assertTrue(step.effects.contains(AnswerEffect.CancelRemote))
        assertTrue(step.effects.contains(AnswerEffect.StopSpeaking))
        // The transcript is kept: stopping output does not erase the question.
        assertEquals("why is the sky blue", step.state.spoken)
    }

    @Test
    fun `stop on a preview discards it without firing`() {
        val m = machine()
        val preview = working(m, AnswerEvent.Command(AssistantCommand.Act(
            Intent.SetTimer(60_000), "set a timer")))
        val step = m.reduceAnswer(preview.state, AnswerEvent.StopAsked, 2_000)
        assertEquals(AnswerStatus.Idle, step.state.status)
        assertNull(step.state.handoff)
        assertTrue(step.effects.none { it is AnswerEffect.RunHandoff })
    }

    @Test
    fun `stop on an answer releases the voice and keeps the answer`() {
        val m = machine()
        val answered = m.reduceAnswer(
            working(m).state,
            AnswerEvent.RemoteAnswer("Shown answer."),
            2_000,
        )
        val step = m.reduceAnswer(answered.state, AnswerEvent.StopAsked, 3_000)
        assertEquals(AnswerStatus.Idle, step.state.status)
        assertEquals(listOf(AnswerEffect.StopSpeaking), step.effects)
        assertEquals("Shown answer.", step.state.answer)
    }

    @Test
    fun `clear wipes the session, the rows, and the transcript`() {
        val m = machine()
        var s = working(m).state
        s = m.reduceAnswer(s, AnswerEvent.RemoteAnswer("An answer."), 2_000).state
        val step = m.reduceAnswer(s, AnswerEvent.ClearAsked, 3_000)
        assertEquals(AnswerState(), step.state)
        assertTrue(step.effects.contains(AnswerEffect.ClearSession))
        assertTrue(m.conversation.exchanges.isEmpty())
        // And a later ask carries no history.
        val ask = m.reduceAnswer(step.state, askCommand(), 4_000)
        assertTrue(ask.effects.filterIsInstance<AnswerEffect.AskRemote>().single().history.isEmpty())
    }

    // -------------------------------------------------------- history rows

    @Test
    fun `finished exchanges collapse into earlier, capped at six`() {
        val m = machine()
        var s = AnswerState()
        var now = 1_000L
        repeat(7) { i ->
            s = m.reduceAnswer(s, askCommand("question $i"), now).state
            now += 1_000
            s = m.reduceAnswer(s, AnswerEvent.RemoteAnswer("answer $i"), now).state
            now += 1_000
        }
        // The current exchange is the seventh; the six before it are the rows,
        // and the first of all has already dropped off the end.
        s = m.reduceAnswer(s, askCommand("question 7"), now).state
        assertEquals(ConversationSession.MAX_PAIRS, s.earlier.size)
        assertEquals(Exchange("question 1", "answer 1"), s.earlier.first())
        assertEquals(Exchange("question 6", "answer 6"), s.earlier.last())
    }

    @Test
    fun `a failed or handed-off exchange leaves no history row`() {
        val m = machine()
        var s = working(m).state
        s = m.reduceAnswer(s, AnswerEvent.RemoteBusy, 2_000).state
        s = m.reduceAnswer(s, askCommand("next"), 3_000).state
        assertTrue(s.earlier.isEmpty())
    }

    // ------------------------------------------------------------- the doors

    @Test
    fun `a typed sentence takes the ask road`() {
        val step = working(machine(), AnswerEvent.Typed("and what about evergreens"))
        assertEquals(AnswerStatus.Working, step.state.status)
        assertEquals("and what about evergreens", step.state.spoken)
        val ask = step.effects.filterIsInstance<AnswerEffect.AskRemote>().single()
        assertEquals("and what about evergreens", ask.prompt)
    }

    @Test
    fun `a new command interrupts whatever was live`() {
        val m = machine()
        val step = m.reduceAnswer(working(m).state, AnswerEvent.Command(AssistantCommand.Read(
            Intent.DeviceFact(Intent.DeviceFact.Kind.TIME), "what time is it")), 2_000)
        // The stale remote ask is cancelled before the new work starts.
        val kinds = step.effects.map { it::class.simpleName }
        assertTrue(kinds.indexOf("CancelRemote") < kinds.indexOf("RunRead"))
    }

    // ---------------------------------------------------------- late results

    @Test
    fun `a late remote answer lands on nothing`() {
        val idle = AnswerState(spoken = "old question")
        val step = machine().reduceAnswer(idle, AnswerEvent.RemoteAnswer("too late"), 9_000)
        assertEquals(idle, step.state)
        assertTrue(step.effects.isEmpty())
    }

    @Test
    fun `a confirm outside a preview does nothing`() {
        val step = machine().reduceAnswer(AnswerState(), AnswerEvent.Confirmed, 1_000)
        assertEquals(AnswerState(), step.state)
        assertTrue(step.effects.isEmpty())
    }

    @Test
    fun `speech ended clears the spoken text and nothing else`() {
        val m = machine()
        val answered = m.reduceAnswer(
            working(m).state,
            AnswerEvent.RemoteAnswer("Shown answer."),
            2_000,
        )
        val step = m.reduceAnswer(answered.state, AnswerEvent.SpeechEnded, 3_000)
        assertNull(step.state.spokenText)
        assertEquals("Shown answer.", step.state.answer)
        assertEquals(AnswerStatus.Answered, step.state.status)
    }

    // -------------------------------------------------------- short for speech

    @Test
    fun `short form keeps at most two sentences`() {
        assertEquals(
            "One. Two.",
            shortForSpeech("One. Two. Three. Four."),
        )
    }

    @Test
    fun `short form is capped at four hundred characters`() {
        val long = "word ".repeat(100).trim()
        val short = shortForSpeech(long)
        assertTrue(short.length <= SPEECH_CAP_CHARS)
        // The cut lands on a space, not mid-word.
        assertTrue(long.startsWith("$short "))
    }

    @Test
    fun `a decimal point is not a sentence boundary`() {
        val text = "Pi is about 3.14159. That is the whole answer."
        assertEquals(text, shortForSpeech(text))
    }

    @Test
    fun `a short answer is its own short form`() {
        assertEquals("It's 14:32", shortForSpeech("It's 14:32"))
    }

    // ------------------------------------------------ a spoken stop's claim

    @Test
    fun `claimsStop names what a bare stop can still halt`() {
        // In flight or waiting on a hand: claimed.
        assertTrue(AnswerState(status = AnswerStatus.Working).claimsStop)
        assertTrue(AnswerState(status = AnswerStatus.Previewing).claimsStop)
        // A voice mid-sentence is claimed even on a settled status.
        assertTrue(
            AnswerState(status = AnswerStatus.Answered, spokenText = "Paris.").claimsStop,
        )
        // A settled card claims nothing, so a later stop still reaches the
        // agent run it was meant for.
        assertFalse(AnswerState(status = AnswerStatus.Answered).claimsStop)
        assertFalse(AnswerState(status = AnswerStatus.HandedOff).claimsStop)
        assertFalse(AnswerState(status = AnswerStatus.Failed).claimsStop)
        assertFalse(AnswerState().claimsStop)
    }
}
