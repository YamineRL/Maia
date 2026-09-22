package dev.maia.app.agent

import dev.maia.app.feel.Schedule
import dev.maia.orb.ApertureState
import dev.maia.app.screens.RunCopy
import dev.maia.transport.AgentEvent
import dev.maia.transport.EventType
import dev.maia.transport.Json
import dev.maia.transport.ProjectEntry
import dev.maia.transport.ProjectState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Answering an agent's question, as a pure function. `docs/M8-copy.md`
 * section 5.18.
 *
 * No agent is prompted anywhere in this file and none can be: [reduceRun] is
 * a function of a state and an event, and every event here is built from a
 * literal frame.
 *
 * The frame in [asked] is the shape captured on the wire on 2026-09-21, with
 * `multiple` and `custom` absent, because that is the case the defaults carry:
 * `custom` defaulting to false would mean a microphone that never opens on
 * any real request.
 */
class RunQuestionTest {

    private val maia = ProjectEntry(7, "maia", "/home/user/projects/maia", ProjectState.ACTIVE)

    private fun event(type: String, data: String = "{}", id: String? = "evt_1"): AgentEvent =
        AgentEvent(id = id, type = type, payload = Json.parse(data))

    private fun run(vararg events: RunEvent, start: RunSession = RunSession()): RunStep {
        var session = start
        var step = RunStep(start)
        var now = 1_000L
        for (e in events) {
            step = reduceRun(session, e, now)
            session = step.session
            now += 100
        }
        return step
    }

    private fun options(vararg labels: String) = labels.joinToString(",") {
        """{"label":"$it","description":"Because of $it."}"""
    }

    /** One question, as the server sends it, with nothing optional set. */
    private val one = """{"question":"Which one?","header":"Pick a colour","options":[${options("Teal", "Amber (Recommended)")}]}"""

    /** A live turn with a question standing on it, and the screen in front. */
    private fun asked(vararg questions: String, seen: Boolean = true): RunSession {
        val body = questions.joinToString(",").ifEmpty { one }
        val events = mutableListOf<RunEvent>(
            RunEvent.Send(maia, "pick something"),
            RunEvent.Admitted(queued = false),
        )
        if (seen) events += RunEvent.Seen
        events += RunEvent.Arrived(
            event(EventType.QUESTION_ASKED, """{"id":"que_1","questions":[$body]}"""),
        )
        return run(*events.toTypedArray()).session
    }

    // ------------------------------------------------------------- arriving

    @Test
    fun `a question blocks the turn and carries its options`() {
        val state = asked().state
        assertEquals(RunStatus.WaitingForYou, state.status)
        assertEquals(BlockKind.Question, state.blocked!!.kind)
        assertEquals("que_1", state.blocked!!.requestId)
        val asking = state.asking!!
        assertEquals(1, asking.questions.size)
        assertEquals("Which one?", asking.current!!.question)
        assertEquals(listOf("Teal", "Amber (Recommended)"), asking.current!!.options.map { it.label })
        // Absent on the wire, and the defaults are what the screen is built
        // on: one answer, and a microphone.
        assertFalse(asking.current!!.multiple)
        assertTrue(asking.current!!.custom)
    }

    @Test
    fun `the question goes into the reply where it arrived, without its header`() {
        val pieces = asked().state.turn!!.pieces
        val asked = pieces.filterIsInstance<ReplyPiece.Asked>().single()
        assertEquals("Which one?", asked.text)
        // Thirty characters of the agent summarising its own question,
        // directly above that question, is the agent saying it twice.
        assertFalse(asked.text.contains("Pick a colour"))
    }

    @Test
    fun `several questions arrive as one piece and are counted`() {
        val two = """{"question":"And then?","options":[${options("Stop")}]}"""
        val state = asked(one, two).state
        assertEquals(2, state.asking!!.questions.size)
        assertTrue(state.asking!!.several)
        assertEquals("Which one?\n\nAnd then?", state.turn!!.pieces.filterIsInstance<ReplyPiece.Asked>().single().text)
    }

    @Test
    fun `the orb thinks while a question stands without a microphone`() {
        val shut = """{"question":"Ready?","custom":false,"options":[${options("Yes", "No")}]}"""
        assertEquals(ApertureState.Thinking, asked(shut).state.aperture)
        // With a microphone open it is the listening one, which is the whole
        // of the difference the user sees.
        assertEquals(ApertureState.Listening, asked().state.aperture)
    }

    // ---------------------------------------------------------- the choosing

    @Test
    fun `CHOOSE ONE sends on the tap`() {
        val step = reduceRun(asked(), RunEvent.Option(0), 5_000)
        assertEquals(listOf(listOf("Teal")), step.session.state.asking!!.sent)
        assertTrue(step.effects.contains(RunEffect.Reply))
        // The hand's, because the thumb that caused it is still on the glass.
        assertTrue(step.effects.contains(RunEffect.FeelByHand(Schedule.agentSent)))
    }

    @Test
    fun `CHOOSE ANY ticks and waits, and sends every tick at once`() {
        val many = """{"question":"Which ones?","multiple":true,"options":[${options("Teal", "Amber", "Rust")}]}"""
        val ticked = run(
            RunEvent.Option(2),
            RunEvent.Option(0),
            start = asked(many),
        )
        assertNull(ticked.session.state.asking!!.sent)
        assertTrue(ticked.effects.isEmpty())
        val sent = reduceRun(ticked.session, RunEvent.SendAnswer, 5_000)
        // In the agent's order, not the order they were tapped: the array is
        // the agent's and reordering it would be a different answer.
        assertEquals(listOf(listOf("Teal", "Rust")), sent.session.state.asking!!.sent)
    }

    @Test
    fun `a second tap unticks and Send with nothing ticked sends nothing`() {
        val many = """{"question":"Which ones?","multiple":true,"options":[${options("Teal", "Amber")}]}"""
        val step = run(RunEvent.Option(1), RunEvent.Option(1), start = asked(many))
        assertEquals(emptyList<Int>(), step.session.state.asking!!.ticked)
        val pressed = reduceRun(step.session, RunEvent.SendAnswer, 5_000)
        assertNull(pressed.session.state.asking!!.sent)
        assertTrue(pressed.effects.isEmpty())
    }

    @Test
    fun `the label on the wire keeps the marker the screen strips`() {
        val step = reduceRun(asked(), RunEvent.Option(1), 5_000)
        // The reply names labels rather than indices, so a screen that
        // renders the shortened label and sends the shortened one is
        // answering a different question from the one on the wire.
        assertEquals(listOf(listOf("Amber (Recommended)")), step.session.state.asking!!.sent)
        assertEquals("Amber", step.session.state.asking!!.shown.single())
        assertEquals("Amber", label("Amber (Recommended)"))
        assertTrue(recommended("Amber (Recommended)"))
        // Exact, and the only one. A label that marks itself some other way
        // keeps every word it has: guessing at a second spelling is how a
        // product starts editing agent output.
        assertEquals("Amber [recommended]", label("Amber [recommended]"))
        assertFalse(recommended("Recommended reading"))
    }

    @Test
    fun `every question is answered before anything leaves the phone`() {
        val two = """{"question":"And then?","options":[${options("Stop", "Carry on")}]}"""
        val first = reduceRun(asked(one, two), RunEvent.Option(0), 5_000)
        // Answers match questions by position, so a list short of the last
        // one is a list of unanswered questions at the end.
        assertNull(first.session.state.asking!!.sent)
        assertFalse(first.effects.contains(RunEffect.Reply))
        assertEquals(1, first.session.state.asking!!.at)
        val second = reduceRun(first.session, RunEvent.Option(1), 5_100)
        assertEquals(listOf(listOf("Teal"), listOf("Carry on")), second.session.state.asking!!.sent)
    }

    // --------------------------------------------------------- the speaking

    @Test
    fun `an utterance that is exactly a label selects that option`() {
        val step = reduceRun(asked(), RunEvent.Said("teal"), 5_000)
        // The match is on the whole utterance and on the label alone, case
        // ignored and nothing else.
        assertEquals(listOf(listOf("Teal")), step.session.state.asking!!.sent)
    }

    @Test
    fun `the stripped label is what a spoken answer is matched against`() {
        val step = reduceRun(asked(), RunEvent.Said("Amber"), 5_000)
        // `Amber` is what the user read, so `Amber` is what they said. What
        // goes out is still the label as the agent wrote it.
        assertEquals(listOf(listOf("Amber (Recommended)")), step.session.state.asking!!.sent)
    }

    @Test
    fun `anything else goes as the user's own words`() {
        for (spoken in listOf("I think teal, probably", "teal please", "the first one")) {
            val step = reduceRun(asked(), RunEvent.Said(spoken), 5_000)
            // A looser match would be a guess about which answer the user
            // gave, and that is the one guess this product cannot afford.
            assertEquals(listOf(listOf(spoken)), step.session.state.asking!!.sent)
        }
    }

    @Test
    fun `a sentence on CHOOSE ANY waits for Send`() {
        val many = """{"question":"Which ones?","multiple":true,"options":[${options("Teal", "Amber")}]}"""
        val heard = run(RunEvent.Option(0), RunEvent.Said("something else entirely"), start = asked(many))
        assertNull(heard.session.state.asking!!.sent)
        assertEquals("something else entirely", heard.session.state.asking!!.heard)
        val sent = reduceRun(heard.session, RunEvent.SendAnswer, 5_000)
        // The sentence wins over the ticks. They cannot both be the answer,
        // and the sentence is the later act.
        assertEquals(listOf(listOf("something else entirely")), sent.session.state.asking!!.sent)
    }

    @Test
    fun `a microphone that heard nothing says the answers are still there`() {
        val step = reduceRun(asked(), RunEvent.HeardNothing, 5_000)
        val asking = step.session.state.asking!!
        assertTrue(asking.nothingHeard)
        assertFalse(asking.saying)
        assertNull(asking.sent)
        assertEquals(listOf(RunEffect.FeelByHand(Schedule.agentStopped), RunEffect.Deafen), step.effects)
        // The orb stops posing as a microphone that is not open.
        assertEquals(ApertureState.Thinking, step.session.state.aperture)
    }

    // -------------------------------------------------------- the microphone

    @Test
    fun `the microphone opens only while the run screen is in front`() {
        // Android hands a capture started from the background silence rather
        // than an error, and the service is `dataSync`, not `microphone`.
        val away = asked(seen = false)
        assertFalse(away.state.onScreen)
        val opened = reduceRun(away, RunEvent.Seen, 5_000)
        assertEquals(listOf(RunEffect.Listen), opened.effects)
        val gone = reduceRun(opened.session, RunEvent.Hidden, 5_100)
        assertEquals(listOf(RunEffect.Deafen), gone.effects)
        assertFalse(gone.session.state.onScreen)
    }

    @Test
    fun `a question that takes no free text opens no microphone at all`() {
        val shut = """{"question":"Ready?","custom":false,"options":[${options("Yes", "No")}]}"""
        val step = run(
            RunEvent.Send(maia, "go"),
            RunEvent.Admitted(queued = false),
            RunEvent.Seen,
            RunEvent.Arrived(event(EventType.QUESTION_ASKED, """{"id":"que_1","questions":[$shut]}""")),
        )
        assertFalse(step.effects.contains(RunEffect.Listen))
        assertFalse(step.session.state.asking!!.saying)
    }

    @Test
    fun `the microphone closes when the answer goes`() {
        val step = reduceRun(asked(), RunEvent.Option(0), 5_000)
        assertTrue(step.effects.contains(RunEffect.Deafen))
        assertTrue(step.effects.indexOf(RunEffect.Reply) < step.effects.indexOf(RunEffect.Deafen))
    }

    @Test
    fun `no string off the stream reaches an effect`() {
        val step = run(RunEvent.Option(0), RunEvent.Replied, start = asked())
        // Rule 12, structurally: the reply effects carry nothing, and the
        // driver reads the labels and the request id off the state.
        for (effect in step.effects) {
            assertFalse(effect.toString().contains("Teal"))
            assertFalse(effect.toString().contains("que_1"))
        }
    }

    // --------------------------------------------------------- the three exits

    @Test
    fun `Decide without me sends the empty list and ends the whole request`() {
        val two = """{"question":"And then?","options":[${options("Stop")}]}"""
        val step = reduceRun(asked(one, two), RunEvent.SkipQuestion, 5_000)
        assertEquals(emptyList<List<String>>(), step.session.state.asking!!.sent)
        assertEquals(Sending.Skip, step.session.state.asking!!.sending)
        assertTrue(step.effects.contains(RunEffect.Reply))
        val landed = reduceRun(step.session, RunEvent.Replied, 5_100)
        assertEquals(
            AnswerMark.LetItDecide,
            landed.session.state.turn!!.pieces.filterIsInstance<ReplyPiece.Answered>().single().mark,
        )
    }

    @Test
    fun `Drop the question rejects it and the turn carries on`() {
        val step = reduceRun(asked(), RunEvent.DropQuestion, 5_000)
        assertNull(step.session.state.asking!!.sent)
        assertTrue(step.effects.contains(RunEffect.Drop))
        val landed = reduceRun(step.session, RunEvent.Replied, 5_100)
        assertEquals(RunStatus.Working, landed.session.state.status)
        assertNull(landed.session.state.blocked)
        assertNull(landed.session.state.asking)
        assertEquals(
            AnswerMark.Dropped,
            landed.session.state.turn!!.pieces.filterIsInstance<ReplyPiece.Answered>().single().mark,
        )
    }

    @Test
    fun `an answer that landed prints what was answered and carries on`() {
        val step = run(RunEvent.Option(1), RunEvent.Replied, start = asked())
        val marker = step.session.state.turn!!.pieces.filterIsInstance<ReplyPiece.Answered>().single()
        assertEquals(AnswerMark.Answered, marker.mark)
        // The receipt carries the words the user read, which is the stripped
        // label, one line per question.
        assertEquals(listOf("Amber"), marker.lines)
        assertEquals(RunStatus.Working, step.session.state.status)
        assertNull(step.session.state.asking)
    }

    // ------------------------------------------------- what did not land

    @Test
    fun `a 404 reports the transaction and not the agent`() {
        val step = run(RunEvent.Option(0), RunEvent.ReplyGone, start = asked())
        val asking = step.session.state.asking!!
        assertEquals(Outcome.Gone, asking.outcome)
        // The controls stay, and what was chosen is still on the screen.
        assertEquals(listOf(listOf("Teal")), asking.sent)
        assertTrue(step.effects.contains(RunEffect.FeelByHand(Schedule.fault)))
        val marker = step.session.state.turn!!.pieces.filterIsInstance<ReplyPiece.Answered>().single()
        assertEquals(AnswerMark.NotTaken, marker.mark)
        // An expired question and a reply that lost its way are the same
        // status on the wire, so neither is claimed.
        assertEquals(dev.maia.app.R.string.m8_run_question_gone_note, RunCopy.answerMarkNote(marker.mark))
    }

    @Test
    fun `a reply that never left the phone keeps what the user said`() {
        val step = run(RunEvent.Said("teal or amber"), RunEvent.ReplyFailed(RunLoss.Tunnel), start = asked())
        val asking = step.session.state.asking!!
        assertEquals(Outcome.Undelivered, asking.outcome)
        assertEquals(listOf(listOf("teal or amber")), asking.sent)
        assertEquals(RunLoss.Tunnel, step.session.state.answerFailed)
    }

    @Test
    fun `Send it again sends the same thing and opens no microphone`() {
        val failed = run(RunEvent.Option(0), RunEvent.ReplyFailed(RunLoss.Tunnel), start = asked())
        val again = reduceRun(failed.session, RunEvent.SendAgain, 5_000)
        assertEquals(listOf(listOf("Teal")), again.session.state.asking!!.sent)
        assertTrue(again.effects.contains(RunEffect.Reply))
        assertFalse(again.effects.contains(RunEffect.Listen))
        assertNull(again.session.state.answerFailed)
    }

    @Test
    fun `Say it again drops the last answer and reopens the microphone`() {
        val failed = run(RunEvent.Said("teal or amber"), RunEvent.ReplyFailed(RunLoss.Tunnel), start = asked())
        val again = reduceRun(failed.session, RunEvent.SayAgain, 5_000)
        val asking = again.session.state.asking!!
        assertEquals(0, asking.at)
        assertEquals(emptyList<List<String>>(), asking.answers)
        assertNull(asking.sent)
        assertNull(asking.heard)
        assertTrue(again.effects.contains(RunEffect.Listen))
        assertNull(again.session.state.answerFailed)
    }

    @Test
    fun `a question that stopped being pending is the one thing Maia can say plainly`() {
        val step = reduceRun(asked(), RunEvent.Withdrawn, 5_000)
        assertNull(step.session.state.asking)
        assertNull(step.session.state.blocked)
        // Unlike the 404, this was observed: the request is not in the
        // pending list, so `IT STOPPED WAITING` is a claim that can be made.
        assertEquals(
            AnswerMark.StoppedWaiting,
            step.session.state.turn!!.pieces.filterIsInstance<ReplyPiece.Answered>().single().mark,
        )
    }

    @Test
    fun `the end of a turn takes the question with it`() {
        val step = reduceRun(asked(), RunEvent.Arrived(event(EventType.SESSION_IDLE)), 5_000)
        assertNull(step.session.state.asking)
        assertEquals(listOf(RunEffect.Unsubscribe, RunEffect.Feel(Schedule.agentEnded), RunEffect.Deafen), step.effects)
    }

    // --------------------------------------------------------------- the copy

    @Test
    fun `the two option labels are the two promises about a tap`() {
        assertEquals(dev.maia.app.R.string.m8_run_options_label, RunCopy.optionsLabel(multiple = false))
        assertEquals(dev.maia.app.R.string.m8_run_options_label_many, RunCopy.optionsLabel(multiple = true))
    }

    @Test
    fun `the announcement says whether the microphone is open`() {
        val listening = asked().let { session ->
            reduceRun(session, RunEvent.Listening, 5_000).session.state
        }
        assertEquals(dev.maia.app.R.string.m8_run_question_announce_open, RunCopy.questionAnnouncement(listening))
        assertEquals(dev.maia.app.R.string.m8_run_question_announce, RunCopy.questionAnnouncement(asked().state))
        // Nothing to announce when nothing is asking.
        assertNull(RunCopy.questionAnnouncement(RunState()))
    }
}
