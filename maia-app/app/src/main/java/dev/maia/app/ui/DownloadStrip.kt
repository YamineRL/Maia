package dev.maia.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** A model being fetched in the background, as the idle screen shows it. */
data class ModelDownload(
    val label: String,
    val bytesDone: Long,
    val bytesTotal: Long,
    val bytesPerSecond: Double,
) {
    val fraction: Float
        get() = if (bytesTotal > 0) (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f) else 0f
}

@Composable
fun DownloadStrip(downloads: Collection<ModelDownload>, modifier: Modifier = Modifier) {
    if (downloads.isEmpty()) return
    val colours = Maia.colours
    Column(
        modifier.fillMaxWidth().padding(horizontal = Maia.space.gutter),
        verticalArrangement = Arrangement.spacedBy(Maia.space.md),
    ) {
        downloads.forEach { download ->
            Column(verticalArrangement = Arrangement.spacedBy(Maia.space.xs)) {
                Text(download.label.uppercase(), style = Maia.type.label, color = colours.inkMid)
                Box(Modifier.fillMaxWidth().height(2.dp).background(colours.lineHair)) {
                    Box(Modifier.fillMaxWidth(download.fraction).height(2.dp).background(colours.accentAperture))
                }
                Text(
                    DownloadCopy.line(download.bytesDone, download.bytesTotal, download.bytesPerSecond),
                    style = Maia.type.caption,
                    color = colours.inkLow,
                )
            }
        }
    }
}
