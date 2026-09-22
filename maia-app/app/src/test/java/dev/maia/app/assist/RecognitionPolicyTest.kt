package dev.maia.app.assist

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Criterion J12: the recognition stub's decision function returns an error for
 * every request and never requests capture or the engine.
 *
 * This is the only part of the assistant role that can be proved on this box,
 * and it is proved over the whole input space rather than over a sample,
 * because the input space is an enum with four values.
 */
class RecognitionPolicyTest {

    @Test
    fun `J12 every request is refused with recognizer busy`() {
        for (request in RecognitionPolicy.Request.entries) {
            assertEquals(
                "request $request should be refused as busy",
                RecognitionPolicy.ERROR_RECOGNIZER_BUSY,
                RecognitionPolicy.decide(request).errorCode,
            )
        }
    }

    @Test
    fun `J12 no request opens the microphone, touches the engine, or shows anything`() {
        for (request in RecognitionPolicy.Request.entries) {
            val decision = RecognitionPolicy.decide(request)
            assertFalse("request $request must not open the microphone", decision.opensMicrophone)
            assertFalse("request $request must not touch the engine", decision.touchesEngine)
            assertFalse("request $request must not show UI", decision.showsUi)
        }
    }

    /**
     * Privacy item V1. The stub is reachable by every app on the phone from
     * first install (docs/research/R3.md), so what it records about its callers
     * is not a detail.
     */
    @Test
    fun `J12 no request logs the calling package`() {
        for (request in RecognitionPolicy.Request.entries) {
            assertFalse(
                "request $request must not log the caller",
                RecognitionPolicy.decide(request).logsCallerPackage,
            )
        }
    }

    /**
     * The error code is written as a literal in [RecognitionPolicy] so that the
     * decision has no Android import at all. This is what stops the literal and
     * the platform drifting apart: the test fixture compiles against the
     * platform's own constant, which the Kotlin compiler inlines, so a platform
     * that renumbered it would fail here rather than ship a wrong code to every
     * app on the phone.
     */
    @Test
    fun `the literal error code is the platform's`() {
        assertEquals(
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            RecognitionPolicy.ERROR_RECOGNIZER_BUSY,
        )
    }
}
