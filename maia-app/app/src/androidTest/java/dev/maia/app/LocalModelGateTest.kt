package dev.maia.app

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.maia.app.answer.AnswerDriver
import dev.maia.app.answer.AnswerSource
import dev.maia.app.answer.AnswerState
import dev.maia.app.answer.AnswerStatus
import dev.maia.app.answer.HandoffOutcome
import dev.maia.app.answer.HandoffRunner
import dev.maia.app.answer.LiteRtConverser
import dev.maia.app.answer.LocalReader
import dev.maia.app.answer.TensorDispatch
import dev.maia.app.flow.AssistantCommand
import dev.maia.audio.speech.Speaker
import dev.maia.transport.Turn
import java.io.File
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The on-device model gates: a real LiteRT-LM engine on a real Tensor G5.
 *
 * Separate from [DeviceGateTest] because it builds its own driver rather
 * than the host's: the point is a devbox that cannot be reached, and the
 * honest way to arrange that without disturbing the stored pairing is a
 * driver whose `assistantFor` returns nothing. The local half is entirely
 * real: the model file, the dispatch library, the NPU.
 *
 * The model is provisioned out of band (scripts/provision-gemma.sh). Where
 * it is absent the generative cases skip rather than fail: an unprovisioned
 * phone is a legal state, and the presence/absence check is the part that
 * still runs.
 */
@RunWith(AndroidJUnit4::class)
class LocalModelGateTest {

    private val spoken = CopyOnWriteArrayList<String>()
    private val speaker = Speaker { text -> spoken.add(text); emptyFlow() }

    private lateinit var context: Context
    private lateinit var converser: LiteRtConverser

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        converser = LiteRtConverser(
            modelDir = File(context.filesDir, LiteRtConverser.MODEL_DIR_NAME),
            cacheDir = File(context.cacheDir, "litert-test"),
            dispatchLibDir = { TensorDispatch.install(context) },
        )
    }

    // -------------------------------------------------------- presence

    @Test
    fun model_presenceMatchesFilesystem() {
        val onDisk = File(context.filesDir,
            "${LiteRtConverser.MODEL_DIR_NAME}/${LiteRtConverser.MODEL_FILE}").isFile
        assertEquals(onDisk, converser.installed)
        Log.i(TAG, "model installed: ${converser.installed}")
    }

    @Test
    fun dispatchLib_extractsToARealFile() {
        // The NPU runtime scans a directory for this name; it must exist on
        // the filesystem, not inside the APK.
        val dir = TensorDispatch.install(context)
        val lib = File(dir, TensorDispatch.LIB)
        assertTrue("dispatch library not at ${lib.absolutePath}", lib.isFile && lib.length() > 0)
    }

    // ------------------------------------------------------- generation

    @Test
    fun reply_generatesLocally_whenModelPresent() {
        assumeTrue("gemma not provisioned on this device", converser.installed)
        val start = SystemClock.elapsedRealtime()
        val answer = runBlocking { converser.reply("What is the capital of France? One word.", emptyList()) }
        val elapsed = SystemClock.elapsedRealtime() - start
        Log.i(TAG, "load+generate took ${elapsed}ms")
        assertNotNull("the model produced nothing", answer)
        assertTrue("expected paris in $answer", answer!!.lowercase().contains("paris"))
    }

    @Test
    fun reply_carriesHistory_andStaysOnPhone() {
        assumeTrue("gemma not provisioned on this device", converser.installed)
        runBlocking {
            assertNotNull(converser.reply("My name is Nuh.", emptyList()))
            val followUp = converser.reply(
                "What is my name? One word.",
                listOf(Turn(dev.maia.transport.Role.USER, "My name is Nuh."),
                    Turn(dev.maia.transport.Role.ASSISTANT, "Your name is Nuh.")),
            )
            assertNotNull(followUp)
            assertTrue("expected nuh in $followUp", followUp!!.lowercase().contains("nuh"))
        }
    }

    @Test
    fun driver_unreachableDevbox_fallsBackToPhoneModel() {
        assumeTrue("gemma not provisioned on this device", converser.installed)
        val states = CopyOnWriteArrayList<AnswerState>()
        val driver = AnswerDriver(
            reader = LocalReader(
                calendar = object : dev.maia.actions.CalendarRepository {
                    override suspend fun calendars() = emptyList<dev.maia.actions.MaiaCalendar>()
                    override suspend fun defaultTarget() = null
                    override suspend fun chooseTarget(calendarId: Long) = Unit
                    override suspend fun commit(draft: dev.maia.nlu.EventDraft, calendarId: Long) = 0L
                    override suspend fun delete(eventId: Long) = false
                    override suspend fun eventsIn(range: ClosedRange<java.time.ZonedDateTime>) =
                        emptyList<dev.maia.actions.CalendarEvent>()
                },
                clock = Clock.systemDefaultZone(),
                battery = { 50 },
            ),
            handoffs = HandoffRunner { HandoffOutcome.Opened },
            // The whole point: there is no devbox for this driver.
            assistantFor = { null },
            converserFor = { converser },
            render = { states += it },
            speaker = speaker,
            feel = { },
            clock = System::currentTimeMillis,
            work = Executors.newSingleThreadExecutor(),
        )
        val command = AssistantCommand.Ask(
            "what is the capital of France? answer in one word",
            "what is the capital of France",
        )
        driver.handle(command)
        val deadline = System.currentTimeMillis() + 180_000
        var end = driver.current()
        while (System.currentTimeMillis() < deadline &&
            !(end.spoken == command.spoken &&
                (end.status == AnswerStatus.Answered || end.status == AnswerStatus.Failed))
        ) {
            Thread.sleep(100)
            end = driver.current()
        }
        assertEquals("expected Answered, got $end", AnswerStatus.Answered, end.status)
        assertEquals(AnswerSource.PhoneModel, end.source)
        assertTrue("expected paris in ${end.answer}", end.answer.lowercase().contains("paris"))
        assertFalse("coding-agent text must never be voiced", spoken.any { it.length > 4000 })
        driver.close()
    }
}

private const val TAG = "LocalModelGate"
