package dev.maia.audio.speech

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
import dev.maia.audio.RollingRate

/**
 * Where the Piper voice lives on disk, and how it got there.
 *
 * The voice is an optional second model set (brief section 5, M9 PRD section
 * 9): refusing this download must leave show-only conversation fully working,
 * which is why nothing constructs it unless asked and why a partial download
 * is invisible to [isComplete] until every byte has landed.
 *
 * This is [dev.maia.audio.ModelStore]'s download engine (resume via `.part`
 * plus a `Range` header, rename only once the byte count matches, one restart
 * on a 416) copied rather than shared, the same decision [OfflineModelStore]
 * documents: `ModelStore`'s tests pin that engine, and refactoring it to be
 * generic risks the resume path of a model already shipping.
 *
 * The files are flat, fetched from the same publisher and the same
 * `resolve/main` convention as the ASR models, rather than the `tar.bz2`
 * sherpa-onnx also publishes. Flat is what lets this engine work unchanged,
 * keeps every file individually resumable, and avoids a decompressor
 * dependency. The cost is the one thing the archive would have hidden: the
 * `espeak-ng-data` directory, which the engine reaches as plain files with
 * `/` in their names.
 *
 * **The espeak-ng-data set is a subset on purpose, not a mistake.** The
 * upstream tree carries dictionaries and language files for about a hundred
 * languages. An `en_*` Piper voice reads only the shared phoneme tables,
 * `en_dict`, and the English `lang` entries, verified on 2026-09-22 by
 * running espeak-ng 1.52 against exactly the files in [ESPEAK_FILES]: the
 * phoneme output for `en-gb-x-rp` and `en-us` was byte-for-byte identical to
 * the full tree. A voice outside English would need its own entries added
 * here; that is a deliberate list, not a gap to discover on the phone.
 */
class VoiceStore(
    private val root: File,
    /**
     * A parameter only so a test can point it at a loopback server, the same
     * seam [dev.maia.audio.ModelStore] leaves open.
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
            val bytes: Long = 0,
            val totalBytes: Long = -1,
            val bytesPerSecond: Double = 0.0,
        ) : Progress

        data object Ready : Progress
    }

    val isComplete: Boolean
        get() = FILES.all { File(root, it).isFile }

    fun paths() = VoicePaths(
        model = File(root, MODEL).absolutePath,
        tokens = File(root, TOKENS).absolutePath,
        dataDir = File(root, ESPEAK_DATA).absolutePath,
    )

    /**
     * Downloads whatever is missing. Safe to call on every launch: files that
     * are already present cost one stat each.
     */
    fun ensure(): Flow<Progress> = flow {
        if (!root.isDirectory && !root.mkdirs()) {
            throw IOException("cannot create voice directory $root")
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
        // Unlike the ASR stores the voice has files in subdirectories, so the
        // parent may not exist yet even once root does.
        part.parentFile?.let { parent ->
            if (!parent.isDirectory && !parent.mkdirs()) {
                throw IOException("$name: cannot create directory $parent")
            }
        }
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
         * Piper `en_GB-alan-low`, the sherpa-onnx conversion in the same
         * publisher's layout the ASR models already use. `low` is the 16 kHz
         * export, chosen for size and synthesis speed on the phone; the
         * medium export is one constant away if the side-by-side disagrees.
         *
         * Voice licences vary per voice (brief section 11). alan's model card
         * points at the mimic3 en_UK apope_low dataset, and [MODEL_CARD] is
         * fetched with the voice so the licence text is on the device for the
         * review that section asks for, not summarised from memory.
         */
        const val VOICE = "en_GB-alan-low"

        private const val BASE_URL =
            "https://huggingface.co/csukuangfj/vits-piper-$VOICE/resolve/main"

        private const val MODEL = "$VOICE.onnx"
        private const val TOKENS = "tokens.txt"

        /**
         * The voice's own metadata: espeak voice name, phoneme map, sample
         * rate, licence pointer. sherpa reads equivalents from the onnx
         * metadata and never opens this file, but it costs 4 KB and is the
         * written record of what the voice believes itself to be.
         */
        private const val MODEL_JSON = "$VOICE.onnx.json"
        private const val MODEL_CARD = "MODEL_CARD"
        private const val ESPEAK_DATA = "espeak-ng-data"

        /** See the class comment: the English-only set, verified. */
        internal val ESPEAK_FILES = listOf(
            "$ESPEAK_DATA/en_dict",
            "$ESPEAK_DATA/phondata",
            "$ESPEAK_DATA/phondata-manifest",
            "$ESPEAK_DATA/phonindex",
            "$ESPEAK_DATA/phontab",
            "$ESPEAK_DATA/intonations",
            "$ESPEAK_DATA/lang/gmw/en",
            "$ESPEAK_DATA/lang/gmw/en-US",
            "$ESPEAK_DATA/lang/gmw/en-GB-x-rp",
        )

        internal val FILES: List<String> = listOf(MODEL, MODEL_JSON, MODEL_CARD, TOKENS) + ESPEAK_FILES

        /**
         * Roughly 64 MB: the fp32 voice is 63,104,662 bytes and the espeak
         * subset plus the small files add under a megabyte, measured against
         * `csukuangfj/vits-piper-en_GB-alan-low` on 2026-09-22. The first-run
         * screen must state a figure before fetching (M9 PRD section 9).
         */
        const val APPROXIMATE_BYTES = 64_000_000L

        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
    }
}
