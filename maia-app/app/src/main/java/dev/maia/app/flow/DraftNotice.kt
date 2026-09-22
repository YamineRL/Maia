package dev.maia.app.flow

/**
 * What, if anything, the waiting-draft notification should say.
 *
 * `Effect.PostDraftWaiting` carries a count and nothing else, on purpose: an
 * effect with a title in it would be a title travelling towards a lock screen.
 * The words come from the queue instead, which the controller already holds, and
 * which of them are used is decided here so it can be a test rather than a
 * screenshot of a shade.
 *
 * The split between private and public is the whole privacy question. The
 * private version is only ever rendered on an unlocked screen and may name the
 * sentence. The public version is what a stranger can read and is the constant
 * "A draft is waiting", with no count: decision U2 as the design seat amended
 * it. The count is something the phone knows and the speaker did not just say,
 * which is what privacy item V3 forbids, and it buys the owner nothing because
 * the action is identical at one draft and at five.
 */
sealed interface DraftNotice {

    /** Nothing is waiting, or nothing may be posted. Any existing notification goes. */
    data object None : DraftNotice

    /** Exactly one. Its own title, and its own when text under it. */
    data class One(val title: String, val whenText: String) : DraftNotice

    /**
     * More than one. [count] is for the private title, which is read after an
     * unlock; [oldestTitle] is the one the next unlock will open, which is the
     * only one of them the user can act on next.
     */
    data class Several(val count: Int, val oldestTitle: String) : DraftNotice
}

/**
 * The notification for a queue, or [DraftNotice.None].
 *
 * [permitted] is `POST_NOTIFICATIONS`. Until it is granted there is simply no
 * notification, which is a behaviour and not a degradation: the drafts are in
 * the queue either way and the next unlock still opens the oldest. The
 * permission is asked for the first time a queued draft is reviewed after an
 * unlock, never from a lock screen, so this function is never the thing that
 * triggers the prompt.
 *
 * A queue with no summaries in it returns [DraftNotice.None] rather than
 * inventing a title, which cannot happen through the reducer and is here
 * because the alternative is a notification that says nothing.
 */
fun draftNotice(queue: List<QueuedDraft>, permitted: Boolean): DraftNotice {
    if (!permitted) return DraftNotice.None
    val oldest = queue.firstOrNull() ?: return DraftNotice.None
    return if (queue.size == 1) {
        DraftNotice.One(oldest.summary.title, oldest.summary.whenText)
    } else {
        DraftNotice.Several(queue.size, oldest.summary.title)
    }
}
