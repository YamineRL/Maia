package dev.maia.app.assist

import dev.maia.app.flow.CaptureFamily
import dev.maia.app.flow.FlowEvent
import dev.maia.app.flow.Origin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Criterion J13, the XML half: every action declared in `shortcuts.xml` routes
 * to an invocation.
 *
 * The point of reading the file rather than a list in Kotlin is that
 * `shortcuts.xml` cannot use string resources for an action, so the action is
 * spelled out twice, once there and once in [InvokeActions]. Two spellings of
 * a contract with launchers is exactly the thing that drifts, and this is what
 * stops it drifting quietly.
 */
class ShortcutsXmlTest {

    private val shortcuts = AssistFiles.shortcuts.children("shortcut")

    /**
     * Per action since M4 row 9 (user decision, 2026-09-13): the two shortcuts
     * route to the same origin and lock but differ in family, so one expected
     * value for every action no longer fits. Every declared action must still
     * appear here and route to exactly its row.
     */
    private val expected: Map<String, FlowEvent.Invoke> = mapOf(
        InvokeActions.NEW_EVENT to FlowEvent.Invoke(Origin.Shortcut, locked = false),
        InvokeActions.QUICK_NOTE to FlowEvent.Invoke(Origin.Shortcut, locked = false, family = CaptureFamily.Note),
    )

    @Test
    fun `J13 every action declared in shortcuts xml routes`() {
        assertTrue("shortcuts.xml declares no shortcuts", shortcuts.isNotEmpty())
        for (shortcut in shortcuts) {
            val id = shortcut.android("shortcutId")
            for (intent in shortcut.children("intent")) {
                val action = intent.android("action")
                assertNotNull("shortcut $id has an intent with no action", action)
                val want = expected[action]
                assertNotNull("shortcut $id declares $action, which has no expected route", want)
                assertEquals("the action of shortcut $id does not route", want, route(action))
            }
        }
    }

    /**
     * M3 shipped one shortcut; M4 row 9 added PRD section 6's "Quick note",
     * and this assertion was updated deliberately for it (user decision,
     * 2026-09-13). The ids and their order are the contract with pins.
     */
    @Test
    fun `the shortcuts are new_event and quick_note, and their ids are the contract`() {
        assertEquals(listOf("new_event", "quick_note"), shortcuts.map { it.android("shortcutId") })
        assertEquals(
            listOf(InvokeActions.NEW_EVENT, InvokeActions.QUICK_NOTE),
            shortcuts.flatMap { it.children("intent") }.map { it.android("action") },
        )
    }

    /**
     * Item I4 and docs/research/R5.md: the shortcut targets the non-exported
     * trampoline, not the exported launcher Activity. If a future edit pointed
     * a shortcut back at MainActivity with an extra, the M1 exposure would be
     * back and nothing else would notice.
     */
    @Test
    fun `the shortcut targets InvokeActivity in this package`() {
        val intents = shortcuts.flatMap { it.children("intent") }
        for (intent in intents) {
            assertEquals(AssistFiles.NAMESPACE, intent.android("targetPackage"))
            assertEquals(
                "dev.maia.app.assist.InvokeActivity",
                intent.android("targetClass"),
            )
        }
    }

    /**
     * The target has to be declared, and it has to be declared non-exported:
     * R5's whole argument is that a launcher starts a shortcut as the
     * publishing package, so exporting it would buy nothing and give every
     * other app the door back.
     */
    @Test
    fun `the target activity is declared and is not exported`() {
        val targets = shortcuts.flatMap { it.children("intent") }.mapNotNull { it.android("targetClass") }
        val activities = AssistFiles.manifest.tags("activity")
            .associateBy { qualified(it.android("name") ?: "") }
        for (target in targets) {
            val activity = activities[target]
            assertNotNull("$target is not declared in the manifest", activity)
            assertEquals("$target must not be exported", "false", activity!!.android("exported"))
        }
    }

    /**
     * A static shortcut is only shown if it has both labels, and the meta-data
     * that publishes it hangs from the exported launcher Activity, which is
     * what `ShortcutParser` checks.
     */
    @Test
    fun `each shortcut has both labels, and the launcher Activity publishes them`() {
        for (shortcut in shortcuts) {
            assertNotNull(shortcut.android("shortcutShortLabel"))
            assertNotNull(shortcut.android("shortcutLongLabel"))
        }
        val publisher = AssistFiles.manifest.tags("activity").single { activity ->
            activity.children("meta-data").any { it.android("name") == "android.app.shortcuts" }
        }
        assertEquals("dev.maia.app.MainActivity", qualified(publisher.android("name")!!))
        assertEquals("true", publisher.android("exported"))
        assertEquals(
            "@xml/shortcuts",
            publisher.children("meta-data")
                .single { it.android("name") == "android.app.shortcuts" }
                .android("resource"),
        )
    }
}
