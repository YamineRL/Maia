package dev.maia.app.agent

import dev.maia.orb.ApertureState
import dev.maia.transport.AskedQuestion
import dev.maia.transport.ProjectEntry

/**
 * What the run screen draws, and nothing else. `docs/M8-copy.md` section 1.
 *
 * Shaped like [dev.maia.app.flow.FlowState] and for the same reason: a screen
 * never reaches past its state into a flag somewhere else. It is a separate
 * value from `FlowState` rather than more members of it, because `FlowState`
 * is the one-sentence loop and this is the only screen in Maia whose content
 * is unbounded and whose life is minutes. Folding a four-minute stream into
 * the nine stages would make every one of them carry a field it never uses.
 *
 * **Nothing here is written to disk.** Section 1.5: replies live in memory for
 * as long as the process does. Maia does not keep a local copy of the contents
 * of the user's machine.
 */
data class RunState(
    /** Null before a project has been chosen. The header shows `7 maia`. */
    val project: ProjectEntry? = null,
    val status: RunStatus = RunStatus.Sending,
    /** The current turn, in full. Null before the first instruction. */
    val turn: Turn? = null,
    /** Earlier turns in the same session, oldest first, collapsed to one line. */
    val earlier: List<Turn> = emptyList(),
    /** Set while an agent has stopped and wants a human. Section 5.3. */
    val blocked: Block? = null,
    /**
     * The question the agent asked, its options, and how far through them the
     * user is. Section 5.18. Null for every block that is not a question.
     *
     * A field beside [blocked] rather than a member of it, because the two
     * have different lives: [blocked] is the fact that an agent is waiting and
     * the id that answers it, and this is a screen's worth of content that
     * outlives the block by one marker. An answer that did not land clears
     * neither; an answer that landed clears both.
     *
     * Everything in it arrived on the stream, so it is drawn and it never
     * becomes an argument to a [RunEffect]: the reply effects carry nothing
     * and the driver reads [Asking.sent] off the state, exactly as it already
     * reads the permission's request id.
     */
    val asking: Asking? = null,
    /**
     * What the blocked notification says right now, or null for no row at
     * all. Section 5.17.
     *
     * The row outlives [blocked]: an answer clears the block and leaves a row
     * saying what happened to it, which is the whole of 5.17's first
     * paragraph. That is why this is a field and not a function of [blocked].
     *
     * It is also the reason the rewrite is not a [RunEffect]. The row has one
     * id and one channel and it is edited in place, so it is a piece of
     * state, and a state that only ever changes here cannot drift from a
     * stream of instructions to change it. [RunAlerts] rewrites the row when
     * this moves and cancels it when it goes null.
     */
    val blockedRow: BlockedNotice? = null,
    /**
     * `todo.updated`, as counts or not at all. Section 1.6: a plan the user
     * cannot count is not worth a line, so a payload without counts is
     * omitted entirely rather than rendered as something vaguer.
     */
    val plan: Plan? = null,
    /**
     * The project that kept running when the stream moved, for
     * `m8_run_left_behind`. Section 13 answer 1: naming another project moves
     * the stream and does not stop the first agent, and believing otherwise is
     * the one thing the user could reasonably get wrong.
     */
    val leftBehind: Int? = null,
    /** Set when nothing was sent, or when the turn ended badly. Section 5.6 to 5.8. */
    val fault: RunFault? = null,
    /**
     * The project behind [RunFault.Retired], which is the only fault that has
     * a subject. Section 5.9's `m8_retired_project_body` says what the number
     * was, and that sentence is the only place the phone ever shows the user
     * the promise that numbers are never reused being kept, so the name has to
     * reach the screen. It comes from the registry the phone already holds and
     * never from anything the agent said.
     */
    val retired: ProjectEntry? = null,
    /**
     * Why a turn that had already been admitted did not finish, for section
     * 5.4's five reason sentences. Null means no run was lost: either nothing
     * is wrong, or the failure happened before the instruction left the phone,
     * and a "did not finish" notification about a turn that never started
     * would name a run the user does not have.
     *
     * It is a separate field from [fault] rather than more members on it
     * because the two answer different questions. [fault] decides which fault
     * screen the run screen shows; this decides which sentence the shade gets,
     * and the mapping is not one to one in either direction: `TunnelOff`
     * before sending sets [fault] and leaves this null, and the two ways a
     * turn can end without a `session.idle` are one [fault] and two of these.
     *
     * Set once and never overwritten, which is section 5.4's last bullet: if
     * the tunnel drops and the reconnect then fails, the earlier cause wins,
     * because it is the one that explains the other.
     */
    val loss: RunLoss? = null,
    /**
     * The numbered list, when a name matched nothing or more than one.
     * Sections 5.10 and 5.11. Nothing on it ranks the candidates.
     */
    val chooser: Chooser? = null,
    /**
     * Why the last answer to a blocked agent did not leave the phone, for
     * `m8_run_answer_undelivered` and `m8_notif_blocked_undelivered_body`.
     *
     * One of section 5.4's four reachable reason sentences and never a fifth:
     * the copy is explicit that this case invents no cause of its own. It is
     * a separate field from [loss] because a refusal that did not go through
     * has lost nothing. The run is still exactly where it was, the block is
     * still up, and the two controls stay where the thumb left them.
     */
    val answerFailed: RunLoss? = null,
    /**
     * The same for a `Stop` that did not go through, for `m8_run_stop_failed`.
     *
     * Section 5.12: the run carries on, `Stop` stays exactly where it is, and
     * nothing marks the reply, because nothing happened to it.
     */
    val stopFailed: RunLoss? = null,
    /**
     * Which of section 5.17's two announcements `IT STOPPED WAITING` carries.
     *
     * One marker, two arrivals. The words on the screen are the same either
     * way, because the fact is the same; what differs is the one clause a
     * screen reader is told, and only the user who pressed something can be
     * told their answer was not sent.
     */
    val announce: RunAnnounce? = null,
    /**
     * When the user's own ending landed and Maia started waiting for the
     * server's. Zero at every other moment.
     *
     * A refusal ends the turn for the user and does not close the stream:
     * `m8_notif_blocked_answered_refuse` promises Maia says when the run has
     * ended, and only the stream can produce that. So [status] is `Stopped`
     * while frames are still expected, and `Stopped` is not [live], which
     * would have taken the foreground service down inside the window the
     * promise has to be kept in. On a phone in a pocket that is not a
     * theoretical loss: a process without foreground importance is the first
     * thing Android reclaims, and the ending would then never arrive.
     *
     * It is a timestamp and not a flag because the wait has to be bounded.
     * Nothing obliges the far end to send anything, and a service held up by
     * a boolean nobody clears is a service held up until the day's
     * `dataSync` budget runs out. [SETTLE_MS] past this, the run machine
     * stops waiting, silently: it unsubscribes and changes nothing on screen,
     * because the user was told what they did and Maia never saw the ending
     * it was waiting for. Inventing one there is the thing rule 12 forbids.
     *
     * Cleared wherever [blockedRow] is cleared, which is every path that ends
     * a turn, and never set by anything but a refusal the far end took.
     */
    val settlingSince: Long = 0L,
    /**
     * True once ten seconds have passed with nothing at all, for
     * `m8_run_nothing_yet`. It appears once and then nothing further changes:
     * it does not count up and it does not estimate.
     */
    val nothingYet: Boolean = false,
    /**
     * Whether the run screen is in front of the user right now.
     *
     * The one field on this state that comes from a window rather than from
     * the wire, and it is here for one reason: the microphone. Section 5.18
     * opens the recogniser on a question that accepts an answer in the user's
     * own words, and Android will not give a background process the
     * microphone. Since Android 11 a capture started from the background
     * returns silence rather than an error, so a phone in a pocket would show
     * `The microphone is open` over a microphone that is not, which is the one
     * thing an announcement must never be false about.
     *
     * Raised by [RunEvent.Seen] and lowered by [RunEvent.Hidden], which are
     * the resume and the pause of the run screen.
     */
    val onScreen: Boolean = false,
) {
    /** The turn is live: `Stop` in the footer rather than `Ask again`. */
    val live: Boolean get() = status in LIVE

    /**
     * The turn ended for the user and the server has not said so yet.
     * See [settlingSince].
     */
    val awaitingEnd: Boolean get() = settlingSince > 0L

    /**
     * Whether this process still has to be here for the stream.
     *
     * [live] is the screen's question and this is the service's, and they are
     * not the same question: a refused turn is over in the footer, which says
     * `Ask again`, and not over on the wire. `RunService.follow` reads this
     * one, and nothing that draws reads it.
     */
    val holding: Boolean get() = live || awaitingEnd

    companion object {
        private val LIVE = setOf(
            RunStatus.Sending,
            RunStatus.Sent,
            RunStatus.Working,
            RunStatus.Queued,
            RunStatus.WaitingForYou,
        )
    }
}

/**
 * The status label, which is the orb's text twin and is mandatory at every
 * size on this screen (section 1.1). `docs/M8-copy.md` section 6.3 lists these
 * eight and no others: all constant, no counts and no timers, and written to
 * be read aloud by a screen reader as well as seen.
 */
enum class RunStatus {
    Sending,
    Sent,
    Working,
    Queued,
    WaitingForYou,
    Finished,
    Stopped,
    DidNotFinish,
}

/**
 * One instruction and its reply.
 *
 * [pieces] is append only (section 1.2). Text that has already rendered is
 * never re-laid-out, never re-wrapped and never replaced, which is an
 * accessibility requirement and not a preference: appended text that reflows
 * moves a screen reader's focus, and a re-layout during a read loses a user
 * their place in a forty-line reply with no way back. The only exception is
 * the tail: a delta extends the last [ReplyPiece.Prose] rather than starting a
 * new one, which is appending to it, not replacing it.
 */
data class Turn(
    val instruction: String,
    val pieces: List<ReplyPiece> = emptyList(),
    /** Null while the turn is live. Set once, at the point it stopped. */
    val end: EndMarker? = null,
    /**
     * The note the user typed with a refusal, printed under
     * `YOU REFUSED THIS` in `body` at `ink.mid`. Section 5.17.
     *
     * Their own words and not a string this product defines, which is the
     * same treatment `YOU SAID` already gives the instruction, and the safe
     * direction under rule 12: it went out, nothing of the agent's came back
     * through it.
     */
    val refusal: String? = null,
    /** When the instruction was admitted, on the run machine's clock. */
    val startedAt: Long = 0,
) {
    /** Nothing has arrived: the empty reply area of section 1.4. */
    val empty: Boolean get() = pieces.isEmpty()
}

/**
 * Two type treatments, no more (section 1.2), plus the tool line.
 *
 * Prose is `body`. Anything fenced is `data.sm` mono on `surface.sunken`, in
 * its own container that scrolls horizontally and is never wrapped, because a
 * wrapped shell command at 200 percent font scale is neither readable nor
 * copyable.
 */
sealed interface ReplyPiece {
    data class Prose(val text: String) : ReplyPiece

    /** A fenced block. [lines] is what `m8_run_code_block_cd` announces. */
    data class Code(val text: String) : ReplyPiece {
        val lines: Int get() = text.count { it == '\n' } + 1
    }

    /**
     * One `session.next.tool.called`, in place, in the order it happened.
     * Section 1.3. [target] is a single short target and is null when the
     * event carried nothing usable as one, which renders as
     * `m8_run_tool_line_bare` rather than an invented target. [count]
     * collapses a run of the same tool: three consecutive reads are one line
     * reading `read x3`.
     */
    data class Tool(val name: String, val target: String? = null, val count: Int = 1) : ReplyPiece

    /**
     * What the user did about a block, marked where it happened in the
     * stream. Section 5.17.
     *
     * In the reply flow rather than above it, because a user who answered
     * from the shade and opens the app a minute later has to find the moment
     * they answered and the reply carries straight on past it. It carries an
     * [AnswerMark] and no words: the marker is one of two constant labels.
     */
    data class Answered(val mark: AnswerMark, val lines: List<String> = emptyList()) : ReplyPiece

    /**
     * The question the agent asked, under `IT ASKED`, at the point in the
     * stream where it arrived. Section 5.18.
     *
     * In the reply flow and not above it, for the same reason
     * [ReplyPiece.Answered] is: the reply is append only, so the question is
     * still there after the answer has gone, which is what a user coming back
     * to the screen needs. [text] is the whole of every question in the
     * request, one paragraph each, because they arrive together and the user
     * reads them before answering the first.
     */
    data class Asked(val text: String) : ReplyPiece
}

/**
 * The two inline markers of section 5.17, which are the `YOU STOPPED THIS`
 * family and are drawn with no hairline because the reply carries on past
 * them.
 *
 * A refusal is not here. It closes the reply, so it is an [EndMarker].
 */
enum class AnswerMark {
    /** `m8_run_answered_allowed`. The status goes back to `WORKING`. */
    Allowed,

    /**
     * `m8_run_answered_too_late`. One label for two arrivals: a press that
     * was too late, and a request reconciled away that the user never
     * touched. [RunAnnounce] is what separates them for a screen reader.
     */
    StoppedWaiting,

    /**
     * `m8_run_answered_question`. The answers themselves go under it, in
     * [ReplyPiece.Answered.lines], one line per question.
     */
    Answered,

    /** `m8_run_question_skipped`: the empty answer list went and the turn runs on. */
    LetItDecide,

    /** `m8_run_question_dismissed`: the question was rejected and the turn runs on. */
    Dropped,

    /**
     * `m8_run_question_gone`, with `m8_run_question_gone_note` under it.
     *
     * Deliberately not [StoppedWaiting]. A 404 on a question reply is two
     * different facts wearing one status code, an expired question and an
     * answer that did not find a live one, and Maia cannot tell them apart.
     * `IT STOPPED WAITING` would be a claim about the agent that is wrong in
     * one of the two; this one reports the transaction instead.
     */
    NotTaken,
}

/**
 * A `question.asked`, as the screen works through it. Section 5.18.
 *
 * **A question is not free text.** It is a list of questions, each with
 * labelled options, and an answer in the user's own words is one of the ways
 * an answer can arrive rather than the only way. So the options are the
 * primary control and the microphone is the second way in, present only where
 * [AskedQuestion.custom] says the far end will take one.
 *
 * **Answers match questions by position**, which is why [answers] is a list
 * built in order and never a map: a gap in the middle cannot be expressed on
 * the wire without inventing a value for it, so the screen walks the questions
 * in order and the skip control ends the whole request rather than one
 * question of it.
 */
data class Asking(
    /** Every question in the request, in the order the agent sent them. */
    val questions: List<AskedQuestion>,
    /** Which one the options under the thumb belong to. */
    val at: Int = 0,
    /** The answers already given, by position, each a list of labels or one sentence. */
    val answers: List<List<String>> = emptyList(),
    /**
     * The same answers as the receipt prints them, one line per question.
     *
     * Kept beside [answers] rather than derived from them, because the two
     * are not the same string: what goes on the wire is the option label
     * exactly as the agent wrote it, and what goes on the screen is that
     * label with the `(Recommended)` marker stripped, which is the one edit
     * section 5.18 sanctions. A free text answer is the same in both.
     */
    val shown: List<String> = emptyList(),
    /**
     * The options ticked on the `CHOOSE ANY` case, as indices into
     * [current]'s options. Indices and not labels, so nothing here can drift
     * from the option it stands for when two options share a label.
     */
    val ticked: List<Int> = emptyList(),
    /** The live partial, inked by confidence, or null while nothing has been heard. */
    val heard: String? = null,
    /**
     * Whether the microphone is wanted for [at].
     *
     * Wanted, not open: [listening] is open. It is raised on arrival and on
     * each question that accepts a sentence, lowered the moment an answer is
     * decided, and raised again by `Say it again`. Whether the microphone
     * actually opens also needs [RunState.onScreen], which is Android's rule
     * and not this product's.
     */
    val saying: Boolean = false,
    /** The capture reported itself started, which is what `m8_run_answer_listening` claims. */
    val listening: Boolean = false,
    /** The microphone closed with nothing heard: `m8_run_answer_nothing_heard`. */
    val nothingHeard: Boolean = false,
    /** What the phone is trying to do. Null when nothing has been sent yet. */
    val sending: Sending? = null,
    /** How that came back, or null while it is still on its way. */
    val outcome: Outcome? = null,
    /**
     * Exactly what went to the far end, kept so `Send it again` can send the
     * same thing and so the receipt can print it.
     *
     * The empty list is a real value here and means `Decide without me`: the
     * model is told the question went unanswered. Null means nothing has been
     * sent at all.
     */
    val sent: List<List<String>>? = null,
) {
    /** The question the options belong to, or null once every one is answered. */
    val current: AskedQuestion? get() = questions.getOrNull(at)

    /** Whether the request carries more than one, which is when the count is shown. */
    val several: Boolean get() = questions.size > 1

    /** Whether a reply is on its way and the controls should not send a second. */
    val inFlight: Boolean get() = sending != null && outcome == null

    /** Whether the last attempt came back as something other than taken. */
    val failed: Boolean get() = outcome != null
}

/**
 * Which of section 5.18's three sends is being made.
 *
 * Three and not two, because the far end hears three different things: an
 * answer, a question reported unanswered, and a question the user turned
 * down. On the phone the last two are the same gesture with different words,
 * which is the argument section 9 item 5 records against having both.
 */
/**
 * The exact marker the agent is instructed to append to the label it
 * recommends. Section 5.18.
 */
private const val RECOMMENDED = " (Recommended)"

/**
 * The label with that marker taken off, which is the label the user reads,
 * the label a spoken answer is matched against, and never the label that goes
 * on the wire.
 *
 * The strip is exact and it is the only one. A label that marks itself some
 * other way keeps every word it has, because a second spelling guessed at is
 * a product that edits agent output.
 */
fun label(label: String): String =
    if (label.endsWith(RECOMMENDED, ignoreCase = true)) {
        label.dropLast(RECOMMENDED.length).trimEnd()
    } else {
        label
    }

/** Whether the chip is drawn above this row. */
fun recommended(label: String): Boolean = label.endsWith(RECOMMENDED, ignoreCase = true)

enum class Sending {
    /** The answers go, matched to questions by position. */
    Answer,

    /** The empty answer list goes: `Decide without me`. */
    Skip,

    /** The rejection goes: `Drop the question`. */
    Drop,
}

/**
 * How a send came back, when it did not come back taken.
 *
 * A reply that was taken leaves nothing here, because it leaves no [Asking]
 * either: the marker goes into the reply and the block is over.
 */
enum class Outcome {
    /** It never left the phone. `m8_run_answer_undelivered`, and the choice stays. */
    Undelivered,

    /** The far end had no such question. `m8_run_question_gone`, and the choice stays. */
    Gone,
}

/** Which clause `IT STOPPED WAITING` announces. Section 5.17. */
enum class RunAnnounce {
    /** `m8_run_too_late_announce`: "Your answer was not sent." */
    TooLate,

    /** `m8_run_withdrawn_announce`: "There is nothing to answer." */
    Withdrawn,
}

/**
 * How a turn ended, marked and never implied (section 1.2, and rule 12:
 * nothing invents an ending).
 */
enum class EndMarker {
    /** `session.idle`. `END OF REPLY`, on a solid hairline. */
    Done,

    /** The user said stop and `interrupt` returned. `YOU STOPPED THIS`. */
    Stopped,

    /**
     * The user refused a permission and the far end took the refusal.
     * `YOU REFUSED THIS`, on a solid hairline, section 5.17.
     *
     * Solid and not dashed for the reason section 5.12 gives `Stopped` its
     * solid rule: this is an ending Maia can account for exactly, and the
     * dashed rule is reserved for the ones it cannot. It is still not a
     * failure. The user meant it; what changed is only the size of what they
     * meant.
     */
    Refused,

    /**
     * The stream died and what is above is what got through. `STOPPED HERE`,
     * on a dashed hairline so the two ends are distinguishable with no colour
     * and no reading. This is rule 12 made visible.
     */
    Cut,
}

/** An agent has stopped and wants a human. The content stays off the notification. */
data class Block(
    val kind: BlockKind,
    val requestId: String,
    /**
     * What the request asked for: a tool name, or a directory glob.
     *
     * Read for one decision and drawn nowhere: section 5.3 draws
     * `m8_run_allow_session` only when this carries something to remember,
     * and when it is empty the control is simply not there. No greyed
     * control and no line explaining what is missing.
     *
     * These are agent-supplied strings, so they stay in the state, are never
     * rendered and never become an argument to a [RunEffect]. Only their
     * count is ever asked about.
     */
    val patterns: List<String> = emptyList(),
)

enum class BlockKind { Permission, Question }

/**
 * The six things the one blocked notification can say. Sections 5.3 and 5.17.
 *
 * One row, rewritten in place: same id, same channel, `setOnlyAlertOnce(true)`
 * so that only [Asking] ever reaches the user as an alert and every later
 * state is a correction they find rather than one that interrupts them.
 *
 * Same shape as [AgentAck], for the same reason: the type that decides what a
 * notification says carries no String, so there is no field an agent's words
 * could travel in.
 */
enum class BlockedNotice {
    /** `m8_notif_blocked_*`, with its actions. The only one that alerts. */
    Asking,

    /** [dev.maia.transport.ReplyOutcome.ACCEPTED] on an allow. Actions gone. */
    Allowed,

    /** The same on a refusal. Different words, and the turn ends. Actions gone. */
    Refused,

    /**
     * The answer never left the phone: an `IOException`, not an outcome. The
     * actions stay, because the press can be repeated.
     */
    Undelivered,

    /** [dev.maia.transport.ReplyOutcome.GONE]. The press was too late. */
    Gone,

    /**
     * Reconciliation found the request already gone, with nothing pressed.
     * The same news as [Gone] and the user did nothing to earn it, so it is
     * the silent correction of 5.17's third case.
     */
    Stale,
}

/** `todo.updated`, only when it carries counts. */
data class Plan(val done: Int, val total: Int)

/**
 * The faults of sections 5.6 to 5.9. Each is a screen with its own title, and
 * each is spoken as a short line because the user just spoke and is owed an
 * answer in the same breath (rule 10).
 */
enum class RunFault {
    /** No route to the machine. `m8_tunnel_off_title`. */
    TunnelOff,

    /** Tunnel up, agent port silent. `m8_no_server_title`. */
    NoServer,

    /** 401. `m8_auth_title`. */
    Refused,

    /** A number outside the registry. `m8_no_project_title`. */
    NoProject,

    /** A number that was allocated and whose project is gone. `m8_retired_project_title`. */
    Retired,

    /**
     * The turn ended without a `session.idle`: `session.error` or
     * `session.next.step.failed`, or a stream lost past one reconnect. The
     * partial reply stays on screen, because a user told something failed and
     * offered nothing to look at assumes everything was lost.
     */
    TurnFailed,
}

/**
 * The five reason sentences of section 5.4, as an enum.
 *
 * The same shape as [AgentAck] and for the same reason: the thing that decides
 * what a notification says carries no String, so there is no field an agent's
 * error text could travel in. [RunCopy.reason] turns one of these into a
 * resource id, and `m8_notif_failed_reason_agent_error` is written in the copy
 * with no format argument at all, so interpolating into it does not compile.
 */
enum class RunLoss {
    /** The tunnel dropped mid-run. `The tunnel went down.` */
    Tunnel,

    /** The tunnel is up and the agent port went silent. `Your machine stopped answering.` */
    NoAnswer,

    /** A 401 mid-run, usually a rotated passphrase. `The passphrase was refused.` */
    Refused,

    /**
     * Ninety seconds of silence and one failed reconnect. `The connection
     * stopped.`
     *
     * It names silence and not a cause, because silence is what the phone
     * observed. It did not see a tunnel go down or a machine go to sleep, and
     * saying the true smaller thing is the same restraint as "was refused"
     * rather than "is wrong".
     */
    Lost,

    /** `session.error` or `session.next.step.failed`. `The agent stopped with an error.` */
    AgentError,
}

/** The numbered list, and the shortlist above it when there is one. */
data class Chooser(
    /** What the recogniser made of the name, shown under `YOU SAID`. */
    val spoken: String,
    /** The shortlist, in number order. Empty when nothing matched at all. */
    val matches: List<ProjectEntry> = emptyList(),
    /** Every active project, in number order, always. Never by recency. */
    val all: List<ProjectEntry> = emptyList(),
    /** The instruction being held: `m8_list_held`. It goes as soon as you choose. */
    val held: String? = null,
    /** The number the user said that does not exist, for `m8_no_project_body`. */
    val badNumber: Int? = null,
)

/**
 * The short confirmations Maia IS allowed to speak, as an enum.
 *
 * This type is the enforcement of `docs/M8-copy.md` section 2, and its shape
 * is the whole argument. Every spoken line in this feature is a constant
 * string resource chosen by one of these members, and the only values that
 * travel with it are integers: a project number, and for
 * `m8_spoken_sent_moved` a second one. **There is no String field on this
 * type and there must never be one**, because a String field is exactly the
 * road by which an agent's words would reach a speaker, and a comment asking
 * people to be careful is not a guard. `AgentSpeechInvariantTest` asserts it.
 *
 * The same reasoning already governs `Effect.SpeakNote`, which carries no
 * payload at all (PRD section 4 principle A).
 */
enum class AgentAck {
    /** `m8_spoken_sent`: the instruction was admitted by the server. */
    Sent,

    /** `m8_spoken_sent_moved`: admitted, and a different project held the stream. */
    SentMoved,

    /** `m8_spoken_queued`: admitted with `delivery: "queue"`. */
    Queued,

    /** `m8_spoken_stopped`: the user said stop and `interrupt` returned. */
    Stopped,

    /** `m8_spoken_which_project`: the spoken name matched nothing. */
    WhichProject,

    /** `m8_spoken_which_one`: the spoken name matched more than one. */
    WhichOne,

    /** `m8_spoken_tunnel_off`. */
    TunnelOff,

    /** `m8_spoken_no_answer`. */
    NoAnswer,

    /** `m8_spoken_refused`. */
    Refused,

    /** `m8_spoken_no_project`. */
    NoProject,

    /**
     * `m8_spoken_not_set_up`: an agent sentence arrived before the phone was
     * ever paired.
     *
     * The eleventh, added on 2026-09-20 with copy section 5.15. Until it
     * existed an unpaired phone answered an agent sentence with silence, on
     * the argument that an unpaired phone has no agent surface. Silence is the
     * one answer the user cannot tell apart from Maia not having heard them,
     * so they say it again, louder, and it fails the same way. It says
     * "agents", not "Maia": Maia just answered, so a sentence claiming Maia is
     * not set up is contradicted by the fact of the reply.
     */
    NotSetUp,

    /**
     * `m8_spoken_stop_not_sent`: `interrupt` never reached the machine.
     *
     * Spoken, and section 5.12 gives the reason: the user said "stop" seconds
     * ago and is owed an answer in the same breath. Its on-screen twin is
     * `m8_run_stop_failed`, above the footer, with `Stop` still there.
     */
    StopNotSent,

    /**
     * `m8_spoken_stop_too_late`: the far end says there was nothing running.
     *
     * The words avoid "finished". The turn may have ended either way, and
     * Maia only observed that there was nothing left to interrupt. It needs
     * no on-screen twin: the screen is already showing the end marker.
     */
    StopTooLate,
}

/**
 * The aperture's pose for a run, as a function of the screen alone, exactly as
 * `FlowState.aperture` is. `docs/M8-copy.md` section 3.2.
 *
 * Two of these are worth their reasoning.
 *
 * **Blocked is `Listening`, and it is truthful rather than borrowed.** With the
 * run screen open and the phone unlocked, an agent blocking opens the
 * microphone, because the natural answer to "may I run this" is "yes" said out
 * loud and a user watching a run should not have to reach for a button to say
 * one word. The pose is not standing in for anything: the microphone genuinely
 * is open and the rim genuinely does move with the room.
 *
 * **A stop the user asked for rests at `Dormant`, not `Fault`.** `Fault` there
 * would be the interface telling the user off for using a feature.
 */
val RunState.aperture: ApertureState
    get() = when (status) {
        // Maia is working with the microphone shut, which is what Thinking
        // means, and the round trip is the same order as a parse.
        RunStatus.Sending -> ApertureState.Thinking
        // The queue is a fact in words and not a pose: it is the same
        // condition with one more item in it, and the agent is working.
        RunStatus.Sent, RunStatus.Working, RunStatus.Queued -> ApertureState.Working
        // Blocked on a permission, or on a question whose far end takes a
        // sentence: the microphone genuinely is open and the rim genuinely
        // does move with the room. Blocked on a question that takes only its
        // own labels, it is not, and section 5.18 says so: `Thinking` rather
        // than a pose standing in for a microphone that would be refused.
        RunStatus.WaitingForYou ->
            if (asking != null && !asking.saying) {
                ApertureState.Thinking
            } else {
                ApertureState.Listening
            }
        RunStatus.Finished, RunStatus.Stopped -> ApertureState.Dormant
        RunStatus.DidNotFinish -> ApertureState.Fault
    }

/**
 * The tool calls this turn has made so far, which step the orb's working arc
 * once each. Read off the reply pieces, where a run of the same call is
 * collapsed into one line with a count, so the sum is every call the stream
 * reported. It starts again from zero with each turn, which the orb takes as
 * a new run.
 */
val RunState.toolCalls: Int
    get() = turn?.pieces?.sumOf { (it as? ReplyPiece.Tool)?.count ?: 0 } ?: 0
