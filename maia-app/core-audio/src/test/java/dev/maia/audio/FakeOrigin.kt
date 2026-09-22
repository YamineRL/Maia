package dev.maia.audio

import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * A one file HTTP/1.1 origin on loopback, for the [ModelStore] tests.
 *
 * This is a raw [ServerSocket] rather than `com.sun.net.httpserver` because
 * these are Android unit tests: they compile against `android.jar`, which does
 * not carry the `jdk.httpserver` module, so the obvious choice does not
 * resolve. `java.net` is in `java.base` and is present either way.
 *
 * It serves exactly what a test asks it to, including the two failures worth
 * proving: a body shorter than the `Content-Length` it promised, and a refusal.
 */
class FakeOrigin {

    private val socket = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
    private val worker: Thread
    private val hits = AtomicInteger()

    /** What to serve, by file name. Anything absent is a 404. */
    val bodies = mutableMapOf<String, ByteArray>()

    /** Names to answer with half the promised bytes and then hang up. */
    val truncate = mutableSetOf<String>()

    /** Names to refuse outright. */
    val refuse = mutableSetOf<String>()

    /**
     * Names to hang up on after this many bytes of the file, counted from the
     * start of the file rather than of the response. One shot: the entry is
     * removed when it fires, so the retry that follows gets the rest.
     */
    val cutOnce = mutableMapOf<String, Int>()

    /** Names for which a Range header is ignored and the whole file sent with 200. */
    val ignoreRange = mutableSetOf<String>()

    /** Every Range header received, in order, as its raw value. */
    val ranges = java.util.Collections.synchronizedList(mutableListOf<String>())

    val url: String get() = "http://127.0.0.1:${socket.localPort}"

    /** How many requests have arrived, for asserting that nothing refetched. */
    val requests: Int get() = hits.get()

    fun resetRequests() = hits.set(0)

    init {
        worker = Thread {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                runCatching { client.use(::handle) }
            }
        }
        worker.isDaemon = true
        worker.start()
    }

    private fun handle(client: Socket) {
        val input = client.getInputStream()

        // Request line, then headers to the blank line. Read a byte at a time
        // rather than buffering, so nothing of the next request is swallowed.
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val byte = input.read()
            if (byte < 0) return
            head.append(byte.toChar())
        }
        hits.incrementAndGet()
        val range = head.lineSequence()
            .firstOrNull { it.startsWith("range:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
        range?.let { ranges += it }

        val path = head.lineSequence().first().split(' ').getOrNull(1).orEmpty()
        val name = path.removePrefix("/")
        val body = bodies[name]

        val output = BufferedOutputStream(client.getOutputStream())
        if (body == null || name in refuse) {
            output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            output.flush()
            return
        }

        val from = range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull()
            ?.takeIf { name !in ignoreRange }
        if (from != null && from >= body.size) {
            output.write(
                "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */${body.size}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    .toByteArray(),
            )
            output.flush()
            return
        }

        // Promise the rest of the file either way. The truncated and cut cases
        // deliver less and close, which is what a dropped connection looks like.
        val offset = from ?: 0
        output.write(
            (
                (if (from != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n") +
                    (if (from != null) "Content-Range: bytes $offset-${body.size - 1}/${body.size}\r\n" else "") +
                    "Content-Length: ${body.size - offset}\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(),
        )
        val end = when {
            name in truncate -> offset + (body.size - offset) / 2
            name in cutOnce -> cutOnce.remove(name)!!.coerceIn(offset, body.size)
            else -> body.size
        }
        output.write(body, offset, end - offset)
        output.flush()
    }

    fun close() {
        socket.close()
        worker.interrupt()
    }
}
