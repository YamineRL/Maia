package dev.maia.app.flow

import dev.maia.app.flow.Fixtures.heard
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the waiting-draft notification says, decided away from Android so that
 * "the lock screen never learns anything the speaker did not just say" is a
 * test rather than a phone somebody once looked at.
 *
 * The public half of the notification is not decided here at all: it is a
 * constant string, byte-identical at one draft and at five, which is the point
 * of it. Only the private half, which is rendered on an unlocked screen, has
 * anything to decide.
 */
class DraftNoticeTest {

    private fun queued(n: Int, title: String = "dinner with sam") = List(n) {
        val draft = heard.copy(title = heard.title.copy(value = "$title $it"))
        QueuedDraft(draft, heardAt = it.toLong(), summary = lockedSummary(draft))
    }

    @Test
    fun `nothing waiting is nothing to say`() {
        assertEquals(DraftNotice.None, draftNotice(emptyList(), permitted = true))
    }

    @Test
    fun `without the permission there is simply no notification`() {
        // Not a degradation and not a fault. The drafts are in the queue either
        // way and the next unlock still opens the oldest; the permission is
        // asked for after an unlock, by the host, or never.
        assertEquals(DraftNotice.None, draftNotice(queued(3), permitted = false))
    }

    @Test
    fun `one draft speaks for itself`() {
        val queue = queued(1)
        assertEquals(
            DraftNotice.One(queue[0].summary.title, queue[0].summary.whenText),
            draftNotice(queue, permitted = true),
        )
    }

    @Test
    fun `several name the oldest, which is the one the next unlock opens`() {
        val queue = queued(3)
        assertEquals(
            DraftNotice.Several(3, queue[0].summary.title),
            draftNotice(queue, permitted = true),
        )
    }

    @Test
    fun `the notice never carries a calendar, because the summary has nowhere to keep one`() {
        // The same shape as criterion J10. LockedSummary is built from the draft
        // alone, so a provider read cannot reach a notification any more than it
        // can reach a lock screen: there is no field for it.
        val notice = draftNotice(queued(1), permitted = true) as DraftNotice.One
        assertEquals(lockedSummary(heard).whenText, notice.whenText)
    }
}
