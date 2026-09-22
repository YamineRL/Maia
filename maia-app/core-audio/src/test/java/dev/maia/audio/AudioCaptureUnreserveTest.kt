package dev.maia.audio

import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * What this file can and cannot prove, stated plainly, because the gap matters
 * more than the assertions.
 *
 * `AudioRecord` is an Android framework class. Unit tests here compile and run
 * against the mockable `android.jar`, whose method bodies all throw rather than
 * do anything, and the module deliberately has no Robolectric and no mocking
 * framework. So on this box an `AudioRecord` cannot be constructed, which means
 * **the real release path of [AudioCapture.unreserve] is not exercised by any
 * test here**: no test in this file ever observes `AudioRecord.release` being
 * called, or being called exactly once. `AudioCapture.reserve` fails silently
 * on the JVM (`getMinBufferSize` throws, `runCatching` swallows it) and leaves
 * the reservation empty, so every path below is the empty-reservation path.
 *
 * Mocking the framework into pretending would produce green tests that assert
 * the mock behaved like the mock. What is tested instead is everything that is
 * genuinely ours: that the reservation field is the whole state machine, that
 * the empty state is reached and left cleanly, that `unreserve` is idempotent
 * and safe on a capture that never reserved, that a failed reservation does not
 * leave a half-state behind, and that concurrent callers do not deadlock or
 * throw. The reservation field is read by reflection on purpose: it is the
 * state under test, and asserting on it is more honest than asserting that a
 * void method did not throw.
 *
 * What settles the rest is the phone. On the Pixel, `reserve` really does
 * construct a recorder, and the M3 criteria for this are: a session prepared
 * and dismissed without speech releases the recorder (no `AudioRecord` object
 * survives the dismissal, and the next `reserve` gets a fresh one); dismissing
 * twice does not log an `AudioRecord` use-after-release or a double free; and a
 * session that is speaking is unaffected by an `unreserve` racing it, which is
 * the one property this file argues structurally rather than empirically.
 */
class AudioCaptureUnreserveTest {

    private fun AudioCapture.reservation(): Any? =
        AudioCapture::class.java.getDeclaredField("reserved")
            .apply { isAccessible = true }
            .get(this)

    @Test
    fun `a capture that never reserved holds nothing`() {
        assertNull(AudioCapture().reservation())
    }

    @Test
    fun `unreserve on a capture that never reserved is a no-op`() {
        val capture = AudioCapture()
        capture.unreserve()
        assertNull(capture.reservation())
    }

    @Test
    fun `unreserve is idempotent`() {
        val capture = AudioCapture()
        capture.unreserve()
        capture.unreserve()
        capture.unreserve()
        assertNull(capture.reservation())
    }

    /**
     * The dismissal order the assistant session actually uses: prepare shows
     * reserve, hide-before-recording shows unreserve. On this box the reserve
     * half cannot succeed, so what this pins is the weaker half of the pair:
     * the sequence completes and the field is empty afterwards either way.
     */
    @Test
    fun `reserve then unreserve leaves no reservation`() {
        val capture = AudioCapture()
        capture.reserve()
        capture.unreserve()
        assertNull(capture.reservation())
    }

    /**
     * A reservation that could not be opened must not be recorded as one.
     * `reserve` swallows the failure by design, and the invariant that keeps
     * that from being a leak is that the field stays null, so the next
     * `frames()` opens its own recorder and `unreserve` has nothing to release.
     * On the JVM every `reserve` takes this path, which makes it the one real
     * branch this file can walk.
     */
    @Test
    fun `a reservation that fails to open is not held`() {
        val capture = AudioCapture()
        repeat(5) { capture.reserve() }
        assertNull(capture.reservation())
    }

    /**
     * The tile, the assistant session and a collector can all arrive on
     * different threads. `reserve` and `unreserve` take the same monitor, so
     * this asks for the two things a lock is for: no deadlock, and no caller
     * left holding an exception. It cannot show the mutual exclusion itself,
     * because with no reservable recorder there is no contended state to
     * observe being torn.
     */
    @Test
    fun `concurrent reserve and unreserve neither deadlock nor throw`() {
        val capture = AudioCapture()
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val failures = mutableListOf<Throwable>()

        val done = CountDownLatch(threads)
        repeat(threads) { index ->
            pool.execute {
                start.await()
                try {
                    repeat(200) {
                        if (index % 2 == 0) capture.reserve() else capture.unreserve()
                    }
                } catch (t: Throwable) {
                    synchronized(failures) { failures += t }
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        val finished = done.await(30, TimeUnit.SECONDS)
        pool.shutdownNow()

        if (!finished) throw AssertionError("reserve and unreserve did not complete, a lock is held")
        synchronized(failures) {
            if (failures.isNotEmpty()) throw AssertionError("threw under contention", failures.first())
        }
        assertNull(capture.reservation())
    }
}
