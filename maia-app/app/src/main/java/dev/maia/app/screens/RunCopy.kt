package dev.maia.app.screens

import dev.maia.app.R
import dev.maia.app.agent.AgentAck
import dev.maia.app.agent.AnswerMark
import dev.maia.app.agent.Block
import dev.maia.app.agent.EndMarker
import dev.maia.app.agent.Outcome
import dev.maia.app.agent.ReplyPiece
import dev.maia.app.agent.RunAnnounce
import dev.maia.app.agent.RunLoss
import dev.maia.app.agent.RunState
import dev.maia.app.agent.RunStatus
import dev.maia.app.agent.Turn
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * What the run screen says, decided without Compose.
 *
 * The same split as [FlowCopy] and `cardRows`: [RunScreen] only draws, and
 * every choice about which words appear is made here, where a JVM test can pin
 * it against `docs/M8-copy.md` without a resource compiler or a device.
 *
 * Everything below returns a resource id or a number. **Nothing here returns a
 * string built from an agent's text**, which is the same discipline
 * `AgentAck` enforces one layer down: the reply reaches the screen as
 * [ReplyPiece] values that are drawn, and it reaches no formatter, no
 * announcement and no content description. `RunCopyTest` checks that by
 * shape, and the one deliberate exception is [codeBlockLines], which is a
 * count of newlines and not the text.
 */
object RunCopy {

    /**
     * The status label, section 6.3's eight, in order.
     *
     * Mandatory at every size on this screen (section 1.1): the orb is 64 dp in
     * the header and `docs/design/README.md` is explicit that below widget size
     * the form is never the sole indicator. So this is not decoration, it is
     * the orb's text twin, and it is also the screen's one live region.
     */
    fun status(status: RunStatus): Int = when (status) {
        RunStatus.Sending -> R.string.m8_run_status_sending
        RunStatus.Sent -> R.string.m8_run_status_sent
        RunStatus.Working -> R.string.m8_run_status_working
        RunStatus.Queued -> R.string.m8_run_status_queued
        RunStatus.WaitingForYou -> R.string.m8_run_status_waiting_for_you
        RunStatus.Finished -> R.string.m8_run_status_finished
        RunStatus.Stopped -> R.string.m8_run_status_stopped
        RunStatus.DidNotFinish -> R.string.m8_run_status_did_not_finish
    }

    /**
     * The spoken line for an [AgentAck], section 6.3's second table.
     *
     * The only reason this is here and not on [AgentAck] itself is that
     * [AgentAck] is in the agent package and a resource id is a screen's
     * business. The shape is the point: an ack and two integers in, a
     * resource id out, and no road by which an agent's words could become
     * something Maia says. `AgentSpeechInvariantTest` holds that line.
     */
    fun spoken(ack: AgentAck): Int = when (ack) {
        AgentAck.Sent -> R.string.m8_spoken_sent
        AgentAck.SentMoved -> R.string.m8_spoken_sent_moved
        AgentAck.Queued -> R.string.m8_spoken_queued
        AgentAck.Stopped -> R.string.m8_spoken_stopped
        AgentAck.WhichProject -> R.string.m8_spoken_which_project
        AgentAck.WhichOne -> R.string.m8_spoken_which_one
        AgentAck.TunnelOff -> R.string.m8_spoken_tunnel_off
        AgentAck.NoAnswer -> R.string.m8_spoken_no_answer
        AgentAck.Refused -> R.string.m8_spoken_refused
        AgentAck.NoProject -> R.string.m8_spoken_no_project
        AgentAck.NotSetUp -> R.string.m8_spoken_not_set_up
        AgentAck.StopNotSent -> R.string.m8_spoken_stop_not_sent
        AgentAck.StopTooLate -> R.string.m8_spoken_stop_too_late
    }

    /**
     * The format arguments [spoken] takes, which are project numbers and
     * nothing else.
     *
     * A count rather than a guess: passing a number to a line that has no
     * placeholder is silently harmless, and failing to pass one to a line that
     * has two is a crash at the moment the user spoke. So the arity is
     * declared next to the mapping it belongs to.
     */
    fun spokenArgs(ack: AgentAck, number: Int?, other: Int?): Array<Any> = when (ack) {
        AgentAck.Sent, AgentAck.Queued, AgentAck.NoProject -> arrayOf(number ?: 0)
        AgentAck.SentMoved -> arrayOf(number ?: 0, other ?: 0)
        else -> emptyArray()
    }

    /**
     * The end marker, section 1.2. Null while the turn is live: the end of a
     * turn is marked and never implied, so there is no marker until there is
     * something true to say.
     */
    /**
     * The sentence a "did not finish" notification leads with, section 5.4.
     *
     * Five constants, chosen by a [RunLoss] the run machine set when it
     * observed the failure. `AgentError` is the one worth naming: it is the
     * string an implementation is tempted to interpolate the agent's own error
     * into, so the copy gives it no format argument at all and the temptation
     * is a compile error rather than a judgement call.
     */
    fun reason(loss: RunLoss): Int = when (loss) {
        RunLoss.Tunnel -> R.string.m8_notif_failed_reason_tunnel
        RunLoss.NoAnswer -> R.string.m8_notif_failed_reason_no_answer
        RunLoss.Refused -> R.string.m8_notif_failed_reason_refused
        RunLoss.Lost -> R.string.m8_notif_failed_reason_lost
        RunLoss.AgentError -> R.string.m8_notif_failed_reason_agent_error
    }

    /**
     * The ongoing notification's body, section 5.14.
     *
     * Three and not one, because the row stands for the whole run and a row
     * that says `Running.` while the agent is blocked is a lie that sits there
     * for as long as the user ignores it. The three track section 6.3's eight
     * labels: `SENDING`, `SENT` and `WORKING` are one condition to anyone who
     * is not watching the screen, and the other two are not.
     */
    fun ongoingBody(status: RunStatus): Int = when (status) {
        RunStatus.Queued -> R.string.m8_notif_open_body_queued
        RunStatus.WaitingForYou -> R.string.m8_notif_open_body_waiting
        else -> R.string.m8_notif_open_body_working
    }

    fun endMarker(end: EndMarker?): Int? = when (end) {
        null -> null
        EndMarker.Done -> R.string.m8_run_end_marker_done
        EndMarker.Stopped -> R.string.m8_run_end_marker_stopped
        EndMarker.Refused -> R.string.m8_run_answered_refused
        EndMarker.Cut -> R.string.m8_run_end_marker_cut
    }

    /**
     * The one line under an end marker, or null for the ends that have none.
     *
     * `m8_run_stopped_note` is the same sentence for both, deliberately and
     * not by reuse of convenience: a stop and a refusal are the same fact from
     * the user's side, that what the agent had already done to their files is
     * still done. Section 5.12 wrote it for `YOU STOPPED THIS` and section
     * 5.17 gives it to `YOU REFUSED THIS` unchanged.
     *
     * `END OF REPLY` and `STOPPED HERE` get none. The first has nothing to
     * warn about and the second is a failure, where a note about changes
     * surviving would read as an accusation.
     */
    fun endMarkerNote(end: EndMarker?): Int? = when (end) {
        EndMarker.Stopped, EndMarker.Refused -> R.string.m8_run_stopped_note
        else -> null
    }

    /**
     * The inline marker, section 5.17, drawn where it happened in the reply
     * and with no hairline: the reply carries on past both of these.
     */
    fun answerMark(mark: AnswerMark): Int = when (mark) {
        AnswerMark.Allowed -> R.string.m8_run_answered_allowed
        AnswerMark.StoppedWaiting -> R.string.m8_run_answered_too_late
        AnswerMark.Answered -> R.string.m8_run_answered_question
        AnswerMark.LetItDecide -> R.string.m8_run_question_skipped
        AnswerMark.Dropped -> R.string.m8_run_question_dismissed
        AnswerMark.NotTaken -> R.string.m8_run_question_gone
    }

    /**
     * The line under `IT DID NOT TAKE THAT`, which is the only marker of the
     * four that has one.
     *
     * It says Maia cannot tell which of the two happened, and that is the
     * whole point of the string: the far end answers a replayed reply and an
     * expired question with the same status, so a confident sentence about
     * the agent would be invented.
     */
    fun answerMarkNote(mark: AnswerMark): Int? =
        if (mark == AnswerMark.NotTaken) R.string.m8_run_question_gone_note else null

    /**
     * `CHOOSE ONE` or `CHOOSE ANY`, which is also the promise about what a tap
     * does: one sends, any waits for `Send`.
     */
    fun optionsLabel(multiple: Boolean): Int =
        if (multiple) R.string.m8_run_options_label_many else R.string.m8_run_options_label

    /**
     * What the live region says when a question arrives. Section 5.18.
     *
     * A second function rather than a branch inside [announcement], because
     * that one takes the two things a screen reader needs for an ended turn
     * and this one needs the microphone, which is neither of them. Both are
     * formatted with the project number, and the number is the phone's.
     */
    fun questionAnnouncement(state: RunState): Int? {
        val asking = state.asking ?: return null
        if (asking.outcome == Outcome.Gone) return R.string.m8_run_question_gone_announce
        return if (asking.listening) {
            R.string.m8_run_question_announce_open
        } else {
            R.string.m8_run_question_announce
        }
    }

    /**
     * What `IT STOPPED WAITING` announces, which is the only thing separating
     * its two arrivals.
     *
     * The words on the screen are identical because the fact is identical.
     * Only the user who pressed something can be told their answer was not
     * sent; the user who pressed nothing is told there is nothing to answer,
     * which is news they can act on rather than an apology for a thing they
     * did not do.
     */
    fun answerAnnouncement(announce: RunAnnounce?): Int? = when (announce) {
        null -> null
        RunAnnounce.TooLate -> R.string.m8_run_too_late_announce
        RunAnnounce.Withdrawn -> R.string.m8_run_withdrawn_announce
    }

    /**
     * Whether the run screen draws `m8_run_allow_session`.
     *
     * Section 5.3: only when the request carries something to remember. With
     * nothing to remember, [dev.maia.transport.PermissionReply.ALWAYS] does
     * exactly what `Allow` does, and a control promising to stop the asking
     * would be a promise about nothing. When it is not drawn, nothing marks
     * its absence: no greyed control, no line explaining what is missing.
     */
    fun sessionControl(block: Block?): Boolean = block?.patterns?.isNotEmpty() == true

    /**
     * `m8_run_end_marker_cut` sits on a dashed hairline rather than a solid
     * one, so the two ends are distinguishable with no colour and no reading.
     * That is the same trick the guessed-value marker uses, and it is why this
     * is a function of the marker rather than of the fault.
     */
    fun endMarkerDashed(end: EndMarker?): Boolean = end == EndMarker.Cut

    /**
     * The caption above the footer when something the user pressed did not
     * leave the phone. Sections 5.12 and 5.17.
     *
     * Both take one of [reason]'s sentences, and the answer case takes one of
     * only four of them: [RunLoss.AgentError] is a claim about the agent that
     * a round trip which never arrived is no evidence for. The run machine
     * never sets it here.
     *
     * Null when nothing failed, which is the ordinary case and the one worth
     * keeping cheap: no empty row, no reserved height.
     */
    fun failedNote(state: RunState): Pair<Int, Int>? = when {
        state.answerFailed != null -> R.string.m8_run_answer_undelivered to reason(state.answerFailed)
        state.stopFailed != null -> R.string.m8_run_stop_failed to reason(state.stopFailed)
        else -> null
    }

    /**
     * The footer control: `Stop` while the turn is live, `Ask again` once it
     * has ended (section 5.12). One control, never both.
     */
    fun footerAction(state: RunState): Int =
        if (state.live) R.string.m8_run_stop_action else R.string.m8_run_ask_again_action

    fun footerDescription(state: RunState): Int? =
        if (state.live) R.string.m8_run_stop_cd else null

    /**
     * The one announcement at the end of a turn, section 6.1, or null when
     * there is nothing to announce.
     *
     * The reply itself is never a live region: on streaming text a live region
     * either interrupts itself every few hundred milliseconds or queues
     * minutes of speech behind the user's next gesture, and the second is
     * worse because it cannot be escaped. So one utterance, once.
     *
     * **None of these three carries a word of the reply**, and that is not an
     * accident of wording. An announcement is a speech path, and section 2.1
     * covers every speech path and not only the one marked "speak": a screen
     * reader reading the agent's answer out loud because Maia handed it to
     * `announceForAccessibility` would be the rule broken from the other side.
     * `m8_run_blocked_announce` takes a project number and the other two take
     * nothing at all.
     */
    fun announcement(end: EndMarker?, blocked: Boolean): Int? = when {
        blocked -> R.string.m8_run_blocked_announce
        end == EndMarker.Done -> R.string.m8_run_done_announce
        end == EndMarker.Cut -> R.string.m8_run_cut_announce
        // A stop the user asked for is not announced: they pressed the control
        // and heard the spoken confirmation, and TalkBack has already read the
        // status label going STOPPED. A third utterance for one decision is
        // noise.
        else -> null
    }

    /**
     * The tool line, section 1.3: the name as the server gives it, lower case,
     * unchanged, and a single short target when the event carried one.
     *
     * There is no translation layer turning `bash` into "Running a command".
     * The user of this feature is the person whose machine it is, and naming
     * the tool is shorter and truer than describing it. A screen reader gets
     * the same line, for the same reason.
     */
    fun toolLine(piece: ReplyPiece.Tool): Int = when {
        piece.count > 1 -> R.string.m8_run_tool_repeat
        piece.target != null -> R.string.m8_run_tool_line
        else -> R.string.m8_run_tool_line_bare
    }

    /** Code blocks announce themselves once, as `Code, %1$d lines`, and are navigable by line. */
    fun codeBlockLines(piece: ReplyPiece.Code): Int = piece.lines

    /**
     * Whether the empty reply area has anything under the status label yet.
     *
     * Section 1.4: no spinner, no skeleton, no progress bar and no three
     * animated dots. The orb is the product's one moving part and a second one
     * competing with it is what PRD section 11 forbids. After ten seconds, one
     * caption, once, which does not count up and does not estimate.
     */
    fun nothingYet(state: RunState): Int? =
        if (state.nothingYet && state.turn?.empty != false) R.string.m8_run_nothing_yet else null

    /**
     * Earlier turns, oldest at the top, collapsed to one line (section 1.5).
     *
     * The instruction identifies the turn and the reply does not, because a
     * reply's first words are rarely about anything. The instruction is the
     * user's own sentence, so it is the one piece of text on this screen that
     * may be formatted into a description.
     */
    fun earlier(state: RunState): List<Turn> = state.earlier

    /**
     * The local time an earlier turn started, in mono, for
     * `m8_run_earlier_turn`.
     *
     * The user's own clock and their own 12 or 24 hour setting, which is what
     * `DateTimeFormatter.ofLocalizedTime(SHORT)` gives. No date: the run
     * screen holds one session, and a session is a sitting.
     */
    fun earlierTime(turn: Turn, zone: ZoneId = ZoneId.systemDefault()): String =
        TIME.format(Instant.ofEpochMilli(turn.startedAt).atZone(zone))

    private val TIME: DateTimeFormatter =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
}
