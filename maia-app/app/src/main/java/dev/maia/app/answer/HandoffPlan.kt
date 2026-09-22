package dev.maia.app.answer

import android.content.Intent
import android.media.AudioManager
import android.provider.AlarmClock
import android.provider.Settings
import android.view.KeyEvent
import dev.maia.nlu.Intent as MaiaIntent
import java.net.URLEncoder
import java.util.Calendar

/**
 * What one act intent becomes in Android terms, with no `android.content`
 * object in sight.
 *
 * The same split as `HandoffSpec`, one layer lower: the spec is what the
 * screen says about the handoff, and this is what the executor asks Android
 * to do. Kept free of constructed Android classes so a JVM test can check
 * every URI, extra and package name the phone will ever fire. The Android
 * types that appear are compile-time constants only (`Settings.ACTION_*`,
 * `AlarmClock.EXTRA_*`, `KeyEvent.KEYCODE_*`), which are inlined where they
 * are used and never touch a stubbed method.
 */
sealed interface HandoffPlan {

    /** `startActivity` with an action, an optional URI and typed extras. */
    data class Launch(
        val action: String,
        val uri: String? = null,
        val extras: List<Extra> = emptyList(),
    ) : HandoffPlan

    /**
     * A dial or a message compose. [target] may be digits or a contact name;
     * which it is decides whether the plan can fire as is or must first pass
     * through contacts, and that decision is the executor's because the
     * permission check lives there.
     */
    data class Reach(
        val kind: ReachKind,
        val target: String?,
        val body: String? = null,
    ) : HandoffPlan

    /** `packageManager.getLaunchIntentForPackage` over [packages], first hit wins. */
    data class App(val packages: List<String>) : HandoffPlan

    /** `CameraManager.setTorchMode`. The CAMERA gate is the executor's. */
    data class Torch(val on: Boolean?) : HandoffPlan

    /** `AudioManager` keys and stream volume. No activity is started. */
    data class Media(val command: MaiaIntent.Media.Command) : HandoffPlan
}

enum class ReachKind { DIAL, MESSAGE }

/** One extra as data. [value] is a String, Int, Long, Boolean or List<Int>. */
data class Extra(val key: String, val value: Any)

/**
 * Maps an act intent to what the executor should do, or null when there is
 * no Android target at all: an app nobody tabled, or an intent that was
 * never a handoff. Null is the driver's `HandoffNoTarget`.
 */
fun planFor(intent: MaiaIntent): HandoffPlan? = when (intent) {
    is MaiaIntent.SetTimer -> HandoffPlan.Launch(
        action = AlarmClock.ACTION_SET_TIMER,
        extras = buildList {
            add(Extra(AlarmClock.EXTRA_LENGTH, (intent.durationMs / 1_000).toInt()))
            intent.label?.let { add(Extra(AlarmClock.EXTRA_MESSAGE, it)) }
        },
    )

    is MaiaIntent.SetAlarm -> HandoffPlan.Launch(
        action = AlarmClock.ACTION_SET_ALARM,
        extras = buildList {
            add(Extra(AlarmClock.EXTRA_HOUR, intent.hour))
            add(Extra(AlarmClock.EXTRA_MINUTES, intent.minute))
            intent.label?.let { add(Extra(AlarmClock.EXTRA_MESSAGE, it)) }
            // "Set an alarm for seven tomorrow" is a one-day alarm, which
            // ACTION_SET_ALARM expresses as EXTRA_DAYS holding the day the
            // user named. Repeating alarms are out of the first pass.
            if (intent.tomorrow) add(Extra(AlarmClock.EXTRA_DAYS, listOf(tomorrow())))
        },
    )

    is MaiaIntent.OpenSettings -> HandoffPlan.Launch(
        action = when (intent.panel) {
            MaiaIntent.OpenSettings.Panel.WIFI -> Settings.ACTION_WIFI_SETTINGS
            MaiaIntent.OpenSettings.Panel.BLUETOOTH -> Settings.ACTION_BLUETOOTH_SETTINGS
            MaiaIntent.OpenSettings.Panel.MAIN -> Settings.ACTION_SETTINGS
        },
    )

    is MaiaIntent.SetTorch -> HandoffPlan.Torch(intent.on)

    is MaiaIntent.Dial -> HandoffPlan.Reach(ReachKind.DIAL, intent.target)

    is MaiaIntent.ComposeMessage ->
        HandoffPlan.Reach(ReachKind.MESSAGE, intent.to, intent.body)

    is MaiaIntent.Navigate -> {
        // A named mode goes to the turn-by-turn scheme so the maps app
        // starts navigating rather than drawing a pin.
        val mode = intent.mode
        HandoffPlan.Launch(
            action = Intent.ACTION_VIEW,
            uri = if (mode == null) {
                "geo:0,0?q=" + encode(intent.destination)
            } else {
                "google.navigation:q=" + encode(intent.destination) +
                    "&mode=" + modeLetter(mode)
            },
        )
    }

    is MaiaIntent.OpenWeb -> HandoffPlan.Launch(
        action = Intent.ACTION_VIEW,
        uri = webUri(intent.target),
    )

    is MaiaIntent.OpenApp -> appPackages(intent.name)?.let(HandoffPlan::App)

    is MaiaIntent.Media -> HandoffPlan.Media(intent.command)

    // Everything else was never a handoff: reads, the calendar and the note
    // have their own cards, the agent intents have their own surface, and a
    // Conversation goes to the devbox. Reaching here is a machine mistake
    // and it ends as NoTarget rather than as a fired intent.
    else -> null
}

/** The `mode` parameter of `google.navigation:`, one letter per phrasing. */
private fun modeLetter(mode: String): String = when (mode.lowercase()) {
    "walk" -> "w"
    "bike" -> "b"
    "transit" -> "r"
    else -> "d"
}

/**
 * Where an `OpenWeb` target goes. A bare domain gets an HTTPS scheme, an
 * explicit scheme is passed verbatim, and anything else is a search the
 * browser resolves: `ACTION_VIEW` on a DuckDuckGo URL rather than
 * `ACTION_WEB_SEARCH`, because the URL lands in whichever browser is default
 * while the search action needs one that registered for it. Either way the
 * user sees the query; neither way does Maia claim the page loaded.
 */
internal fun webUri(target: String): String {
    val trimmed = target.trim()
    return when {
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
        domainLike(trimmed) -> "https://$trimmed"
        else -> "https://duckduckgo.com/?q=" + encode(trimmed)
    }
}

/**
 * "github.com" is a domain; "espresso machines" is not.
 *
 * Stricter than `Handoffs.kt`'s version of the same question: that one only
 * chooses a label, so "a.b" is enough. This one picks the URI the browser
 * will be sent, so the last label must be letters or "example.123" would
 * get a scheme it cannot use.
 */
internal fun domainLike(target: String): Boolean {
    if (target.isEmpty() || target.length > 253 || target.any { it.isWhitespace() }) return false
    val labels = target.split('.')
    if (labels.size < 2) return false
    val tld = labels.last()
    return labels.all { label -> label.isNotEmpty() && label.all { it.isLetterOrDigit() || it == '-' } } &&
        tld.length >= 2 && tld.all(Char::isLetter)
}

/** A spoken target that can go straight into a `tel:` or `smsto:` URI. */
internal fun dialable(target: String): Boolean =
    target.isNotBlank() && DIALABLE.matches(target)

/** The digits a `tel:`/`smsto:` URI carries: punctuation for pauses kept, words dropped. */
internal fun digits(target: String): String =
    target.filter { it.isDigit() || it in "+*#;," }

private val DIALABLE = Regex("^[+0-9*#][0-9*#+;,.()\\-\\s]*$")

/**
 * The curated name to package table for `OpenApp`.
 *
 * Values are candidate lists tried in order, because the same spoken name
 * covers the GrapheneOS build of an app and the Google one. No `<queries>`
 * element exists and none is added (M9 PRD section 12): a name outside the
 * table is an honest NoTarget, not a launch-and-hope on a guessed package.
 */
private val APP_PACKAGES: Map<String, List<String>> = mapOf(
    "spotify" to listOf("com.spotify.music"),
    "signal" to listOf("org.thoughtcrime.securesms"),
    "whatsapp" to listOf("com.whatsapp"),
    "maps" to listOf("com.google.android.apps.maps"),
    "google maps" to listOf("com.google.android.apps.maps"),
    "gmail" to listOf("com.google.android.gm"),
    "camera" to listOf("app.grapheneos.camera", "com.android.camera2", "com.google.android.GoogleCamera"),
    "clock" to listOf("com.google.android.deskclock", "com.android.deskclock"),
    "calendar" to listOf("app.grapheneos.calendar", "com.google.android.calendar", "com.android.calendar"),
    "settings" to listOf("com.android.settings"),
    "files" to listOf("com.android.documentsui"),
    "firefox" to listOf("org.mozilla.firefox"),
    "chrome" to listOf("com.android.chrome", "com.chrome.beta"),
)

internal fun appPackages(name: String): List<String>? =
    APP_PACKAGES[name.trim().lowercase()]

/** The `Calendar` day constant for tomorrow, for `AlarmClock.EXTRA_DAYS`. */
private fun tomorrow(): Int {
    val day = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
    return if (day == Calendar.SATURDAY) Calendar.SUNDAY else day + 1
}

/** `Uri.encode` without `Uri`: URLEncoder is the same percent-escaping on the JVM. */
private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8")

/**
 * The `KeyEvent` code a media command dispatches, or null for the volume
 * commands, which are stream adjustments rather than key events.
 */
internal fun keyCodeFor(command: MaiaIntent.Media.Command): Int? = when (command) {
    MaiaIntent.Media.Command.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
    MaiaIntent.Media.Command.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
    MaiaIntent.Media.Command.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
    MaiaIntent.Media.Command.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
    else -> null
}

/**
 * The `AudioManager.adjustStreamVolume` direction for a volume command, or
 * null for the transport commands.
 */
internal fun volumeFor(command: MaiaIntent.Media.Command): Int? = when (command) {
    MaiaIntent.Media.Command.VOLUME_UP -> AudioManager.ADJUST_RAISE
    MaiaIntent.Media.Command.VOLUME_DOWN -> AudioManager.ADJUST_LOWER
    MaiaIntent.Media.Command.MUTE -> AudioManager.ADJUST_MUTE
    else -> null
}
