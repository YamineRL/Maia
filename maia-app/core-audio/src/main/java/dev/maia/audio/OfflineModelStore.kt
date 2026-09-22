package dev.maia.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Where the Parakeet TDT 0.6B v3 rescore model lives on disk, and how it got
 * there.
 *
 * This is [ModelStore]'s download engine (resume via `.part` plus a `Range`
 * header, rename only once the byte count matches, one restart on a 416)
 * copied rather than shared, on purpose: [ModelStore]'s tests already pin
 * that engine's correctness, and refactoring it to be generic under time
 * pressure risks the one thing that must not happen, which is a subtly wrong
 * resume path for the model already shipping. See `docs/M4-status.md` open
 * item B and `docs/M0-brief.md` section 1 for why a second model exists at
 * all: the streaming zipformer is the live-partials engine and this is the
 * offline rescore that replaces its draft once capture ends.
 *
 * At roughly 670 MB (encoder 652 MB, decoder 12 MB, joiner 6 MB, tokens under
 * 1 MB, all int8, measured against `csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8`
 * on Hugging Face on 2026-09-14) this is about nine times the zipformer's 70
 * MB. That is a size and RAM number for `maia-privacy` and the user to weigh,
 * not something this class decides.
 */
class OfflineModelStore(
    private val root: File,
    private val baseUrl: String = BASE_URL,
    private val nanoTime: () -> Long = System::nanoTime,
) {

    sealed interface Progress {
        data class Downloading(
            val file: String,
            val index: Int,
            val count: Int,
            val fraction: Float,
            val bytes: Long = 0,
            val totalBytes: Long = -1,
            val bytesPerSecond: Double = 0.0,
        ) : Progress

        data object Ready : Progress
    }

    val isComplete: Boolean
        get() = FILES.all { File(root, it).isFile }

    fun paths() = OfflineModelPaths(
        encoder = File(root, ENCODER).absolutePath,
        decoder = File(root, DECODER).absolutePath,
        joiner = File(root, JOINER).absolutePath,
        tokens = File(root, TOKENS).absolutePath,
    )

    fun ensure(): Flow<Progress> = flow {
        if (!root.isDirectory && !root.mkdirs()) {
            throw IOException("cannot create model directory $root")
        }
        val missing = FILES.filterNot { File(root, it).isFile }
        missing.forEachIndexed { index, name ->
            emit(Progress.Downloading(name, index + 1, missing.size, 0f))
            download(name) { bytes, total, rate ->
                val fraction = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
                emit(Progress.Downloading(name, index + 1, missing.size, fraction, bytes, total, rate))
            }
        }
        emit(Progress.Ready)
    }.flowOn(Dispatchers.IO)
        .buffer(Channel.RENDEZVOUS)

    private suspend inline fun download(name: String, onProgress: (Long, Long, Double) -> Unit) {
        val target = File(root, name)
        val part = File(root, "$name.part")
        repeat(2) {
            when (transfer(name, part, onProgress)) {
                Outcome.Complete -> {
                    coroutineContext.ensureActive()
                    if (!part.renameTo(target)) {
                        part.delete()
                        throw IOException("$name: cannot rename into place")
                    }
                    return
                }
                Outcome.Unsatisfiable -> part.delete()
            }
        }
        throw IOException("$name: the server refused every range, including the whole file")
    }

    private enum class Outcome { Complete, Unsatisfiable }

    private suspend inline fun transfer(
        name: String,
        part: File,
        onProgress: (Long, Long, Double) -> Unit,
    ): Outcome {
        val offset = if (part.isFile) part.length() else 0L
        val connection = (URL("$baseUrl/$name").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
        }
        try {
            val code = connection.responseCode
            if (code == HTTP_RANGE_NOT_SATISFIABLE && offset > 0) return Outcome.Unsatisfiable
            if (code !in 200..299) {
                part.delete()
                throw IOException("$name: HTTP $code")
            }
            val resumed = code == HttpURLConnection.HTTP_PARTIAL
            val base = if (resumed) offset else 0L
            val length = connection.contentLengthLong
            val expected = if (length >= 0) base + length else -1L
            var written = base
            val rate = RollingRate(nanoTime)
            onProgress(written, expected, 0.0)
            connection.inputStream.use { source ->
                java.io.FileOutputStream(part, resumed).use { sink ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = source.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                        written += read
                        rate.add(read.toLong())
                        onProgress(written, expected, rate.bytesPerSecond())
                    }
                }
            }
            if (expected >= 0 && written != expected) {
                throw IOException("$name: got $written bytes, expected $expected")
            }
            return Outcome.Complete
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /**
         * csukuangfj's own int8 export of NeMo's Parakeet TDT 0.6B v3, the same
         * publisher as the zipformer in [ModelStore] and, like it, all-int8
         * rather than the fp16-encoder variant some other mirrors carry.
         */
        private const val BASE_URL =
            "https://huggingface.co/csukuangfj/" +
                "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main"

        private const val ENCODER = "encoder.int8.onnx"
        private const val DECODER = "decoder.int8.onnx"
        private const val JOINER = "joiner.int8.onnx"
        private const val TOKENS = "tokens.txt"

        internal val FILES = listOf(ENCODER, DECODER, JOINER, TOKENS)

        /** Measured via HTTP HEAD against Hugging Face on 2026-09-14. */
        const val APPROXIMATE_BYTES = 670_478_772L

        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
    }
}
