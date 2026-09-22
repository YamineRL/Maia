package dev.maia.app.flow

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The one flow machine in the process. M3 brief section 2.1, item I6.
 *
 * This is here for the reason [dev.maia.app.EngineHolder] is here. M1 moved the
 * recogniser out of a view model when the tile arrived and there were suddenly
 * two things that could want it. M3 does the same to the flow itself: an
 * assistant session window, a Quick Settings tile and an Activity can all be
 * alive at the same moment, and a runner per view model would be two machines,
 * two queues of waiting drafts and two claims on one microphone. So there is one
 * of these, hosts observe [state] and send it events, and a view model is a thin
 * adapter over it exactly as `DictationViewModel` became at M1.
 *
 * **What it does not do.** It does not decide anything. Every decision is
 * [reduce], which is pure and stays that way; this class owns the value the
 * reducer folds over, runs what the reducer asks for, and nothing else. If a
 * rule ever needs to be written here, it is in the wrong place.
 *
 * **On there being no `close`.** Like the engine, this is held until the process
 * dies. The queue of drafts waiting for an unlock is memory only at M3 (open
 * question U1), so a controller that could be torn down and rebuilt would be a
 * way to lose them without the user doing anything. Process death loses them and
 * that is stated; nothing else may.
 *
 * @param effects what actually talks to Android. Injected, so the whole machine
 *   including its effect ordering is drivable on the JVM.
 * @param scope where effects run. Long lived, and never a host's scope: an
 *   effect that outlives the window that triggered it (a write, a spoken
 *   confirmation) is normal.
 * @param now the reducer's clock, the same one the reducer's `ScheduleTick`
 *   offsets are on.
 */
class FlowController(
    private val effects: EffectRunner,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    initial: FlowSession = FlowSession(),
) {

    private val _session = MutableStateFlow(initial)

    /**
     * Everything, including the lock flag and the waiting queue.
     *
     * Hosts want [state]; this is for the few things that need the queue, such
     * as the notification deciding what its private version says.
     */
    val session: StateFlow<FlowSession> = _session.asStateFlow()

    private val _state = MutableStateFlow(initial.state)

    /** What one screen draws. This is what a host renders and nothing else. */
    val state: StateFlow<FlowState> = _state.asStateFlow()

    private val _writtenTo = MutableStateFlow<String?>(null)

    /**
     * The calendar the last commit went to, for the confirmation line.
     *
     * `FlowState.Confirmed` does not carry a target, deliberately: the write is
     * already done and a screen that could still name a different calendar than
     * the one written to would be able to lie. So the name is remembered here as
     * the flow passes through `Committing`, which is the last state that knows
     * it, and the host hands it to the screen as `writtenTo`.
     */
    val writtenTo: StateFlow<String?> = _writtenTo.asStateFlow()

    /** How loud the microphone is, 0 to 1. Read per frame by the orb's rim. */
    val micLevel: StateFlow<Float> get() = effects.micLevel

    /** The playback envelope of the spoken confirmation, 0 to 1. */
    val speech: StateFlow<Float> get() = effects.speech

    // ------------------------------------------------------------ the host

    @Volatile
    private var host: FlowHost? = null

    /**
     * The window on screen takes the three effects a process cannot run.
     *
     * Last in wins, because that is what "on screen" means when a tile launches
     * an Activity over a session window. There is no stack: a host that is gone
     * calls [detach] and the one before it does not come back, because by then
     * it is not on screen either.
     */
    fun attach(host: FlowHost) {
        this.host = host
    }

    /** Only clears the host if it is still this one, so a late detach cannot steal the live window. */
    fun detach(host: FlowHost) {
        if (this.host === host) this.host = null
    }

    // ----------------------------------------------------------- the events

    private val lock = Any()
    private val pending = ArrayDeque<FlowEvent>()
    private var draining = false

    /**
     * Fold one event in, then run what it asked for.
     *
     * Events arrive from a finger on the main thread, from the recogniser on a
     * background thread, and from an effect answering itself, so they are
     * serialised here. The queue exists for the third case: an effect that
     * answers synchronously would otherwise re-enter the reducer half way
     * through the step that started it, and the two steps' effects would
     * interleave. Instead the inner event waits its turn and the loop below
     * picks it up, so the order a host sees is always the order the reducer
     * produced.
     */
    fun send(event: FlowEvent) = synchronized(lock) {
        pending.addLast(event)
        if (draining) return@synchronized
        draining = true
        try {
            while (true) {
                val next = pending.removeFirstOrNull() ?: break
                val step = reduce(_session.value, next, now())
                _session.value = step.session
                _state.value = step.session.state
                (step.session.state as? FlowState.Committing)?.let {
                    _writtenTo.value = it.target.calendar.displayName
                }
                if (step.effects.isNotEmpty()) perform(step.effects, step.session.origin)
            }
        } finally {
            draining = false
        }
    }

    /**
     * One coroutine per step, not one per effect, so that the effects of a step
     * run in the order the reducer emitted them. That order is load bearing in
     * at least one place: a stop and a parse arrive together when a sentence
     * ends, and parsing audio the recogniser has not finished with is a race
     * nobody would find twice.
     */
    private fun perform(effects: List<Effect>, origin: Origin?) {
        scope.launch {
            for (effect in effects) {
                val taken = runOnHost(effect)
                if (!taken) this@FlowController.effects.run(effect, origin) { send(it) }
            }
        }
    }

    /**
     * The three effects that belong to whatever window is on screen.
     *
     * They are answered here rather than inside the runner because the answer is
     * about ownership, which is this class's subject, and because a runner given
     * a host reference would be a second place that decides which window is
     * live. Returns true when the effect was one of them, host or no host: an
     * unlock nobody can ask for is dropped exactly like an unlock the user
     * cancelled, and the locked screen stays as it was.
     */
    private fun runOnHost(effect: Effect): Boolean {
        val host = this.host
        when (effect) {
            Effect.RequestUnlock -> host?.requestUnlock()
            Effect.HideSession -> host?.hideSession()
            Effect.ShowSurface -> host?.showSurface()
            is Effect.PostDraftWaiting -> host?.postDraftWaiting(effect.count)
            else -> return false
        }
        return true
    }
}
