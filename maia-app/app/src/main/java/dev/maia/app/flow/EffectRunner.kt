package dev.maia.app.flow

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A level that never moves, for a runner with no microphone and no voice. */
private val SILENT: StateFlow<Float> = MutableStateFlow(0f)

/**
 * Whatever actually does the things the reducer asks for.
 *
 * A separate type from [FlowController] on purpose. The controller is about
 * ownership: one machine, one queue, one claim on the microphone, alive for as
 * long as the process is. This is about Android: the recogniser, the parser, the
 * calendar provider, the motor and the voice. Splitting them is what lets the
 * controller be driven on the JVM by a runner that records effects into a list,
 * so every rule about what runs when is a test here rather than a phone.
 *
 * It never sees [Effect.RequestUnlock], [Effect.HideSession] or
 * [Effect.PostDraftWaiting]. Those belong to the window on screen and the
 * controller hands them to its [FlowHost], because which window is live is an
 * ownership question and there should be one place that answers it.
 */
interface EffectRunner {

    /**
     * Do one thing, and return.
     *
     * [origin] is the door the live session came through, or null when nothing
     * is in flight. It is passed because the invoke haptic reads differently
     * depending on it: for [Origin.Assistant] the system has already buzzed for
     * the long-press and a second pulse on top of it is noise, which is decision
     * D3.
     *
     * [send] delivers an event back into the controller. A callback rather than
     * a returned value because most effects answer later, some answer many
     * times, and one ([Effect.StartCapture]) answers for as long as somebody is
     * talking. It is safe to call from any thread.
     *
     * Suspending, and expected to return quickly. An effect that waits on
     * something launches it and returns, so the effects of one step run in the
     * order the reducer emitted them instead of each one blocking the next.
     */
    suspend fun run(effect: Effect, origin: Origin?, send: (FlowEvent) -> Unit)

    /** How loud the microphone is while capture is running, 0 to 1. */
    val micLevel: StateFlow<Float> get() = SILENT

    /** The playback envelope of a spoken confirmation, 0 to 1. */
    val speech: StateFlow<Float> get() = SILENT
}
