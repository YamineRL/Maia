package dev.maia.app.assist

import dev.maia.app.flow.CaptureFamily
import dev.maia.app.flow.FlowEvent
import dev.maia.app.flow.Origin

/**
 * Actions that reach [InvokeActivity]. Item I3 in brief section 8: these are a
 * contract with launchers from the day they ship, because a user who pins a
 * shortcut to a home screen holds the id and the action. Renaming one breaks
 * every pin.
 */
object InvokeActions {

    /** The `new_event` static shortcut in `res/xml/shortcuts.xml`. */
    const val NEW_EVENT: String = "dev.maia.app.action.NEW_EVENT"

    /**
     * The `quick_note` static shortcut, M4 brief section 5.4. Routes exactly as
     * [NEW_EVENT] does, plus [CaptureFamily.Note] as a hint about the door.
     */
    const val QUICK_NOTE: String = "dev.maia.app.action.QUICK_NOTE"

    /**
     * The Quick Settings tile's unlocked path, and any future in-app door that
     * wants to name its own origin. Not declared in `shortcuts.xml`, not in an
     * intent filter, and reachable only from inside Maia, because
     * [InvokeActivity] is not exported.
     */
    const val INVOKE: String = "dev.maia.app.action.INVOKE"

    /** Extra on [INVOKE]: the [Origin] name, exactly as [Origin.name] spells it. */
    const val EXTRA_ORIGIN: String = "dev.maia.app.extra.ORIGIN"
}

/**
 * The pure router: an action and its extras in, an invocation or nothing out.
 * Criterion J13.
 *
 * **Why a function and not a `when` inside the Activity.** Everything that
 * arrives here arrived from outside Maia's own code: a launcher replaying a
 * pinned shortcut from last year, a tile, or an app firing an intent it
 * guessed. An unrecognised action must do nothing at all, and "nothing at all"
 * is the case that never gets tested when it is three lines in an `onCreate`.
 * Here it is a null, and a test asserts it.
 *
 * **Why `locked` is always false.** The lock is not the router's to know. It is
 * read once from `KeyguardManager` by the host ([InvokeActivity]) and copied
 * onto the returned event, exactly as brief section 2.2 requires. For the
 * shortcut door the value is false in practice as well as in the type:
 * launcher shortcuts do not work locked, and nothing in M3 tries to change
 * that.
 *
 * @param action the intent's action, null included, because an intent may have
 *   none.
 * @param extras a plain map rather than a `Bundle`, so that the whole router is
 *   testable on the JVM. The Activity converts.
 */
fun route(action: String?, extras: Map<String, Any?> = emptyMap()): FlowEvent.Invoke? = when (action) {
    InvokeActions.NEW_EVENT -> FlowEvent.Invoke(Origin.Shortcut, locked = false)
    InvokeActions.QUICK_NOTE -> FlowEvent.Invoke(Origin.Shortcut, locked = false, family = CaptureFamily.Note)
    InvokeActions.INVOKE -> originOf(extras)?.let { FlowEvent.Invoke(it, locked = false) }
    else -> null
}

/**
 * The named origin, or null if it is missing, misspelled or not a string.
 *
 * Deliberately strict. A door that cannot say which door it is is not a door
 * this router knows, and guessing [Origin.Launcher] would silently attribute
 * someone else's intent to the launcher and spoil criterion P3's per-door
 * latency figures.
 */
private fun originOf(extras: Map<String, Any?>): Origin? {
    val name = extras[InvokeActions.EXTRA_ORIGIN] as? String ?: return null
    return Origin.entries.firstOrNull { it.name == name }
}
