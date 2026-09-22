package dev.maia.app.assist

import dev.maia.app.flow.CaptureFamily
import dev.maia.app.flow.Origin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Criterion J13, the router half: an unknown action routes to null, and the
 * doors Maia publishes route to the invocation they claim.
 *
 * The other half, that every action declared in `shortcuts.xml` routes, is
 * [ShortcutsXmlTest], which reads the XML rather than a copy of it.
 */
class RouterTest {

    @Test
    fun `J13 the new event shortcut routes to a shortcut invocation`() {
        assertEquals(
            dev.maia.app.flow.FlowEvent.Invoke(Origin.Shortcut, locked = false),
            route(InvokeActions.NEW_EVENT),
        )
    }

    /** M4 brief section 5.4 and J15: the same invocation as new_event, plus the note hint. */
    @Test
    fun `J13 the quick note shortcut routes to a shortcut invocation in the note family`() {
        assertEquals(
            dev.maia.app.flow.FlowEvent.Invoke(Origin.Shortcut, locked = false, family = CaptureFamily.Note),
            route(InvokeActions.QUICK_NOTE),
        )
    }

    @Test
    fun `J13 an unrecognised action routes to null`() {
        // QUICK_NOTE was this row until M4 declared it (user decision, M4 row 9,
        // 2026-09-13). Its place is taken by an action nobody publishes, so the
        // row still checks the same thing: a plausible Maia action that was
        // never declared goes nowhere.
        assertNull(route("dev.maia.app.action.QUICK_TASK"))
        assertNull(route("android.intent.action.VIEW"))
        assertNull(route(""))
        assertNull(route(null))
        // The action is what is matched, never the extras. An origin on an
        // action nobody published does not conjure a door into existence.
        assertNull(route(null, mapOf(InvokeActions.EXTRA_ORIGIN to Origin.Tile.name)))
    }

    @Test
    fun `J13 the invoke action carries its origin, and every origin is spellable`() {
        for (origin in Origin.entries) {
            assertEquals(
                dev.maia.app.flow.FlowEvent.Invoke(origin, locked = false),
                route(InvokeActions.INVOKE, mapOf(InvokeActions.EXTRA_ORIGIN to origin.name)),
            )
        }
    }

    @Test
    fun `an invoke with no origin, a misspelled origin or a non-string origin is not a door`() {
        assertNull(route(InvokeActions.INVOKE))
        assertNull(route(InvokeActions.INVOKE, mapOf(InvokeActions.EXTRA_ORIGIN to "tile")))
        assertNull(route(InvokeActions.INVOKE, mapOf(InvokeActions.EXTRA_ORIGIN to "Keyboard")))
        assertNull(route(InvokeActions.INVOKE, mapOf(InvokeActions.EXTRA_ORIGIN to 3)))
    }

    /**
     * The lock is read once by the host from `KeyguardManager` and copied on.
     * The router must never assert anything about it, because the router cannot
     * know, and a router that guessed false would be a locked capture rule
     * decided by an intent.
     */
    @Test
    fun `the router never claims to know the lock state`() {
        assertFalse(route(InvokeActions.NEW_EVENT)!!.locked)
        assertFalse(route(InvokeActions.QUICK_NOTE)!!.locked)
        assertFalse(route(InvokeActions.INVOKE, mapOf(InvokeActions.EXTRA_ORIGIN to "Tile"))!!.locked)
    }
}
