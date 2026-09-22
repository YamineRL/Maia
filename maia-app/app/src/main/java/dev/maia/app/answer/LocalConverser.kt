package dev.maia.app.answer

import dev.maia.transport.Turn
import java.io.Closeable

/**
 * The on-device conversational model: Gemma on the Tensor NPU, reached only
 * when the devbox cannot answer (M10).
 *
 * The seam exists so the answer driver can be unit tested against a fake and
 * the LiteRT-LM half never reaches a JVM test: the contract is three verbs,
 * none of which name an engine, a backend or a model file.
 *
 * **Answer only, never an action.** The devbox gateway may propose a
 * validated intent; this side may not. A 2B model's JSON is not reliable
 * enough to hand to `RemotePlan`, and the user who asked a question off-grid
 * wants words, not a half-formed handoff. Deterministic intents were decided
 * upstream in the grammar before this was ever reached.
 *
 * Implementations are expected to be lazy: holding the interface must not
 * mean holding the model. Load on the first [reply], keep whatever is cheap
 * to keep, and let [close] give it all back.
 */
interface LocalConverser : Closeable {

    /**
     * A complete model file is on the phone. Says nothing about whether it
     * will run: the runtime may be absent, the NPU dispatch missing, or the
     * build incompatible, and all of those surface inside [reply] instead.
     * The driver reads this before paying for a fallback attempt.
     */
    val installed: Boolean

    /**
     * One question, one whole answer, or null when the model cannot produce
     * one: not installed, failed to load, cancelled by the platform, or its
     * output was empty. A null is never a guess; the caller keeps the
     * original fault.
     *
     * Suspending because generation takes seconds and must sit inside the
     * caller's cancellation scope. [history] is the same shown-only
     * conversation the devbox would have been sent; nothing else exists to
     * give it.
     */
    suspend fun reply(prompt: String, history: List<Turn>): String?

    /**
     * Interrupts whatever [reply] is doing, from any thread. A no-op when
     * idle. This is how "stop" reaches a native generation call that a
     * coroutine cancel alone cannot unblock.
     */
    fun cancel()
}
