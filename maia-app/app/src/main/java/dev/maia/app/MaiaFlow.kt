package dev.maia.app

import android.content.Context
import android.os.SystemClock
import dev.maia.app.flow.FlowController
import dev.maia.app.flow.FlowSession
import dev.maia.app.flow.initialState
import dev.maia.audio.ModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Where the process keeps its one [FlowController].
 *
 * A separate object from the controller, so that the controller stays a plain
 * class a test can build four of. This is the part that cannot be tested and
 * should therefore contain nothing but wiring: which scope, which clock, which
 * runner, and the lock that makes sure there is exactly one.
 *
 * There is no teardown, for the reason in [EngineHolder]: the waiting-draft
 * queue is memory only at M3 (open question U1), so anything that could rebuild
 * the controller would be a way to lose drafts without the user asking.
 */
object MaiaFlow {

    /**
     * Main, immediate, and not a background dispatcher.
     *
     * Effects are launched here and every one of them hands off immediately to
     * something else: the provider has its own IO dispatcher, the recogniser
     * runs on Default inside [dev.maia.audio.Dictation], the motor is a binder
     * call. What is left on this thread is the dispatch itself, and doing that
     * on main is what lets a host's `collectAsState` see a state change in the
     * same frame as the press that caused it.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var instance: FlowController? = null

    private val lock = Any()

    fun controller(context: Context): FlowController {
        instance?.let { return it }
        val app = context.applicationContext
        return synchronized(lock) {
            instance ?: FlowController(
                effects = AndroidEffects.forApp(app, scope),
                scope = scope,
                // The same clock the reducer's ScheduleTick offsets are on.
                // elapsedRealtime rather than wall time, because the undo window
                // must not be shortened by a clock correction.
                now = SystemClock::elapsedRealtime,
                initial = FlowSession(state = initialState(modelsPresent(app))),
            ).also { instance = it }
        }
    }

    /**
     * Whether the 70 MB model set is on the phone, which decides between the
     * first-run screen and a flow that can hear something.
     *
     * A handful of `File.exists` calls on whichever thread first asks for the
     * controller. Read here rather than awaited from [EngineHolder], because a
     * flow that starts in Idle and jumps to FirstRun a moment later would draw
     * an invoke control that cannot work.
     */
    private fun modelsPresent(app: Context): Boolean =
        runCatching { ModelStore(File(app.filesDir, "models")).isComplete }.getOrDefault(false)
}
