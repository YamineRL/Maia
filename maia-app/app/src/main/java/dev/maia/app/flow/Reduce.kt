package dev.maia.app.flow

import dev.maia.actions.CalendarTarget
import dev.maia.actions.notes.Note
import dev.maia.app.feel.Schedule
import dev.maia.nlu.Edit
import dev.maia.nlu.EventDraft
import dev.maia.nlu.Field
import dev.maia.nlu.Intent
import dev.maia.nlu.Provenance
import dev.maia.orb.ApertureState

/**
 * How long 4c stays on screen at least. The handoff's "about 400 ms": the parse
 * takes single-digit milliseconds, and a card that replaces the sentence before
 * it can be read looks like the sentence was never shown.
 */
const val UNDERSTAND_FLOOR_MS = 400L
/**
 * How long [FlowState.Understanding] waits for [FlowEvent.Parsed] before
 * giving up on it and settling for itself, same as a Cancel.
 *
 * Sitting on the phone this session (2026-09-14) turned up three separate
 * sentences, on both the zipformer draft and a correctly rescored Parakeet
 * transcript, where Parsed never arrived and the aperture spun on
 * Understanding with no crash and nothing in logcat. Root cause not found
 * yet; this is the backstop so a dropped [Effect.Parse] answer degrades
 * (PRD principle 4) into the sentence being shown back, rather than a
 * screen only a manual Cancel can leave.
 */
const val UNDERSTAND_TIMEOUT_MS = 6_000L
/** How often [FlowState.Understanding] rechecks itself while still waiting. */
const val UNDERSTAND_POLL_MS = 200L

/** 4f's undo window, counted from the write completing. */
const val UNDO_WINDOW_MS = 8_000L

/** M1's wording for a permission revoked between opening the card and committing. */
const val WRITE_DENIED = "Maia no longer has permission to write to the calendar"

data class Step(val state: FlowState, val effects: List<Effect> = emptyList())

/** Where the flow starts, which depends only on whether the models are on the phone. */
fun initialState(modelsPresent: Boolean): FlowState =
    if (modelsPresent) FlowState.Idle() else FlowState.FirstRun()

/**
 * The aperture's pose, as a function of the screen alone, so the orb can never
 * show something the screen is not doing.
 *
 * Preview and NoCalendar rest at dormant: the user is reading and deciding, and
 * nothing is being heard or worked on.
 */
val FlowState.aperture: ApertureState
    get() = when (this) {
        is FlowState.FirstRun, is FlowState.Idle, is FlowState.Preview, is FlowState.NoCalendar,
        is FlowState.Queued, is FlowState.NotePreview, is FlowState.NoFolder, is FlowState.NoteConfirmed,
        ->
            ApertureState.Dormant
        is FlowState.Invoking, is FlowState.Listening -> ApertureState.Listening
        is FlowState.Understanding, is FlowState.Committing, is FlowState.Writing -> ApertureState.Thinking
        is FlowState.Confirmed -> if (speaking) ApertureState.Speaking else ApertureState.Dormant
        is FlowState.Fault -> ApertureState.Fault
    }

/**
 * Whether a parsed event carried no date at all, which is 4g.
 *
 * The rule is the parser's own signature for "nothing temporal was said": the
 * start is [Provenance.Inferred] with no span, the duration is inferred too, and
 * it is not all day (a day heard alone resolves to an all-day event with a heard
 * start). A corrected start never faults.
 *
 * It is narrower than it should be and says so. `TemporalResolver` does not
 * record which words gave the start, and folds a heard date with a guessed hour
 * into the same [Provenance.Inferred], so "thursday afternoon" also matches and
 * faults with its date chips. Once `:core-nlu` puts a span on a heard start, the
 * span check here excludes those without a change to this function.
 */
fun noDateHeard(draft: EventDraft): Boolean =
    draft.start.provenance == Provenance.Inferred &&
        draft.start.span == null &&
        draft.duration.provenance == Provenance.Inferred &&
        !draft.allDay

/**
 * The whole screen machine. Pure: no clock is read, `now` is given, and every
 * consequence is returned as an [Effect].
 *
 * An event that does not apply in a state returns the state unchanged with no
 * effects. That is what makes a late result harmless: a write succeeding after
 * the user left, or a delete answering after a new invocation, lands on a state
 * that does not listen for it.
 *
 * [locked] is whether the keyguard was up when this session was invoked, read
 * once by the host and carried since (M3 brief section 2.2). It defaults to
 * false so that every M2 caller and every M2 test means what it always meant,
 * and it changes exactly one decision: where an understood sentence goes. The
 * whole locked policy is that one fork plus the states it cannot reach from
 * there. Anything that needs the queue as well lives in [reduce] over a
 * [FlowSession].
 */
fun reduce(state: FlowState, event: FlowEvent, now: Long, locked: Boolean = false): Step = when (state) {
    is FlowState.FirstRun -> firstRun(state, event)
    is FlowState.Idle -> event.asInvoke()?.let { invoke(now, surface = it.surface) }
    is FlowState.Invoking -> capturing(Capture(state.pressedAt, null, state.surface), event, now)
    is FlowState.Listening -> capturing(Capture(state.pressedAt, state, state.surface), event, now)
    is FlowState.Understanding -> understanding(state, event, now, locked)
    is FlowState.Preview -> preview(state, event, now, locked)
    is FlowState.NoCalendar -> noCalendar(state, event)
    is FlowState.Committing -> committing(state, event, now)
    is FlowState.Confirmed -> confirmed(state, event, now)
    is FlowState.Fault -> fault(state, event, now, locked)
    is FlowState.NotePreview -> notePreview(state, event)
    is FlowState.NoFolder -> noFolder(state, event)
    is FlowState.Writing -> writing(state, event)
    is FlowState.NoteConfirmed -> noteConfirmed(event, now)
    // Queued's events all need the queue, which is not in [FlowState]. They are
    // handled one level up, by the session reducer in Session.kt.
    is FlowState.Queued -> null
} ?: Step(state)

private fun firstRun(state: FlowState.FirstRun, event: FlowEvent): Step? = when (event) {
    FlowEvent.DownloadRequested ->
        if (state.download.running) {
            null
        } else {
            Step(state.copy(download = state.download.copy(running = true, error = null)), listOf(Effect.DownloadModels))
        }
    is FlowEvent.DownloadProgress -> Step(
        state.copy(
            download = state.download.copy(
                file = event.file,
                bytesDone = event.bytesDone,
                bytesTotal = event.bytesTotal,
                running = true,
                error = null,
            ),
        ),
    )
    is FlowEvent.DownloadFailed ->
        Step(state.copy(download = state.download.copy(running = false, error = event.message)))
    FlowEvent.ModelsReady -> Step(FlowState.Idle())
    else -> null
}

/**
 * Every invocation starts the same way, whatever it interrupts.
 *
 * [surface] is the modal screen the press happened on, carried into
 * [FlowState.Invoking] so the capture can hand it to [Effect.Parse] when
 * the transcript lands: which surface heard the sentence is a fact about
 * the press, not about any state the flow reaches afterwards.
 */
private fun invoke(now: Long, before: List<Effect> = emptyList(), surface: Surface = Surface.Neutral): Step =
    Step(FlowState.Invoking(now, surface), before + listOf(Effect.Haptic(Schedule.invoke), Effect.StartCapture))

/** Invoking and Listening take the same events; Invoking is Listening before any word. */
private class Capture(val pressedAt: Long, val listening: FlowState.Listening?, val surface: Surface)

private fun capturing(capture: Capture, event: FlowEvent, now: Long): Step? {
    val listening = capture.listening
    return when (event) {
        FlowEvent.CaptureStarted ->
            if (listening == null) Step(FlowState.Listening(capture.pressedAt, surface = capture.surface)) else null
        is FlowEvent.PartialHeard -> {
            val current = listening ?: FlowState.Listening(capture.pressedAt, surface = capture.surface)
            val first = !current.firstPartialFelt && event.text.isNotBlank()
            Step(
                current.copy(
                    partial = event.text,
                    words = event.words,
                    firstPartialFelt = current.firstPartialFelt || first,
                ),
                if (first) listOf(Effect.Haptic(Schedule.firstPartial(now - capture.pressedAt))) else emptyList(),
            )
        }
        is FlowEvent.FinalHeard ->
            if (event.text.isBlank()) {
                // The endpointer closed on silence. Nothing was said, so there is
                // nothing to understand and nothing to fault about.
                Step(FlowState.Idle(), listOf(Effect.StopCapture(discardAudio = true)))
            } else {
                Step(
                    FlowState.Understanding(event.text, since = now),
                    listOf(
                        Effect.StopCapture(discardAudio = false),
                        Effect.Parse(event.text, capture.surface),
                        Effect.ScheduleTick(now + UNDERSTAND_FLOOR_MS),
                    ),
                )
            }
        is FlowEvent.CaptureFailed -> Step(
            FlowState.Fault(FaultReason.CaptureFailed(event.message), transcript = listening?.partial.orEmpty()),
            listOf(Effect.StopCapture(discardAudio = true), Effect.Haptic(Schedule.fault)),
        )
        // A cancel throws the audio away and says nothing: no Speak, no write.
        FlowEvent.Cancel -> Step(FlowState.Idle(), listOf(Effect.StopCapture(discardAudio = true)))
        else -> null
    }
}

private fun understanding(state: FlowState.Understanding, event: FlowEvent, now: Long, locked: Boolean): Step? =
    when (event) {
        is FlowEvent.Parsed ->
            if (state.intent != null) {
                null
            } else {
                val parsed = state.copy(intent = event.intent, at = event.at)
                if (now - state.since >= UNDERSTAND_FLOOR_MS) route(parsed, now, locked) else Step(parsed)
            }
        FlowEvent.Tick -> when {
            state.intent != null && now - state.since >= UNDERSTAND_FLOOR_MS -> route(state, now, locked)
            now - state.since >= UNDERSTAND_TIMEOUT_MS -> Step(FlowState.Idle(state.transcript))
            else -> Step(state, listOf(Effect.ScheduleTick(now + UNDERSTAND_POLL_MS)))
        }
        FlowEvent.Cancel -> Step(FlowState.Idle(state.transcript))
        else -> null
    }

/**
 * Where an understood sentence goes.
 *
 * [Intent.Unparsed] opens the card, as at M1 (PRD principle 4), and never takes
 * the no-date fault: its title is the whole sentence and the card is already
 * the place to fix it. A note opens the note card, or is kept while locked,
 * exactly like an event (M4 brief sections 5.2 and 6). Agenda and availability
 * are not M4; they return to Idle with the transcript on screen, which is honest
 * about having heard the sentence and done nothing with it, where a fault would
 * claim something broke.
 */
private fun route(state: FlowState.Understanding, now: Long, locked: Boolean): Step = when (val intent = state.intent) {
    is Intent.CreateEvent ->
        if (noDateHeard(intent.draft)) {
            Step(
                FlowState.Fault(FaultReason.NoDateHeard, state.transcript, intent.draft),
                listOf(Effect.Haptic(Schedule.fault)),
            )
        } else {
            openCard(intent.draft, now, locked)
        }
    is Intent.Unparsed -> openCard(intent.draft, now, locked)
    is Intent.CaptureNote -> {
        val at = state.at
        if (at != null) {
            openNote(noteOf(intent, at, state.transcript), now, locked)
        } else {
            // No wall clock came with the parse, so there is no day to file the
            // note under. Only a hand-built event lacks one (the runner always
            // stamps it), and M3's handling of an unfiled sentence stands.
            if (locked) keep(null, state.transcript, now) else Step(FlowState.Idle(state.transcript))
        }
    }
    // The four M8 agent sentences. Locked, they take the same road as
    // everything else: M8 PRD section 9 says instructions queue and are not
    // sent, because locked is locked and consistent with M3. Unlocked, the
    // flow's job ends here: the sentence goes out of the door as an
    // [AgentCommand] and the run surface owns everything after it, including
    // the minutes.
    is Intent.AgentInstruction ->
        agent(state, now, locked) {
            AgentCommand.Instruct(intent.project, intent.instruction.value.orEmpty(), state.transcript)
        }
    is Intent.AgentFocus -> agent(state, now, locked) { AgentCommand.Focus(intent.project, state.transcript) }
    is Intent.AgentStop -> agent(state, now, locked) { AgentCommand.Stop }
    is Intent.AgentStatus -> agent(state, now, locked) { AgentCommand.Status }
    // M9's everyday sentences, handed to the answer surface exactly as an
    // agent sentence is handed to the run surface. The three roads are
    // decided here rather than by the surface, because "what kind of thing
    // was said" is the grammar's question and the grammar already answered
    // it. Locked, they take the same road as an agent sentence: kept, shown
    // back, and nothing read or asked until the phone is unlocked, which is
    // section 6's locked rule as a shape rather than a check.
    is Intent.Agenda, is Intent.Availability, is Intent.Calculate, is Intent.DeviceFact ->
        assist(state, now, locked) { AssistantCommand.Read(intent, state.transcript) }
    is Intent.SetTimer, is Intent.SetAlarm, is Intent.OpenSettings, is Intent.SetTorch,
    is Intent.Dial, is Intent.ComposeMessage, is Intent.Navigate, is Intent.OpenWeb,
    is Intent.OpenApp, is Intent.Media ->
        assist(state, now, locked) { AssistantCommand.Act(intent, state.transcript) }
    is Intent.Conversation ->
        assist(state, now, locked) { AssistantCommand.Ask(intent.text, state.transcript) }
    else -> if (locked) keep(null, state.transcript, now) else Step(FlowState.Idle(state.transcript))
}

/**
 * One sentence, out of the door.
 *
 * Idle with the transcript on screen, which is what the one-sentence loop does
 * when it has finished with a sentence. It is not a claim that anything
 * happened: the run surface says that, on its own screen, and it is the only
 * thing that knows.
 *
 * No haptic here. The admission haptic belongs to the moment the instruction
 * actually left the phone, which is a network round trip away and is
 * [dev.maia.app.agent.RunEffect.Feel]'s to emit. Buzzing now would be a
 * confirmation of something that has not happened yet, and half the time it
 * would be a confirmation of something about to fail.
 */
private fun agent(
    state: FlowState.Understanding,
    now: Long,
    locked: Boolean,
    command: () -> AgentCommand,
): Step =
    if (locked) {
        keep(null, state.transcript, now)
    } else {
        Step(FlowState.Idle(state.transcript), listOf(Effect.RunAgent(command())))
    }

/**
 * [agent]'s twin for the answer surface. Same fork for the same reason: a
 * locked phone keeps the sentence and answers nothing, because every M9 road
 * is a read, a reach or a reveal.
 */
private fun assist(
    state: FlowState.Understanding,
    now: Long,
    locked: Boolean,
    command: () -> AssistantCommand,
): Step =
    if (locked) {
        // Not [keep]'s question shape: the command itself waits for the
        // unlock and the answer surface picks it up there, which is the only
        // road by which a locked "what do I have tomorrow" is ever answered.
        Step(
            FlowState.Queued(
                draft = null,
                heardAt = now,
                summary = questionSummary(state.transcript),
                command = command(),
            ),
            listOf(Effect.Haptic(Schedule.invoke)),
        )
    } else {
        Step(FlowState.Idle(state.transcript), listOf(Effect.Assist(command())))
    }

/**
 * The one fork the lock makes. Everything else about the locked policy is a
 * consequence of it: [FlowState.Queued] has no edge to [FlowState.Committing],
 * and [Effect.LoadTarget] is emitted here and only here on the road from a
 * sentence to a card.
 */
private fun openCard(draft: EventDraft, now: Long, locked: Boolean): Step =
    if (locked) keep(draft, draft.transcript, now) else Step(FlowState.Preview(draft), listOf(Effect.LoadTarget))

/**
 * Keep what was heard until the phone is unlocked.
 *
 * Haptic and nothing else: D2's default is that a locked capture is felt, not
 * heard, because a phone on a table in a room with other people should not
 * announce that a sentence was taken. [Schedule.invoke]'s single click stands
 * in for a "kept" pattern of its own, which is design's to draw and lives in
 * `feel/Schedule` when D4 lands.
 */
private fun keep(draft: EventDraft?, transcript: String, now: Long): Step = Step(
    FlowState.Queued(
        draft = draft,
        heardAt = now,
        summary = if (draft != null) lockedSummary(draft) else questionSummary(transcript),
    ),
    listOf(Effect.Haptic(Schedule.invoke)),
)

/**
 * The note a parse becomes. The intent's own transcript when it has one, and the
 * sentence on screen when it does not, because a fault keeps the words verbatim
 * and a blank transcript would keep nothing.
 */
private fun noteOf(intent: Intent.CaptureNote, at: java.time.ZonedDateTime, heard: String): Note =
    Note.of(intent, at).let { if (it.transcript.isBlank()) it.copy(transcript = heard) else it }

/**
 * [openCard] for a note, and the same fork: locked, the note is kept and nothing
 * is read, not even which folder is held; unlocked, the card opens and asks.
 */
private fun openNote(note: Note, now: Long, locked: Boolean): Step =
    if (locked) {
        Step(
            FlowState.Queued(draft = null, heardAt = now, summary = noteSummary(note), note = note),
            listOf(Effect.Haptic(Schedule.invoke)),
        )
    } else {
        Step(FlowState.NotePreview(note), listOf(Effect.LoadNotesFolder))
    }

private val CalendarTarget?.writable: Boolean get() = this != null && calendar.writable

private fun preview(state: FlowState.Preview, event: FlowEvent, now: Long, locked: Boolean): Step? = when (event) {
    is FlowEvent.TargetLoaded ->
        if (event.target.writable) {
            Step(state.copy(target = event.target, error = null))
        } else {
            Step(FlowState.NoCalendar(state.draft))
        }
    is FlowEvent.TargetUnreadable -> Step(state.copy(error = event.message))
    is FlowEvent.Edited -> Step(state.copy(draft = state.draft.with(event.edit), error = null))
    is FlowEvent.Hold -> hold(state, event.fraction)
    FlowEvent.Cancel -> Step(FlowState.Idle(state.draft.transcript))
    // U2, amended (M4 row 8): the same words as a note. [openNote] and not a
    // NotePreview built here, so this is the same fork a parsed note takes:
    // the card opens and asks for the folder, and nothing is written. The
    // locked branch is unreachable today (a Preview is never shown locked) and
    // is kept because it is the safe answer if that ever changes.
    is FlowEvent.KeepAsNote -> openNote(Note.of(state.draft, event.at), now, locked)
    // M3 brief section 2.2: a card in hand is not replaced by a new sentence,
    // whatever door the new sentence came through. The refusal is shown on the
    // card the user already has, because the alternative is a lost edit and no
    // way to tell that it was lost.
    //
    // Deliberately [FlowEvent.Invoke] and not [FlowEvent.asInvoke], which is the
    // one place the two part company. M2 defined a bare Press over a card as
    // nothing at all, M2's card is being written against that this morning, and
    // a refusal that appears under a thumb that pressed nothing is worse than
    // the door-aware refusal being the only one that speaks.
    is FlowEvent.Invoke -> Step(state.copy(error = LockedCopy.CARD_IN_HAND), listOf(Effect.Haptic(Schedule.fault)))
    else -> null
}

/**
 * The hold, and the only road to a write.
 *
 * A write needs the hold at 1 and a writable target in hand. With no target yet
 * (still loading, or unreadable) the hold simply resets: the calendar is not
 * known to be absent, so NoCalendar would be a lie, and a write is impossible.
 */
private fun hold(state: FlowState.Preview, fraction: Float): Step {
    val progress = fraction.coerceIn(0f, 1f)
    val target = state.target
    if (progress >= 1f) {
        return if (target != null && target.writable) {
            Step(
                FlowState.Committing(state.draft, target),
                listOf(Effect.Haptic(Schedule.commit), Effect.WriteEvent(state.draft, target.calendar.id)),
            )
        } else {
            Step(state.copy(hold = 0f, holdSteps = 0))
        }
    }
    if (progress == 0f) return Step(state.copy(hold = 0f, holdSteps = 0))
    val (steps, felt) = climb(state.holdSteps, progress)
    return Step(state.copy(hold = progress, holdSteps = steps), felt)
}

/**
 * The haptic ladder for a hold below 1, shared by both cards so a note's hold
 * feels exactly like an event's: the step count after [progress], and the steps
 * not yet felt in this press.
 */
private fun climb(holdSteps: Int, progress: Float): Pair<Int, List<Effect>> {
    val reached = minOf(Schedule.HOLD_STEPS, (progress * Schedule.HOLD_STEPS).toInt() + 1)
    return maxOf(holdSteps, reached) to (holdSteps until reached).map { Effect.Haptic(Schedule.holdStep(it)) }
}

private fun noCalendar(state: FlowState.NoCalendar, event: FlowEvent): Step? = when (event) {
    is FlowEvent.TargetLoaded ->
        if (event.target.writable) Step(FlowState.Preview(state.draft, target = event.target)) else null
    FlowEvent.Cancel -> Step(FlowState.Idle(state.draft.transcript))
    else -> null
}

private fun notePreview(state: FlowState.NotePreview, event: FlowEvent): Step? = when (event) {
    is FlowEvent.NotesFolderLoaded -> {
        val folder = event.folder
        if (folder == null) Step(FlowState.NoFolder(state.note)) else Step(state.copy(folder = folder, error = null))
    }
    is FlowEvent.Hold -> noteHold(state, event.fraction)
    is FlowEvent.NoteBodyEdited ->
        if (event.text.isBlank()) {
            null
        } else {
            Step(state.copy(note = state.note.copy(body = Field(event.text.trim(), Provenance.Corrected)), error = null))
        }
    FlowEvent.Cancel -> Step(FlowState.Idle(state.note.transcript))
    // A note card in hand is refused a new sentence for the same reason an
    // event card is; see [preview].
    is FlowEvent.Invoke -> Step(state.copy(error = LockedCopy.CARD_IN_HAND), listOf(Effect.Haptic(Schedule.fault)))
    else -> null
}

/**
 * [hold] for a note. A write needs the hold at 1 and a folder in hand; with no
 * folder yet the hold resets, because the folder is not known to be absent.
 * [FlowState.Writing] does not listen for a hold, so a second 1 writes nothing
 * (criterion J11).
 */
private fun noteHold(state: FlowState.NotePreview, fraction: Float): Step {
    val progress = fraction.coerceIn(0f, 1f)
    val folder = state.folder
    if (progress >= 1f) {
        return if (folder != null) {
            Step(
                FlowState.Writing(state.note, folder),
                listOf(Effect.Haptic(Schedule.commit), Effect.WriteNote(state.note)),
            )
        } else {
            Step(state.copy(hold = 0f, holdSteps = 0))
        }
    }
    if (progress == 0f) return Step(state.copy(hold = 0f, holdSteps = 0))
    val (steps, felt) = climb(state.holdSteps, progress)
    return Step(state.copy(hold = progress, holdSteps = steps), felt)
}

private fun noFolder(state: FlowState.NoFolder, event: FlowEvent): Step? = when (event) {
    is FlowEvent.NotesFolderLoaded -> event.folder?.let { Step(FlowState.NotePreview(state.note, folder = it)) }
    FlowEvent.Cancel -> Step(FlowState.Idle(state.note.transcript))
    else -> null
}

/**
 * The append's answer.
 *
 * On success Maia says "Noted." ([Effect.SpeakNote], D4 as `docs/M4-copy.md`
 * section 4 answers it): one word, nothing from the note. The ringer and
 * silent rules are the speaker's, as for an event. No [FlowState.NoteConfirmed]
 * edge listens for the speech events, so the pose stays the plain confirmed
 * one and there is nothing to hush on the way out.
 */
private fun writing(state: FlowState.Writing, event: FlowEvent): Step? = when (event) {
    is FlowEvent.NoteWritten -> Step(FlowState.NoteConfirmed(state.note, event.ref), listOf(Effect.SpeakNote))
    is FlowEvent.NoteWriteFailed ->
        if (event.folderGone) {
            Step(FlowState.NoFolder(state.note), listOf(Effect.Haptic(Schedule.fault)))
        } else {
            Step(
                FlowState.Fault(FaultReason.NoteWriteFailed(event.message), state.note.transcript),
                listOf(Effect.Haptic(Schedule.fault)),
            )
        }
    else -> null
}

/** No undo to run out, so no tick: leave, or invoke again. */
private fun noteConfirmed(event: FlowEvent, now: Long): Step? = when {
    event == FlowEvent.Cancel -> Step(FlowState.Idle())
    else -> event.asInvoke()?.let { invoke(now, surface = it.surface) }
}

private fun committing(state: FlowState.Committing, event: FlowEvent, now: Long): Step? = when (event) {
    is FlowEvent.WriteSucceeded -> {
        val deadline = now + UNDO_WINDOW_MS
        Step(
            FlowState.Confirmed(state.draft, event.eventId, deadline),
            listOf(Effect.Speak(state.draft), Effect.ScheduleTick(deadline)),
        )
    }
    is FlowEvent.WriteFailed -> Step(
        FlowState.Preview(
            state.draft,
            target = state.target,
            error = if (event.denied) WRITE_DENIED else event.message ?: "the event could not be written",
        ),
        listOf(Effect.Haptic(Schedule.fault)),
    )
    else -> null
}

private fun confirmed(state: FlowState.Confirmed, event: FlowEvent, now: Long): Step? {
    val hush = if (state.speaking) listOf(Effect.StopSpeaking) else emptyList()
    return when (event) {
        FlowEvent.Undo -> when {
            state.undo != UndoStatus.Offered -> null
            now >= state.undoDeadline -> Step(state.copy(undo = UndoStatus.Expired))
            else -> Step(state.copy(undo = UndoStatus.Undoing), listOf(Effect.DeleteEvent(state.eventId)))
        }
        is FlowEvent.Deleted ->
            if (state.undo == UndoStatus.Undoing && event.eventId == state.eventId) {
                Step(state.copy(undo = if (event.existed) UndoStatus.Undone else UndoStatus.AlreadyGone))
            } else {
                null
            }
        is FlowEvent.DeleteFailed ->
            if (state.undo == UndoStatus.Undoing && event.eventId == state.eventId) {
                Step(state.copy(undo = UndoStatus.Failed))
            } else {
                null
            }
        FlowEvent.Tick ->
            if (state.undo == UndoStatus.Offered && now >= state.undoDeadline) {
                Step(state.copy(undo = UndoStatus.Expired))
            } else {
                null
            }
        FlowEvent.SpeechStarted -> if (state.speaking) null else Step(state.copy(speaking = true))
        FlowEvent.SpeechEnded -> if (state.speaking) Step(state.copy(speaking = false)) else null
        // Leaving or invoking again ends the window. The event stays: no delete.
        FlowEvent.Cancel -> Step(FlowState.Idle(), hush)
        else -> event.asInvoke()?.let { invoke(now, hush, it.surface) }
    }
}

private fun fault(state: FlowState.Fault, event: FlowEvent, now: Long, locked: Boolean): Step? = when (event) {
    is FlowEvent.DatePicked -> {
        val draft = state.draft
        if (state.reason == FaultReason.NoDateHeard && draft != null) {
            // A date chosen on a locked 4g fault still cannot open a card, for
            // the same reason the sentence could not: the card is a commit
            // affordance and a provider read. It joins the queue instead.
            openCard(draft.with(Edit.Start(event.start)).copy(transcript = state.transcript), now, locked)
        } else {
            null
        }
    }
    FlowEvent.Cancel -> Step(FlowState.Idle(state.transcript))
    else -> event.asInvoke()?.let { invoke(now, surface = it.surface) }
}
