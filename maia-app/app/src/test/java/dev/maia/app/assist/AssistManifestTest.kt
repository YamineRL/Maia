package dev.maia.app.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Criterion J14, and the same assertion lint makes for J15.
 *
 * J14: `voice_interaction.xml` names a session service, a recognition service
 * and a settings Activity whose classes are declared in the manifest, and both
 * voice services carry the `BIND_VOICE_INTERACTION` guard.
 *
 * **What this reads, and what it does not.** This reads `:app`'s source
 * manifest, because the merged one is a build output that a unit test would
 * have to either depend on or guess the path of, and a test that quietly skips
 * when its input is missing proves nothing. That is a real limit and it was not
 * narrow: `:core-audio` contributes `INTERNET` and `:core-actions` contributes
 * `READ_CALENDAR` and `WRITE_CALENDAR`, all three shipped in every APK, and
 * none of them is visible from here.
 *
 * So nothing in this file is a statement about the app's permission set, its
 * `<queries>` or its foreground service types. The permission set is checked
 * over the merged manifest by `checkMergedManifestPermissions`, registered in
 * `maia-app/build.gradle.kts` and wired into `check`, and that task carries the
 * allowlist and the reason for each entry. What this file is good for is speed:
 * it fails in seconds on `:app`'s own declarations, before a Gradle check has to
 * merge anything.
 *
 * For the components asserted below the limit really is narrow, and stays worth
 * stating: no library module contributes a component, so for every class named
 * here the source manifest and the merged manifest say the same thing. The
 * merged manifest is still what `assembleDebug` and lint read, and J15 is
 * lint's.
 */
class AssistManifestTest {

    private val application = AssistFiles.manifest.children("application").single()
    private val services = application.children("service")
    private val activities = application.children("activity")

    private fun service(name: String) = services.single { qualified(it.android("name")!!) == name }

    @Test
    fun `J14 voice_interaction names a session service, a recognition service and a settings Activity`() {
        val xml = AssistFiles.voiceInteraction
        assertEquals("voice-interaction-service", xml.tagName)

        val session = xml.android("sessionService")
        val recognition = xml.android("recognitionService")
        val settings = xml.android("settingsActivity")
        assertNotNull("no sessionService", session)
        assertNotNull("no recognitionService", recognition)
        assertNotNull("no settingsActivity", settings)

        val declaredServices = declared("service")
        val declaredActivities = declared("activity")
        assertTrue("$session is not declared", session in declaredServices)
        assertTrue("$recognition is not declared", recognition in declaredServices)
        assertTrue("$settings is not declared", settings in declaredActivities)
    }

    /**
     * docs/research/R1.md: the role observer skips any interactor service whose
     * `getSupportsAssist()` is false and falls back to an `ACTION_ASSIST`
     * Activity, which would make Maia an assist-activity assistant with no
     * session at all. Absent is not the same as true here, so absent fails.
     */
    @Test
    fun `J14 the voice interaction service supports assist`() {
        assertEquals("true", AssistFiles.voiceInteraction.android("supportsAssist"))
    }

    /**
     * Claimed only if step 0 finds this build's keyguard exposes a voice
     * affordance, and step 0 needs the phone. Until then it is absent rather
     * than claimed. Brief section 1.1.
     */
    @Test
    fun `launch from keyguard is not claimed before the phone has said so`() {
        assertNull(AssistFiles.voiceInteraction.android("supportsLaunchVoiceAssistFromKeyguard"))
    }

    @Test
    fun `J14 both voice services carry the BIND_VOICE_INTERACTION guard`() {
        val voice = service("dev.maia.app.assist.MaiaVoiceService")
        val session = service("dev.maia.app.assist.MaiaSessionService")
        for (guarded in listOf(voice, session)) {
            assertEquals(
                "${guarded.android("name")} is not guarded",
                "android.permission.BIND_VOICE_INTERACTION",
                guarded.android("permission"),
            )
        }
    }

    /**
     * `BIND_VOICE_INTERACTION` is a signature permission held by the system. It
     * is the guard on Maia's services and never something Maia requests. Brief
     * section 7, and the same for the three the brief forbids outright.
     *
     * Read the name literally: this is about what **`:app`'s own manifest**
     * requests and declares, and a library could add any of these without this
     * test noticing. `checkMergedManifestPermissions` is what holds the same
     * three rules (no forbidden permission, nothing microphone shaped, no
     * `<queries>`) against the whole merged manifest. This one exists to fail
     * first and fast on the file a person is most likely to be editing.
     */
    @Test
    fun `Maia requests no permission it is only supposed to be guarded by`() {
        val requested = AssistFiles.manifest.tags("uses-permission").mapNotNull { it.android("name") }
        val forbidden = listOf(
            "android.permission.BIND_VOICE_INTERACTION",
            "android.permission.DISABLE_KEYGUARD",
            "android.permission.SYSTEM_ALERT_WINDOW",
            "android.permission.USE_FULL_SCREEN_INTENT",
            // Row 11, deliberately not built. docs/research/R2.md shows a
            // foreground service would not fix the one case that actually
            // fails, which is concurrency rather than the lock. What R2
            // rejected was a foreground service for the microphone, and that
            // is what stays forbidden: M8 holds one for `dataSync`, to keep a
            // four-minute network stream alive with the screen off, which is a
            // different service for a different reason and captures nothing.
            // `RunServiceManifestTest` is where that one is checked.
            "android.permission.FOREGROUND_SERVICE_MICROPHONE",
        )
        for (permission in forbidden) {
            assertFalse("$permission must not be requested", permission in requested)
        }
        assertTrue("no <queries> element", AssistFiles.manifest.children("queries").isEmpty())

        // The other half of the same promise, and the half that matters: no
        // service may declare a microphone type, whatever permissions the
        // manifest grows later.
        val types = AssistFiles.manifest.tags("service").mapNotNull { it.android("foregroundServiceType") }
        for (type in types) {
            assertFalse("$type captures audio", type.contains("microphone", ignoreCase = true))
        }
    }

    /**
     * The voice interaction service is found by its action, and it carries the
     * meta-data that points at `voice_interaction.xml`. Without the meta-data
     * the service parses as not an interactor at all.
     */
    @Test
    fun `J14 the voice service declares its action and its meta-data`() {
        val voice = service("dev.maia.app.assist.MaiaVoiceService")
        val actions = voice.children("intent-filter").flatMap { it.children("action") }
            .mapNotNull { it.android("name") }
        assertEquals(listOf("android.service.voice.VoiceInteractionService"), actions)
        assertEquals(
            "@xml/voice_interaction",
            voice.children("meta-data").single { it.android("name") == "android.voice_interaction" }
                .android("resource"),
        )
    }

    /**
     * The same for the recognition stub, whose `android.speech` meta-data is
     * what carries `selectableAsDefault`. Its exported intent filter, not the
     * role, is what any other app can reach, which makes this the declaration
     * with the widest reach in the manifest. Whether the framework ever points
     * `Settings.Secure.voice_recognition_service` at it is a separate question,
     * and G7 says that on this build it does not (docs/research/R3.md, the
     * 2026-09-13 hardware result).
     */
    @Test
    fun `the recognition stub declares its action and its android speech meta-data`() {
        val stub = service("dev.maia.app.assist.MaiaRecognitionService")
        val actions = stub.children("intent-filter").flatMap { it.children("action") }
            .mapNotNull { it.android("name") }
        assertEquals(listOf("android.speech.RecognitionService"), actions)
        assertEquals(
            "@xml/speech_recognition",
            stub.children("meta-data").single { it.android("name") == "android.speech" }
                .android("resource"),
        )
    }

    /**
     * J15 is a lint criterion and lint is what gates it. This is the same
     * assertion written down, so that the reason for each exception is in the
     * tree rather than in a lint baseline: no exported component without an
     * intent filter or a permission, other than the launcher Activity.
     */
    @Test
    fun `J15 no exported component is unguarded, except the launcher Activity`() {
        val exported = (services + activities + application.children("receiver") +
            application.children("provider"))
            .filter { it.android("exported") == "true" }
        for (component in exported) {
            val name = qualified(component.android("name")!!)
            if (name == "dev.maia.app.MainActivity") continue
            val guarded = component.android("permission") != null
            val filtered = component.children("intent-filter").isNotEmpty()
            assertTrue(
                "$name is exported with neither an intent filter nor a permission",
                guarded || filtered,
            )
        }
    }

    /**
     * G9. Exactly one component is direct boot aware, and it is the interactor.
     *
     * The flag is what lets the system resolve and bind the role holder before
     * the first unlock, which is the unverified cause of the empty
     * `voice_interaction_service` the spike found after a reboot. It is also a
     * promise about what that component may touch, so the set is pinned rather
     * than left to grow: anything else made direct boot aware runs for a locked
     * user, where credential-encrypted storage throws rather than returns
     * nothing, and the session service in particular builds the whole flow.
     *
     * The `<application>` tag is checked too, because setting it there makes
     * every component direct boot aware at once and would be an easy way to
     * undo this without touching a single `<service>`.
     */
    @Test
    fun `G9 only the voice interaction service is direct boot aware`() {
        assertEquals(
            "true",
            service("dev.maia.app.assist.MaiaVoiceService").android("directBootAware"),
        )
        assertNull(
            "directBootAware on <application> makes every component direct boot aware",
            application.android("directBootAware"),
        )
        val aware = (services + activities + application.children("receiver") +
            application.children("provider"))
            .filter { it.android("directBootAware") == "true" }
            .map { qualified(it.android("name")!!) }
        assertEquals(listOf("dev.maia.app.assist.MaiaVoiceService"), aware)
    }

    /**
     * Item I4. The trampoline that starts capture is not reachable from outside
     * Maia, which is the whole of the change: at M1 any installed app could
     * fire MainActivity's extra and switch on the microphone (privacy item V4).
     */
    @Test
    fun `I4 InvokeActivity is declared and not exported`() {
        val invoke = activities.single { qualified(it.android("name")!!) == "dev.maia.app.assist.InvokeActivity" }
        assertEquals("false", invoke.android("exported"))
        assertTrue(
            "a non-exported trampoline must not advertise an intent filter",
            invoke.children("intent-filter").isEmpty(),
        )
    }
}
