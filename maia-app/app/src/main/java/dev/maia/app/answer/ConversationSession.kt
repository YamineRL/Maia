package dev.maia.app.answer

import dev.maia.transport.Role
import dev.maia.transport.Turn
import java.util.UUID

/**
 * The conversation this surface remembers, as PRD section 7 bounds it.
 *
 * Process-scoped: it outlives any one answer screen and dies with the process,
 * which is exactly the lifetime the PRD gives it. What it holds is bounded in
 * three directions at once: at most [MAX_PAIRS] exchanges, only text the user
 * actually saw, and nothing older than [EXPIRY_MS] of quiet. There is nowhere
 * here for credentials, audio, agent events, calendar contents or a hidden
 * prompt, because the type was written so none of them fit.
 *
 * A class and not a value, unlike `FlowSession` or `RunSession`, because its
 * owner is the process rather than the reducer: [AnswerEffect.AskRemote]
 * carries a snapshot of it, [AnswerEffect.ClearSession] asks that it be wiped,
 * and neither has a field for the session itself. The [AnswerMachine] holds
 * the one instance and mutates it through the three verbs below.
 *
 * The clock is never read. `now` arrives on every call from the same source
 * the machine's events use, so a test moves the ten-minute window by moving a
 * long, and there is one clock in the surface rather than two that can drift.
 */
class ConversationSession(
    /**
     * A random in-memory id. It exists so a request can be correlated on the
     * gateway without carrying anything identifying; it never leaves the
     * process on this side and is never written down. Injected so a test can
     * pin it.
     */
    val id: String = newId(),
) {

    /**
     * The exchanges the user saw, oldest first, at most [MAX_PAIRS] of them.
     * Read-only to the outside; a copy, so the list cannot be grown around
     * the cap.
     */
    val exchanges: List<Exchange>
        get() = pairs.toList()

    /** When the last exchange completed, on the caller's clock. */
    var lastActiveAt: Long = 0L
        private set

    private val pairs = ArrayDeque<Exchange>()

    /**
     * The history a remote ask may carry: the kept exchanges flattened into
     * wire [Turn]s, or nothing once the window has run out.
     *
     * Expiry is checked here rather than trusted to a timer, because a stale
     * list is only ever wrong in one direction: it would send context the
     * user thinks is gone. Checking at the point of use means the ten minutes
     * cannot be widened by a missed event. An expired session is emptied for
     * real, not merely hidden, so the reset the PRD describes happens the
     * first time anyone looks.
     */
    fun history(now: Long): List<Turn> {
        if (expired(now)) pairs.clear()
        return pairs.flatMap { exchange ->
            listOf(
                Turn(Role.USER, exchange.asked),
                Turn(Role.ASSISTANT, exchange.answered),
            )
        }
    }

    /**
     * Keep one exchange, both halves of which were on screen.
     *
     * "Only shown text enters it" is the caller's rule; the guard here is
     * narrower: an exchange missing either half is refused, because a
     * one-sided [Turn] would put words in a mouth that never said them.
     * When the cap is reached the oldest pair is dropped before the next one
     * is kept, which is section 7's rule stated as `while` rather than as a
     * hope that callers keep count.
     */
    fun record(asked: String, answered: String, now: Long) {
        if (asked.isBlank() || answered.isBlank()) return
        if (expired(now)) pairs.clear()
        pairs.addLast(Exchange(asked, answered))
        while (pairs.size > MAX_PAIRS) pairs.removeFirst()
        lastActiveAt = now
    }

    /** "Clear this conversation". Local, immediate, and silent on the wire. */
    fun clear() {
        pairs.clear()
        lastActiveAt = 0L
    }

    private fun expired(now: Long): Boolean =
        pairs.isNotEmpty() && now - lastActiveAt >= EXPIRY_MS

    companion object {
        /** Section 7: at most six user/assistant turn pairs. */
        const val MAX_PAIRS = 6

        /** Section 3.1: follow-up context expires after ten minutes of inactivity. */
        const val EXPIRY_MS = 10 * 60 * 1_000L

        /** The id scheme: random, non-identifying, never persisted. */
        fun newId(): String = UUID.randomUUID().toString()
    }
}
