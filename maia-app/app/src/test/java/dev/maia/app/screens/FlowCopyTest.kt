package dev.maia.app.screens

import dev.maia.app.flow.Download
import dev.maia.app.flow.FaultReason
import dev.maia.app.flow.FlowEvent
import dev.maia.app.flow.FlowState
import dev.maia.app.flow.Fixtures
import dev.maia.app.flow.LockedCopy
import dev.maia.app.flow.UndoStatus
import dev.maia.app.flow.asInvoke
import dev.maia.app.flow.reduce
import dev.maia.app.ui.DownloadCopy
import dev.maia.audio.OfflineModelStore
import dev.maia.audio.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.roundToInt

class FlowCopyTest {

    @Test
    fun `the invoke control is M2's press, told which door it is`() {
        assertEquals(FlowEvent.Press.asInvoke(), LAUNCHER_INVOKE)
        assertFalse(LAUNCHER_INVOKE.locked)
    }

    @Test
    fun `scored words take their ink from confidence`() {
        val inked = inkedWords(listOf(Word("dinner", 0.9f), Word("sam", 0.3f)), "dinner sam")
        assertEquals(listOf(InkedWord("dinner", true), InkedWord("sam", false)), inked)
    }

    @Test
    fun `an unscored partial is not drawn as doubtful`() {
        assertEquals(
            listOf(InkedWord("dinner", true), InkedWord("with", true)),
            inkedWords(emptyList(), " dinner  with "),
        )
        assertTrue(inkedWords(emptyList(), "  ").isEmpty())
    }

    @Test
    fun `undo counts whole seconds up and stops at zero`() {
        assertEquals(8, undoSecondsLeft(now = 0, deadline = 8_000))
        assertEquals(8, undoSecondsLeft(now = 500, deadline = 8_000))
        assertEquals(1, undoSecondsLeft(now = 7_001, deadline = 8_000))
        assertEquals(0, undoSecondsLeft(now = 8_000, deadline = 8_000))
        assertEquals(0, undoSecondsLeft(now = 9_000, deadline = 8_000))
        assertEquals(8, undoSecondsLeft(now = -5_000, deadline = 8_000))
    }

    @Test
    fun `undo is offered only while it can still work`() {
        assertEquals(UndoLine("Undo", "3 s", null), undoLine(UndoStatus.Offered, 3))
        assertEquals(UndoLine(null, null, null), undoLine(UndoStatus.Offered, 0))
        assertEquals(UndoLine(null, null, null), undoLine(UndoStatus.Expired, 5))
        UndoStatus.entries.filter { it != UndoStatus.Offered }.forEach {
            assertNull("$it offers an undo", undoLine(it, 5).action)
        }
    }

    @Test
    fun `a delete that found nothing or failed never reads as an undo`() {
        assertFalse(undoLine(UndoStatus.AlreadyGone, 0).note!!.startsWith("Undone"))
        assertTrue(undoLine(UndoStatus.Failed, 0).note!!.contains("may still be"))
    }

    @Test
    fun `written to names the calendar when the host knows it`() {
        assertEquals("Written to Personal", writtenTo("Personal"))
        assertEquals("Written to the calendar", writtenTo(null))
        assertEquals("Written to the calendar", writtenTo(" "))
    }

    @Test
    fun `chips offer tonight, tomorrow and a named day`() {
        // Sunday 13 September, 10:00.
        val chips = dateChips(Fixtures.dateless.start.value, Locale.ENGLISH)
        assertEquals(listOf("Tonight", "Tomorrow", "Tuesday"), chips.map { it.label })
        assertEquals(listOf("Sun 13 Sep  20:00", "Mon 14 Sep  10:00", "Tue 15 Sep  10:00"), chips.map { it.detail })
    }

    @Test
    fun `chip hours round up and never cross midnight`() {
        val zone = Fixtures.zone
        val late = dateChips(ZonedDateTime.of(2026, 9, 13, 23, 30, 0, 0, zone), Locale.ENGLISH)
        assertEquals(listOf("Tomorrow", "Tuesday"), late.map { it.label })
        assertEquals(ZonedDateTime.of(2026, 9, 14, 23, 0, 0, 0, zone), late.first().start)

        val odd = dateChips(ZonedDateTime.of(2026, 9, 13, 10, 20, 5, 0, zone), Locale.ENGLISH)
        assertEquals(ZonedDateTime.of(2026, 9, 14, 11, 0, 0, 0, zone), odd[1].start)

        val eight = dateChips(ZonedDateTime.of(2026, 9, 13, 20, 0, 0, 0, zone), Locale.ENGLISH)
        assertEquals("Tomorrow", eight.first().label)
    }

    @Test
    fun `every chip opens the card through the reducer`() {
        val fault = FlowState.Fault(FaultReason.NoDateHeard, "dinner with sam", Fixtures.dateless)
        dateChips(Fixtures.dateless.start.value, Locale.ENGLISH).forEach { chip ->
            val next = reduce(fault, FlowEvent.DatePicked(chip.start), now = 0).state
            assertTrue("${chip.label} gave $next", next is FlowState.Preview)
            assertEquals(chip.start, (next as FlowState.Preview).draft.start.value)
        }
    }

    @Test
    fun `first run offers the download and then gets out of the way`() {
        val fresh = firstRunLine(Download())
        assertNull(fresh.numbers)
        assertEquals("Download", fresh.action)

        val mb = 1024L * 1024L
        val running = firstRunLine(Download("a", 10 * mb, 70 * mb, running = true), bytesPerSecond = 1e6)
        assertNull(running.action)
        assertEquals(DownloadCopy.line(10 * mb, 70 * mb, 1e6), running.numbers)
    }

    @Test
    fun `live bytes win over the last progress kept in state`() {
        val mb = 1024L * 1024L
        val line = firstRunLine(Download("a", 1 * mb, null, running = true), 30 * mb, 70 * mb, 0.0)
        assertEquals("30.0 / 70.0 MB", line.numbers)
    }

    @Test
    fun `a failed download says why and offers another go`() {
        val line = firstRunLine(Download("a", 5, 10, running = false, error = "the connection dropped"))
        assertEquals("Try again", line.action)
        assertEquals("the connection dropped", line.note)
        assertTrue(line.failed)
    }

    @Test
    fun `fault copy follows the handoff and the locked copy`() {
        assertEquals("I did not hear a day", faultCopy(FaultReason.NoDateHeard).title)
        assertEquals("Too many drafts are waiting for an unlock", faultCopy(FaultReason.QueueFull).title)
        assertTrue(faultCopy(FaultReason.QueueFull).title.endsWith(LockedCopy.QUEUE_FULL.drop(1)))
    }

    @Test
    fun `quotes keep the words and drop nothing`() {
        assertEquals("“dinner with sam”", quoted(" dinner with sam "))
        assertNull(quoted(""))
        assertNull(quoted(null))
    }

    // ------------------------------------------- the rescorer consent screen
    //
    // PRD section 18: ask once on first run, before the first Parakeet
    // download. These words have no generator and no copy document, so this is
    // the guard, and it is written to fail on the two ways this screen rots:
    // the size drifting away from the bytes actually fetched, and a promise
    // being made with nothing behind it.

    /**
     * The size on the screen is the size the counter reaches.
     *
     * [DownloadCopy] counts in 1024 x 1024 and labels it MB, so a consent
     * screen that said 670 would name a number the progress line never gets
     * to. This pins the word to `OfflineModelStore.APPROXIMATE_BYTES`, so the
     * day the model changes the copy goes red rather than quietly becoming a
     * lie.
     */
    @Test
    fun `the rescorer screen names the size the download actually is`() {
        val megabytes = OfflineModelStore.APPROXIMATE_BYTES / (1024.0 * 1024.0)
        val rounded = (megabytes / 10).roundToInt() * 10
        assertEquals(640, rounded)
        listOf(
            FlowCopy.RESCORER_BODY,
            FlowCopy.RESCORER_METERED,
            FlowCopy.RESCORER_SETTING_ON,
            FlowCopy.RESCORER_SETTING_REMOVE_CAPTION,
        ).forEach {
            assertTrue("$it does not name $rounded MB", it.contains("$rounded MB"))
        }
        assertEquals("640.0 / 640.0 MB", DownloadCopy.bytes(671_088_640, 671_088_640))
    }

    /** The three things the decision required the words to carry. */
    @Test
    fun `the rescorer screen says what it costs, what it buys and what declining loses`() {
        assertTrue(FlowCopy.RESCORER_SOURCE.contains("huggingface.co"))
        assertTrue(FlowCopy.RESCORER_BODY.contains("Maia works without it"))
        assertTrue(FlowCopy.RESCORER_METERED.contains("mobile data"))
        assertTrue(FlowCopy.RESCORER_LATER.contains("settings"))
    }

    /**
     * The way back is a promise, so the words behind it have to exist. A
     * settings row named in one constant and missing from the next is the
     * pairing screen's failure in miniature.
     */
    @Test
    fun `the way to change the answer later has words of its own`() {
        listOf(
            FlowCopy.RESCORER_SETTING_TITLE,
            FlowCopy.RESCORER_SETTING_ON,
            FlowCopy.RESCORER_SETTING_OFF,
            FlowCopy.RESCORER_SETTING_REMOVE,
            FlowCopy.RESCORER_SETTING_REMOVE_CAPTION,
        ).forEach { assertTrue(it.isNotBlank()) }
        assertTrue(FlowCopy.RESCORER_SETTING_REMOVE.startsWith("Turn it off"))
    }

    /**
     * One download twice over is one vocabulary, not two: the running note,
     * the retry and the action word are the first screen's, reused.
     */
    @Test
    fun `the second download borrows the first screen's controls`() {
        assertEquals("Download", FlowCopy.DOWNLOAD)
        assertEquals("Resumes if interrupted", FlowCopy.RESUMES)
        assertEquals("Try again", FlowCopy.TRY_AGAIN)
        assertEquals("Skip it", FlowCopy.RESCORER_SKIP)
    }

    /** The house rules, on the words this screen adds. */
    @Test
    fun `the rescorer copy keeps the house rules`() {
        listOf(
            FlowCopy.RESCORER_EYEBROW,
            FlowCopy.RESCORER_TITLE,
            FlowCopy.RESCORER_BODY,
            FlowCopy.RESCORER_SOURCE,
            FlowCopy.RESCORER_METERED,
            FlowCopy.RESCORER_LATER,
            FlowCopy.RESCORER_SKIP,
            FlowCopy.RESCORER_SETTING_TITLE,
            FlowCopy.RESCORER_SETTING_ON,
            FlowCopy.RESCORER_SETTING_OFF,
            FlowCopy.RESCORER_SETTING_REMOVE,
            FlowCopy.RESCORER_SETTING_REMOVE_CAPTION,
        ).forEach {
            assertFalse("$it has an em dash", it.contains('\u2014') || it.contains('\u2013'))
            assertFalse("$it shouts", it.contains('!'))
        }
    }
}
