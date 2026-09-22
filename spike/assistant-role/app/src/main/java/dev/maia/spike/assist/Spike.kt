package dev.maia.spike.assist

import android.app.KeyguardManager
import android.content.Context
import android.os.SystemClock
import android.os.UserManager
import android.service.voice.VoiceInteractionSession
import android.util.Log

/**
 * One log tag for the whole spike, so `adb logcat -s MaiaSpike:V` is the entire
 * record of a run. M3 brief section 0.1: the log goes to logcat only, and
 * nothing is written to disk.
 */
const val TAG = "MaiaSpike"

fun log(message: String) {
    Log.i(TAG, "[${SystemClock.elapsedRealtimeNanos() / 1_000_000L} ms] $message")
}

/**
 * The show flags, spelled out. The source bits say which gesture the system
 * thinks fired, which is the difference between G2 (long-press power) and G3
 * (corner swipe) when only the log is available.
 */
fun describeShowFlags(flags: Int): String {
    val names = buildList {
        if (flags and VoiceInteractionSession.SHOW_WITH_ASSIST != 0) add("SHOW_WITH_ASSIST")
        if (flags and VoiceInteractionSession.SHOW_WITH_SCREENSHOT != 0) add("SHOW_WITH_SCREENSHOT")
        if (flags and VoiceInteractionSession.SHOW_SOURCE_APPLICATION != 0) add("SOURCE_APPLICATION")
        if (flags and VoiceInteractionSession.SHOW_SOURCE_ACTIVITY != 0) add("SOURCE_ACTIVITY")
        if (flags and VoiceInteractionSession.SHOW_SOURCE_PUSH_TO_TALK != 0) add("SOURCE_PUSH_TO_TALK")
        if (flags and VoiceInteractionSession.SHOW_SOURCE_ASSIST_GESTURE != 0) add("SOURCE_ASSIST_GESTURE")
        if (flags and VoiceInteractionSession.SHOW_SOURCE_NOTIFICATION != 0) add("SOURCE_NOTIFICATION")
        if (flags and VoiceInteractionSession.SHOW_SOURCE_AUTOMOTIVE_SYSTEM_UI != 0) add("SOURCE_AUTOMOTIVE")
    }
    val known = names.joinToString("|").ifEmpty { "none" }
    return "0x${Integer.toHexString(flags)} ($known)"
}

/**
 * What the session window prints, and what every log line about lock state
 * says. Four facts, all read and none written:
 *
 * - `isKeyguardLocked`: the keyguard is up. It can be up with no credential set.
 * - `isDeviceLocked`: the device is locked behind a credential. This is the one
 *   that matters for storage.
 * - `isUserUnlocked`: the system's own answer for whether credential encrypted
 *   storage is available (M3 brief section 4.4).
 * - the directory probe: whether credential encrypted storage actually answers
 *   a listing. Read only. Nothing is created, so V7 holds.
 */
data class LockProbe(
    val keyguardLocked: Boolean,
    val deviceLocked: Boolean,
    val userUnlocked: Boolean,
    val ceReadable: Boolean,
    val ceDetail: String,
) {
    fun lines(): List<String> = listOf(
        "isKeyguardLocked = $keyguardLocked",
        "isDeviceLocked   = $deviceLocked",
        "isUserUnlocked   = $userUnlocked",
        "CE storage read  = $ceReadable ($ceDetail)",
    )

    override fun toString(): String = lines().joinToString("; ")
}

fun probeLock(context: Context): LockProbe {
    val keyguard = context.getSystemService(KeyguardManager::class.java)
    val userManager = context.getSystemService(UserManager::class.java)

    // Credential encrypted storage, read only. filesDir.list() returns null
    // when the directory cannot be read, which is the state before first
    // unlock after a reboot that G9 is about. Listing creates nothing; the
    // spike still writes no file.
    var detail: String
    val readable = try {
        val entries = context.filesDir?.list()
        detail = if (entries == null) "list() returned null" else "${entries.size} entries"
        entries != null
    } catch (t: Throwable) {
        detail = "${t.javaClass.simpleName}: ${t.message}"
        false
    }

    return LockProbe(
        keyguardLocked = keyguard?.isKeyguardLocked ?: false,
        deviceLocked = keyguard?.isDeviceLocked ?: false,
        userUnlocked = userManager?.isUserUnlocked ?: false,
        ceReadable = readable,
        ceDetail = detail,
    )
}
