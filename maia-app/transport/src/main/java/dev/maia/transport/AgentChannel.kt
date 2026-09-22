package dev.maia.transport

import java.io.Closeable

/**
 * One HTTP conversation with the agent server, with the transport left open.
 *
 * On the phone the implementation is the gomobile tunnel: `maiatunnel.Agent`
 * dials the devbox through tailcat and never binds a loopback port, because on
 * Android loopback is device-wide and the thing behind this port runs shell
 * commands. In tests it is a plain socket to 127.0.0.1. Everything above this
 * interface is identical in both cases, which is the point: the session
 * bookkeeping, the event parsing and the error mapping are all exercised
 * against the real server from the build machine.
 *
 * Implementations must be safe to call from any thread.
 */
interface AgentChannel : Closeable {

    /**
     * Performs one request and returns when the whole body has arrived.
     *
     * `path` starts with a slash and carries its own query string. A non-2xx
     * status comes back as a [Reply], not an exception: the body carries the
     * server's explanation and the caller decides what it means. Only a
     * failure to complete the exchange throws, as [java.io.IOException].
     */
    fun request(method: String, path: String, body: String? = null): Reply

    /**
     * Opens a server-sent-event stream and delivers it a line at a time.
     * Returns immediately; reading happens elsewhere. Close the returned
     * handle to end it.
     */
    fun stream(path: String, sink: LineSink): Closeable
}

/** One finished HTTP response. */
data class Reply(val status: Int, val body: String) {
    val ok: Boolean get() = status in 200..299
}

/**
 * Raw stream lines, before any SSE framing.
 *
 * The split is deliberate. Assembling lines into frames is pure string work
 * ([SseAssembler]), so it belongs where it can be tested without a network, a
 * tunnel or a phone, and not inside a Go goroutine or a socket loop.
 *
 * Called from a background thread. [onClosed] arrives exactly once, with an
 * empty reason for a clean end of stream.
 */
interface LineSink {
    fun onLine(line: String)
    fun onClosed(reason: String)
}

/**
 * The server answered, and said no. Carries the status so a caller can tell
 * 401 (the passphrase is wrong or missing) from 404 (that session is gone)
 * from 400 (we sent nonsense).
 */
class AgentException(val status: Int, message: String) :
    RuntimeException("agent: HTTP $status: $message")
