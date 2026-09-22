package dev.maia.app.agent

/**
 * The gomobile binding, as narrowly as Kotlin can name it.
 *
 * This interface exists because of what gomobile can and cannot marshal.
 * Across that boundary only string, int, int64, bool, []byte, error, pointers
 * to exported structs and Java-implemented interfaces survive, and a Go `int`
 * arrives as a Java `long`. So `maiatunnel.Reply.getStatus()` is a `long`, a
 * body that is absent is the empty string rather than null, and there is no
 * way to hand Go a `net.Conn`, a context or a coroutine. Everything HTTP
 * therefore stays on the Go side and Kotlin sees a status, a body, and one
 * line at a time.
 *
 * Those are the shapes worth adapting, and adapting is logic, and logic is
 * worth testing. A JVM unit test cannot load `libgojni.so`, so the adaptation
 * lives in [TunnelChannel] over this interface and the only untestable part is
 * [BoundAgent] below, which forwards five calls and decides nothing.
 *
 * Every method may be called from any thread. [stream] returns at once and
 * reads on a Go goroutine.
 */
internal interface GoAgent {

    /**
     * The credential sent with every request, in the `Authorization` header.
     *
     * There is no variant of this that takes a URL, deliberately. OpenCode
     * accepts `?auth_token=` at parity with the header and a credential in a
     * URL lands in every log, crash report and referrer, so the shape that
     * would allow it is simply absent rather than discouraged.
     */
    fun setBasicAuth(user: String, password: String)

    /** Bounds one [request]. Zero means no bound. Streams ignore it. */
    fun setTimeoutMillis(ms: Long)

    /** [body] is the empty string when there is none: Go has no nullable string. */
    fun request(method: String, path: String, body: String): GoReply

    /**
     * Opens a stream. [onClosed] arrives exactly once, with an empty reason
     * for a clean end of stream. Both callbacks arrive on a Go goroutine.
     */
    fun stream(path: String, onLine: (String) -> Unit, onClosed: (String) -> Unit): GoStream

    /**
     * Tells Go the network facts just pushed differ from the ones the live
     * tunnel was built against, so the tailcat client is dropped and lazily
     * rebuilt by the next dial. It cannot be poked in place: the monitor
     * tailcat builds is private to it and polls only every ten minutes on
     * Android, so a client holding a dead interface never finds out.
     */
    fun networkChanged()

    fun close()
}

/** One finished response. [status] is a `long` because a Go `int` is. */
internal class GoReply(val status: Long, val body: String)

/** One open stream. Closing is idempotent on the Go side. */
internal interface GoStream {
    fun close()
}

/**
 * The real binding. Six forwarding methods and no decisions, which is why it
 * is the one class here with no test: there is nothing in it that could be
 * wrong without `maiatunnel.Agent` itself being wrong.
 */
internal class BoundAgent(private val agent: maiatunnel.Agent) : GoAgent {

    override fun setBasicAuth(user: String, password: String) = agent.setBasicAuth(user, password)

    override fun setTimeoutMillis(ms: Long) = agent.setTimeoutMillis(ms)

    override fun request(method: String, path: String, body: String): GoReply {
        val reply = agent.request(method, path, body)
        return GoReply(reply.status, reply.body ?: "")
    }

    override fun stream(path: String, onLine: (String) -> Unit, onClosed: (String) -> Unit): GoStream {
        val stream = agent.stream(path, object : maiatunnel.LineSink {
            override fun onLine(line: String?) {
                onLine(line ?: "")
            }

            override fun onClosed(reason: String?) {
                onClosed(reason ?: "")
            }
        })
        return object : GoStream {
            override fun close() = stream.close()
        }
    }

    override fun networkChanged() = agent.networkChanged()

    override fun close() = agent.close()
}
