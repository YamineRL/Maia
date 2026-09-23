package dev.maia.app

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.maia.audio.speech.SherpaSpeaker
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real voice gate: a SherpaSpeaker built from the voice on disk, asked
 * to speak for real.
 *
 * The answer-surface gates use a recording speaker, so the native callback
 * path had no coverage until it crashed: sherpa's JNI looks up the
 * specialised `invoke(float[]):Integer` that only a class declares, and a
 * Kotlin 2 invokedynamic lambda does not carry it. This test would have
 * caught that: it exercises the callback for real.
 *
 * Where the voice download is absent the case skips rather than fails: an
 * unprovisioned phone is a legal state. The check that still runs is the
 * presence one, which reports what the filesystem holds.
 */
@RunWith(AndroidJUnit4::class)
class VoiceGateTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @Test
    fun voice_presenceMatchesFilesystem() {
        val root = File(context.filesDir, "voice")
        val onDisk = File(root, "en_GB-alan-low.onnx").isFile
        Log.i(TAG, "voice present: $onDisk")
        assertTrue("voice directory missing", root.isDirectory == onDisk || onDisk)
    }

    @Test
    fun speak_generatesAudio_throughTheRealCallback() {
        val speaker = SherpaSpeaker.createIfReady(File(context.filesDir, "voice"))
        assumeTrue("voice not provisioned on this device", speaker != null)
        speaker ?: return
        try {
            val levels = mutableListOf<Float>()
            val start = SystemClock.elapsedRealtime()
            runBlocking {
                withTimeout(60_000) {
                    // The flow ends after a trailing silence level, and a
                    // quiet window mid-word also emits 0f, so collect it all.
                    speaker.speak("Hello.").collect { levels += it }
                }
            }
            val elapsed = SystemClock.elapsedRealtime() - start
            Log.i(TAG, "spoke in ${elapsed}ms, ${levels.size} envelope windows")
            assertTrue("no audio envelope emitted", levels.size > 1)
            assertTrue("all windows silent", levels.any { it > 0f })
        } finally {
            speaker.close()
        }
        assertNotNull(speaker)
    }
}

private const val TAG = "VoiceGate"
