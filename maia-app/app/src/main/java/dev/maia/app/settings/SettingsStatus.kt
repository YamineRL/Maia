package dev.maia.app.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.maia.app.R
import dev.maia.app.answer.LiteRtConverser
import dev.maia.app.agent.PrefsAgentSecrets
import dev.maia.app.ui.ButtonKind
import dev.maia.app.ui.DownloadCopy
import dev.maia.app.ui.Maia
import dev.maia.app.ui.MaiaButton
import dev.maia.app.ui.ModelDownload
import dev.maia.audio.ModelStore
import dev.maia.audio.OfflineModelStore
import java.io.File
import java.util.Locale

/** The three models Maia keeps on the phone, keyed like `EngineHolder.downloads`. */
enum class ModelKind(val key: String?) { Speech(null), SharperSpeech("rescorer"), OfflineAnswers("gemma") }

/** One model's row: what is on disk, and whether it can be fetched from here. */
data class ModelRow(val kind: ModelKind, val onDisk: Boolean, val bytes: Long)

data class PermissionRow(val label: Int, val granted: Boolean)

/** What the settings screen reports beyond its choices. Read off the main thread. */
data class SettingsStatus(
    val devboxPaired: Boolean,
    val assistantPaired: Boolean,
    val everReached: Boolean,
    val models: List<ModelRow>,
    val permissions: List<PermissionRow>,
    val version: String,
)

/** A byte count as the settings screen prints it: MB under a gigabyte, GB above. */
fun sizeLabel(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) String.format(Locale.ROOT, "%.1f GB", mb / 1024) else String.format(Locale.ROOT, "%.0f MB", mb)
}

private fun File.size(): Long = walkTopDown().filter { it.isFile }.sumOf { it.length() }

private val PERMISSIONS = listOf(
    Manifest.permission.RECORD_AUDIO to R.string.settings_perm_microphone,
    Manifest.permission.READ_CALENDAR to R.string.settings_perm_calendar,
    Manifest.permission.READ_CONTACTS to R.string.settings_perm_contacts,
    Manifest.permission.CAMERA to R.string.settings_perm_camera,
    Manifest.permission.POST_NOTIFICATIONS to R.string.settings_perm_notifications,
)

fun readSettingsStatus(context: Context): SettingsStatus {
    val files = context.filesDir
    val secrets = PrefsAgentSecrets(context)
    val speech = File(files, "models")
    val sharper = File(files, "models-parakeet")
    val gemmaDir = File(files, LiteRtConverser.MODEL_DIR_NAME)
    val gemma = File(gemmaDir, LiteRtConverser.MODEL_FILE)
    val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
    val requested = info.requestedPermissions.orEmpty().toSet()
    return SettingsStatus(
        devboxPaired = secrets.load()?.complete == true,
        assistantPaired = secrets.assistantComplete(),
        everReached = secrets.everSucceeded(),
        models = listOf(
            ModelRow(ModelKind.Speech, ModelStore(speech).isComplete, speech.size()),
            ModelRow(ModelKind.SharperSpeech, OfflineModelStore(sharper).isComplete, sharper.size()),
            ModelRow(ModelKind.OfflineAnswers, gemma.isFile, gemmaDir.size()),
        ),
        permissions = PERMISSIONS.filter { it.first in requested }.map { (name, label) ->
            PermissionRow(label, ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED)
        },
        version = "${info.versionName} (${info.longVersionCode})",
    )
}

@Composable
private fun SectionHead(text: Int) {
    Text(
        stringResource(text).uppercase(),
        style = Maia.type.label,
        color = Maia.colours.inkMid,
        modifier = Modifier.padding(top = Maia.space.lg),
    )
}

@Composable
private fun Line(name: String, value: String, good: Boolean) {
    val colours = Maia.colours
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, style = Maia.type.body, color = colours.inkHigh)
        Text(value, style = Maia.type.body, color = if (good) colours.inkMid else colours.accentAperture)
    }
}

/** The sections under the choices: the devbox, the models, the permissions, the build. */
@Composable
fun StatusSections(
    status: SettingsStatus?,
    downloads: Map<String, ModelDownload>,
    onDownload: (ModelKind) -> Unit,
    onPermissions: () -> Unit,
) {
    if (status == null) return
    val colours = Maia.colours

    SectionHead(R.string.settings_devbox_label)
    Line(
        stringResource(R.string.settings_devbox_agent),
        stringResource(if (status.devboxPaired) R.string.settings_paired else R.string.settings_not_paired),
        status.devboxPaired,
    )
    Line(
        stringResource(R.string.settings_devbox_assistant),
        stringResource(if (status.assistantPaired) R.string.settings_paired else R.string.settings_not_paired),
        status.assistantPaired,
    )
    Text(
        stringResource(if (status.everReached) R.string.settings_devbox_reached else R.string.settings_devbox_never),
        style = Maia.type.caption,
        color = colours.inkMid,
    )

    SectionHead(R.string.settings_models_label)
    status.models.forEach { row ->
        val name = stringResource(
            when (row.kind) {
                ModelKind.Speech -> R.string.settings_model_speech
                ModelKind.SharperSpeech -> R.string.settings_model_sharper
                ModelKind.OfflineAnswers -> R.string.settings_model_answers
            },
        )
        val live = row.kind.key?.let { downloads[it] }
        Column(verticalArrangement = Arrangement.spacedBy(Maia.space.xs)) {
            when {
                live != null -> {
                    Line(name, stringResource(R.string.settings_model_downloading, (live.fraction * 100).toInt()), true)
                    Box(Modifier.fillMaxWidth().height(2.dp).background(colours.lineHair)) {
                        Box(Modifier.fillMaxWidth(live.fraction).height(2.dp).background(colours.accentAperture))
                    }
                    Text(
                        DownloadCopy.line(live.bytesDone, live.bytesTotal, live.bytesPerSecond),
                        style = Maia.type.caption,
                        color = colours.inkLow,
                    )
                }
                row.onDisk -> Line(name, stringResource(R.string.settings_model_ready, sizeLabel(row.bytes)), true)
                else -> {
                    Line(name, stringResource(R.string.settings_model_missing), false)
                    if (row.kind.key != null) {
                        Text(
                            stringResource(
                                if (row.kind == ModelKind.OfflineAnswers) R.string.settings_model_answers_caption
                                else R.string.settings_model_sharper_caption,
                            ),
                            style = Maia.type.caption,
                            color = colours.inkMid,
                        )
                        MaiaButton(
                            stringResource(R.string.settings_model_download_now),
                            ButtonKind.Quiet,
                            Modifier.fillMaxWidth(),
                        ) { onDownload(row.kind) }
                    }
                }
            }
        }
    }

    SectionHead(R.string.settings_perms_label)
    status.permissions.forEach { p ->
        Line(
            stringResource(p.label),
            stringResource(if (p.granted) R.string.settings_perm_allowed else R.string.settings_perm_denied),
            p.granted,
        )
    }
    MaiaButton(stringResource(R.string.settings_perms_open), ButtonKind.Quiet, Modifier.fillMaxWidth(), onClick = onPermissions)

    SectionHead(R.string.settings_about_label)
    Line(stringResource(R.string.settings_about_version), status.version, true)
    Line(
        stringResource(R.string.settings_about_storage),
        sizeLabel(status.models.sumOf { it.bytes }),
        true,
    )
}
