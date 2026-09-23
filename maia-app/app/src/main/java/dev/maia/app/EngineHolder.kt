package dev.maia.app

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import dev.maia.app.answer.LiteRtConverser
import dev.maia.app.ui.DownloadCopy
import dev.maia.app.ui.ModelDownload
import dev.maia.audio.AudioCapture
import dev.maia.audio.ModelStore
import dev.maia.audio.OfflineModelStore
import dev.maia.audio.ParakeetRescorer
import dev.maia.audio.Rescorer
import dev.maia.audio.StreamingRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** How ready the engine is, for whatever screen happens to be watching. */
sealed interface Warmth {
    data object Cold : Warmth
    data class Downloading(
        val file: String,
        val index: Int,
        val count: Int,
        val fraction: Float,
        /** This file's bytes on disk and its full length, -1 until known. For 4h's mono counts. */
        val bytes: Long = 0,
        val totalBytes: Long = -1,
        /** Recent throughput; zero means no ETA yet, not a stalled download. */
        val bytesPerSecond: Double = 0.0,
    ) : Warmth

    data object Loading : Warmth
    data object Ready : Warmth
    data class Failed(val message: String) : Warmth
}

/**
 * The recogniser, owned by the process rather than by a screen.
 *
 * M0 had one entry point and the ViewModel could own the engine. M1 has two:
 * the Quick Settings tile needs the recogniser warm before any Activity
 * exists, and `onStartListening` fires when the shade opens, which is the only
 * window in which the load can be paid for without the user watching it.
 * Ownership therefore moves here and the ViewModel borrows.
 *
 * **On never closing it.** There is no `close()` on this object and that is
 * deliberate, not an oversight. Releasing an `OnlineRecognizer` while a stream
 * created from it is still unwinding is a use-after-free across JNI: it takes
 * the process down rather than throwing. `DictationViewModel.onCleared` used
 * to join the listening job before closing for exactly that reason. A
 * process-scoped engine has no safe moment to be released, because the only
 * moment it would be released is process teardown, at which point the memory
 * goes back anyway. So it is held until the process dies and the kernel
 * reclaims it. The cost is roughly 70 to 100 MB resident for as long as the
 * system lets the process live, which section 7 of the M1 brief says to
 * measure on the phone before defending.
 */
object EngineHolder {

    private const val TAG = "MaiaAudio"
    private const val GEMMA = "gemma"
    private const val RESCORER = "rescorer"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()

    private val _warmth = MutableStateFlow<Warmth>(Warmth.Cold)
    val warmth: StateFlow<Warmth> = _warmth.asStateFlow()

    /**
     * Shared, because the reservation in [AudioCapture.reserve] is only worth
     * anything if the collection that follows it is the same instance.
     */
    val capture = AudioCapture()

    /**
     * In flight or finished. Held so that two callers racing, the tile and the
     * Activity it launches, share one load instead of reading 70 MB twice.
     */
    private var loading: Deferred<StreamingRecognizer>? = null

    /**
     * The second-pass engine, same single-flight rules as [loading].
     *
     * Unlike the streaming engine this one is deliberately **not** warmed
     * eagerly. It is ~670 MB on disk against the zipformer's 70 MB, and
     * pulling that onto the phone is the download maia-privacy and the user
     * weigh, not something a warmth policy pays for in the background. It
     * loads when the flow first needs it, and once loaded it stays resident
     * for the life of the process for the same JNI reason as the streaming
     * engine.
     */
    private var rescoreLoading: Deferred<Rescorer>? = null

    @Volatile
    private var rescoreReady: Rescorer? = null

    /**
     * The rescorer if it is already loaded, and null otherwise.
     *
     * This is the lookup [dev.maia.audio.Dictation] makes at each utterance
     * boundary; null means "keep the streaming draft", which is exactly the
     * behaviour before this pass existed. It never triggers a load, so the
     * first sentence after a cold start is always the streaming draft and
     * that is stated rather than accidental: by the time the user has spoken
     * a second sentence the download and load have finished.
     */
    fun rescorerOrNull(): Rescorer? = rescoreReady

    /**
     * Start the rescore model's download and load, if it has not started.
     *
     * Fire and forget like [warm]: failures are swallowed here (the flow
     * works without the second pass) and progress is not surfaced, because
     * the first-run screen belongs to the zipformer and a second progress
     * story mid-capture would be a new screen nobody asked for. Called by
     * the capture path once the streaming engine is up, so the 670 MB starts
     * arriving the first time the app hears anything, not at process start.
     */
    fun warmRescorer(context: Context) {
        val app = context.applicationContext
        scope.launch {
            lock.withLock {
                if (rescoreLoading?.isCancelled == false) return@withLock
                rescoreLoading = scope.async { loadRescorer(app) }
            }
        }
    }

    private val _downloads = MutableStateFlow<Map<String, ModelDownload>>(emptyMap())

    /** Background model downloads in flight, keyed by model, for the idle screen's strip. */
    val downloads: StateFlow<Map<String, ModelDownload>> = _downloads.asStateFlow()

    private fun progressReporter(key: String): (ModelDownload) -> Unit {
        var last = 0L
        return { download ->
            val now = System.nanoTime()
            if (now - last >= 250_000_000L || download.bytesDone >= download.bytesTotal) {
                last = now
                _downloads.update { it + (key to download) }
            }
        }
    }

    private fun downloadEnded(key: String) = _downloads.update { it - key }

    @Volatile
    private var localModelFetch: Job? = null

    /**
     * Fetch the missing Gemma fallback on an unmetered network, or on any network when the
     * user asked from settings ([anyNetwork]); resumes at the next launch.
     */
    fun fetchLocalModel(context: Context, anyNetwork: Boolean = false) {
        val app = context.applicationContext
        if (localModelFetch?.isActive == true) return
        val dir = File(app.filesDir, LiteRtConverser.MODEL_DIR_NAME)
        if (File(dir, LiteRtConverser.MODEL_FILE).isFile) return
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        if (!anyNetwork && (connectivity == null || connectivity.isActiveNetworkMetered)) return
        localModelFetch = scope.launch {
            val report = progressReporter(GEMMA)
            try {
                OfflineModelStore(dir, LiteRtConverser.MODEL_BASE_URL, files = listOf(LiteRtConverser.MODEL_FILE))
                    .ensure().collect { p ->
                        if (p is OfflineModelStore.Progress.Downloading) {
                            report(ModelDownload(DownloadCopy.OFFLINE_ANSWERS, p.bytes, p.totalBytes, p.bytesPerSecond))
                        }
                    }
                Log.i(TAG, "local model on disk")
            } catch (e: Exception) {
                Log.w(TAG, "local model download stopped, resumes at next launch", e)
            } finally {
                downloadEnded(GEMMA)
            }
        }
    }

    private suspend fun loadRescorer(app: Context): Rescorer {
        // async's failure mode is silence: nobody ever awaits rescoreLoading,
        // so an uncaught throw here would sit in the Deferred forever with
        // no crash and no log, and rescorerOrNull() would return null with
        // no sign why. Logged explicitly so a JNI-level failure of the
        // offline recogniser (paths.7, "if sherpa JNI misbehaves on-device")
        // is visible on the phone sitting rather than looking identical to
        // "still downloading".
        try {
            val store = OfflineModelStore(File(app.filesDir, "models-parakeet"))
            val report = progressReporter(RESCORER)
            var finished = 0L
            var index = 0
            var fileTotal = 0L
            try {
                store.ensure().collect { p ->
                    if (p is OfflineModelStore.Progress.Downloading) {
                        if (p.index != index) {
                            finished += fileTotal.coerceAtLeast(0)
                            index = p.index
                        }
                        fileTotal = p.totalBytes
                        report(
                            ModelDownload(
                                DownloadCopy.SPEECH_ACCURACY,
                                finished + p.bytes,
                                OfflineModelStore.APPROXIMATE_BYTES,
                                p.bytesPerSecond,
                            )
                        )
                    }
                }
            } finally {
                downloadEnded(RESCORER)
            }
            Log.i(TAG, "rescore model on disk, loading into a recognizer")
            return ParakeetRescorer(store.paths()).also {
                rescoreReady = it
                Log.i(TAG, "rescore model ready")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "rescore model failed to load, keeping the streaming draft for good", e)
            throw e
        }
    }

    /**
     * Get ready, and return at once if already ready.
     *
     * Fire and forget: this is what the tile calls from `onStartListening`,
     * where there is nothing to await and nothing to report. Failures land in
     * [warmth] and are the screen's problem, not the tile's.
     */
    fun warm(context: Context) {
        val app = context.applicationContext
        scope.launch {
            capture.reserve()
            runCatching { engine(app) }
        }
    }

    /**
     * The engine half of [warm], without the recorder half. M3 brief item I7.
     *
     * Additive, and [warm] keeps its M1 meaning for the tile, because the two
     * callers want different things. The shade opens a second or two before the
     * tap, so the tile can afford both halves and needs both. The assistant role
     * binds this process for as long as the role is held (research R2), which is
     * most of the phone's day and not a moment before speech: reserving an
     * unstarted `AudioRecord` there would hold a recorder object until the
     * process dies. So the role gets this, the tile keeps [warm], and which
     * moment gets which is the table in [dev.maia.app.flow.warmthFor] rather
     * than a decision taken at each call site.
     *
     * Fire and forget, like [warm]. Failures land in [warmth].
     */
    fun warmEngine(context: Context) {
        val app = context.applicationContext
        scope.launch { runCatching { engine(app) } }
    }

    /**
     * [warmEngine] with the first-run opt-in kept.
     *
     * [warm] and [warmEngine] go through [engine], which downloads whatever
     * is missing: right for the warmth table, where the caller is a voice
     * entry point that cannot work at all without the model. Wrong for the
     * launcher path, where a warm that started a 70 MB fetch on its own would
     * be a download nobody asked for and the first-run screen would be lying
     * about what pressing its button starts. So this one checks the store
     * first and does nothing on a fresh install, leaving `FlowState.FirstRun`
     * exactly as it was.
     *
     * Called by `MainActivity` after the window can draw. The warmth table
     * has no row for "the app was opened": the role warms on bind, the tile
     * on the shade opening, and a user who reaches Maia through the launcher
     * icon was paying the whole model load under the first Speak tap, which
     * is the slowest moment there is to pay it. Loading here instead buys
     * the same readiness [dev.maia.app.flow.WarmMoment.RoleReady] buys, at
     * the same price: roughly 70 to 100 MB resident until the process dies.
     */
    fun warmEngineIfPresent(context: Context) {
        val app = context.applicationContext
        scope.launch {
            if (!ModelStore(File(app.filesDir, "models")).isComplete) return@launch
            runCatching { engine(app) }
        }
    }

    /**
     * The engine, loading it if this is the first ask.
     *
     * Suspends until it is ready. Throws whatever the load threw, so the
     * caller can show it.
     */
    suspend fun engine(context: Context): StreamingRecognizer {
        val app = context.applicationContext
        val job = lock.withLock {
            // A load that failed leaves a cancelled Deferred behind. Dropped
            // rather than remembered, so a user who lost their connection half
            // way through the download gets another attempt by pressing the
            // button again instead of being told about the old failure for the
            // life of the process.
            loading?.takeUnless { it.isCancelled }
                ?: scope.async { load(app) }.also { loading = it }
        }
        return try {
            job.await()
        } catch (e: Throwable) {
            lock.withLock { if (loading === job) loading = null }
            throw e
        }
    }

    /**
     * Non-suspending peek, for a caller that only wants the engine if it is
     * already there. A plain field rather than [Deferred.getCompleted], which
     * is experimental and throws when the load failed.
     */
    @Volatile
    var ready: StreamingRecognizer? = null
        private set

    private suspend fun load(app: Context): StreamingRecognizer {
        try {
            val store = ModelStore(File(app.filesDir, "models"))
            if (!store.isComplete) {
                store.ensure().collect { progress ->
                    when (progress) {
                        is ModelStore.Progress.Downloading -> _warmth.value = Warmth.Downloading(
                            file = progress.file,
                            index = progress.index,
                            count = progress.count,
                            fraction = progress.fraction,
                            bytes = progress.bytes,
                            totalBytes = progress.totalBytes,
                            bytesPerSecond = progress.bytesPerSecond,
                        )

                        ModelStore.Progress.Ready -> Unit
                    }
                }
            }
            _warmth.value = Warmth.Loading
            return StreamingRecognizer(store.paths()).also {
                ready = it
                _warmth.value = Warmth.Ready
            }
        } catch (e: Exception) {
            _warmth.value = Warmth.Failed(e.message ?: e.toString())
            throw e
        }
    }
}
