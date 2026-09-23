package dev.maia.app.agent

import dev.maia.app.feel.Pattern
import dev.maia.nlu.agent.ProjectRef
import dev.maia.transport.AgentClient
import dev.maia.transport.AgentEvent
import dev.maia.transport.AgentException
import dev.maia.transport.Delivery
import dev.maia.transport.EventListener
import dev.maia.transport.ReplyOutcome
import dev.maia.transport.PermissionReply
import dev.maia.transport.ProjectEntry
import dev.maia.transport.ProjectState
import dev.maia.transport.Session
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.Executor

/**
 * How many event ids [AgentDriver.seenIds] remembers.
 *
 * The set exists for the seconds around a resubscribe, when history and the
 * new stream can both carry the same event. A turn can emit far more ids
 * than this over its life, but a duplicate that arrives five hundred events
 * late is not one the overlap can produce, so the eldest is simply dropped.
 */
private const val SEEN_IDS_MAX = 512

/**
 * A spoken sentence, all the way to text on a screen.
 *
 * The three pieces this joins already exist and none of them knew about the
 * others: `:core-nlu` turns words into a [ProjectRef], the registry turns a
 * number into a directory, and `:transport`'s [AgentClient] turns a directory
 * and an instruction into a session and a stream. This is the only class that
 * holds all three, and [reduceRun] is where every decision about what the
 * screen says actually lives. What is here is the parts a pure function cannot
 * do: sockets, threads and a clock.
 *
 * **One project streams at a time** (PRD section 13 answer 1). There is one
 * [stream] field, and opening a new one closes the old one. The agent behind
 * the old one keeps running, which is what `m8_run_left_behind` tells the user
 * and the one thing they could reasonably get wrong.
 *
 * **No agent text reaches [speak].** [speak] takes an [AgentAck] and two
 * integers, and there is nothing in this class that could hand it a string:
 * see [AgentAck] and `AgentSpeechInvariantTest`.
 *
 * Every public method returns at once and does its work on [work]. Nothing
 * here touches a `Looper`, which is what makes it a JVM test.
 */
class AgentDriver(
    private val client: AgentClient,
    /** The registry, re-read each turn: a project can be added while the app runs. */
    private val projects: () -> List<ProjectEntry>,
    private val render: (RunState) -> Unit,
    private val speak: (AgentAck, Int?, Int?) -> Unit,
    private val feel: (Pattern) -> Unit,
    /**
     * Section 4.6's patterns, which are `USAGE_TOUCH` whatever they are.
     *
     * A second sink and not a flag on the first, because [feel] routes on the
     * pattern alone and `Schedule.fault` is the channel's vibration on one
     * surface and the hand's on this one. No default: three call sites, and
     * each of them should have to say where a hand pattern goes rather than
     * inherit silence.
     */
    private val feelByHand: (Pattern) -> Unit,
    /**
     * Opens and closes the microphone for an agent's question. Section 5.18.
     *
     * A `Boolean` and nothing else. The obvious extra argument would be the
     * option labels as hotwords, and they are exactly what rule 12 keeps out
     * of an effect: they arrived on the event stream. So the recogniser is
     * not biased towards them, and the strict match in [reduceRun] is done on
     * whatever it heard.
     */
    private val listen: (Boolean) -> Unit = {},
    private val clock: () -> Long,
    private val work: Executor,
    /** Delivers [RunEvent.Tick] after a delay. Its own thread, never [work]'s. */
    private val timer: (Long, () -> Unit) -> Unit,
) : Closeable {

    private val lock = Any()
    private var session: RunSession = RunSession()

    /** The one open stream, and the directory and session it belongs to. */
    private var stream: Closeable? = null
    private var agentSession: Session? = null
    private var directory: String? = null

    /**
     * Ids of events already handed to the machine, so one never arrives
     * twice.
     *
     * The reconnect path replays `GET /api/session/{id}/history` and then
     * opens a fresh stream, and nothing promises those two sources are
     * disjoint: an event emitted inside the gap can sit in the snapshot and
     * still come down the new stream, and a dying stream can deliver one
     * more frame while its replacement is already up. The machine prints
     * text by appending deltas, so a duplicate is not cosmetic, it is a
     * reply printed twice.
     *
     * Bounded because a turn can emit thousands of these and the window a
     * duplicate could travel through is the seconds around a resubscribe.
     * Insertion order is kept precisely so the eldest id is the one evicted.
     */
    private val seenIds = LinkedHashSet<String>()

    /** Rises for each scheduled tick so a stale one cannot fire into a new turn. */
    private var generation: Int = 0

    // ------------------------------------------------------------ the mouth

    /**
     * An instruction, addressed or not.
     *
     * [spoken] is the transcript, for `YOU SAID` on the chooser. [instruction]
     * is the user's own words: the only string that leaves this class, and it
     * came from the phone's microphone rather than from an agent.
     */
    fun instruct(ref: ProjectRef, instruction: String, spoken: String) = work.execute {
        when (val where = resolve(ref, spoken, held = instruction)) {
            is Where.Go -> send(where.project, instruction)
            is Where.Ask -> apply(where.event)
        }
    }

    /** Moves the stream to another project without sending anything. */
    fun focus(ref: ProjectRef, spoken: String) = work.execute {
        when (val where = resolve(ref, spoken, held = null)) {
            is Where.Go -> {
                val left = synchronized(lock) { session.state.project?.number?.takeIf { it != where.project.number } }
                apply(RunEvent.Focus(where.project, left))
            }
            is Where.Ask -> apply(where.event)
        }
    }

    /**
     * Stop.
     *
     * `interrupt` is not an undo: what the agent has already done to the
     * user's files stays done, which is what `m8_run_stop_cd` says out loud to
     * a screen reader. A stop with nothing running is not an error and says
     * nothing, because the user asking twice is not a fault.
     */
    fun stop() = work.execute {
        val target = synchronized(lock) { agentSession.takeIf { session.state.live } } ?: return@execute
        try {
            client.interrupt(target)
            apply(RunEvent.Interrupted)
        } catch (e: IOException) {
            // Not `Failed`. A stop that did not go through has not ended the
            // run and has not lost it: the agent is still working, `Stop` is
            // still the right control, and a fault screen over a run that is
            // still going would be a lie (section 5.12).
            apply(RunEvent.StopFailed(lossOf(e)))
        } catch (e: AgentException) {
            // 404 is the server saying there was nothing to interrupt.
            if (e.status == 404) apply(RunEvent.StopTooLate) else apply(RunEvent.StopFailed(lossOf(e)))
        }
    }

    /**
     * An answer to a blocked agent, from either surface.
     *
     * Public because both surfaces reach it: the run screen through
     * [dev.maia.app.screens.RunAction], and the shade through [RunService].
     * Neither passes a request id, because neither has one: the id arrived on
     * the event stream and lives on the state, which is where this reads it.
     *
     * A blank note is no note. An empty string travelling to the agent as
     * feedback is worse than nothing, because "the user said nothing" and
     * "the user was not asked" look the same at the other end.
     */
    fun answer(reply: PermissionReply, note: String? = null) = work.execute {
        apply(RunEvent.Answer(reply, note?.takeIf { it.isNotBlank() }))
    }

    /**
     * The run screen is in front of the user.
     *
     * Sections 5.17 and 4.2: the blocked row is cancelled when the run screen
     * is opened on that project, and the one repeating pattern in this product
     * stops repeating as soon as a human is demonstrably present. Called from
     * the window, on every resume, so a tap on the notification and a user who
     * walked back into the app both count as present.
     *
     * Nothing is sent anywhere. The block itself is untouched: the agent is
     * still waiting and the controls are on the screen being looked at.
     */
    fun seen() = work.execute { apply(RunEvent.Seen) }

    /**
     * The run screen went away.
     *
     * The blocked row is not put back, because it was spent. What this does
     * close is the microphone: Android hands a capture started from the
     * background silence rather than an error, and a caption reading
     * `The microphone is open` over a phone in a pocket is a lie the platform
     * will not correct for us.
     */
    fun hidden() = work.execute { apply(RunEvent.Hidden) }

    // ------------------------------------------- section 5.18, the question

    /** One option row, tapped. Sends on `CHOOSE ONE`, ticks on `CHOOSE ANY`. */
    fun option(index: Int) = work.execute { apply(RunEvent.Option(index)) }

    /** `Send`, on `CHOOSE ANY`. */
    fun sendAnswer() = work.execute { apply(RunEvent.SendAnswer) }

    /** `Decide without me`: the empty answer list, which ends the request. */
    fun skipQuestion() = work.execute { apply(RunEvent.SkipQuestion) }

    /** `Drop the question`: the rejection. The turn carries on. */
    fun dropQuestion() = work.execute { apply(RunEvent.DropQuestion) }

    /** `Send it again`, after a reply that did not land. */
    fun sendAgain() = work.execute { apply(RunEvent.SendAgain) }

    /** `Say it again`: the last answer is dropped and the microphone reopens. */
    fun sayAgain() = work.execute { apply(RunEvent.SayAgain) }

    /** The capture is running. Only now does the screen say the microphone is open. */
    fun listening() = work.execute { apply(RunEvent.Listening) }

    /** A partial transcript. */
    fun heard(text: String) = work.execute { apply(RunEvent.Heard(text)) }

    /** A final transcript, which is either an option label or the answer itself. */
    fun said(text: String) = work.execute { apply(RunEvent.Said(text)) }

    /** The microphone closed with nothing in it. */
    fun heardNothing() = work.execute { apply(RunEvent.HeardNothing) }

    /**
     * The project list or a fault screen was dismissed.
     *
     * Section 5.10's `Discard`, and the way off a fault screen that has
     * nothing else to offer. It runs on [work] like every other entry point,
     * so the held instruction is dropped on the same thread that would have
     * sent it and the two cannot race.
     */
    fun dismiss() = work.execute { apply(RunEvent.Dismissed) }

    /** What the screen currently holds, for a surface that has just been created. */
    fun current(): RunState = synchronized(lock) { session.state }

    override fun close() {
        synchronized(lock) {
            generation++
            runCatching { stream?.close() }
            stream = null
        }
    }

    // ------------------------------------------------------------- resolving

    private sealed interface Where {
        class Go(val project: ProjectEntry) : Where
        class Ask(val event: RunEvent) : Where
    }

    /**
     * A [ProjectRef] and a registry become a directory, or a list.
     *
     * Maia will not choose. The grammar has already decided whether a name was
     * said in full, and this only turns that decision into a screen: a name
     * that matched several lists several, a name that matched none lists all
     * of them, and a number that is not in the registry is its own fault
     * screen because it is a different mistake and deserves a different
     * answer.
     */
    private fun resolve(ref: ProjectRef, spoken: String, held: String?): Where {
        val all = projects().filter { it.state == ProjectState.ACTIVE }.sortedBy { it.number }
        fun ask(matches: List<ProjectEntry>, ack: AgentAck, bad: Int? = null) =
            Where.Ask(RunEvent.Choose(Chooser(spoken, matches, all, held, bad), ack))

        return when (ref) {
            is ProjectRef.Numbered -> byNumber(ref.number, all)
            is ProjectRef.Named -> byNumber(ref.number, all)
            is ProjectRef.Ambiguous ->
                ask(all.filter { it.number in ref.candidates }, AgentAck.WhichOne)
            is ProjectRef.Unknown -> ask(emptyList(), AgentAck.WhichProject)
            ProjectRef.Current -> {
                val current = synchronized(lock) { session.state.project }
                // Section 13 answer 3: an unaddressed sentence goes to the
                // current session. With no current session there is nothing to
                // guess at, and guessing at which project would be the one
                // thing principle B forbids.
                current?.let { Where.Go(it) } ?: ask(emptyList(), AgentAck.WhichProject)
            }
        }
    }

    private fun byNumber(number: Int, all: List<ProjectEntry>): Where {
        val hit = projects().firstOrNull { it.number == number }
        return when {
            hit == null -> Where.Ask(
                RunEvent.Choose(Chooser("$number", emptyList(), all, null, number), AgentAck.NoProject),
            )
            // Allocated once and never reused, so a retired number is a real
            // answer and not a miss: the project is gone, and saying which one
            // it was is more use than a list.
            hit.state == ProjectState.RETIRED ->
                Where.Ask(RunEvent.Failed(RunFault.Retired, number, hit))
            else -> Where.Go(hit)
        }
    }

    // ------------------------------------------------------------- the turn

    private fun send(project: ProjectEntry, instruction: String) {
        val left = synchronized(lock) { session.state.project?.number?.takeIf { it != project.number } }
        apply(RunEvent.Send(project, instruction, left))
    }

    /** Runs one event through [reduceRun] and then its effects, in order. */
    private fun apply(event: RunEvent) {
        val step = synchronized(lock) {
            val next = reduceRun(session, event, clock())
            session = next.session
            next
        }
        render(step.session.state)
        for (effect in step.effects) perform(effect)
    }

    private fun perform(effect: RunEffect) {
        when (effect) {
            is RunEffect.Subscribe -> subscribe(effect.after)
            RunEffect.Unsubscribe -> synchronized(lock) {
                runCatching { stream?.close() }
                stream = null
            }
            is RunEffect.Prompt -> prompt(effect)
            RunEffect.Interrupt -> Unit
            is RunEffect.Feel -> feel(effect.pattern)
            is RunEffect.FeelByHand -> feelByHand(effect.pattern)
            is RunEffect.Answer -> answer(effect)
            RunEffect.Reply -> reply()
            RunEffect.Drop -> drop()
            RunEffect.Listen -> listen(true)
            RunEffect.Deafen -> listen(false)
            RunEffect.Reconcile -> reconcile()
            is RunEffect.Say -> speak(effect.ack, effect.number, effect.other)
            is RunEffect.ScheduleTick -> armTick(effect.atMs)
        }
    }

    /**
     * Opens or reopens the stream for the current project.
     *
     * Subscribing needs a session, and creating one is a request that can fail
     * in all three of section 9's ways, so this is where a turn most often
     * dies before it starts. It dies with a named fault and not a stack trace.
     *
     * On a reconnect, [after] is the last event id the machine saw and the
     * gap behind it is backfilled from history before the new stream opens.
     * The global event route takes no resume cursor, so without that fetch
     * every delta emitted while the link was down is gone for good, which on
     * the pinned server includes the `session.next.step.ended` a turn's end
     * is inferred from.
     */
    private fun subscribe(after: String?) {
        val project = synchronized(lock) { session.state.project } ?: return
        try {
            val target = sessionFor(project)
            val listener = object : EventListener {
                /**
                 * The stall rule, and the whole of it.
                 *
                 * PRD section 9 is written in frames of any kind, not in
                 * events: a heartbeat comment, a frame that parses and a
                 * frame that does not are equally proof the link is there,
                 * and [EventListener.onAlive] is that primitive. Resetting
                 * the timer here and nowhere else is what makes an unknown
                 * event type look alive rather than dead, which is the
                 * shape a stream of types written after this phone shipped
                 * will have.
                 *
                 * This runs on the stream's reader thread, so it notes the
                 * time and does nothing else.
                 */
                override fun onAlive() = apply(RunEvent.Heartbeat)

                override fun onEvent(event: AgentEvent) {
                    // A replayed event and a live one can carry the same id:
                    // the backfill and the new stream overlap by a few
                    // seconds at most, but a duplicated delta is a reply
                    // printed twice, so a second arrival goes no further.
                    if (markSeen(event.id)) apply(RunEvent.Arrived(event))
                }

                override fun onClosed(reason: String) = apply(RunEvent.StreamClosed(reason))
            }
            if (after != null) {
                backfill(target, after, listener)
                // The replay may have carried the turn's own ending, and a
                // turn that is over never arms the clock checks that would
                // close a stream: subscribing anyway would leave one open
                // and heartbeating forever on nothing.
                if (!synchronized(lock) { session.state.live }) return
            }
            val handle = client.sessionEvents(target, listener, after = after)
            synchronized(lock) {
                if (stream !== handle) runCatching { stream?.close() }
                stream = handle
            }
        } catch (e: IOException) {
            apply(RunEvent.Failed(faultOf(e), project.number))
        } catch (e: AgentException) {
            apply(RunEvent.Failed(faultOf(e), project.number))
        }
    }

    /**
     * Replays what a dropped stream missed, ahead of the new subscription.
     *
     * [after] is an anchor, not a hint: only events strictly after it are
     * replayed, and only when it is actually found in the history. An anchor
     * that is not there cannot be told apart from a history window that does
     * not reach back far enough, and guessing at a position risks printing a
     * delta twice, which is the exact failure this path exists to prevent.
     * Nothing is replayed in that case.
     *
     * Delivery goes through [listener], the same object the live stream is
     * about to get, so a replayed event takes the same dedupe, bookkeeping
     * and reduction a live one would. Delivering this way also records each
     * replayed id in [seenIds], which is what drops the copy if the server
     * sends it again on the new stream.
     *
     * Every failure lands the same way: no replay, and the stream still
     * opens. This is recovery, and a recovery that could fail the turn would
     * be worse than the drop it was treating, which is why this catches
     * everything rather than the two failures the rest of the class names.
     */
    private fun backfill(target: Session, after: String, listener: EventListener) {
        val missed = try {
            val history = client.history(target)
            val anchor = history.indexOfFirst { it.id == after }
            if (anchor < 0) return
            history.subList(anchor + 1, history.size)
        } catch (e: Exception) {
            return
        }
        for (event in missed) listener.onEvent(event)
    }

    /**
     * Notes an event id as delivered. False means it already was.
     *
     * Null ids pass untouched: a frame with no id cannot be a replayed
     * duplicate, because replay matches on ids alone.
     */
    private fun markSeen(id: String?): Boolean {
        if (id == null) return true
        synchronized(lock) {
            if (!seenIds.add(id)) return false
            if (seenIds.size > SEEN_IDS_MAX) {
                val eldest = seenIds.iterator()
                eldest.next()
                eldest.remove()
            }
            return true
        }
    }

    /**
     * An answer to a blocked agent, and the three things that can become of
     * it.
     *
     * The request id is read here rather than carried on the effect. It is a
     * string that arrived on the event stream, and effects are the one place
     * agent-supplied text is not allowed to go: see [AgentAck] and
     * `AgentSpeechInvariantTest`. It is on the state already, which is where
     * everything else off the stream lives, and reading it under the lock a
     * moment before the call also means the id sent is the one the screen is
     * currently showing.
     *
     * The three outcomes stay three all the way to three screens. `ACCEPTED`
     * and `GONE` are what the server said; an `IOException` is the phone
     * failing to ask, which is neither of those and is the one case where the
     * block is still standing and the controls stay.
     */
    private fun answer(effect: RunEffect.Answer) {
        val target = synchronized(lock) { agentSession } ?: return
        val requestId = synchronized(lock) { session.state.blocked?.requestId }
        // Section 5.3: a block whose id never arrived is still shown, because
        // knowing an agent is waiting beats knowing nothing. It just cannot
        // be answered, and inventing an id to send would answer some other
        // request.
        if (requestId.isNullOrEmpty()) return
        try {
            when (client.replyPermission(target, requestId, effect.reply, effect.note)) {
                ReplyOutcome.ACCEPTED -> apply(RunEvent.Answered(effect.reply, effect.note))
                ReplyOutcome.GONE -> apply(RunEvent.AnswerTooLate)
            }
        } catch (e: IOException) {
            apply(RunEvent.AnswerFailed(lossOf(e)))
        } catch (e: AgentException) {
            apply(RunEvent.AnswerFailed(lossOf(e)))
        }
    }

    /**
     * The answers to an agent's question, or the empty list that lets it
     * decide. Section 5.18.
     *
     * Every string sent is read off the state under the lock, for the same
     * reason the permission's request id is: the labels arrived on the event
     * stream, and an effect is the one place stream text may not travel. What
     * goes on the wire is the label exactly as the agent wrote it, marker and
     * all, because the reply names labels rather than indices and a shortened
     * label answers a different question.
     *
     * `GONE` is the 404, and it is deliberately not reported as the agent
     * having moved on. A question that really did expire and a reply that
     * arrived at a session which no longer holds it produce the same status,
     * and Maia cannot see which.
     */
    private fun reply() {
        val target = synchronized(lock) { agentSession } ?: return
        val request = synchronized(lock) {
            val id = session.state.blocked?.requestId
            val answers = session.state.asking?.sent
            if (id.isNullOrEmpty() || answers == null) null else id to answers
        } ?: return
        try {
            when (client.replyQuestion(target, request.first, request.second)) {
                ReplyOutcome.ACCEPTED -> apply(RunEvent.Replied)
                ReplyOutcome.GONE -> apply(RunEvent.ReplyGone)
            }
        } catch (e: IOException) {
            apply(RunEvent.ReplyFailed(lossOf(e)))
        } catch (e: AgentException) {
            apply(RunEvent.ReplyFailed(lossOf(e)))
        }
    }

    /**
     * `Drop the question`: the tool fails and the turn keeps running.
     *
     * The same three outcomes as [reply], because from the phone's side this
     * is the same transaction: it either landed, or the far end had nothing
     * by that id, or it never left.
     */
    private fun drop() {
        val target = synchronized(lock) { agentSession } ?: return
        val requestId = synchronized(lock) { session.state.blocked?.requestId }
        if (requestId.isNullOrEmpty()) return
        try {
            when (client.rejectQuestion(target, requestId)) {
                ReplyOutcome.ACCEPTED -> apply(RunEvent.Replied)
                ReplyOutcome.GONE -> apply(RunEvent.ReplyGone)
            }
        } catch (e: IOException) {
            apply(RunEvent.ReplyFailed(lossOf(e)))
        } catch (e: AgentException) {
            apply(RunEvent.ReplyFailed(lossOf(e)))
        }
    }

    /**
     * Is the request the screen is showing still pending?
     *
     * There is no withdrawal event. A request abandoned because the turn was
     * interrupted or ended leaves nothing on the stream at all, so a phone
     * that was asleep can hold a live-looking row over a request nobody is
     * waiting on. The session's pending list is the only way to find out, and
     * this is the only caller.
     *
     * A failure here is swallowed on purpose. Nothing was observed: the row
     * is not known to be stale, the user pressed nothing, and telling them
     * that a question they did not ask could not be answered would be noise.
     */
    private fun reconcile() {
        val target = synchronized(lock) { agentSession } ?: return
        val requestId = synchronized(lock) { session.state.blocked?.requestId } ?: return
        if (requestId.isEmpty()) return
        // Two lists, because a question is not in the permission one. Which
        // is asked is decided by what the screen is showing, and a state with
        // no block at all returned above.
        val question = synchronized(lock) { session.state.blocked?.kind == BlockKind.Question }
        val pending = try {
            if (question) {
                client.pendingQuestions(target).map { it.id }
            } else {
                client.pendingPermissions(target).map { it.id }
            }
        } catch (e: IOException) {
            return
        } catch (e: AgentException) {
            return
        }
        // Still there under a different session is still there: the id is
        // allocated by the one project instance this list came from.
        if (pending.none { it == requestId }) apply(RunEvent.Withdrawn)
    }

    private fun prompt(effect: RunEffect.Prompt) {
        val target = synchronized(lock) { agentSession } ?: return
        try {
            // Queue by default (section 13 answer 2): a steer discards what the
            // agent was mid-way through, and on a phone the user often cannot
            // see what that was.
            val admitted = client.prompt(
                target,
                effect.instruction,
                if (effect.queue) Delivery.QUEUE else Delivery.STEER,
            )
            apply(RunEvent.Admitted(queued = admitted.delivery == Delivery.QUEUE.wire))
        } catch (e: IOException) {
            apply(RunEvent.Failed(faultOf(e)))
        } catch (e: AgentException) {
            apply(RunEvent.Failed(faultOf(e)))
        }
    }

    /** One session per directory, reused for the life of the process. */
    private fun sessionFor(project: ProjectEntry): Session {
        synchronized(lock) {
            val existing = agentSession
            if (existing != null && directory == project.path) return existing
        }
        val made = client.createSession(project.path)
        synchronized(lock) {
            agentSession = made
            directory = project.path
        }
        return made
    }

    /**
     * Arms the clock check, once, for this generation of the turn.
     *
     * The generation is what keeps a tick armed before a `session.idle` from
     * firing into the next instruction and reading its silence clock, which
     * would be a reconnect nobody asked for.
     */
    private fun armTick(atMs: Long) {
        val mine = synchronized(lock) { ++generation }
        val delay = (atMs - clock()).coerceAtLeast(0)
        timer(delay) {
            val current = synchronized(lock) { generation }
            if (current == mine) apply(RunEvent.Tick)
        }
    }

    /**
     * Which of section 9's three failures this is.
     *
     * The distinction is the whole value of the fault screens: "the tunnel is
     * down" and "the agent is not running" have different fixes, and a single
     * "something went wrong" makes the user try both. Go's error text is the
     * only thing that carries it, which is why [TunnelChannel] keeps it.
     */
    /**
     * The same three failures, as one of section 5.4's reason sentences.
     *
     * [RunLoss.AgentError] is deliberately not reachable from here. Section
     * 5.17 says this case takes one of four existing sentences and invents no
     * fifth, and "the agent stopped with an error" is a claim about the agent
     * that a failed round trip is no evidence for: the phone did not hear
     * from the agent, which is what [RunLoss.Lost] says and all it says.
     */
    private fun lossOf(e: Exception): RunLoss = when (faultOf(e)) {
        RunFault.TunnelOff -> RunLoss.Tunnel
        RunFault.NoServer -> RunLoss.NoAnswer
        RunFault.Refused -> RunLoss.Refused
        else -> RunLoss.Lost
    }

    private fun faultOf(e: Exception): RunFault {
        // A status is a fact and error text is a guess, so the status is asked
        // first and the text only when there is none.
        if (e is AgentException) {
            return when (e.status) {
                401, 403 -> RunFault.Refused
                // The server answered, so the tunnel is up and the agent is
                // running. Something else about this turn is wrong, and
                // claiming the tunnel is down would send the user to fix a
                // thing that is not broken.
                else -> RunFault.TurnFailed
            }
        }
        val text = e.message.orEmpty().lowercase()
        return when {
            text.contains("connection refused") || text.contains("econnrefused") -> RunFault.NoServer
            // Go marks every failure to bring a conn up through the tunnel
            // with "maiatunnel: tunnel dial" (agent.go's dial). Without the
            // mark the conn was established and the exchange died after it:
            // a stalled response and a dead tunnel can both say "context
            // deadline exceeded", and only the first belongs on the
            // tunnel-off screen.
            !text.contains("maiatunnel: tunnel dial") -> RunFault.TurnFailed
            else -> RunFault.TunnelOff
        }
    }
}
