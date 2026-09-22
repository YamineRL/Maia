package dev.maia.app.agent

import android.content.Context
import dev.maia.audio.Dictation
import dev.maia.app.EngineHolder
import dev.maia.audio.Transcript
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.Closeable

/**
 * The microphone an agent's question opens. Section 5.18.
 *
 * The same engine, the same capture and the same rescorer the note flow uses,
 * because there is one microphone on the phone and one recogniser in this
 * process. [EngineHolder.capture] is a single shared `AudioCapture`, so a
 * dictation started here while the note flow holds one would be two readers of
 * one device: the two are never open together by construction, since the run
 * screen and the flow are different windows and this only opens while the run
 * screen is in front.
 *
 * **No hotwords.** The obvious bias is the option labels, and they arrived on
 * the agent's event stream, which rule 12 keeps out of effects. What comes
 * back is matched against the labels afterwards, exactly and on the whole
 * utterance, by [reduceRun].
 *
 * **Nothing here decides anything.** Started, a partial, a final, or closed
 * with nothing: four events into the reducer, which owns every consequence.
 */
class AnswerMic(private val context: Context) : Closeable {

    /**
     * Set once, immediately after the driver is built.
     *
     * The knot is real: the driver needs a microphone sink and the microphone
     * needs somewhere to put words. One of the two has to be filled in after
     * the other exists, and a field that is written once during wiring is
     * plainer than a lazy holder.
     */
    var driver: AgentDriver? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /** Open or close, driven by `RunEffect.Listen` and `RunEffect.Deafen`. */
    fun set(open: Boolean) {
        if (open) open() else close(release = true)
    }

    private fun open() {
        val previous = job
        job = scope.launch {
            // A cancel is asynchronous and the recorder is released in the old
            // job's finally. Starting before that lands finds the device still
            // held and blames another app, which would be this one.
            previous?.join()
            try {
                val engine = EngineHolder.engine(context)
                EngineHolder.warmRescorer(context)
                val dictation = Dictation(
                    recognizer = engine,
                    capture = EngineHolder.capture,
                    rescorer = EngineHolder::rescorerOrNull,
                )
                driver?.listening()
                var anything = false
                dictation.run(emptyList()).collect { event ->
                    when (val transcript = event.transcript) {
                        is Transcript.Partial -> {
                            if (transcript.text.isNotBlank()) anything = true
                            driver?.heard(transcript.text)
                        }

                        is Transcript.Final -> {
                            anything = true
                            driver?.said(transcript.text)
                        }
                    }
                }
                // The flow ended without a final. That is the endpointer
                // closing on silence, which is `m8_run_answer_nothing_heard`
                // and not a failure: there is nothing to report to the user
                // about the recogniser, only about the silence.
                if (!anything) driver?.heardNothing()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A recogniser that could not start and a room that said
                // nothing look the same from the screen, and the honest
                // caption for both is that nothing was heard. The options are
                // all still there, which is the half that matters.
                driver?.heardNothing()
            }
        }
    }

    private fun close(release: Boolean) {
        job?.cancel()
        job = null
        // The role keeps this process warm for as long as a reservation is
        // held, so a microphone closed without this outlives the question by
        // the life of the phone.
        if (release) EngineHolder.capture.unreserve()
    }

    override fun close() {
        close(release = true)
        scope.cancel()
    }
}
