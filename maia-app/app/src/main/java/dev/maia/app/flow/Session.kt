package dev.maia.app.flow

import dev.maia.actions.notes.Note
import dev.maia.app.feel.Schedule

/**
 * How many drafts may wait for an unlock at once. M3 brief section 4.2.
 *
 * Five, and the sixth locked invocation is refused before the microphone opens.
 * The number is not about memory, which would allow thousands; it is about a
 * phone in a pocket that presses its own side key all afternoon. Five is enough
 * for a walk to the car and small enough to review in one sitting.
 */
const val QUEUE_CAP = 5

/**
 * The flow plus the two things that outlive any one screen: whether this
 * session was invoked over a keyguard, and the drafts waiting for an unlock.
 *
 * The queue is here and not in [FlowState] on purpose. [FlowState] is what one
 * screen draws, and M2's machine is built on that being true: `aperture` is a
 * function of the state alone, and every M2 test compares whole states. A queue
 * copied into ten state classes would be ten places to forget it, and a queue
 * carried only by [FlowState.Queued] would be lost the moment the user hides
 * the locked screen, which is exactly when it matters most. The queue belongs
 * to the process, so it lives beside the state in the value the process-scoped
 * `FlowController` holds, and it is a plain value so a JVM test can build a
 * five-deep queue in one line and drive it.
 *
 * [locked] is read once by the host at invocation and carried from there
 * (section 2.2). Nothing in this file asks Android anything.
 *
 * [origin] is the door the live session came through, null when nothing is in
 * flight. Only [Origin.Assistant] owns a window the flow has to ask to go away.
 */
data class FlowSession(
    val state: FlowState = FlowState.Idle(),
    val locked: Boolean = false,
    val queue: List<QueuedDraft> = emptyList(),
    val origin: Origin? = null,
    /**
     * The note whose append just failed, held only while the note write fault
     * is on screen so its "Try again" can reopen the card (M4 row 8). Beside
     * the state rather than in [FlowState.Fault] for the reason the queue is:
     * the fault's shape is pinned by equality in the row 5 tests. Memory only,
     * like a queued note, and null everywhere else.
     */
    val retryNote: Note? = null,
)

data class SessionStep(val session: FlowSession, val effects: List<Effect> = emptyList())

/**
 * The whole machine, including the parts that need to know about the keyguard
 * and the queue. Pure, like the inner [reduce], and for the same reasons.
 *
 * Everything that can be decided from one screen is still decided by the inner
 * reducer, and this function does four things it cannot: it decides whether an
 * invocation is allowed at all, it moves drafts in and out of the queue, it
 * turns an unlock into the oldest waiting card, and it lets the flow settle.
 */
fun reduce(session: FlowSession, event: FlowEvent, now: Long): SessionStep {
    val step = step(session, event, now)
    return step.copy(session = step.session.copy(retryNote = retryNote(session, step.session)))
}

private fun step(session: FlowSession, event: FlowEvent, now: Long): SessionStep {
    val invoke = event.asInvoke()
    return when {
        // A bare M2 [FlowEvent.Press] says which door it is not (it is the
        // launcher) but nothing at all about the keyguard, so it must not be
        // allowed to assert that there isn't one. It inherits whatever the
        // session already believes, and only [FlowEvent.Unlocked] ever clears
        // that.
        invoke != null ->
            invoked(session, if (event is FlowEvent.Press) invoke.copy(locked = session.locked) else invoke, now)
        event == FlowEvent.Unlocked -> unlocked(session, now)
        event == FlowEvent.Hidden -> hidden(session)
        event == FlowEvent.RetryNote -> retried(session)
        session.state is FlowState.Queued -> queued(session, session.state, event)
        else -> {
            val step = reduce(session.state, event, now, session.locked)
            settle(session, session.copy(state = step.state), step.effects, now)
        }
    }
}

/**
 * What [FlowSession.retryNote] holds after a step: the note of a write that
 * just failed, carried while that fault stays on screen, and nothing otherwise.
 */
private fun retryNote(before: FlowSession, after: FlowSession): Note? {
    val fault = after.state as? FlowState.Fault ?: return null
    if (fault.reason !is FaultReason.NoteWriteFailed) return null
    val writing = before.state as? FlowState.Writing
    return writing?.note ?: before.retryNote
}

/**
 * "Try again" on the note write fault: the note card again, asking for the
 * folder as a spoken note does, and writing nothing. Anywhere else, or with no
 * note kept, or over a keyguard (unreachable, since a locked phone never
 * writes, but the folder read must not be the one road that forgets it), it
 * does nothing.
 */
private fun retried(session: FlowSession): SessionStep {
    val note = session.retryNote
    val fault = session.state as? FlowState.Fault
    if (note == null || session.locked || fault?.reason !is FaultReason.NoteWriteFailed) return SessionStep(session)
    return SessionStep(session.copy(state = FlowState.NotePreview(note)), listOf(Effect.LoadNotesFolder))
}

/**
 * An invocation, from any of the four doors.
 *
 * The order matters. The inner reducer is asked first, because it already
 * carries M2's answer for every state: a second invocation while
 * [FlowState.Invoking], [FlowState.Listening] or [FlowState.Understanding]
 * changes nothing, whatever its origin, which is one microphone and one owner
 * (criterion J7) and needs no new rule here. Only when it says a capture would
 * start is there anything for this function to decide.
 */
private fun invoked(session: FlowSession, event: FlowEvent.Invoke, now: Long): SessionStep {
    // One microphone, one owner (criterion J7). A session that is already
    // hearing something keeps hearing it, whatever door was used the second
    // time; bringing that door's window to the front is the host's business and
    // not the flow's. This is stated here rather than left to the inner
    // reducer's silence, because "it happens to be ignored" and "it is refused"
    // read the same in a passing test and not in a year's time.
    if (session.state is FlowState.Invoking ||
        session.state is FlowState.Listening ||
        session.state is FlowState.Understanding
    ) {
        return SessionStep(session)
    }
    // A locked screen with a kept draft on it is a resting screen: the draft is
    // already in the queue, so speaking again starts a new capture rather than
    // being refused. That is how a queue reaches five in the first place.
    val from = if (session.state is FlowState.Queued) FlowState.Idle() else session.state
    val step = reduce(from, event, now, locked = event.locked)
    if (step.state !is FlowState.Invoking) {
        // Ignored, or refused on a card in hand. Either way no capture starts,
        // so the session's lock and door are what they already were.
        return SessionStep(session.copy(state = step.state), step.effects)
    }
    if (event.locked && session.queue.size >= QUEUE_CAP) {
        // The refusal is here rather than after the sentence, so a pocket never
        // opens the microphone for a sixth time. No draft, no transcript,
        // because nothing was heard.
        return SessionStep(
            session.copy(state = FlowState.Fault(FaultReason.QueueFull, transcript = ""), locked = true),
            listOf(Effect.Haptic(Schedule.fault)),
        )
    }
    return SessionStep(
        session.copy(state = step.state, locked = event.locked, origin = event.origin),
        step.effects,
    )
}

/**
 * The keyguard is gone.
 *
 * The locked screen, if there was one, becomes the words it was showing, and
 * then [settle] does the rest: with drafts waiting it opens the oldest, which
 * is the same road as an unlocked flow coming to rest. There is no second
 * "open the queue" path, so the two cannot drift apart.
 */
private fun unlocked(session: FlowSession, now: Long): SessionStep {
    val open = session.copy(locked = false)
    val state = session.state
    val next = if (state is FlowState.Queued) open.copy(state = FlowState.Idle(state.summary.transcript)) else open
    return settle(session, next, emptyList(), now)
}

/**
 * The host's window went away.
 *
 * Only two states care, and the rest deliberately do not. The flow is process
 * scoped from M3 (brief section 2.1), so a card, a fault or an open undo window
 * survives a dismissed window and is still there when the user comes back; that
 * is the whole point of hoisting it out of a view model. What cannot survive is
 * an open microphone, which stops exactly as a cancel would (criterion J8), and
 * a locked screen nobody is looking at, whose draft stays in the queue and gets
 * a notification instead.
 */
private fun hidden(session: FlowSession): SessionStep = when (val state = session.state) {
    is FlowState.Invoking, is FlowState.Listening ->
        SessionStep(session.copy(state = FlowState.Idle()), listOf(Effect.StopCapture(discardAudio = true)))
    is FlowState.Queued ->
        SessionStep(session.copy(state = FlowState.Idle()), waiting(session.queue.size))
    else -> SessionStep(session)
}

/**
 * What the locked screen itself can do: unlock, discard, or be taken away.
 *
 * Every other event is ignored, which is the same table M2 writes for every
 * other state. Discard drops this draft and only this draft, and the flow
 * leaves rather than falling back to a lock screen with nothing on it.
 */
private fun queued(session: FlowSession, state: FlowState.Queued, event: FlowEvent): SessionStep = when (event) {
    FlowEvent.UnlockRequested -> SessionStep(session, listOf(Effect.RequestUnlock))
    FlowEvent.Cancel -> {
        val rest = session.queue.filterNot { it.heardAt == state.heardAt && it.draft == state.pending }
        SessionStep(
            session.copy(state = FlowState.Idle(), queue = rest),
            listOf(Effect.HideSession) + waiting(rest.size),
        )
    }
    else -> SessionStep(session)
}

/**
 * Where a step lands once the queue has had its say.
 *
 * Three things happen here, in one place, after every event that is not
 * itself about the lock:
 *
 * 1. A sentence that was just kept joins the queue, or is refused if the queue
 *    is somehow already full. The refusal is unreachable through [invoked],
 *    which refuses earlier, and it is kept because this is the last point at
 *    which a draft can enter memory and an unreachable guard here is cheaper
 *    than the bug it forecloses.
 * 2. An unlocked flow that has come to rest with drafts waiting opens the
 *    oldest of them, which is section 4.2's "commit or discard moves to the
 *    next" and section 2.3's "Idle, queue not empty, Unlocked" at once.
 * 3. An assistant session that has come to rest asks its host to go away,
 *    or, when it came to rest by handing a sentence to the answer or run
 *    surface, to make way for that surface.
 */
private fun settle(before: FlowSession, after: FlowSession, effects: List<Effect>, now: Long): SessionStep {
    val state = after.state
    if (state is FlowState.Queued) {
        val pending = state.pending ?: return SessionStep(after, effects)
        if (after.queue.size >= QUEUE_CAP) {
            return SessionStep(
                after.copy(state = FlowState.Fault(FaultReason.QueueFull, state.summary.transcript)),
                effects + Effect.Haptic(Schedule.fault),
            )
        }
        val item = QueuedDraft(pending, state.heardAt, state.summary)
        return SessionStep(after.copy(queue = after.queue + item), effects)
    }
    if (state is FlowState.Idle && !after.locked) {
        val oldest = after.queue.firstOrNull()
        if (oldest != null) {
            val rest = after.queue.drop(1)
            val opened = openQueued(oldest, now)
            return SessionStep(
                after.copy(state = opened.state, queue = rest, origin = after.origin),
                effects + opened.effects + waiting(rest.size),
            )
        }
    }
    if (state is FlowState.Idle && before.state !is FlowState.Idle && after.origin == Origin.Assistant) {
        val owed = effects.any { it is Effect.Assist || it is Effect.RunAgent }
        return SessionStep(after.copy(origin = null), effects + if (owed) Effect.ShowSurface else Effect.HideSession)
    }
    return SessionStep(after, effects)
}

/**
 * The card for a draft that waited.
 *
 * The draft is opened exactly as it was resolved when it was spoken: not
 * re-parsed, not nudged onto today. What changes is that the card can say when
 * the sentence was said, and only when the day has turned under it, which is
 * the whole of criterion J6.
 *
 * A note opens its own card and asks for the folder, the note's equivalent of
 * the provider read, now that the lock is gone (M4 criterion J9).
 */
private fun openQueued(item: QueuedDraft, now: Long): Step = when (val pending = item.draft) {
    is Pending.Event -> Step(
        FlowState.Preview(
            draft = pending.draft,
            heardNote = saidAtNote(item.heardAt, now, pending.draft.start.value.zone),
        ),
        listOf(Effect.LoadTarget),
    )
    is Pending.Note -> Step(FlowState.NotePreview(pending.note), listOf(Effect.LoadNotesFolder))
    // A kept assistant sentence opens no card: it goes out of the door to
    // the answer surface, and the surface's screen is the answer. Idle with
    // the words shown is what the one-sentence loop does with a finished
    // sentence, which is what this one now is.
    is Pending.Command ->
        Step(FlowState.Idle(item.summary.transcript), listOf(Effect.Assist(pending.command)))
}

/** What a locked screen holds for the queue, or null for a bare question, which nothing can open. */
private val FlowState.Queued.pending: Pending?
    get() = draft?.let { Pending.Event(it) }
        ?: note?.let { Pending.Note(it) }
        ?: command?.let { Pending.Command(it) }

/** The waiting-draft notification, or its removal when nothing is waiting. */
private fun waiting(count: Int): List<Effect> = listOf(Effect.PostDraftWaiting(count))
