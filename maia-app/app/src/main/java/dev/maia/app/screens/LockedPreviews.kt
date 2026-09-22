package dev.maia.app.screens

import android.content.res.Configuration.UI_MODE_NIGHT_NO
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import dev.maia.app.flow.LockedSummary
import dev.maia.app.flow.lockedSummary
import dev.maia.app.flow.questionSummary
import dev.maia.app.ui.LocalMaiaColours
import dev.maia.app.ui.MaiaOrbHost
import dev.maia.app.ui.maiaColours
import dev.maia.nlu.EventDraft
import dev.maia.nlu.Field
import dev.maia.nlu.Provenance
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * One preview per locked scene, on the handoff's frame, beside M2's.
 *
 * Its own file rather than more previews in `FlowPreviews.kt`: that is M2's,
 * and a preview file is the cheapest thing in the tree to keep separate.
 *
 * Both guessed and heard are here because the difference between them is the
 * whole of `docs/M3-copy.md` section 9 item 2, and it is one pair of angle
 * brackets that is easy to lose and impossible to see in a test.
 */
private object LockedSample {
    private val zone = ZoneId.of("Europe/Zurich")

    val heard = EventDraft(
        title = Field("dinner with sam", Provenance.Heard, 0..2),
        start = Field(ZonedDateTime.of(2026, 9, 17, 20, 0, 0, 0, zone), Provenance.Heard),
        duration = Field(Duration.ofHours(1), Provenance.Inferred),
        transcript = "dinner with sam thursday at eight p m",
    )

    val guessed = heard.copy(
        start = Field(ZonedDateTime.of(2026, 9, 17, 20, 0, 0, 0, zone), Provenance.Inferred),
        transcript = "dinner with sam at eight",
    )
}

/**
 * Each preview is drawn twice, dark and light, and the palette follows the
 * preview's uiMode. Dark is listed first because dark is the product.
 */
@Composable
private fun LockedFrame(scene: LockedScene) {
    CompositionLocalProvider(LocalMaiaColours provides maiaColours()) {
        MaiaOrbHost(scene.aperture) { LockedScreen(scene = scene, onEvent = {}) }
    }
}

@Preview(name = "locked kept, heard", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "locked kept, heard, light", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_NO)
@Composable
private fun KeptHeardPreview() =
    LockedFrame(LockedScene.Kept(lockedSummary(LockedSample.heard), whenGuessed = false))

@Preview(name = "locked kept, guessed day", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "locked kept, guessed day, light", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_NO)
@Composable
private fun KeptGuessedPreview() =
    LockedFrame(LockedScene.Kept(lockedSummary(LockedSample.guessed), whenGuessed = true))

@Preview(name = "locked kept as a note", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "locked kept as a note, light", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_NO)
@Composable
private fun KeptNotePreview() =
    LockedFrame(LockedScene.Kept(LockedSummary("call the vet", "", "note call the vet"), whenGuessed = false, note = true))

@Preview(name = "locked read-back refused", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "locked read-back refused, light", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_NO)
@Composable
private fun ReadBackPreview() =
    LockedFrame(LockedScene.ReadBack(questionSummary("what is on tomorrow")))

@Preview(name = "locked before first unlock", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "locked before first unlock, light", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_NO)
@Composable
private fun BeforeFirstUnlockPreview() = LockedFrame(LockedScene.BeforeFirstUnlock)

@Preview(name = "locked no microphone", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "locked no microphone, light", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_NO)
@Composable
private fun NoMicrophonePreview() = LockedFrame(LockedScene.NoMicrophone)

@Preview(name = "locked first run", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "locked first run, light", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_NO)
@Composable
private fun FirstRunLockedPreview() = LockedFrame(LockedScene.FirstRun)

@Preview(name = "locked queue full, one waiting", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "locked queue full, one waiting, light", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_NO)
@Composable
private fun QueueFullOnePreview() = LockedFrame(LockedScene.QueueFull(1))

@Preview(name = "locked queue full, five waiting", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "locked queue full, five waiting, light", widthDp = 412, heightDp = 915, uiMode = UI_MODE_NIGHT_NO)
@Composable
private fun QueueFullManyPreview() = LockedFrame(LockedScene.QueueFull(5))
