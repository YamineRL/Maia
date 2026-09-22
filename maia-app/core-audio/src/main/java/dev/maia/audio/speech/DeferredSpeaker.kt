package dev.maia.audio.speech

import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * A speaker that becomes a voice when the optional model lands mid-process.
 *
 * M9 PRD section 9: the voice is a first-use download the user starts from
 * the answer screen, after the wiring already exists. Asking
 * [SherpaSpeaker.createIfReady] once at wiring time and holding
 * [SilentSpeaker] forever after means a voice fetched during the session
 * never speaks until the process restarts, which is the one case the
 * download affordance creates. This wrapper asks the store on every sentence
 * instead: [VoiceStore.isComplete] is a handful of file stats, and once a
 * speaker is built it is kept for the life of the process, as
 * [SherpaSpeaker]'s own construction cost requires.
 *
 * What is deliberately not here:
 *
 *  - The ringer policy. [RingerAwareSpeaker] wraps this in production, so a
 *    deferred speaker cannot bypass it.
 *  - A retry on failure. A voice that will not open latches to the fallback:
 *    a model that failed to load once is not about to succeed on the next
 *    sentence, and a retry is a native load paid under the user's finger.
 *  - The completeness check while incomplete is not latched either: every
 *    sentence re-asks, so a download that finishes between two answers takes
 *    effect on the second one.
 */
class DeferredSpeaker(
    /**
     * Whether the voice is on disk. A file-stat check, cheap enough to run
     * per sentence.
     */
    private val ready: () -> Boolean,
    /**
     * Builds the real speaker, or returns null when the engine will not open
     * the files. Called at most once, the first time [ready] is true.
     */
    private val build: () -> Speaker?,
    /** What speaks while there is no voice, and after a build that failed. */
    private val fallback: Speaker = SilentSpeaker,
) : Speaker {

    /** The resolved speaker, latched. Null until the voice is whole. */
    @Volatile
    private var built: Speaker? = null

    override fun speak(text: String): Flow<Float> = delegate().speak(text)

    private fun delegate(): Speaker {
        built?.let { return it }
        // Outside the lock on purpose: the check is a few file stats and the
        // ordinary case for the life of a phone that never downloads.
        if (!ready()) return fallback
        return synchronized(this) {
            built ?: (build() ?: fallback).also { built = it }
        }
    }

    companion object {
        /**
         * The production shape: sherpa over the voice directory, silent until
         * every file is there. `createIfReady` is not reused because it
         * re-checks completeness itself; here [ready] asks the store and
         * [build] is handed the paths it already computed.
         */
        fun sherpa(root: File, threads: Int = 2): DeferredSpeaker {
            val store = VoiceStore(root)
            return DeferredSpeaker(
                ready = { store.isComplete },
                build = {
                    runCatching { SherpaSpeaker(store.paths(), threads = threads) }.getOrNull()
                },
            )
        }
    }
}
