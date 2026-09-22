package dev.maia.app.agent

import dev.maia.app.feel.Pattern
import dev.maia.transport.ProjectEntry

/**
 * Which of the three unprompted events is worth a notification, and which is
 * not. `docs/M8-copy.md` sections 4.1 and 5.4.
 *
 * This is the whole of step 5's decision-making, and it is here rather than in
 * [AgentNotifier] because it is decision-making: [AgentNotifier] posts what it
 * is given and knows nothing about a run. Everything in this file is pure
 * Kotlin over [RunState], so `RunAlertsTest` drives it with no `Context`, no
 * `NotificationManager` and no phone.
 *
 * Three rules, and the third is the one that matters:
 *
 * **A hand pattern goes to the hand.** Sent, queued and stopped are the
 * consequence of something the user just did, so they stay `USAGE_TOUCH`
 * through [Haptics] and are never a notification.
 *
 * **A notification is the vibration.** Ended, blocked and failed have no
 * [Haptics] call at all: the channel carries the buzz, so Do Not Disturb
 * governs them and a phone put down for the night stays quiet.
 *
 * **Not every fault is a notification.** `RunEvent.Failed` emits
 * `Schedule.fault` for the faults that happen before anything was sent as
 * well: tunnel off, no server, a refused passphrase, a number with no
 * project. Those are on the screen the user is looking at and were spoken in
 * the same breath as the instruction, and "%1$d %2$s did not finish" over a
 * turn that never started would be a lie about a run that does not exist. A
 * failed notification is posted only when a turn was live and stopped being
 * live badly.
 */
class RunAlerts(
    /** The state as of the event being felt. [AgentDriver] renders before it performs. */
    private val state: () -> RunState,
    /** The `USAGE_TOUCH` half, which is [AgentNotifier.feel]'s [Haptics]. */
    private val hand: (Pattern) -> Unit,
    private val post: (Posting) -> Unit,
    /**
     * The blocked row being rewritten in place. Default no-op, because a
     * caller that only wants the three alerts of section 4.1 is a complete
     * caller: the rewrite is section 5.17's and adds nothing to them.
     */
    private val rewrite: (BlockedRow) -> Unit = {},
    /**
     * Section 4.2's one repeat: schedule it with a [Knock], cancel it with
     * null. Default no-op for the same reason [rewrite] has one.
     */
    private val knock: (Knock?) -> Unit = {},
    private val clear: (RunAlert) -> Unit,
    /** The phone's own clock, for the duration in a finished run's body. */
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * One notification to post. There is no text on it and no field one could
     * arrive through: a project, and the kind of block for the two bodies
     * section 5.3 defines. Same shape as [AgentAck] and for the same reason.
     */
    data class Posting(
        val alert: RunAlert,
        val project: ProjectEntry,
        /** The kind of block, for section 5.3's two bodies. Never its content. */
        val kind: BlockKind? = null,
        /** Which of section 5.4's five reason sentences a lost run gets. */
        val loss: RunLoss? = null,
        /** When the turn started and when it ended, for section 5.2's duration. */
        val startedAt: Long = 0,
        val endedAt: Long = 0,
    )

    /**
     * What the one blocked row says now. Sections 5.3 and 5.17: one id, one
     * channel, `setOnlyAlertOnce(true)`, edited rather than replaced.
     *
     * There is no text here either, and [BlockedNotice] is why there cannot
     * be: the six states are an enum and the words are resource ids.
     */
    data class BlockedRow(
        val notice: BlockedNotice,
        val project: ProjectEntry,
        /** Only for the states that keep their actions. Never its content. */
        val kind: BlockKind? = null,
        /** Which of section 5.4's four sentences an undelivered answer gets. */
        val loss: RunLoss? = null,
    )

    /**
     * The one repeat, as the two facts it needs and no others. Section 4.2.
     *
     * A project and a kind, which is exactly what posted the row in the first
     * place, because the repeat re-posts that row rather than composing a
     * second one. No text here either, for the reason [Posting] has none.
     */
    data class Knock(val project: ProjectEntry, val kind: BlockKind)

    private val showing = mutableSetOf<RunAlert>()

    /** The row as last drawn, so an unchanged state is not redrawn. */
    private var row: BlockedNotice? = null

    /**
     * The hand's sink, for `RunEffect.FeelByHand`.
     *
     * Section 4.6 adds three events and no seventh pattern, and all three are
     * the hand's: an answer or a stop that did not land is felt a second
     * after the thumb that caused it. It goes straight through, because the
     * decision has already been made by the effect that got here.
     */
    fun feelByHand(pattern: Pattern) = hand(pattern)

    /** The agent's haptic sink, for `RunEffect.Feel`. */
    fun feel(pattern: Pattern) {
        if (AgentAlerts.byHand(pattern)) {
            hand(pattern)
            return
        }
        val posting = decide(pattern, state(), clock()) ?: return
        showing += posting.alert
        post(posting)
        // Section 4.2: blocked is the only pattern in this product that
        // repeats, once, twenty seconds later, and it repeats because it is
        // the only event where a person is actually needed by something that
        // has stopped. Scheduled here and nowhere else, so the thing that
        // raised the row is the thing that arranges to raise it again.
        if (posting.alert == RunAlert.Blocked && posting.kind != null) {
            knock(Knock(posting.project, posting.kind))
        }
    }

    /**
     * Withdraws a notification the screen has moved past.
     *
     * A blocked notification outliving its block is the worst of these: the
     * user answers on the screen, the agent carries on, and a notification
     * still says an agent is waiting for them. Ended and failed are withdrawn
     * when the next turn goes live, because the user is plainly back.
     *
     * Called from [AgentDriver]'s render sink, which runs before the effects
     * of the same event, so a block's own posting is never cleared by the
     * render that carried it.
     */
    fun render(current: RunState) {
        val wanted = current.blockedRow
        when {
            // The row and the block do not end together. An answer clears the
            // block and leaves the row saying what became of it, so the row
            // going null is the only thing that cancels it: the turn ending,
            // a new one starting, or the screen being opened on it.
            wanted == null -> {
                row = null
                knock(null)
                drop(RunAlert.Blocked)
            }
            // `Asking` is posted by `feel`, through the channel, with its
            // vibration. Everything after it is a correction the user finds
            // rather than one that interrupts them.
            wanted != row && wanted != BlockedNotice.Asking -> {
                row = wanted
                // The repeat is cancelled by everything that moves the row
                // off `Asking`: an answer that landed, one that did not, a
                // request reconciled away. Section 5.17's own sentence is
                // that it stops as soon as a human is demonstrably present,
                // and every one of these is a human being present.
                knock(null)
                // A rewrite, never a first post. The row is cancelled when
                // the run screen is opened on that project, and a user who is
                // looking at the screen they answered on should not be handed
                // a fresh notification saying they answered.
                if (RunAlert.Blocked in showing) {
                    current.project?.let {
                        rewrite(BlockedRow(wanted, it, current.blocked?.kind, current.answerFailed))
                    }
                }
            }
            else -> row = wanted
        }
        if (current.live) {
            drop(RunAlert.Ended)
            drop(RunAlert.Failed)
        }
    }

    private fun drop(alert: RunAlert) {
        if (showing.remove(alert)) clear(alert)
    }

    companion object {

        /**
         * The decision, as a function. Null means the user is told by the
         * screen and the spoken line and by nothing else.
         */
        fun decide(pattern: Pattern, state: RunState, now: Long = 0): Posting? {
            val alert = AgentAlerts.alertFor(pattern) ?: return null
            val project = state.project ?: return null
            return when (alert) {
                RunAlert.Ended -> Posting(
                    alert,
                    project,
                    startedAt = state.turn?.startedAt ?: 0,
                    endedAt = now,
                )
                // The kind and never the content. A block with no kind on the
                // state is not a block worth waking someone for.
                RunAlert.Blocked -> state.blocked?.let { Posting(alert, project, kind = it.kind) }
                // [RunState.loss] is the run machine's own record of a turn
                // that was admitted and then stopped. Null is every fault that
                // happened before a byte was sent, and those are on the screen
                // and were spoken; they get no notification.
                RunAlert.Failed -> state.loss?.let { Posting(alert, project, loss = it) }
            }
        }
    }
}
