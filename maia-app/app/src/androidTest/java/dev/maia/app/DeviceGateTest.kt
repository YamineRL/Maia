package dev.maia.app

import android.content.Context
import android.hardware.camera2.CameraManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.maia.app.answer.AnswerDriver
import dev.maia.app.answer.AnswerFault
import dev.maia.app.answer.AnswerHost
import dev.maia.app.answer.AnswerSource
import dev.maia.app.answer.AnswerState
import dev.maia.app.answer.AnswerStatus
import dev.maia.app.answer.PermNeeded
import dev.maia.app.flow.AssistantCommand
import dev.maia.audio.speech.Speaker
import dev.maia.nlu.Intent
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The on-device gates the JVM suite cannot reach.
 *
 * Everything here runs against the production wiring: `AnswerHost.driver`
 * builds the same `AnswerDriver`, `LocalReader`, `HandoffExecutor` and lazy
 * tunnel channel the app itself uses, so a green run means real intents
 * resolved on this phone, the torch HAL toggled, and the devbox gateway
 * answered through tailcat. The unit suite proves the machines; this file
 * proves the phone.
 *
 * Requires the assistant credential to be stored (pairing screen, or seeded
 * into `maia_agent.xml`) and CAMERA granted for the torch case:
 *
 *     adb shell pm grant dev.maia.app android.permission.CAMERA
 *     ./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class DeviceGateTest {

    /** Every string the answer surface ever handed to a voice. */
    private val spoken = CopyOnWriteArrayList<String>()

    private val speaker = Speaker { text ->
        spoken.add(text)
        emptyFlow()
    }

    private lateinit var context: Context
    private lateinit var driver: AnswerDriver

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        // Any wiring a live MainActivity built carries the app's own speaker;
        // rebuild so the recording one above hears this run.
        AnswerHost.rebuild()
        // The Go netstack wants the phone's interfaces and default route
        // before the first dial. In the app AgentHost pushes these when the
        // wiring builds; in this process nothing else has, so the remote
        // asks would be Unreachable without it.
        dev.maia.app.agent.NetFacts.push(context)
        driver = AnswerHost.driver(context, speaker)
    }

    @After
    fun tearDown() {
        AnswerHost.rebuild()
        home()
    }

    // ---------------------------------------------------------- helpers

    private fun state(): AnswerState = driver.current()

    private fun send(command: AssistantCommand) = driver.handle(command)

    private fun home() {
        InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand("input keyevent KEYCODE_HOME")
            .close()
        Thread.sleep(300)
    }

    private val terminal = setOf(
        AnswerStatus.Answered,
        AnswerStatus.HandedOff,
        AnswerStatus.Failed,
        AnswerStatus.Previewing,
    )

    /**
     * Waits out a command: first until the machine has taken it (the state's
     * [AnswerState.spoken] becomes the command's transcript), then until a
     * terminal status. Keying on the transcript matters, because the command
     * is applied on the driver's work thread and an early poll would read
     * the Idle of the last test, not this one.
     */
    private fun awaitDone(command: AssistantCommand, deadlineMs: Long = 15_000): AnswerState {
        val deadline = System.currentTimeMillis() + deadlineMs
        var last = state()
        while (System.currentTimeMillis() < deadline) {
            last = state()
            if (last.spoken == command.spoken && last.status in terminal) return last
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for $command; last state: $last")
    }

    /** A handoff that needed no preview ends HandedOff with no fault. */
    private fun handedOff(command: AssistantCommand): AnswerState {
        send(command)
        val end = awaitDone(command)
        assertEquals("expected HandedOff, got $end", AnswerStatus.HandedOff, end.status)
        assertNull(end.fault)
        home()
        return end
    }

    // ------------------------------------------------------------ reads

    @Test
    fun clock_readsOnPhone_andSpoken() {
        val command = AssistantCommand.Read(Intent.DeviceFact(Intent.DeviceFact.Kind.TIME), "what time is it")
        send(command)
        val end = awaitDone(command)
        assertEquals(AnswerStatus.Answered, end.status)
        assertEquals(AnswerSource.Phone, end.source)
        assertTrue(end.answer.isNotBlank())
        // A local answer has no separate spoken form; the text is the speech.
        assertTrue("nothing reached the voice", spoken.isNotEmpty())
        assertEquals(end.spokenText ?: end.answer, spoken.last())
    }

    @Test
    fun battery_readsOnPhone() {
        val command = AssistantCommand.Read(Intent.DeviceFact(Intent.DeviceFact.Kind.BATTERY), "battery level")
        send(command)
        val end = awaitDone(command)
        assertEquals(AnswerStatus.Answered, end.status)
        assertEquals(AnswerSource.Phone, end.source)
        assertTrue("answer should name a level, got ${end.answer}", end.answer.contains('%'))
    }

    @Test
    fun arithmetic_computedOnPhone() {
        val command = AssistantCommand.Read(Intent.Calculate("40+2"), "what is 40 plus 2")
        send(command)
        val end = awaitDone(command)
        assertEquals(AnswerStatus.Answered, end.status)
        assertEquals(AnswerSource.Phone, end.source)
        assertTrue("expected 42 in ${end.answer}", end.answer.contains("42"))
    }

    // -------------------------------------------------------- handoffs

    @Test
    fun timer_previewsThenFiresOnConfirm() {
        val command = AssistantCommand.Act(
            Intent.SetTimer(durationMs = 5 * 60_000, label = "maia gate"),
            "set a timer for five minutes",
        )
        send(command)
        val preview = awaitDone(command)
        assertNotNull(preview.handoff)
        assertTrue(preview.handoff!!.label.contains("Timer"))
        assertTrue(preview.handoff!!.needsConfirm)
        // Nothing fired yet: the state is a card, not a claim.
        driver.confirm()
        // The confirm runs on the work thread through Working before the
        // handoff fires; Previewing and Working both mean "not done yet".
        val deadline = System.currentTimeMillis() + 15_000
        var end = state()
        while (System.currentTimeMillis() < deadline &&
            (end.spoken != command.spoken ||
                end.status == AnswerStatus.Previewing ||
                end.status == AnswerStatus.Working)
        ) {
            Thread.sleep(50)
            end = state()
        }
        assertEquals("expected HandedOff, got $end", AnswerStatus.HandedOff, end.status)
        home()
    }

    @Test
    fun wifiSettings_opensRealPanel() {
        handedOff(AssistantCommand.Act(Intent.OpenSettings(Intent.OpenSettings.Panel.WIFI), "open wifi settings"))
    }

    @Test
    fun dial_opensPopulated_neverCalls() {
        val end = handedOff(AssistantCommand.Act(Intent.Dial("+15551234567"), "call 555 1234"))
        assertEquals("Phone", end.handoff?.target)
    }

    @Test
    fun contactName_withoutGrant_isPermissionFault() {
        // READ_CONTACTS is not granted in this run. "mum" is not dialable, so
        // the executor must resolve through contacts, fail honestly, and the
        // state must name the missing permission rather than guess a number.
        val command = AssistantCommand.Act(Intent.Dial("mum"), "call mum")
        send(command)
        val end = awaitDone(command)
        assertEquals("expected Failed, got $end", AnswerStatus.Failed, end.status)
        assertEquals(AnswerFault.Permission(PermNeeded.Contacts), end.fault)
    }

    @Test
    fun messageCompose_opensPopulated_neverSends() {
        handedOff(AssistantCommand.Act(Intent.ComposeMessage("+15551234567", "hi from maia"), "text 555 1234"))
    }

    @Test
    fun navigation_opensMapsApp() {
        handedOff(AssistantCommand.Act(Intent.Navigate("Trafalgar Square"), "directions to trafalgar square"))
    }

    @Test
    fun webTarget_opensBrowser() {
        handedOff(AssistantCommand.Act(Intent.OpenWeb("example.com"), "open example dot com"))
    }

    @Test
    fun appName_opensThroughCuratedTable() {
        handedOff(AssistantCommand.Act(Intent.OpenApp("settings"), "open settings app"))
    }

    @Test
    fun mediaPause_dispatchesRealKey() {
        handedOff(AssistantCommand.Act(Intent.Media(Intent.Media.Command.PAUSE), "pause music"))
    }

    @Test
    fun torch_togglesHalNotIntent() {
        // CAMERA is granted for the run (see class doc). The proof is the
        // torch callback firing, not the state label: the HAL says the light
        // changed, which is the whole point of doing this on a phone.
        val cameras = context.getSystemService(CameraManager::class.java)
        val lit = CountDownLatch(1)
        val cameraId = cameras.cameraIdList.first()
        cameras.registerTorchCallback(
            object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(id: String, enabled: Boolean) {
                    if (enabled) lit.countDown()
                }
            },
            android.os.Handler(android.os.Looper.getMainLooper()),
        )
        handedOff(AssistantCommand.Act(Intent.SetTorch(true), "turn on the torch"))
        assertTrue("torch HAL never reported the light on", lit.await(5, TimeUnit.SECONDS))
        handedOff(AssistantCommand.Act(Intent.SetTorch(false), "turn off the torch"))
    }

    // --------------------------------------------------------- the remote

    @Test
    fun question_roundTripsDevbox_spokenFormVoiced() {
        val command = AssistantCommand.Ask(
            "what is the capital of France? answer in one word",
            "what is the capital of France",
        )
        send(command)
        // A sleeping model may need to wake, then generate: this is the one
        // slow gate. 120 seconds is generous and still bounded.
        val end = awaitDone(command, 120_000)
        assertEquals("expected Answered, got $end", AnswerStatus.Answered, end.status)
        assertEquals(AnswerSource.Devbox, end.source)
        assertTrue("expected paris in ${end.answer}", end.answer.lowercase().contains("paris"))
        // The voice carries the short form when the model gave one. SpeechEnded
        // clears spokenText before we read the state, and the Speak effect
        // dispatches after Answered lands, so give the speaker a moment and
        // assert on what it actually received, not the cleared field.
        val speechDeadline = System.currentTimeMillis() + 5_000
        while (spoken.isEmpty() && System.currentTimeMillis() < speechDeadline) Thread.sleep(50)
        assertTrue("nothing reached the voice", spoken.isNotEmpty())
        assertTrue(
            "expected the answer voiced, got ${spoken.last()}",
            spoken.last().lowercase().contains("paris"),
        )
    }

    @Test
    fun typedFollowUp_carriesConversation() {
        val command = AssistantCommand.Ask(
            "what is the capital of France? answer in one word",
            "what is the capital of France",
        )
        send(command)
        awaitDone(command, 120_000)
        driver.typed("and of Germany")
        // The typed text becomes the new exchange's transcript, so the same
        // transcript key tells this answer apart from the first one.
        val end = awaitDone(
            AssistantCommand.Ask("and of Germany", "and of Germany"),
            120_000,
        )
        assertEquals("expected Answered, got $end", AnswerStatus.Answered, end.status)
        assertTrue("expected berlin in ${end.answer}", end.answer.lowercase().contains("berlin"))
    }
}
