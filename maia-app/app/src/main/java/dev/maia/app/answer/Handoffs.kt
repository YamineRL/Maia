package dev.maia.app.answer

import dev.maia.nlu.Intent

/**
 * What an Act intent will open, as a [HandoffSpec] the screen can describe
 * without an Android `Intent` in sight.
 *
 * Two decisions live here and nowhere else. The first is section 6's preview
 * rule: timers and alarms are consequential enough to get a compact preview
 * and a confirm before anything fires, and everything else goes straight to
 * its system UI, where the user confirms in the target app itself. The
 * second is which of these needs a runtime grant before it can run at all:
 * only the torch, whose `CameraManager.setTorchMode` wants `CAMERA`
 * (sections 3.2 and 12). The driver reads [HandoffSpec.needsCamera] and asks
 * first; the machine reads only [HandoffSpec.needsConfirm].
 *
 * Null for anything that is not an Act intent. Agenda, availability, a device
 * fact and a calculation are reads, and a remote action can propose them
 * (section 8.3's capability list includes them), so the machine asks rather
 * than assumes: a read has no target to hand off to and describing it as one
 * would be the same lie as claiming a dial placed a call.
 */
fun specFor(intent: Intent): HandoffSpec? = when (intent) {
    is Intent.SetTimer -> HandoffSpec(
        label = "Timer for ${durationText(intent.durationMs)}" + labelled(intent.label),
        target = "Clock",
        needsConfirm = true,
    )
    is Intent.SetAlarm -> HandoffSpec(
        label = "Alarm for %02d:%02d".format(intent.hour, intent.minute) +
            (if (intent.tomorrow) " tomorrow" else "") + labelled(intent.label),
        target = "Clock",
        needsConfirm = true,
    )
    is Intent.OpenSettings -> HandoffSpec(
        label = when (intent.panel) {
            Intent.OpenSettings.Panel.WIFI -> "Wi-Fi settings"
            Intent.OpenSettings.Panel.BLUETOOTH -> "Bluetooth settings"
            Intent.OpenSettings.Panel.MAIN -> "Settings"
        },
        target = "Settings",
        needsConfirm = false,
    )
    is Intent.SetTorch -> HandoffSpec(
        label = when (intent.on) {
            true -> "Torch on"
            false -> "Torch off"
            null -> "Torch"
        },
        target = "Torch",
        needsConfirm = false,
        needsCamera = true,
    )
    is Intent.Dial -> HandoffSpec(
        label = "Call ${intent.target}",
        target = "Phone",
        needsConfirm = false,
    )
    is Intent.ComposeMessage -> HandoffSpec(
        label = if (intent.to.isNullOrBlank()) "Send a message" else "Text ${intent.to}",
        target = "Messages",
        needsConfirm = false,
    )
    is Intent.Navigate -> HandoffSpec(
        label = "Directions to ${intent.destination}",
        target = "Maps",
        needsConfirm = false,
    )
    is Intent.OpenWeb -> HandoffSpec(
        // A bare domain reads as a place to open; anything else is a query.
        // The browser decides which it truly is either way, so the label is
        // the honest description rather than a guess at the scheme.
        label = when {
            looksLikeDomain(intent.target) -> "Open ${intent.target}"
            // WeatherAsk's query reads as a title on its own.
            intent.target.startsWith("weather") -> intent.target.replaceFirstChar { it.uppercase() }
            else -> "Search for ${intent.target}"
        },
        target = "Browser",
        needsConfirm = false,
    )
    is Intent.OpenApp -> HandoffSpec(
        label = "Open ${intent.name}",
        target = "App",
        needsConfirm = false,
    )
    is Intent.Media -> HandoffSpec(
        label = when (intent.command) {
            Intent.Media.Command.PLAY -> "Play"
            Intent.Media.Command.PAUSE -> "Pause"
            Intent.Media.Command.NEXT -> "Next track"
            Intent.Media.Command.PREVIOUS -> "Previous track"
            Intent.Media.Command.VOLUME_UP -> "Volume up"
            Intent.Media.Command.VOLUME_DOWN -> "Volume down"
            Intent.Media.Command.MUTE -> "Mute"
        },
        target = "Media",
        needsConfirm = false,
    )
    else -> null
}

/**
 * A timer length the way it was said: "12 minutes", "1 hour 30 minutes",
 * "45 seconds". Seconds only appear when there is no whole minute, because
 * "set a timer for an hour and a half" arrives as milliseconds too and
 * "90 minutes" is not what anyone said.
 */
private fun durationText(durationMs: Long): String {
    val totalSeconds = maxOf(durationMs, 0L) / 1_000L
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    val parts = mutableListOf<String>()
    if (hours > 0) parts += unit(hours, "hour")
    if (minutes > 0) parts += unit(minutes, "minute")
    if (seconds > 0 || parts.isEmpty()) parts += unit(seconds, "second")
    return parts.joinToString(" ")
}

private fun unit(n: Long, word: String): String = if (n == 1L) "1 $word" else "$n ${word}s"

/** The user's own label on the card line, or nothing when there is none. */
private fun labelled(label: String?): String =
    if (label.isNullOrBlank()) "" else ": $label"

/** "github.com" is a place; "train times" is a query. Spaces settle it. */
private fun looksLikeDomain(target: String): Boolean =
    ' ' !in target.trim() && '.' in target
