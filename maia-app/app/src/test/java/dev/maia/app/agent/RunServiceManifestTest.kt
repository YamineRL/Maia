package dev.maia.app.agent

import dev.maia.app.assist.AssistFiles
import dev.maia.app.assist.android
import dev.maia.app.assist.children
import dev.maia.app.assist.qualified
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The foreground service, as the manifest declares it, and the notification
 * code, as text.
 *
 * Same method and same limit as `AssistManifestTest`: this reads `:app`'s own
 * source manifest rather than the merged one, because the merged manifest is a
 * build output and a test that skips when its input is missing proves nothing.
 * No library module contributes a component, so for the service below the two
 * say the same thing.
 *
 * The permission assertions here are narrower than they look. They check that
 * `:app` declares the two the `dataSync` type needs; they are not a statement
 * about the app's permission set, which a library can add to without this file
 * changing. That set, and the rule that no service anywhere may declare a
 * microphone type, belong to `checkMergedManifestPermissions` in
 * `maia-app/build.gradle.kts`, which reads the merged manifest and runs under
 * `check`.
 */
class RunServiceManifestTest {

    private val application = AssistFiles.manifest.children("application").single()
    private val services = application.children("service")
    private val service = services.single { qualified(it.android("name")!!) == "dev.maia.app.agent.RunService" }

    /**
     * Android 15's cumulative `dataSync` budget, which is about six hours in
     * any twenty-four and only counts the time this service is actually up.
     *
     * When it runs out the system calls `onTimeout` and then crashes the app
     * if the service is still up seconds later. `Service.onTimeout` does
     * nothing by default, so not overriding it is a decision to crash mid
     * reply. Pinned by reflection because the alternative is an androidTest
     * that waits six hours.
     */
    @Test
    fun `the run service handles the daily foreground budget running out`() {
        val declared = RunService::class.java.declaredMethods.any {
            it.name == "onTimeout" &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes.all { type -> type == Int::class.javaPrimitiveType }
        }
        assertTrue("RunService must override onTimeout(startId, fgsType)", declared)
    }

    @Test
    fun `the run service is declared, not exported and carries no intent filter`() {
        assertEquals("false", service.android("exported"))
        // Nothing outside this process has anything to say to it, and an
        // exported service that puts the app in the foreground is a way for
        // any installed app to hold Maia's process up.
        assertTrue(service.children("intent-filter").isEmpty())
        assertNull("a turn cannot begin before the first unlock", service.android("directBootAware"))
    }

    /**
     * API 34 requires the type in the manifest to match the one passed to
     * `startForeground`, and requires the matching permission. All three are
     * checked together because two of them agreeing is not enough.
     */
    @Test
    fun `the type is dataSync in the manifest, in code and in the permissions`() {
        assertEquals("dataSync", service.android("foregroundServiceType"))

        val permissions = AssistFiles.manifest.children("uses-permission")
            .mapNotNull { it.android("name") }
            .toSet()
        assertTrue(
            "FOREGROUND_SERVICE is missing",
            "android.permission.FOREGROUND_SERVICE" in permissions,
        )
        assertTrue(
            "the API 34 companion permission for dataSync is missing",
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC" in permissions,
        )

        assertTrue(
            "startForeground does not pass FOREGROUND_SERVICE_TYPE_DATA_SYNC",
            source("agent/RunService.kt").contains("FOREGROUND_SERVICE_TYPE_DATA_SYNC"),
        )
        assertFalse(
            "shortService is capped at three minutes and a four-minute run is supported",
            source("agent/RunService.kt").contains("SHORT_SERVICE"),
        )
    }

    /**
     * Section 2.1, from the notification's side.
     *
     * A notification can speak. `setTicker` was exactly that: the text a
     * screen reader announced when a notification arrived, with no gesture
     * from the user and nothing on screen to read. It is ignored on current
     * versions, which is precisely why it would be set by someone who had
     * stopped thinking about it. The rule the product states is that an
     * agent's words are never spoken, and a rule that holds only on the
     * surfaces someone remembered is not a rule.
     *
     * So: no ticker, and no accessibility announcement of any kind from the
     * code that posts these. The screen's own single announcement is a
     * resource id chosen by `RunCopy.announcement`, which is a different file
     * and a different test.
     */
    @Test
    fun `nothing in the notification path can speak`() {
        listOf("agent/AgentNotifier.kt", "agent/RunService.kt", "agent/AgentAlerts.kt").forEach { path ->
            val text = source(path)
            listOf("setTicker", "announceForAccessibility", "AccessibilityEvent", "TextToSpeech").forEach { door ->
                assertFalse("$path calls $door", text.contains("$door("))
            }
        }
    }

    /**
     * The notification's text comes from resources and integers, never from
     * anything the agent produced. `ReplyPiece` is the type an agent's words
     * arrive in, so naming it here is the check: the notifier cannot mention a
     * type it does not import.
     */
    @Test
    fun `no agent text can reach a notification`() {
        val text = source("agent/AgentNotifier.kt")
        assertFalse("the notifier names ReplyPiece", text.contains("ReplyPiece"))
        assertFalse("the notifier reads a turn", text.contains("Turn"))
        assertFalse("the notifier takes a raw body string", text.contains("body: String"))
    }

    private fun source(relative: String): String {
        val root = "src/main/java/dev/maia/app/$relative"
        val candidates = listOf(File(root), File("app/$root"), File("maia-app/app/$root"))
        return (candidates.firstOrNull { it.isFile } ?: error("cannot find $root")).readText()
    }
}
