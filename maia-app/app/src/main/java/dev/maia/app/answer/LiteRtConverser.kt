package dev.maia.app.answer

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import dev.maia.transport.AssistantClient
import dev.maia.transport.Role
import dev.maia.transport.Turn
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The on-device half of a remote ask: Gemma 4 E2B through LiteRT-LM, on the
 * Tensor NPU when the dispatch library is present.
 *
 * The model is a Google-published, Tensor-G5-compiled `.litertlm` artifact,
 * ungated on Hugging Face. [dev.maia.app.EngineHolder.fetchLocalModel]
 * downloads it on unmetered networks only (3 GB), and
 * scripts/provision-gemma.sh can still push it over USB. [installed] is the
 * single gate every caller checks first.
 *
 * Everything native is lazy. Constructing this object reads nothing and
 * loads nothing; the first [reply] pays the engine's initialisation, which
 * is seconds of disk and RAM, and only a question the devbox could not
 * answer ever causes one. The engine then stays loaded until [close]:
 * unloading between turns would tax a follow-up with the full load again,
 * and the driver is process-scoped anyway.
 *
 * Each reply gets a fresh [Conversation] seeded with [history] rather than
 * a held one. It costs a prefill and buys correctness: `ConversationSession`
 * expires context after ten quiet minutes, and a native session kept open
 * would remember what the contract has already forgotten.
 */
class LiteRtConverser(
    private val modelDir: File,
    /**
     * Produces the directory holding `libLiteRtDispatch_GoogleTensor.so`,
     * extracting it from assets on first call. The dispatch library is the
     * NPU's front door and it is scanned by directory, so it must exist as
     * a real file rather than an entry inside the APK.
     */
    private val dispatchLibDir: () -> File,
    /** The engine's cache directory, where a second load is much cheaper. */
    private val cacheDir: File? = null,
    /** Backends tried in order; a test or a non-Tensor phone can narrow it. */
    private val backends: List<() -> Backend>? = null,
) : LocalConverser {

    private val lock = Mutex()

    private var engine: Engine? = null

    /** Initialisation was tried and every backend refused it. Paid once. */
    private var engineDead = false

    /** The conversation in flight, so [cancel] can reach the native call. */
    @Volatile
    private var running: Conversation? = null

    override val installed: Boolean
        get() = File(modelDir, MODEL_FILE).isFile

    override suspend fun reply(prompt: String, history: List<Turn>): String? = lock.withLock {
        val conv = openConversation(history) ?: return null
        val reply = try {
            withTimeoutOrNull(REPLY_TIMEOUT_MS) {
                // sendMessage blocks until the reply is complete; a stop
                // reaches it through cancelProcess, not through the
                // coroutine's own cancellation.
                withContext(Dispatchers.Default) {
                    conv.sendMessage(prompt.take(AssistantClient.MAX_TEXT))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        } finally {
            running = null
            // A no-op when the reply completed; on timeout or cancel it is
            // what actually stops the native call still on its thread.
            runCatching { conv.cancelProcess() }
            runCatching { conv.close() }
        }
        reply?.textOf()?.takeIf { it.isNotBlank() }
    }

    override fun cancel() {
        runCatching { running?.cancelProcess() }
    }

    override fun close() {
        runCatching { running?.close() }
        runCatching { engine?.close() }
        engine = null
    }

    /**
     * The engine, loaded once, or null when no backend would take the model.
     * A failure is remembered in [engineDead] so the next unreachable ask
     * does not pay the load attempt again.
     */
    private suspend fun ensureEngine(): Engine? {
        if (engineDead) return null
        engine?.let { return it }
        val model = File(modelDir, MODEL_FILE)
        if (!model.isFile) return null
        val dispatch = runCatching { dispatchLibDir() }.getOrNull()
        val order = backends ?: listOf<() -> Backend>(
            { Backend.NPU(dispatch?.absolutePath ?: "") },
            { Backend.GOOGLE_TENSOR() },
            { Backend.GPU() },
            { Backend.CPU(CPU_THREADS) },
        )
        for (backend in order) {
            val built = runCatching {
                Engine(
                    EngineConfig(
                        modelPath = model.absolutePath,
                        backend = backend(),
                        cacheDir = cacheDir?.absolutePath,
                        maxNumTokens = MAX_CONTEXT_TOKENS,
                    )
                ).also { candidate ->
                    withContext(Dispatchers.Default) { candidate.initialize() }
                }
            }.getOrNull()
            if (built != null) {
                engine = built
                return built
            }
        }
        engineDead = true
        return null
    }

    private suspend fun openConversation(history: List<Turn>): Conversation? {
        val alive = ensureEngine() ?: return null
        return runCatching {
            alive.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(SYSTEM_INSTRUCTION),
                    initialMessages = history.takeLast(MAX_LOCAL_TURNS).map { turn ->
                        when (turn.role) {
                            Role.USER -> Message.user(turn.text.take(AssistantClient.MAX_TEXT))
                            Role.ASSISTANT -> Message.model(turn.text.take(AssistantClient.MAX_TEXT))
                        }
                    },
                    samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 1.0),
                    maxOutputToken = MAX_OUTPUT_TOKENS,
                )
            )
        }.getOrNull()?.also { running = it }
    }

    companion object {
        /**
         * The Tensor G5 build of the model, the only file this class will
         * open. A generic `.litertlm` is not a substitute: the NPU needs the
         * statically-quantised artifact Google compiled for this chip.
         */
        const val MODEL_FILE = "gemma-4-E2B-it_Google_Tensor_G5.litertlm"

        /** The directory under `filesDir` the provision script pushes to. */
        const val MODEL_DIR_NAME = "models-gemma"

        const val MODEL_BASE_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main"

        /** Six exchanges of wire history, minus what the context cannot hold. */
        private const val MAX_LOCAL_TURNS = 8

        /** Generation is bounded: a conversational answer is a paragraph, not a page. */
        private const val MAX_OUTPUT_TOKENS = 512

        /**
         * The context the engine is told to budget for. The AOT build caps
         * well below the model's trained window; 4k leaves room for the
         * system prompt, eight turns and the reply.
         */
        private const val MAX_CONTEXT_TOKENS = 4096

        /** The whole call, load included. A cold start is the long pole. */
        private const val REPLY_TIMEOUT_MS = 120_000L

        /** Fallback CPU threads, matching the rest of the audio stack's thrift. */
        private const val CPU_THREADS = 4

        /**
         * What the model is told it is. Short on purpose: it is a system
         * prompt paid in context on every reply, and the model only ever
         * sees shown text anyway.
         */
        private const val SYSTEM_INSTRUCTION =
            "You are Maia, a private assistant on the user's phone. " +
                "Answer the question directly and briefly, in one or two sentences. " +
                "If you do not know, say so plainly."
    }
}

/** The text inside a reply [Message], or nothing when it carried none. */
private fun Message.textOf(): String =
    contents?.contents.orEmpty().filterIsInstance<Content.Text>()
        .joinToString("") { it.text }
        .trim()

/**
 * Where `libLiteRtDispatch_GoogleTensor.so` comes from.
 *
 * The library is the NPU half of LiteRT and is not inside the litertlm AAR;
 * upstream ships it in LiteRT's `litert_npu_runtime_libraries.zip` as a
 * dynamic feature. Maia carries it as a compressed asset instead, fetched by
 * scripts/fetch-litert.sh, and lands it as a real file on first use because
 * the runtime finds it by scanning a directory. `filesDir` is used rather
 * than the packaged native library dir so the rest of the app's jniLibs keep
 * their mmap-from-APK packaging.
 */
object TensorDispatch {

    const val LIB = "libLiteRtDispatch_GoogleTensor.so"
    private const val ASSET = "litert/$LIB"
    private const val DIR = "litert-dispatch"

    /**
     * The directory holding the dispatch library, copied out of assets on
     * first call. Throws when the asset was never fetched, which the caller
     * treats as "no NPU on this build".
     */
    fun install(context: Context): File {
        val dir = File(context.filesDir, DIR)
        val target = File(dir, LIB)
        if (!target.isFile) {
            dir.mkdirs()
            val tmp = File(dir, "$LIB.part")
            context.assets.open(ASSET).use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw java.io.IOException("cannot place $LIB")
            }
        }
        preloadVendorLibs(context)
        return dir
    }

    /**
     * The dispatch library reaches the EdgeTPU stack by
     * `dlopen("libedgetpu_litert.so")` on the soname alone. That works for a
     * shell process whose linker path covers `/vendor/lib64`, but an app's
     * classloader namespace permits only `/data`, so even an absolute-path
     * load of a vendor library is refused and the southbound initialisation
     * fails. The workaround: copy the EdgeTPU stack's whole dependency
     * closure into `filesDir`, which is a permitted path, and load the
     * copies by absolute path. Their sonames then resolve in this
     * namespace and the dispatch library's dlopen finds the loaded
     * handles. An absent or unreadable vendor library simply degrades the
     * NPU path as before.
     *
     * Loading is a fixpoint because the list is flat while dependencies
     * are not: a lib whose NEEDED is not yet loaded fails, and a second
     * pass succeeds once its parent landed. Already-loaded sonames are
     * reused by the linker, so the app's own `libc++` is not duplicated.
     */
    private fun preloadVendorLibs(context: Context) {
        val dest = File(context.filesDir, VENDOR_DIR)
        val pending = VENDOR_LIBS.mapNotNull { lib ->
            val source = File("/vendor/lib64", lib).takeIf { it.isFile }
                ?: File("/system/lib64", lib).takeIf { it.isFile }
                ?: return@mapNotNull null
            val target = File(dest, lib)
            runCatching {
                if (target.length() != source.length()) {
                    dest.mkdirs()
                    source.inputStream().use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
            target
        }.toMutableList()
        var progress = true
        while (progress && pending.isNotEmpty()) {
            progress = false
            pending.removeAll { target ->
                runCatching { System.load(target.absolutePath) }
                    .isSuccess.also { if (it) progress = true }
            }
        }
    }

    private const val VENDOR_DIR = "litert-vendor"

    /**
     * The EdgeTPU stack's dependency closure on Tensor devices, minus the
     * NDK and bionic internals an app already has. Computed from the
     * NEEDED entries of the vendor public libraries; an OTA that adds a
     * requirement degrades to CPU or GPU rather than failing the app.
     */
    private val VENDOR_LIBS = listOf(
        "libedgetpu_litert.so",
        "libedgetpu_client.google.so",
        "libedgetpu_tachyon.google.so",
        "libgxp.so",
        "lib_aion_buffer.so",
        "libedgetpu_util.so",
        "libbase.so",
        "com.google.edgetpu_app_service-V10-ndk.so",
        "com.google.edgetpu_vendor_service-V2-ndk.so",
        "libcutils.so",
        "libutils.so",
        "libfmq.so",
        "android.hardware.common.fmq-V1-ndk.so",
        "android.hardware.common-V2-ndk.so",
        "libc++.so",
        "libvndksupport.so",
        "pixel-power-ext-V1-ndk.so",
        "android.hardware.graphics.mapper@4.0.so",
        "android.hardware.graphics.allocator-V2-ndk.so",
        "libhidlbase.so",
        "android.hardware.graphics.common@1.2.so",
        "android.hardware.graphics.common@1.0.so",
        "android.hardware.graphics.common@1.1.so",
        "android.hardware.graphics.common-V7-ndk.so",
        "libdmabufheap.so",
        "libhardware.so",
        "libion.so",
        "libdl_android.so",
        "ld-android.so",
    )
}
