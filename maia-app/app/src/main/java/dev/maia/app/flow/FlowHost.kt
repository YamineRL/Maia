package dev.maia.app.flow

/**
 * The three things the flow cannot do for itself, and the window that can.
 *
 * `FlowController` lives at process scope, which means it has no `Context` worth
 * having: the application context cannot dismiss a keyguard, cannot take a
 * system window down, and must not be the thing that decides whether a
 * notification is allowed to exist yet. Every one of those answers depends on
 * which door is on screen at the moment the effect is emitted, so they are asked
 * of whatever is on screen rather than resolved from a static field.
 *
 * Each method has a no-op default, and that is the interface's whole design.
 * There are three hosts and none of them can do all three things:
 *
 * - **The session window** (`MaiaSession`, row 7) implements [requestUnlock]
 *   with `KeyguardManager.requestDismissKeyguard` on the Activity it is given,
 *   and [hideSession] with `hide()`. It is the only host that owns a window the
 *   flow has to ask to go away. It must **not** implement
 *   [postDraftWaiting]: it is the host that is on screen while locked, and
 *   asking for `POST_NOTIFICATIONS` from a lock screen is the one thing the
 *   locked policy forbids.
 * - **The Activity hosts** (`MainActivity`, `InvokeActivity`) implement
 *   [requestUnlock] and [postDraftWaiting]. They finish themselves, so
 *   [hideSession] stays a no-op rather than becoming a second `finish()` path.
 * - **The tile** implements none of them today. It is left here as a host
 *   rather than excluded, because when the tile's locked path (row 10) lands it
 *   is the one that will need [requestUnlock].
 *
 * A missing host is not an error. An effect with nobody to run it is dropped and
 * the flow carries on, which is the same shape as a cancelled unlock: the
 * reducer never hears back, and the screen stays exactly as it was.
 */
interface FlowHost {

    /**
     * Ask for the keyguard. Answered by [FlowEvent.Unlocked] on success and by
     * nothing at all on a cancel or a failure.
     */
    fun requestUnlock() {}

    /** Take the window down. Only the assistant session owns one. */
    fun hideSession() {}

    /**
     * Take the window down in favour of the answer and run surfaces. A host
     * that already draws them has nothing to do.
     */
    fun showSurface() {}

    /**
     * Post, update at [count] or, at zero, remove the waiting-draft
     * notification.
     *
     * Only a host that is running on an unlocked screen may implement this, and
     * `POST_NOTIFICATIONS` is asked for here the first time a queued draft is
     * reviewed after an unlock, never before. Until it is granted there is
     * simply no notification, which is a behaviour and not a fault: the drafts
     * are still in the queue and the next unlock still opens the oldest.
     */
    fun postDraftWaiting(count: Int) {}
}
