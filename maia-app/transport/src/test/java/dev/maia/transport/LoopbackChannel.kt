package dev.maia.transport

import java.io.Closeable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/**
 * An [AgentChannel] over an ordinary socket, for running the client against
 * the real server from the build machine.
 *
 * This is test-only on purpose and must never reach the phone. It has no
 * tunnel in it, so on Android it would either fail or, worse, work by talking
 * to a loopback port some other app could also open. The shipped
 * implementation dials through gomobile and binds nothing.
 */
class LoopbackChannel(
    host: String,
    port: Int,
    username: String,
    password: String,
    private val readTimeoutMillis: Int = 30_000,
) : AgentChannel {

    private val base = "http://$host:$port"
    private val auth = "Basic " + Base64.getEncoder()
        .encodeToString("$username:$password".toByteArray())

    override fun request(method: String, path: String, body: String?): Reply {
        val c = open(method, path)
        c.readTimeout = readTimeoutMillis
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val status = c.responseCode
        val stream = if (status in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return Reply(status, text)
    }

    override fun stream(path: String, sink: LineSink): Closeable {
        val c = open("GET", path)
        c.setRequestProperty("Accept", "text/event-stream")
        // No read timeout: the stream is silent between heartbeats by design.
        c.readTimeout = 0
        val stopped = AtomicBoolean(false)

        val thread = Thread({
            var reason = ""
            try {
                val status = c.responseCode
                if (status !in 200..299) {
                    reason = "HTTP $status"
                } else {
                    c.inputStream.bufferedReader().use { reader ->
                        while (!stopped.get()) {
                            val line = reader.readLine() ?: break
                            sink.onLine(line)
                        }
                    }
                }
            } catch (e: IOException) {
                if (!stopped.get()) reason = e.message ?: e.toString()
            } finally {
                c.disconnect()
                sink.onClosed(reason)
            }
        }, "loopback-sse")
        thread.isDaemon = true
        thread.start()

        return Closeable {
            stopped.set(true)
            c.disconnect()
        }
    }

    override fun close() {}

    private fun open(method: String, path: String): HttpURLConnection {
        // URI, not URL: the URL constructor is deprecated, and the path
        // already carries a percent-encoded query.
        val c = URI(base + path).toURL().openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 5_000
        c.setRequestProperty("Authorization", auth)
        return c
    }
}
