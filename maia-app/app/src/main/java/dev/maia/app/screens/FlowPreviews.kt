package dev.maia.app.screens

import android.content.res.Configuration.UI_MODE_NIGHT_NO
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import dev.maia.actions.CalendarTarget
import dev.maia.actions.MaiaCalendar
import dev.maia.app.flow.Download
import dev.maia.app.flow.FaultReason
import dev.maia.app.flow.FlowState
import dev.maia.app.flow.LockedCopy
import dev.maia.app.flow.UNDO_WINDOW_MS
import dev.maia.app.flow.UndoStatus
import dev.maia.app.flow.aperture
import dev.maia.app.flow.lockedSummary
import dev.maia.app.flow.questionSummary
import dev.maia.app.ui.LocalMaiaColours
import dev.maia.app.ui.MaiaOrbHost
import dev.maia.app.ui.maiaColours
import dev.maia.audio.Word
import dev.maia.nlu.EventDraft
import dev.maia.nlu.Field
import dev.maia.nlu.Provenance
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

/** One preview per state, on the handoff's frame. Built by hand, like the test fixtures. */
private object Sample {
    private val zone = ZoneId.of("Europe/Zurich")

    val heard = EventDraft(
        title = Field("dinner with sam", Provenance.Heard, 0..2),
        start = Field(ZonedDateTime.of(2026, 9, 17, 20, 0, 0, 0, zone), Provenance.Heard),
        duration = Field(Duration.ofHours(1), Provenance.Inferred),
        transcript = "dinner with sam thursday at eight p m",
    )

    val dateless = EventDraft(
        title = Field("dinner with sam", Provenance.Heard, 0..2),
        start = Field(ZonedDateTime.of(2026, 9, 13, 10, 0, 0, 0, zone), Provenance.Inferred),
        duration = Field(Duration.ofHours(1), Provenance.Inferred),
        transcript = "dinner with sam",
    )

    val personal = CalendarTarget(MaiaCalendar(7, "Personal", "me@example.org"), chosen = true)
    val words = listOf(Word("dinner", 0.9f), Word("with", 0.8f), Word("sam", 0.4f), Word("thurs", 0.2f))
    const val MB = 1024L * 1024L
}

/**
 * Each preview is drawn twice, dark and light, and the palette follows the
 * preview's uiMode. Dark is listed first because dark is the product.
 */
@Composable
private fun Frame(state: FlowState, now: Long = 0, content: (@Composable () -> Unit)? = null) {
    CompositionLocalProvider(LocalMaiaColours provides maiaColours()) {
        MaiaOrbHost(state.aperture) {
            content?.invoke() ?: FlowScreen(state = state, onEvent = {}, now = now, writtenTo = "Personal")
        }
    }
}

@Preview(name = "4h first run", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "4h first run, light", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_NO)
@Composable
private fun FirstRunPreview() = Frame(FlowState.FirstRun())

@Preview(name = "4h downloading", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "4h downloading, light", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_NO)
@Composable
private fun DownloadingPreview() {
    val state = FlowState.FirstRun(Download("encoder.onnx", 32 * Sample.MB, 70 * Sample.MB, running = true))
    Frame(state) { FlowScreen(state, onEvent = {}, now = 0, bytesPerSecond = 1.0 * Sample.MB) }
}

@Preview(name = "4h failed", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "4h failed, light", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_NO)
@Composable
private fun DownloadFailedPreview() =
    Frame(FlowState.FirstRun(Download("encoder.onnx", 12 * Sample.MB, 70 * Sample.MB, error = "the connection dropped")))

@Preview(name = "idle", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "idle, light", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_NO)
@Composable
private fun IdlePreview() = Frame(FlowState.Idle())

@Preview(name = "idle, last heard", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "idle, last heard, light", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_NO)
@Composable
private fun IdleHeardPreview() = Frame(FlowState.Idle("what is on tomorrow"))

@Preview(name = "4a invoking", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "4a invoking, light", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_NO)
@Composable
private fun InvokingPreview() = Frame(FlowState.Invoking(pressedAt = 0))

@Preview(name = "4b listening", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "4b listening, light", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_NO)
@Composable
private fun ListeningPreview() =
    Frame(FlowState.Listening(pressedAt = 0, partial = "dinner with sam thurs", words = Sample.words))

@Preview(name = "4c understanding", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "4c understanding, light", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_NO)
@Composable
private fun UnderstandingPreview() = Frame(FlowState.Understanding(Sample.heard.transcript, since = 0))

@Preview(name = "3b preview", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "3b preview, light", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_NO)
@Composable
private fun CardPreview() = Frame(FlowState.Preview(Sample.heard, Sample.personal, hold = 0.4f))

@Preview(name = "3b preview, refused over a card", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "3b preview, refused over a card, light", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_NO)
@Composable
private fun CardRefusedPreview() =
    Frame(FlowState.Preview(Sample.heard, Sample.personal, error = LockedCopy.CARD_IN_HAND, heardNote = "said at 23:50"))

@Preview(name = "queued", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "queued, light", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_NO)
@Composable
private fun QueuedPreview() = Frame(FlowState.Queued(Sample.heard, heardAt = 0, summary = lockedSummary(Sample.heard)))

@Preview(name = "queued question", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "queued question, light", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_NO)
@Composable
private fun QueuedQuestionPreview() =
    Frame(FlowState.Queued(null, heardAt = 0, summary = questionSummary("what is on tomorrow")))

@Preview(name = "4i no calendar", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "4i no calendar, light", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_NO)
@Composable
private fun NoCalendarPreview() = Frame(FlowState.NoCalendar(Sample.heard))

@Preview(name = "4e committing", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "4e committing, light", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_NO)
@Composable
private fun CommittingPreview() = Frame(FlowState.Committing(Sample.heard, Sample.personal))

@Preview(name = "4f confirmed", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "4f confirmed, light", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_NO)
@Composable
private fun ConfirmedPreview() =
    Frame(FlowState.Confirmed(Sample.heard, eventId = 42, undoDeadline = UNDO_WINDOW_MS), now = 2_000)

@Preview(name = "4f undone", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "4f undone, light", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_NO)
@Composable
private fun UndonePreview() =
    Frame(FlowState.Confirmed(Sample.heard, eventId = 42, undoDeadline = UNDO_WINDOW_MS, undo = UndoStatus.Undone))

@Preview(name = "4g no date heard", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "4g no date heard, light", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_NO)
@Composable
private fun NoDatePreview() = Frame(FlowState.Fault(FaultReason.NoDateHeard, "dinner with sam", Sample.dateless))

@Preview(name = "capture failed", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "capture failed, light", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_NO)
@Composable
private fun CaptureFailedPreview() =
    Frame(FlowState.Fault(FaultReason.CaptureFailed("recogniser stopped"), "dinner with"))

@Preview(name = "queue full", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "queue full, light", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_NO)
@Composable
private fun QueueFullPreview() = Frame(FlowState.Fault(FaultReason.QueueFull, ""))
