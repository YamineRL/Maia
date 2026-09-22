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
 * Where the zipformer lives on disk, and how it got there.
 *
 * 70 MB is too much to put in the APK, so the model is fetched on first run
 * into filesDir. Each file downloads to a `.part` and is renamed only once
 * the bytes written match the length the server promised. A half-written
 * model that keeps its final name is the worst outcome here: ONNX Runtime
 * rejects it deep inside native code with an error that says nothing about
 * truncation, and the app looks broken rather than incomplete.
 *
 * The `.part` survives a dropped connection and a user leaving the first-run
 * screen, and the next [ensure] asks for the rest with a `Range` header. On a
 * phone on a train, 70 MB that starts over at every tunnel never finishes.
 * What the `.part` never survives is a refusal: a 404 or 403 means the name
 * is wrong upstream, and resuming into it would be resuming into nothing.
 */
class ModelStore(
    private val root: File,
    /**
     * Where the files come from. A parameter only so a test can point it at a
     * loopback server and exercise the truncation path below, which is the one
     * failure here that is worth proving and is impossible to provoke against
     * HuggingFace on purpose.
     */
    private val baseUrl: String = BASE_URL,
    private val nanoTime: () -> Long = System::nanoTime,
) {

    sealed interface Progress {
        data class Downloading(
            val file: String,
            val index: Int,
            val count: Int,
            val fraction: Float,
            /** Bytes of this file on disk, including any resumed from an earlier attempt. */
            val bytes: Long = 0,
            /** This file's full length, or -1 until the server has said. */
            val totalBytes: Long = -1,
            /**
             * Recent throughput, over [RollingRate.WINDOW_NANOS]. Zero until
             * there is enough to say, so the screen shows no ETA rather than a
             * wild one. Resumed bytes are not counted: they cost no time.
             */
            val bytesPerSecond: Double = 0.0,
        ) : Progress

        data object Ready : Progress
    }

    val isComplete: Boolean
        get() = FILES.all { File(root, it).isFile }

    fun paths() = ModelPaths(
        encoder = File(root, ENCODER).absolutePath,
        decoder = File(root, DECODER).absolutePath,
        joiner = File(root, JOINER).absolutePath,
        tokens = File(root, TOKENS).absolutePath,
        bpeVocab = File(root, BPE).absolutePath,
    )

    /**
     * Downloads whatever is missing. Safe to call on every launch: files that
     * are already present cost one stat each.
     */
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
        // Rendezvous, not flowOn's default buffer of 64. With a buffer the
        // download runs ahead of a collector that has already gone, and can
        // finish a small file and rename it into place after being abandoned.
        // Handing over each progress step keeps it at the collector's pace, so
        // cancelling stops it at the next emission, before another byte lands.
        .buffer(Channel.RENDEZVOUS)

    private suspend inline fun download(name: String, onProgress: (Long, Long, Double) -> Unit) {
        val target = File(root, name)
        val part = File(root, "$name.part")
        // One restart at most: a 416 says the part is no prefix of this file,
        // and a second 416 on an empty part would be the server misbehaving.
        repeat(2) {
            when (transfer(name, part, onProgress)) {
                Outcome.Complete -> {
                    // Abandoned after the last byte: keep the part, never publish it.
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
                // A refusal, not a drop. Nothing here is worth resuming into.
                part.delete()
                throw IOException("$name: HTTP $code")
            }
            // 206 appends to what is there. Anything else in 2xx is the server
            // ignoring the range and sending the whole file, so start over
            // rather than appending a second copy of the head.
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
                // Kept. These bytes are true, and the next attempt asks for the rest.
                throw IOException("$name: got $written bytes, expected $expected")
            }
            return Outcome.Complete
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /**
         * sherpa-onnx-streaming-zipformer-en-2023-06-26, the chunk-16-left-64
         * export. The left-128 export is byte for byte the same size on disk
         * and differs only in how much left context it attends to at run time,
         * so if accuracy disappoints, that swap costs nothing but compute.
         */
        private const val BASE_URL =
            "https://huggingface.co/csukuangfj/" +
                "sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main"

        private const val ENCODER = "encoder-epoch-99-avg-1-chunk-16-left-64.int8.onnx"

        // int8 for the encoder and joiner, fp32 for the decoder. The decoder is
        // 2 MB and quantising it buys nothing measurable.
        private const val DECODER = "decoder-epoch-99-avg-1-chunk-16-left-64.onnx"
        private const val JOINER = "joiner-epoch-99-avg-1-chunk-16-left-64.int8.onnx"
        private const val TOKENS = "tokens.txt"
        private const val BPE = "bpe.model"

        internal val FILES = listOf(ENCODER, DECODER, JOINER, TOKENS, BPE)

        /** Roughly 70 MB, dominated by the encoder at 67.8 MB. */
        const val APPROXIMATE_BYTES = 73_800_000L

        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
    }
}
