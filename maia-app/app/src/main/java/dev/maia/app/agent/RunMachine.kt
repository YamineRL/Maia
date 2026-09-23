package dev.maia.app.agent

import dev.maia.app.feel.Pattern
import dev.maia.app.feel.Schedule
import dev.maia.transport.AgentEvent
import dev.maia.transport.EventType
import dev.maia.transport.PermissionReply
import dev.maia.transport.ProjectEntry

/**
 * How long a stream may be silent before it is treated as dropped.
 *
 * PRD section 9 and `docs/M8-copy.md` section 1.4. Heartbeats arrive as SSE
 * comments roughly every ten seconds, so ninety is nine missed in a row and
 * not a slow agent: a four-minute tool call still heartbeats. Maia never times
 * an agent out for being slow. This is the stream's limit, not the agent's,
 * which is why the clock is reset by a heartbeat and not only by an event.
 */
const val SILENCE_MS = 90_000L


/**
 * After the one reconnect, how much further silence is a fault.
 *
 * Section 1.4: "If a further thirty seconds bring nothing, that is the fault
 * in section 5.6." Shorter than [SILENCE_MS] because by this point the
 * question is no longer whether the agent is slow, it is whether the link came
 * back at all, and a reconnected stream sends `server.connected` at once.
 */
const val RECONNECT_GRACE_MS = 30_000L

/** How long the empty reply area stays empty before `m8_run_nothing_yet`. */
const val NOTHING_YET_MS = 10_000L

/**
 * How often a standing block is checked against what the far end still has
 * pending. `docs/M8-copy.md` section 5.17, case three.
 *
 * There is no `permission.expired`, `withdrawn` or `cancelled` event anywhere
 * in the stream: a request abandoned by an interrupt or by the end of a turn
 * simply stops being pending, silently, so a phone that was asleep can be
 * holding a notification for something nobody is waiting on. Asking is the
 * only way to find out. Thirty seconds is the same order as the reconnect
 * grace: often enough that the row is corrected before a thumb finds it, rare
 * enough that a blocked agent is not a poller.
 */
const val RECONCILE_MS = 30_000L

/**
 * How long Maia waits for the server's ending after the user's own.
 *
 * A refusal ends the turn and does not close the stream, because
 * `m8_notif_blocked_answered_refuse` promises Maia says when the run has
 * ended and only the stream produces that. Something has to bound the wait:
 * nothing obliges the far end to send anything, and the foreground service
 * this window holds up would otherwise be held up until the day's `dataSync`
 * budget ran out and took the app with it.
 *
 * Thirty seconds, the same as [RECONNECT_GRACE_MS] and for the same reason it
 * is shorter than [SILENCE_MS]: by this point the question is not whether the
 * agent is slow. A rejected request fails every other permission waiting on
 * that session and ends the turn, so the ending is the next thing the server
 * has to say. Past this, Maia stops waiting and says nothing: see
 * [RunState.settlingSince].
 */
const val SETTLE_MS = 30_000L

/**
 * How long the stream gets to continue a turn after its last step ended.
 *
 * `session.idle` is the contracted end of a turn, but the pinned server
 * never emits it: a turn's last frame is `session.next.step.ended`, followed
 * by heartbeats and nothing else. So the end is inferred instead. A step
 * that ended with a `stop` finish may be the last of the turn, and this
 * window is how long the stream has to prove it is not: a queued prompt
 * dequeued, a tool call dispatched, another step started. Any one of those
 * arrives within milliseconds when it is coming, so fifteen seconds is
 * generous, and a wrong guess in either direction stays honest: close too
 * early and events that were coming are cancelled instead, never claimed
 * to have ended; close late and the reply simply sits finished a moment
 * before the screen says so.
 */
const val STEP_END_GRACE_MS = 15_000L

/**
 * The stream's health, beside [RunState] rather than in it.
 *
 * None of this is drawn, and [RunState] is what one screen draws. It is the
 * same split as `FlowSession` and `FlowState`, for the same reason: a field
 * that no screen reads, carried by every state, is a field every state has to
 * remember to copy.
 */
data class StreamHealth(
    /** The last event id seen, which is what a reconnect resumes from. */
    val lastEventId: String? = null,
    /** When the last frame of any kind arrived, heartbeats included. */
    val lastFrameAt: Long = 0,
    /** One reconnect, and one only. PRD section 9. */
    val reconnected: Boolean = false,
    val open: Boolean = false,
    /** When the pending list was last asked, so a block is not a poller. */
    val lastReconcileAt: Long = 0,
    /**
     * When a `session.next.step.ended` with a `stop` finish arrived and no
     * `session.next.*` event has followed it. Zero at every other moment.
     *
     * The inferred ending of a turn, because `session.idle` never arrives on
     * this server: see [STEP_END_GRACE_MS]. Set by the step's own event and
     * cleared by anything that continues the turn, so the value only ever
     * marks the gap between a last step and whatever would prove the turn
     * is still going.
     */
    val stepEndedAt: Long = 0,
)

data class RunSession(
    val state: RunState = RunState(),
    val stream: StreamHealth = StreamHealth(),
)

data class RunStep(val session: RunSession, val effects: List<RunEffect> = emptyList())

/** What the run machine asks the world to do. */
sealed interface RunEffect {

    /** Open the per-session stream, resuming from [after] when there is one. */
    data class Subscribe(val after: String?) : RunEffect

    /** Close the stream. Emitted when the stream moves, and when a turn ends. */
    data object Unsubscribe : RunEffect

    /**
     * Send the instruction. [instruction] is the user's own words, read back
     * out of what they said, and it is the only string in this whole type that
     * is not a constant: nothing that arrived from an agent is ever an
     * argument to any effect here.
     */
    data class Prompt(val instruction: String, val queue: Boolean) : RunEffect

    /** `POST /api/session/{id}/interrupt`. Not an undo, which `m8_run_stop_cd` says. */
    data object Interrupt : RunEffect

    /**
     * Answer the block the state is currently holding.
     *
     * The request id is deliberately not on this effect. It arrived on the
     * stream, so putting it here would be the one rule this file exists to
     * keep, broken: the driver reads it off the state it already holds at the
     * moment it performs this. [note] is the user's own words, typed into the
     * refusal field, and is the same class of string as
     * [RunEffect.Prompt.instruction].
     */
    data class Answer(val reply: PermissionReply, val note: String? = null) : RunEffect

    /**
     * Ask the far end what is still pending, and withdraw the block if this
     * one is not. Section 5.17 case three. Carries nothing: the driver knows
     * the directory and the session, and the answer comes back as an event.
     */
    data object Reconcile : RunEffect

    /**
     * Send what [RunState.asking] is holding, to the request the state is
     * holding. Section 5.18.
     *
     * Carries nothing, for the reason [RunEffect.Answer] does not carry the
     * request id: every one of an answer's parts arrived on the stream. The
     * option labels are the agent's own words and the request id is the
     * agent's own string, so the driver reads both off the state at the moment
     * it performs this, and there is no field on this type an agent's text
     * could travel in.
     *
     * [RunState.Asking.sent] decides what goes. An empty list is a real value
     * and is `Decide without me`.
     */
    data object Reply : RunEffect

    /** Reject the question outright: `Drop the question`. The turn carries on. */
    data object Drop : RunEffect

    /**
     * Open the microphone for an answer in the user's own words.
     *
     * Derived rather than emitted by hand, in [reduceRun], from three facts:
     * the question accepts a sentence, the screen is in front of the user,
     * and Maia is waiting to hear one. Anything that changes one of those
     * three produces this or [Deafen] without having to remember to.
     */
    data object Listen : RunEffect

    /** Close it. The microphone is released the instant it is not wanted. */
    data object Deafen : RunEffect


    data class Feel(val pattern: Pattern) : RunEffect

    /**
     * The same, but `USAGE_TOUCH` whatever the pattern is.
     *
     * Section 4.6 adds three events and no seventh pattern, and every one of
     * the three is the hand's: an answer or a stop that did not land is felt
     * a second after the user's own thumb. `Schedule.fault` is the
     * notification's on one surface and the hand's on this one, so which it
     * is cannot be decided from the pattern alone. This effect is that
     * decision, made where it is known.
     */
    data class FeelByHand(val pattern: Pattern) : RunEffect

    /**
     * Say one of the ten short confirmations.
     *
     * [ack] chooses a constant string resource. [number] and [other] are
     * project numbers. There is no String on this effect and there must never
     * be one: see [AgentAck].
     */
    data class Say(val ack: AgentAck, val number: Int? = null, val other: Int? = null) : RunEffect

    /** Deliver [RunEvent.Tick] at [atMs], on the machine's own clock. */
    data class ScheduleTick(val atMs: Long) : RunEffect
}

/** What happens to a run. */
sealed interface RunEvent {

    /**
     * The stream moves to [project]. [leftBehind] is the number of the project
     * that was streaming and still is, or null when nothing was.
     */
    data class Focus(val project: ProjectEntry, val leftBehind: Int? = null) : RunEvent

    /** A project resolved and an instruction is on its way to it. */
    data class Send(val project: ProjectEntry, val instruction: String, val leftBehind: Int? = null) : RunEvent

    /** The server admitted the instruction. [queued] when it went behind a busy agent. */
    data class Admitted(val queued: Boolean) : RunEvent

    /** One parsed event off the stream. */
    data class Arrived(val event: AgentEvent) : RunEvent

    /** A frame of any kind, heartbeats included. Resets the silence clock only. */
    data object Heartbeat : RunEvent

    /** The stream ended. [reason] is empty for a clean end. Never shown to the user. */
    data class StreamClosed(val reason: String) : RunEvent

    /** `interrupt` returned. A stop the user asked for is not a failure. */
    data object Interrupted : RunEvent

    /**
     * The user pressed one of the answer controls, on either surface.
     *
     * Nothing changes on the screen yet, and that is section 5.3's rule: no
     * label promises the press is the end of it, because on a locked phone
     * the press is followed by a keyguard and only then by the send. What
     * reports is the outcome.
     */
    data class Answer(val reply: PermissionReply, val note: String? = null) : RunEvent

    /** The far end took the answer. [PermissionReply.REJECT] ends the turn there. */
    data class Answered(val reply: PermissionReply, val note: String? = null) : RunEvent

    /** The far end answered, and said it is not waiting any more. A 404, not a loss. */
    data object AnswerTooLate : RunEvent

    /** The answer never reached the far end. The block stands and can be pressed again. */
    data class AnswerFailed(val loss: RunLoss) : RunEvent

    /**
     * The pending list came back without this request in it.
     *
     * The user pressed nothing, so nothing is felt and nothing is spoken:
     * the row is being corrected, not raised.
     */
    data object Withdrawn : RunEvent

    /** `interrupt` never reached the machine. The run carries on. */
    data class StopFailed(val loss: RunLoss) : RunEvent

    /** The far end says there was nothing running. The screen already says so. */
    data object StopTooLate : RunEvent

    /**
     * Nothing was sent, or the turn ended badly.
     *
     * [entry] is set for [RunFault.Retired] alone, because that is the only
     * fault whose screen names a project. It is a registry row the phone
     * already holds, not anything the far end sent back with the failure.
     */
    data class Failed(
        val fault: RunFault,
        val number: Int? = null,
        val entry: ProjectEntry? = null,
    ) : RunEvent

    /** A name matched nothing or more than one, or a number does not exist. */
    data class Choose(val chooser: Chooser, val ack: AgentAck) : RunEvent

    /**
     * The user pressed `Discard` on the project list, or left a fault screen.
     *
     * Section 5.10: "Nothing is sent and the words are forgotten." The held
     * instruction is on the chooser, so forgetting it is exactly dropping the
     * chooser, and there is nowhere else on the phone it was written down.
     */
    data object Dismissed : RunEvent

    /**
     * The run screen is in front of the user.
     *
     * Section 5.17: the blocked row is cancelled "when the run screen is
     * opened on that project", and section 4.2's one repeat stops "as soon as
     * a human is demonstrably present". This is that fact, and it is the only
     * event in this file that comes from a window rather than from the wire.
     *
     * It takes the row down and leaves [RunState.blocked] exactly where it
     * is. The agent is still waiting; what changed is that the user is now
     * looking at the two controls, so a notification telling them to go and
     * look at them is spent.
     */
    data object Seen : RunEvent

    /**
     * The run screen is no longer in front of the user.
     *
     * The pause half of [Seen], and it exists for the microphone alone: a
     * capture started by a process in the background returns silence on
     * Android 11 and later, so the one honest thing to do when the window
     * goes is to stop claiming the microphone is open. The blocked row is not
     * put back: it was spent the moment a human looked at the screen.
     */
    data object Hidden : RunEvent

    /**
     * One option row was tapped, by its position in the question's list.
     *
     * A position and not a label, so nothing that arrived on the stream
     * becomes an argument to an event that a test writes by hand, and so two
     * options that happen to share a label cannot be confused for each other.
     */
    data class Option(val index: Int) : RunEvent

    /** `Send`, on the `CHOOSE ANY` case, once something is ticked. */
    data object SendAnswer : RunEvent

    /** `Decide without me`: the empty answer list, which ends the whole request. */
    data object SkipQuestion : RunEvent

    /** `Drop the question`: the rejection, which leaves the turn running. */
    data object DropQuestion : RunEvent

    /** `Send it again`, after a send that did not land. The same answer goes. */
    data object SendAgain : RunEvent

    /** `Say it again`: the microphone reopens and the last answer is replaced. */
    data object SayAgain : RunEvent

    /** The capture actually started, which is what `m8_run_answer_listening` claims. */
    data object Listening : RunEvent

    /** A partial. The user's own words, on their way to becoming an answer. */
    data class Heard(val text: String) : RunEvent

    /** The endpointer fired. This is the answer, unless it is one of the labels. */
    data class Said(val text: String) : RunEvent

    /** The microphone closed with nothing in it. The agent is still waiting. */
    data object HeardNothing : RunEvent

    /** The far end took the reply, whichever of the three it was. */
    data object Replied : RunEvent

    /**
     * The far end has no such question.
     *
     * Two facts in one status code, an expired question and a reply that did
     * not find a live one, and the server does not separate them. Neither
     * does this.
     */
    data object ReplyGone : RunEvent

    /** The reply never left the phone. The choice stays and can be sent again. */
    data class ReplyFailed(val loss: RunLoss) : RunEvent

    /** The scheduled clock check. */
    data object Tick : RunEvent
}

/**
 * The whole run screen, as a pure function. No Android, no IO, no clock of its
 * own: [now] is passed in, exactly as the flow reducer takes it, so every rule
 * about what happens after ninety seconds is a test and not a phone.
 *
 * **The rule this file exists to keep**: nothing that arrived on the stream
 * ever becomes an argument to a [RunEffect]. Agent text goes into [RunState],
 * which is drawn, and stops there. The only effect that reaches a speaker is
 * [RunEffect.Say], whose payload is an enum and two integers.
 */
fun reduceRun(session: RunSession, event: RunEvent, now: Long): RunStep {
    // The microphone, derived rather than remembered. Every handler below
    // changes the facts and none of them has to decide what that means for a
    // recorder, which is the same reasoning that keeps the blocked row out of
    // the effect list: one place owns it, so it cannot drift.
    val before = micWanted(session.state)
    val step = step(session, event, now)
    val after = micWanted(step.session.state)
    if (before == after) return step
    return step.copy(effects = step.effects + if (after) RunEffect.Listen else RunEffect.Deafen)
}

/**
 * Whether the recogniser should be running for an answer right now.
 *
 * Three facts and all three are necessary. The far end has to accept an
 * answer in the user's own words, or the microphone is a control wired to
 * nothing (section 5.18). Maia has to be waiting to hear one, which
 * [RunState.Asking.saying] is. And the run screen has to be in front of the
 * user, which is Android's rule rather than this product's: a capture started
 * from the background is silence, not an error, so the alternative is a phone
 * in a pocket telling a screen reader the microphone is open.
 */
private fun micWanted(state: RunState): Boolean {
    val asking = state.asking ?: return false
    return state.onScreen && asking.saying && asking.current?.custom == true
}

private fun step(session: RunSession, event: RunEvent, now: Long): RunStep = when (event) {
    is RunEvent.Focus -> focus(session, event, now)
    is RunEvent.Send -> send(session, event, now)
    is RunEvent.Admitted -> admitted(session, event, now)
    is RunEvent.Arrived -> arrived(session, event, now)
    RunEvent.Heartbeat -> RunStep(session.copy(stream = session.stream.copy(lastFrameAt = now)))
    is RunEvent.StreamClosed -> closed(session, now)
    RunEvent.Interrupted -> interrupted(session)
    is RunEvent.Answer -> answer(session, event)
    is RunEvent.Answered -> answered(session, event, now)
    RunEvent.AnswerTooLate -> stoppedWaiting(session, RunAnnounce.TooLate)
    is RunEvent.AnswerFailed -> answerFailed(session, event)
    RunEvent.Withdrawn -> stoppedWaiting(session, RunAnnounce.Withdrawn)
    is RunEvent.StopFailed -> stopFailed(session, event)
    RunEvent.StopTooLate -> stopTooLate(session)
    is RunEvent.Failed -> failed(session, event)
    is RunEvent.Choose -> choose(session, event)
    RunEvent.Dismissed -> dismissed(session)
    RunEvent.Seen -> seen(session)
    RunEvent.Hidden -> hidden(session)
    is RunEvent.Option -> option(session, event.index)
    RunEvent.SendAnswer -> sendAnswer(session)
    RunEvent.SkipQuestion -> skipQuestion(session)
    RunEvent.DropQuestion -> dropQuestion(session)
    RunEvent.SendAgain -> sendAgain(session)
    RunEvent.SayAgain -> sayAgain(session)
    RunEvent.Listening -> listening(session)
    is RunEvent.Heard -> heard(session, event.text)
    is RunEvent.Said -> said(session, event.text)
    RunEvent.HeardNothing -> heardNothing(session)
    RunEvent.Replied -> replied(session)
    RunEvent.ReplyGone -> replyGone(session)
    is RunEvent.ReplyFailed -> replyFailed(session, event.loss)
    RunEvent.Tick -> tick(session, now)
}

// ---------------------------------------------------------------- addressing

/**
 * The stream moves. Section 13 answer 1: one project streams at a time, and a
 * project named with nothing after it is a complete utterance.
 *
 * The old stream is closed and the old turn is kept: the agent that was
 * running is still running, which is what [RunState.leftBehind] says on screen
 * and what `m8_spoken_sent_moved` says out loud.
 */
private fun focus(session: RunSession, event: RunEvent.Focus, now: Long): RunStep = RunStep(
    RunSession(
        state = RunState(
            project = event.project,
            status = RunStatus.Sending,
            earlier = closeOut(session.state),
            leftBehind = event.leftBehind,
        ),
        stream = StreamHealth(lastFrameAt = now, open = true),
    ),
    listOfNotNull(
        if (session.stream.open) RunEffect.Unsubscribe else null,
        RunEffect.Subscribe(null),
        RunEffect.ScheduleTick(now + NOTHING_YET_MS),
    ),
)

/**
 * A project and an instruction.
 *
 * Subscribe first, prompt second, always. `POST /prompt` answers with a
 * `SessionInputAdmitted` and says nothing about the work, so a client that
 * prompts and then subscribes can miss the first deltas of its own reply. The
 * effect order here is the one the driver runs in, and the controller runs a
 * step's effects in order for exactly this kind of reason.
 */
private fun send(session: RunSession, event: RunEvent.Send, now: Long): RunStep {
    return RunStep(
        RunSession(
            state = RunState(
                project = event.project,
                status = RunStatus.Sending,
                turn = Turn(instruction = event.instruction, startedAt = now),
                earlier = closeOut(session.state),
                leftBehind = event.leftBehind,
            ),
            stream = StreamHealth(lastFrameAt = now, open = true),
        ),
        listOfNotNull(
            if (session.stream.open) RunEffect.Unsubscribe else null,
            // From the live edge, never from a stored id. A resume replays
            // events, and a replayed delta appended to a turn that already has
            // it is a reply printed twice. Resuming is for the one reconnect,
            // which happens inside a turn, not for a new instruction.
            RunEffect.Subscribe(null),
            RunEffect.Prompt(event.instruction, queue = true),
            RunEffect.ScheduleTick(now + NOTHING_YET_MS),
        ),
    )
}

/**
 * The server has the instruction.
 *
 * Section 13 answer 2: queue by default, because a steer discards what the
 * agent was mid-way through and on a phone the user often cannot see what that
 * was. The queue is a fact in words and not a pose (section 3.2): the orb is
 * `Working` either way, because the agent is working.
 */
private fun admitted(session: RunSession, event: RunEvent.Admitted, now: Long): RunStep {
    val status = if (event.queued) RunStatus.Queued else RunStatus.Sent
    val left = session.state.leftBehind
    val number = session.state.project?.number
    val ack = when {
        event.queued -> RunEffect.Say(AgentAck.Queued, number)
        left != null -> RunEffect.Say(AgentAck.SentMoved, number, left)
        else -> RunEffect.Say(AgentAck.Sent, number)
    }
    return RunStep(
        session.copy(state = session.state.copy(status = status, fault = null, loss = null)),
        listOf(
            // One dry tap: it left the phone. Section 4.2, and a consequence
            // of the hand, so it keeps USAGE_TOUCH.
            RunEffect.Feel(if (event.queued) Schedule.agentQueued else Schedule.agentSent),
            ack,
            RunEffect.ScheduleTick(now + NOTHING_YET_MS),
        ),
    )
}

// ------------------------------------------------------------------- streaming

private fun arrived(session: RunSession, event: RunEvent.Arrived, now: Long): RunStep {
    val e = event.event
    val stream = session.stream.copy(
        lastEventId = e.id ?: session.stream.lastEventId,
        lastFrameAt = now,
        open = true,
        stepEndedAt = when {
            // A stopped step may be the end of the turn; the window starts.
            e.type == EventType.STEP_ENDED ->
                if (EventPayload.stepStopped(e)) now else 0L
            // Anything the turn emits next proves it is still going, and a
            // block means it stopped to wait rather than ended.
            e.type.startsWith("session.next.") || e.type in EventType.BLOCKING -> 0L
            else -> session.stream.stepEndedAt
        },
    )
    val state = session.state
    val turn = state.turn

    return when (e.type) {
        // A reconnect is the first moment a phone that was asleep can find
        // out that what it is still showing was answered or abandoned while
        // it was away. Nothing on the stream says so, so it asks.
        EventType.SERVER_CONNECTED ->
            if (state.blocked == null) {
                RunStep(session.copy(stream = stream))
            } else {
                RunStep(
                    session.copy(stream = stream.copy(lastReconcileAt = now)),
                    listOf(RunEffect.Reconcile),
                )
            }

        EventType.TEXT_DELTA -> {
            val text = EventPayload.delta(e) ?: return RunStep(session.copy(stream = stream))
            RunStep(
                session.copy(
                    state = state.copy(
                        status = if (state.status == RunStatus.WaitingForYou) state.status else RunStatus.Working,
                        blocked = if (state.status == RunStatus.WaitingForYou) state.blocked else null,
                        turn = (turn ?: Turn("")).append(text),
                        nothingYet = false,
                    ),
                    stream = stream,
                ),
            )
        }

        EventType.TEXT_ENDED -> RunStep(session.copy(stream = stream))

        EventType.TOOL_CALLED -> {
            val call = EventPayload.tool(e) ?: return RunStep(session.copy(stream = stream))
            RunStep(
                session.copy(
                    state = state.copy(
                        status = RunStatus.Working,
                        turn = (turn ?: Turn("")).tool(call.first, call.second),
                        nothingYet = false,
                    ),
                    stream = stream,
                ),
            )
        }

        EventType.TODO_UPDATED -> RunStep(
            // Omitted entirely when the payload carries no counts. A plan the
            // user cannot count is not worth a line (section 1.6).
            session.copy(state = state.copy(plan = EventPayload.plan(e) ?: state.plan), stream = stream),
        )

        in EventType.BLOCKING -> {
            val kind = if (e.type.startsWith("permission")) BlockKind.Permission else BlockKind.Question
            // Section 5.18. Empty for a permission, and empty for a question
            // whose payload did not parse, which is still shown and simply
            // cannot be answered: knowing an agent is waiting beats knowing
            // nothing, and inventing an option would answer a question nobody
            // asked.
            val questions = if (kind == BlockKind.Question) EventPayload.questions(e) else emptyList()
            val asking = questions.takeIf { it.isNotEmpty() }?.let {
                // The microphone is wanted from the moment it arrives when the
                // far end will take a sentence, which is what tapping `Answer`
                // in the shade has meant since 5.3. Whether it opens is a
                // second question, and [micWanted] asks it.
                Asking(questions = it, saying = it.first().custom)
            }
            RunStep(
                session.copy(
                    state = state.copy(
                        status = RunStatus.WaitingForYou,
                        blocked = Block(
                            kind,
                            EventPayload.requestId(e) ?: "",
                            EventPayload.patterns(e),
                        ),
                        asking = asking,
                        // Under `IT ASKED`, at the point in the stream it
                        // arrived at, so it is still there after the answer
                        // has gone.
                        turn = if (asking == null) turn else (turn ?: Turn("")).asked(asking),
                        blockedRow = BlockedNotice.Asking,
                        answerFailed = null,
                        announce = null,
                    ),
                    // Stamped now, so the first recheck is thirty seconds
                    // away rather than at the next tick: a request that has
                    // just been asked is not one that needs confirming.
                    stream = stream.copy(lastReconcileAt = now),
                ),
                // A knock: heavy, then two equal taps that do not diminish. It
                // arrives later and unprompted, so it is the notification's
                // vibration and is never spoken (section 2.5): a permission
                // request read aloud is agent output read aloud.
                listOf(RunEffect.Feel(Schedule.agentBlocked)),
            )
        }

        EventType.STEP_ENDED -> RunStep(
            session.copy(stream = stream),
            // Punctual rather than riding the standing tick: the window is
            // fifteen seconds and the cadence ten, so waiting for the next
            // one could hold `Finished` back by most of another window.
            if (stream.stepEndedAt > 0) {
                listOf(RunEffect.ScheduleTick(now + STEP_END_GRACE_MS))
            } else {
                emptyList()
            },
        )

        EventType.SESSION_IDLE -> finished(session.copy(stream = stream))

        in EventType.TERMINAL -> RunStep(
            session.copy(
                state = state.copy(
                    status = RunStatus.DidNotFinish,
                    blocked = null,
                    // Marked at the point it stopped. What is above it is what
                    // got through, and nothing invents the rest.
                    blockedRow = null,
                    asking = null,
                    settlingSince = 0L,
                    turn = turn?.copy(end = turn.end ?: EndMarker.Cut),
                    fault = RunFault.TurnFailed,
                    // The agent reported it, so the shade says so. Never with
                    // a word of what it reported: section 5.4.
                    loss = state.loss ?: RunLoss.AgentError,
                    nothingYet = false,
                ),
                stream = stream.copy(open = false),
            ),
            listOf(RunEffect.Unsubscribe, RunEffect.Feel(Schedule.fault)),
        )

        // An event type this build does not act on. The union will grow and a
        // client that throws on a new member is a client that breaks on a
        // server upgrade.
        else -> RunStep(session.copy(stream = stream))
    }
}

/**
 * The turn ended and the screen can say so.
 *
 * Reached two ways: a `session.idle` off the stream, or the grace window in
 * [tick] expiring after a stopped last step. The two are the same fact, the
 * server simply never sends the first on this build, so both land here.
 */
private fun finished(session: RunSession): RunStep {
    val turn = session.state.turn
    return RunStep(
        session.copy(
            state = session.state.copy(
                status = RunStatus.Finished,
                blocked = null,
                blockedRow = null,
                asking = null,
                settlingSince = 0L,
                // Set once. A refusal has already closed this reply with
                // `YOU REFUSED THIS`, and the far end reporting the turn
                // as idle afterwards does not unmark what the user did.
                turn = turn?.copy(end = turn.end ?: EndMarker.Done),
                nothingYet = false,
            ),
            stream = session.stream.copy(open = false, stepEndedAt = 0L),
        ),
        // Rises and resolves: something finished, elsewhere. Not spoken.
        listOf(RunEffect.Unsubscribe, RunEffect.Feel(Schedule.agentEnded)),
    )
}

/**
 * The stream ended.
 *
 * Reconnect once, show what arrived, mark it incomplete, never invent the
 * ending. A stream that ends after the turn is over is not a fault: the turn
 * already closed itself on `session.idle` and the driver unsubscribed, so the
 * close is the answer to that and nothing more.
 */
private fun closed(session: RunSession, now: Long): RunStep {
    if (!session.state.live) {
        // The other half of the refusal wait: the ending arrives, or the
        // stream closes. This is the second one, and it ends the wait exactly
        // as an ending would, silently and with nothing added to the screen.
        return RunStep(
            session.copy(
                state = session.state.copy(settlingSince = 0L),
                stream = session.stream.copy(open = false),
            ),
        )
    }
    if (!session.stream.reconnected) {
        return RunStep(
            session.copy(stream = session.stream.copy(open = true, reconnected = true, lastFrameAt = now)),
            listOf(
                RunEffect.Subscribe(session.stream.lastEventId),
                RunEffect.ScheduleTick(now + RECONNECT_GRACE_MS),
            ),
        )
    }
    return cut(session)
}

/** One reconnect is all there is. The second loss is the end of the turn. */
private fun cut(session: RunSession): RunStep = RunStep(
    session.copy(
        state = session.state.copy(
            status = RunStatus.DidNotFinish,
            blocked = null,
            blockedRow = null,
            asking = null,
            settlingSince = 0L,
            turn = session.state.turn?.let { it.copy(end = it.end ?: EndMarker.Cut) },
            fault = RunFault.TurnFailed,
            // Frames stopped arriving and one reconnect did not bring them
            // back. That is all the phone observed, and all it says.
            loss = session.state.loss ?: RunLoss.Lost,
        ),
        stream = session.stream.copy(open = false),
    ),
    listOf(RunEffect.Unsubscribe, RunEffect.Feel(Schedule.fault)),
)

/**
 * The wait after a refusal, which is the only reason a clock check reaches a
 * turn that is not [RunState.live].
 *
 * Nothing is drawn and nothing is said either way. If the ending arrives, the
 * ordinary handlers take it and clear [RunState.settlingSince] with
 * everything else. If it does not, the stream is dropped [SETTLE_MS] later and
 * the screen keeps exactly what it already says: `YOU REFUSED THIS`, `STOPPED`
 * and `Ask again`. No fault, no `did not finish` and no second vibration,
 * because a refusal is not a failure (section 5.17) and Maia did not observe
 * an ending to report.
 */
private fun settling(session: RunSession, now: Long): RunStep {
    if (!session.state.awaitingEnd) return RunStep(session)
    if (now - session.state.settlingSince < SETTLE_MS) {
        return RunStep(session, listOf(RunEffect.ScheduleTick(now + NOTHING_YET_MS)))
    }
    return RunStep(
        session.copy(
            state = session.state.copy(settlingSince = 0L),
            stream = session.stream.copy(open = false),
        ),
        listOfNotNull(if (session.stream.open) RunEffect.Unsubscribe else null),
    )
}

/**
 * The clock check: the ten second caption, and the silence rule.
 *
 * Both live here rather than in two timers because they are the same question
 * asked at two scales, and because a machine with one scheduled event is one a
 * test can drive by moving a long.
 */
private fun tick(session: RunSession, now: Long): RunStep {
    if (!session.state.live) return settling(session, now)
    // The inferred ending, checked before silence: a turn whose last step
    // stopped and whose grace ran out finished, and must not be cut. Not
    // while queued, because a step that ends there is the turn ahead of
    // ours ending, and not while blocked, because waiting on a human is
    // not finished.
    if (session.stream.stepEndedAt > 0 &&
        session.state.blocked == null &&
        session.state.status != RunStatus.Queued &&
        now - session.stream.stepEndedAt >= STEP_END_GRACE_MS
    ) {
        return finished(session)
    }
    val quiet = now - session.stream.lastFrameAt
    val limit = if (session.stream.reconnected) RECONNECT_GRACE_MS else SILENCE_MS
    if (quiet >= limit) {
        // Treated as dropped, on the same rule as a stream that ended.
        if (session.stream.reconnected) return cut(session)
        return RunStep(
            session.copy(stream = session.stream.copy(open = true, reconnected = true, lastFrameAt = now)),
            listOf(
                RunEffect.Unsubscribe,
                RunEffect.Subscribe(session.stream.lastEventId),
                RunEffect.ScheduleTick(now + RECONNECT_GRACE_MS),
            ),
        )
    }
    // The standing block, checked no more often than once every thirty
    // seconds. It is a correction and not a poll: the answer only ever
    // removes a row the user has no reason to look at.
    if (session.state.blocked != null && now - session.stream.lastReconcileAt >= RECONCILE_MS) {
        return RunStep(
            session.copy(stream = session.stream.copy(lastReconcileAt = now)),
            listOf(RunEffect.Reconcile, RunEffect.ScheduleTick(now + NOTHING_YET_MS)),
        )
    }
    val turn = session.state.turn
    val showCaption = turn != null && turn.empty && !session.state.nothingYet &&
        now - turn.startedAt >= NOTHING_YET_MS
    val state = if (showCaption) session.state.copy(nothingYet = true) else session.state
    return RunStep(
        session.copy(state = state),
        // It appears once and then nothing further changes. The next tick is
        // still scheduled, because the silence rule needs one.
        listOf(RunEffect.ScheduleTick(now + NOTHING_YET_MS)),
    )
}

/**
 * A human is looking at the run screen, so the row that exists to fetch one
 * has done its job. Sections 5.17 and 4.2.
 *
 * Only the row goes. [RunState.blocked] is untouched, because the agent is
 * still waiting and the screen the user is now looking at is where the two
 * controls are. [RunAlerts] cancels the notification and section 4.2's repeat
 * off the row going null, which is the same road every other ending takes.
 */
private fun seen(session: RunSession): RunStep {
    val state = session.state
    if (state.blockedRow == null && state.onScreen) return RunStep(session)
    return RunStep(session.copy(state = state.copy(blockedRow = null, onScreen = true)))
}

/**
 * The window went. Nothing is undone and nothing is put back.
 *
 * The row is not restored, because it was spent: a human looked at the two
 * controls. What this does change is the microphone, which
 * [micWanted] reads off [RunState.onScreen], and which Android would have
 * turned into silence the moment this process stopped being foreground.
 */
private fun hidden(session: RunSession): RunStep {
    if (!session.state.onScreen) return RunStep(session)
    return RunStep(session.copy(state = session.state.copy(onScreen = false)))
}

// ------------------------------------------------------------ the question

/**
 * One option row, tapped. Section 5.18.
 *
 * `CHOOSE ONE` sends on the tap, because a single-choice list where the
 * choice still has to be confirmed is two presses for one decision, which
 * section 5.10 settled for the project list. `CHOOSE ANY` ticks, because with
 * more than one allowed there is no tap that can be read as the last one.
 */
private fun option(session: RunSession, index: Int): RunStep {
    val asking = session.state.asking ?: return RunStep(session)
    if (asking.inFlight) return RunStep(session)
    val question = asking.current ?: return RunStep(session)
    val chosen = question.options.getOrNull(index) ?: return RunStep(session)
    if (!question.multiple) return answer(session, listOf(chosen.label), label(chosen.label))
    val ticked = if (index in asking.ticked) asking.ticked - index else asking.ticked + index
    return RunStep(session.copy(state = session.state.copy(asking = asking.copy(ticked = ticked))))
}

/**
 * `Send`, on the `CHOOSE ANY` case.
 *
 * A sentence the user spoke wins over the ticks when there is one, because
 * the two cannot both be the answer and the sentence is the later act. With
 * neither, nothing goes: `Send` is not drawn until something is ticked, and a
 * press that arrives anyway is not a reason to send an empty answer, which
 * means something else entirely.
 */
private fun sendAnswer(session: RunSession): RunStep {
    val asking = session.state.asking ?: return RunStep(session)
    if (asking.inFlight) return RunStep(session)
    val question = asking.current ?: return RunStep(session)
    val spoken = asking.heard?.trim()?.takeIf { it.isNotEmpty() && question.custom }
    if (spoken != null) return answer(session, listOf(spoken), spoken)
    val chosen = asking.ticked.sorted().mapNotNull { question.options.getOrNull(it)?.label }
    if (chosen.isEmpty()) return RunStep(session)
    return answer(session, chosen, chosen.joinToString(", ") { label(it) })
}

/**
 * One question answered, and either the next one or the send.
 *
 * Nothing leaves the phone until the last question has an answer, because
 * answers match questions by position and a partial list is a list of
 * unanswered questions at the end. So the haptic is here too and not on every
 * tap: [Schedule.agentSent] is an answer leaving the phone, and until the
 * last one it has not left.
 */
private fun answer(session: RunSession, answer: List<String>, shown: String): RunStep {
    val asking = session.state.asking ?: return RunStep(session)
    val next = asking.copy(
        answers = asking.answers + listOf(answer),
        shown = asking.shown + shown,
        at = asking.at + 1,
        ticked = emptyList(),
        heard = null,
        nothingHeard = false,
        listening = false,
    )
    val question = next.current
    if (question != null) {
        return RunStep(session.copy(state = session.state.copy(asking = next.copy(saying = question.custom))))
    }
    return send(session, next.copy(saying = false), Sending.Answer, next.answers)
}

/**
 * `Decide without me`: the empty answer list, which ends the whole request.
 *
 * Not one question of it. Answers match questions by position, so there is no
 * way to leave a gap in the middle without inventing a value for it, and
 * `m8_run_question_skip_cd` says as much out loud.
 */
private fun skipQuestion(session: RunSession): RunStep {
    val asking = session.state.asking ?: return RunStep(session)
    if (asking.inFlight) return RunStep(session)
    return send(session, asking.copy(saying = false), Sending.Skip, emptyList())
}

/** `Drop the question`: the rejection. The turn carries on either way. */
private fun dropQuestion(session: RunSession): RunStep {
    val asking = session.state.asking ?: return RunStep(session)
    if (asking.inFlight) return RunStep(session)
    return send(session, asking.copy(saying = false), Sending.Drop, null)
}

/**
 * Hand one of the three sends to the driver.
 *
 * The same `TICK` for all three, which looks wrong for a moment and is right:
 * section 4.6's vocabulary encodes ended against carries on, and every one of
 * these sends something and leaves the turn running. It is the hand's,
 * because the thumb that caused it is still on the glass.
 */
private fun send(
    session: RunSession,
    asking: Asking,
    sending: Sending,
    payload: List<List<String>>?,
): RunStep = RunStep(
    session.copy(
        state = session.state.copy(
            asking = asking.copy(sending = sending, outcome = null, sent = payload),
            answerFailed = null,
        ),
    ),
    listOf(
        if (sending == Sending.Drop) RunEffect.Drop else RunEffect.Reply,
        RunEffect.FeelByHand(Schedule.agentSent),
    ),
)

/** `Send it again`. The same thing goes, and the microphone does not open. */
private fun sendAgain(session: RunSession): RunStep {
    val asking = session.state.asking ?: return RunStep(session)
    val sending = asking.sending.takeIf { asking.failed } ?: return RunStep(session)
    return send(session, asking, sending, asking.sent)
}

/**
 * `Say it again`. The microphone opens and these words are replaced.
 *
 * The last answer given is the one replaced, which is the one the user is
 * looking at: this control only exists after a send that did not land, and by
 * then every question has an answer. Dropping it steps the screen back onto
 * the question it belonged to, with its options where they were.
 */
private fun sayAgain(session: RunSession): RunStep {
    val asking = session.state.asking ?: return RunStep(session)
    if (!asking.failed || asking.answers.isEmpty()) return RunStep(session)
    return RunStep(
        session.copy(
            state = session.state.copy(
                asking = asking.copy(
                    at = asking.at - 1,
                    answers = asking.answers.dropLast(1),
                    shown = asking.shown.dropLast(1),
                    ticked = emptyList(),
                    heard = null,
                    nothingHeard = false,
                    saying = true,
                    sending = null,
                    outcome = null,
                    sent = null,
                ),
                answerFailed = null,
            ),
        ),
    )
}

/** The capture started. Only now does the screen claim the microphone is open. */
private fun listening(session: RunSession): RunStep {
    val asking = session.state.asking ?: return RunStep(session)
    return RunStep(session.copy(state = session.state.copy(asking = asking.copy(listening = true))))
}

/** A partial. It replaces `m8_run_answer_listening`, which has done its job. */
private fun heard(session: RunSession, text: String): RunStep {
    val asking = session.state.asking ?: return RunStep(session)
    if (!asking.saying) return RunStep(session)
    return RunStep(
        session.copy(state = session.state.copy(asking = asking.copy(heard = text, nothingHeard = false))),
    )
}

/**
 * The endpointer fired, and this is the answer.
 *
 * **An utterance that is exactly an option label selects that option.** The
 * match is on the whole utterance and on the label alone, case ignored and
 * nothing else. "I think teal, probably" matches nothing and goes as free
 * text, which is the right answer both times: a looser match would be a guess
 * about which answer the user gave, and that is the one guess this product
 * cannot afford.
 *
 * The label matched against is the one on the screen, which is the one with
 * `(Recommended)` stripped, because that is the word the user read and
 * therefore the word they said. What goes on the wire is still the label as
 * the agent wrote it.
 */
private fun said(session: RunSession, text: String): RunStep {
    val asking = session.state.asking ?: return RunStep(session)
    if (!asking.saying) return RunStep(session)
    val question = asking.current ?: return RunStep(session)
    val spoken = text.trim()
    if (spoken.isEmpty()) return heardNothing(session)
    val match = question.options.indexOfFirst { label(it.label).equals(spoken, ignoreCase = true) }
    if (match >= 0) return option(session, match)
    // A sentence, on a question that takes several answers, cannot be
    // combined with the ticks and cannot be the last word either: the copy is
    // explicit that nothing goes on `CHOOSE ANY` until the user says so. So
    // it is held under `YOUR ANSWER` and `Send` sends it.
    if (question.multiple) {
        return RunStep(
            session.copy(
                state = session.state.copy(asking = asking.copy(heard = spoken, saying = false)),
            ),
        )
    }
    return answer(session, listOf(spoken), spoken)
}

/**
 * The microphone closed with nothing in it.
 *
 * The cancel pattern, because that one really did end with nothing sent, and
 * a caption saying the answers are still there: a microphone that closed with
 * nothing sent looks exactly like one that sent nothing on purpose, and a
 * user who thinks the moment has passed will not come back to it.
 */
private fun heardNothing(session: RunSession): RunStep {
    val asking = session.state.asking ?: return RunStep(session)
    if (!asking.saying) return RunStep(session)
    return RunStep(
        session.copy(
            state = session.state.copy(
                asking = asking.copy(saying = false, listening = false, nothingHeard = true, heard = null),
            ),
        ),
        listOf(RunEffect.FeelByHand(Schedule.agentStopped)),
    )
}

/**
 * The far end took it. The marker goes into the reply and the turn carries
 * straight on.
 *
 * Back to `WORKING`, which is the live region and therefore the announcement,
 * so section 5.18 needs no new announcement string here. Nothing is rewritten
 * in the shade either: every control for a question is on this screen, and
 * 5.17 cancelled the row the moment the screen was opened.
 */
private fun replied(session: RunSession): RunStep {
    val state = session.state
    val asking = state.asking ?: return RunStep(session)
    val mark = when (asking.sending) {
        Sending.Skip -> AnswerMark.LetItDecide
        Sending.Drop -> AnswerMark.Dropped
        else -> AnswerMark.Answered
    }
    return RunStep(
        session.copy(
            state = state.copy(
                status = if (state.status == RunStatus.WaitingForYou) RunStatus.Working else state.status,
                blocked = null,
                asking = null,
                blockedRow = null,
                answerFailed = null,
                turn = state.turn?.mark(mark, if (mark == AnswerMark.Answered) asking.shown else emptyList()),
            ),
        ),
    )
}

/**
 * The far end has no such question, which is not the same as saying it
 * stopped waiting.
 *
 * A question that genuinely expired and a reply that failed to find a live
 * one produce the same 404, and Maia cannot tell them apart. So the marker
 * reports the transaction and not the agent's state, the controls stay, and
 * `Send it again` is offered: a second attempt on a question that really is
 * gone costs one tap, and on an answer that lost its way it works.
 */
private fun replyGone(session: RunSession): RunStep {
    val state = session.state
    val asking = state.asking ?: return RunStep(session)
    return RunStep(
        session.copy(
            state = state.copy(
                asking = asking.copy(outcome = Outcome.Gone),
                turn = state.turn?.mark(AnswerMark.NotTaken),
            ),
        ),
        listOf(RunEffect.FeelByHand(Schedule.fault)),
    )
}

/**
 * The reply never left the phone.
 *
 * `m8_run_answer_undelivered` with one of section 5.4's four reasons, which
 * is 5.17's caption reused unchanged, and **what the user chose or said stays
 * on the screen**. That is the difference from the permission case: a
 * permission is one button that can be pressed again, and an answer is a
 * choice already made or a sentence already spoken.
 */
private fun replyFailed(session: RunSession, loss: RunLoss): RunStep {
    val state = session.state
    val asking = state.asking ?: return RunStep(session)
    return RunStep(
        session.copy(
            state = state.copy(
                asking = asking.copy(outcome = Outcome.Undelivered),
                answerFailed = loss,
            ),
        ),
        listOf(RunEffect.FeelByHand(Schedule.fault)),
    )
}

// ------------------------------------------------------------------- outcomes

/**
 * A stop the user asked for is not a failure (section 3.2), so the orb goes to
 * `Dormant` and not to `Fault`, and the end marker says `YOU STOPPED THIS`
 * rather than `STOPPED HERE`.
 */
private fun interrupted(session: RunSession): RunStep = RunStep(
    session.copy(
        state = session.state.copy(
            status = RunStatus.Stopped,
            blocked = null,
            blockedRow = null,
            asking = null,
            settlingSince = 0L,
            stopFailed = null,
            turn = session.state.turn?.let { it.copy(end = it.end ?: EndMarker.Stopped) },
        ),
        stream = session.stream.copy(open = false),
    ),
    listOf(
        RunEffect.Unsubscribe,
        // One low, soft thump and nothing after it: the only single THUD in
        // the product, and the cancel haptic PRD section 11 says is undefined.
        RunEffect.Feel(Schedule.agentStopped),
        RunEffect.Say(AgentAck.Stopped),
    ),
)

/**
 * Nothing was sent. Four of these are spoken, because the user just spoke and
 * is owed an answer within the same breath, and because all four mean the same
 * practical thing: nothing left the phone, so say so now rather than let them
 * wait for a reply that is never coming.
 *
 * The widening below, where a fault arriving after `Admitted` sets an
 * [EndMarker] whatever the cause was, has been checked against the copy as it
 * now stands. Section 1.2 confirms it: `m8_run_end_marker_cut` and
 * `m8_run_cut_announce` name no cause, so they are true of every mid-run loss
 * and there is nothing here to add a second string for. The one place a cause
 * is allowed to be carried is `m8_notif_failed_body` in section 5.4, which
 * writes out five reason sentences, one per [RunLoss]. If a sixth way to lose
 * a run appears, that is the string to extend, not this function.
 */
private fun failed(session: RunSession, event: RunEvent.Failed): RunStep {
    val ack = when (event.fault) {
        RunFault.TunnelOff -> AgentAck.TunnelOff
        RunFault.NoServer -> AgentAck.NoAnswer
        RunFault.Refused -> AgentAck.Refused
        RunFault.NoProject, RunFault.Retired -> AgentAck.NoProject
        // The turn already started, so this arrives on a later clock and rule
        // 10 forbids speaking it. Felt and shown, never said.
        RunFault.TurnFailed -> null
    }
    // Whether a run was actually lost, which is a different question from
    // whether something went wrong. Before `Admitted` the instruction has not
    // left the phone: the status is still `Sending`, nothing is running, and
    // there is no turn for a shade to report the end of. After it, the same
    // fault means a run the user is waiting on has stopped, and section 5.4
    // gives that a sentence.
    val loss = if (session.state.status == RunStatus.Sending) {
        null
    } else {
        when (event.fault) {
            RunFault.TunnelOff -> RunLoss.Tunnel
            RunFault.NoServer -> RunLoss.NoAnswer
            RunFault.Refused -> RunLoss.Refused
            RunFault.TurnFailed -> RunLoss.AgentError
            // Neither can arise once a turn is running: both are decided
            // against the registry before anything is sent.
            RunFault.NoProject, RunFault.Retired -> null
        }
    }
    return RunStep(
        session.copy(
            state = session.state.copy(
                status = RunStatus.DidNotFinish,
                blocked = null,
                blockedRow = null,
                asking = null,
                settlingSince = 0L,
                fault = event.fault,
                retired = event.entry,
                // The marker goes wherever a running turn stopped, not only
                // where the agent was the one that broke. Section 5.4: the tap
                // opens the run screen scrolled to `STOPPED HERE`, for all
                // five reasons, because the partial reply is the most useful
                // thing the user owns at that moment.
                turn = if (loss != null) {
                    session.state.turn?.let { it.copy(end = it.end ?: EndMarker.Cut) }
                } else {
                    session.state.turn
                },
                loss = session.state.loss ?: loss,
            ),
            stream = session.stream.copy(open = false),
        ),
        listOfNotNull(
            if (session.stream.open) RunEffect.Unsubscribe else null,
            RunEffect.Feel(Schedule.fault),
            ack?.let { RunEffect.Say(it, event.number) },
        ),
    )
}

/**
 * The list or the fault screen is closed, and what it was holding goes.
 *
 * No effects, and in particular nothing spoken and nothing felt: the user just
 * pressed a control, so they know what they did, and rule 10's "answer in the
 * same breath" is about answering a sentence, not narrating a tap. The held
 * instruction is forgotten here and nowhere else, because the chooser is the
 * only place it was.
 *
 * The status is left alone. A discard on the list does not end a run: there
 * was no run, which is why the screen exists.
 */
private fun dismissed(session: RunSession): RunStep = RunStep(
    session.copy(state = session.state.copy(chooser = null, fault = null, retired = null)),
    emptyList(),
)

/**
 * The numbered list. Maia will not choose, and says so to the user's face:
 * `m8_ambiguous_body`. The microphone is open on that screen, which is the
 * whole reason a number is the primary key, so the list is not a dead end.
 */
private fun choose(session: RunSession, event: RunEvent.Choose): RunStep = RunStep(
    session.copy(
        state = session.state.copy(
            chooser = event.chooser,
            fault = if (event.chooser.badNumber != null) RunFault.NoProject else null,
        ),
    ),
    listOf(RunEffect.Say(event.ack, event.chooser.badNumber)),
)

/**
 * The user pressed an answer control, on the shade or in the footer.
 *
 * The screen does not move. Section 5.3: no label promises the press is the
 * end of it, because on a locked phone the press is followed by a keyguard
 * and only then by the send, and what reports is the outcome. The only thing
 * cleared is a previous failure, because the user is trying again and a
 * caption about the last attempt is now about nothing.
 */
private fun answer(session: RunSession, event: RunEvent.Answer): RunStep {
    if (session.state.blocked == null) return RunStep(session)
    return RunStep(
        session.copy(state = session.state.copy(answerFailed = null)),
        listOf(RunEffect.Answer(event.reply, event.note)),
    )
}

/**
 * The far end took the answer. Two outcomes, drawn differently, because only
 * one of them ends the turn (section 5.17).
 *
 * **The stream is not closed on a refusal**, and that is the load-bearing
 * line here. `m8_notif_blocked_answered_refuse` promises that Maia tells the
 * user when the run has ended, and only the stream can produce that. "The run
 * ends there" is what a refusal does and not something Maia has watched
 * happen, so the marker goes down, the status goes to `STOPPED`, and the
 * ending itself is still reported by whichever event the far end sends.
 */
private fun answered(session: RunSession, event: RunEvent.Answered, now: Long): RunStep {
    val state = session.state
    val turn = state.turn
    if (event.reply == PermissionReply.REJECT) {
        return RunStep(
            session.copy(
                state = state.copy(
                    status = RunStatus.Stopped,
                    blocked = null,
                    blockedRow = BlockedNotice.Refused,
                    answerFailed = null,
                    turn = turn?.copy(
                        end = turn.end ?: EndMarker.Refused,
                        refusal = event.note?.takeIf { it.isNotBlank() },
                    ),
                    // The turn is over for the user and not for the wire, and
                    // this is the field that says so. `Stopped` is not
                    // [RunState.live], so without it the foreground service
                    // goes down inside the window the body's own promise has
                    // to be kept in.
                    settlingSince = now,
                ),
            ),
            listOf(
                // The cancel pattern, unchanged. A refusal ends the turn, so
                // it is the same class of event as `Stop` landing and the
                // hand is told the same thing (section 4.6).
                RunEffect.FeelByHand(Schedule.agentStopped),
                // The clock check keeps running through the wait, because
                // the wait is what it now bounds. Ticks stop at the ending,
                // whichever of the two arrives.
                RunEffect.ScheduleTick(now + NOTHING_YET_MS),
            ),
        )
    }
    return RunStep(
        session.copy(
            state = state.copy(
                // Back to `WORKING`, which is the live region and therefore
                // the screen reader announcement, so 5.17 needs no new string.
                status = RunStatus.Working,
                blocked = null,
                blockedRow = BlockedNotice.Allowed,
                answerFailed = null,
                turn = turn?.mark(AnswerMark.Allowed),
            ),
        ),
        listOf(
            // The instruction-admitted pattern, unchanged: something the hand
            // just did left the phone and the thing carries on.
            RunEffect.FeelByHand(Schedule.agentSent),
        ),
    )
}

/**
 * The far end is not waiting any more. One marker, two arrivals.
 *
 * [RunAnnounce.TooLate] followed a press and is felt, because what the user
 * pressed did not happen. [RunAnnounce.Withdrawn] followed nothing: the row
 * is being corrected rather than raised, so it is silent in the hand as well
 * as in the shade.
 *
 * The status leaves `WAITING FOR YOU` and the turn decides the rest. If the
 * run carries on, `Stop` comes back under the marker; if it ended, the
 * ordinary end marker closes the reply below it.
 */
private fun stoppedWaiting(session: RunSession, announce: RunAnnounce): RunStep {
    val state = session.state
    if (state.blocked == null) return RunStep(session)
    val late = announce == RunAnnounce.TooLate
    return RunStep(
        session.copy(
            state = state.copy(
                status = if (state.status == RunStatus.WaitingForYou) RunStatus.Working else state.status,
                blocked = null,
                // The question went with it. Unlike the 404 on a reply, this
                // is something Maia observed: the request is not in the
                // pending list, so `IT STOPPED WAITING` is a claim it can
                // make. Section 5.18 withholds those words from the reply
                // path and not from this one.
                asking = null,
                blockedRow = if (late) BlockedNotice.Gone else BlockedNotice.Stale,
                answerFailed = null,
                announce = announce,
                turn = state.turn?.mark(AnswerMark.StoppedWaiting),
            ),
        ),
        listOfNotNull(
            if (late) RunEffect.FeelByHand(Schedule.fault) else null,
        ),
    )
}

/**
 * The answer never left the phone. Nothing about the run changed, so nothing
 * about the screen changes except the caption: the block stands, and the two
 * controls stay exactly where they are, because the press can be repeated and
 * moving a control the user is about to press again is its own small cruelty.
 */
private fun answerFailed(session: RunSession, event: RunEvent.AnswerFailed): RunStep = RunStep(
    session.copy(
        state = session.state.copy(
            answerFailed = event.loss,
            blockedRow = BlockedNotice.Undelivered,
        ),
    ),
    listOf(RunEffect.FeelByHand(Schedule.fault)),
)

/**
 * `interrupt` never reached the machine. The run carries on, `Stop` stays
 * exactly where it is, and nothing marks the reply, because nothing happened
 * to it (section 5.12).
 */
private fun stopFailed(session: RunSession, event: RunEvent.StopFailed): RunStep = RunStep(
    session.copy(state = session.state.copy(stopFailed = event.loss)),
    listOf(RunEffect.FeelByHand(Schedule.fault), RunEffect.Say(AgentAck.StopNotSent)),
)

/**
 * The far end says there was nothing running.
 *
 * No screen change at all: the turn has already ended, so the screen is
 * already showing `END OF REPLY` or `STOPPED HERE` with the matching status
 * label, and that is the fuller version of the same news.
 */
private fun stopTooLate(session: RunSession): RunStep = RunStep(
    session,
    listOf(RunEffect.FeelByHand(Schedule.fault), RunEffect.Say(AgentAck.StopTooLate)),
)

// --------------------------------------------------------------------- helpers

/**
 * The current turn joins the earlier ones when the screen moves on.
 *
 * A turn that was still live is marked cut, not done: the stream is about to
 * be closed under it and what is on screen is what got through. Rule 12 again,
 * and the reason this is one function rather than three copies of a `copy`.
 */
private fun closeOut(state: RunState): List<Turn> {
    val turn = state.turn ?: return state.earlier
    return state.earlier + (turn.end?.let { turn } ?: turn.copy(end = EndMarker.Cut))
}

/**
 * Append, never replace.
 *
 * A delta extends the tail prose rather than starting a new piece, which is
 * appending to it. Text already rendered is never re-laid-out, because that
 * moves a screen reader's accessibility focus mid-read.
 */
private fun Turn.append(text: String): Turn {
    if (text.isEmpty()) return this
    val last = pieces.lastOrNull()
    return if (last is ReplyPiece.Prose) {
        copy(pieces = pieces.dropLast(1) + ReplyPiece.Prose(last.text + text))
    } else {
        copy(pieces = pieces + ReplyPiece.Prose(text))
    }
}

/**
 * One inline marker, appended where it happened in the stream. [lines] is the
 * answers under it, one per question, and is empty for every marker but
 * `YOU ANSWERED`.
 */
private fun Turn.mark(mark: AnswerMark, lines: List<String> = emptyList()): Turn =
    copy(pieces = pieces + ReplyPiece.Answered(mark, lines))

/**
 * The question, at the point in the stream where it arrived.
 *
 * Every question of the request in one piece, blank line between, because
 * they arrived in one frame and the copy has the user read them all before
 * answering the first. The header is not printed: section 5.18 rejects it,
 * and thirty characters of the agent's own summary above its own question is
 * the agent talking about itself twice.
 */
private fun Turn.asked(asking: Asking): Turn =
    copy(pieces = pieces + ReplyPiece.Asked(asking.questions.joinToString("\n\n") { it.question }))

/**
 * A run of the same tool collapses: three consecutive `read` calls render as
 * one line reading `read x3`. Consecutive, and only consecutive, because the
 * lines are in the order things happened and reordering them to group would be
 * a different claim about the run.
 */
private fun Turn.tool(name: String, target: String?): Turn {
    val last = pieces.lastOrNull()
    if (last is ReplyPiece.Tool && last.name == name && last.target == target) {
        return copy(pieces = pieces.dropLast(1) + last.copy(count = last.count + 1))
    }
    return copy(pieces = pieces + ReplyPiece.Tool(name, target))
}
