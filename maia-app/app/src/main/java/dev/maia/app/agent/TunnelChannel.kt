package dev.maia.app.agent

import dev.maia.transport.AgentChannel
import dev.maia.transport.LineSink
import dev.maia.transport.Reply
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The phone's [AgentChannel]: HTTP to the devbox through the tunnel.
 *
 * This is the one genuinely Android-shaped piece of M8 and it is why the seam
 * exists at all. `:transport` holds the client, the envelopes, the SSE framing
 * and the registry, all of it pure Kotlin so it runs against the real server
 * from the build machine. What it cannot hold is this: a connection that goes
 * through tailcat rather than through a socket, which on Android means Go.
 *
 * **No loopback port.** `tunnel/maiatunnel` binds `127.0.0.1:<port>` for every
 * Peer forward, which is right for Termius and wrong here. On Android loopback
 * is device-wide rather than per app, so a bound port is a port every other
 * app on the phone can open, and the thing behind this one runs shell commands
 * with every tool pre-approved. `maiatunnel.Agent` wraps `DialTCPPort` in an
 * `http.Transport.DialContext` instead, so nothing this class does is
 * reachable from another app, and no `VpnService` is needed either.
 *
 * **What this class adds over [GoAgent].** Three adaptations, each of which
 * has been a bug in something before:
 *
 * 1. A Go `int` is a Java `long`. A status that arrives as 401L and is
 *    compared against an `Int` silently never matches.
 * 2. Go has no nullable string, so an absent body is `""` on the way down and
 *    a null body is `""` on the way up. `Reply.body` stays non-null.
 * 3. A Go error crosses as a thrown `Exception`, including `RuntimeException`
 *    for a panic. [AgentChannel] contracts [IOException] for a failure to
 *    complete the exchange, so everything is mapped, and nothing that escapes
 *    from here carries a message the caller has to guess at.
 *
 * **Nothing here logs.** Not the path, not the body, not a failure. A request
 * body carries an instruction, a reply body carries the contents of the user's
 * machine, and the `Authorization` header carries the only lock facing the
 * phone. The passphrase is never held by this class at all: it is pushed into
 * the Go side by [authorise] and there is no getter.
 */
class TunnelChannel internal constructor(
    private val agent: GoAgent,
) : AgentChannel {

    private val closed = AtomicBoolean(false)

    /**
     * Attaches the credential. Separate from the constructor so that a channel
     * can outlive a passphrase being re-entered after a 401, which is the one
     * recovery `docs/M8-copy.md` section 5.8 offers.
     */
    fun authorise(user: String, passphrase: String) {
        agent.setBasicAuth(user, passphrase)
    }

    /**
     * Bounds a single [request]. Streams ignore it entirely, which is correct:
     * a held stream that is silent for ninety seconds is handled by the
     * silence rule in the run machine and not by a socket timeout, because a
     * four-minute tool call still heartbeats and a timeout cannot tell the two
     * apart.
     */
    fun timeoutMillis(ms: Long) {
        agent.setTimeoutMillis(ms)
    }

    override fun request(method: String, path: String, body: String?): Reply {
        require(path.startsWith("/")) { "path must start with a slash" }
        if (closed.get()) throw IOException("the agent channel is closed")
        val reply = try {
            agent.request(method, path, body ?: "")
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            // Go's error text is the only thing that says whether the tunnel
            // is down or the port is silent, and section 9 wants those named
            // separately. It is carried, not swallowed, and never logged here.
            throw IOException("agent request failed: ${e.message ?: e.javaClass.simpleName}", e)
        }
        return Reply(status = reply.status.toInt(), body = reply.body)
    }

    override fun stream(path: String, sink: LineSink): Closeable {
        require(path.startsWith("/")) { "path must start with a slash" }
        if (closed.get()) throw IOException("the agent channel is closed")

        // Exactly once, whoever gets there first. Go calls OnClosed when the
        // server ends the stream and also when Close cancels it, so a caller
        // that closes a stream and then sees the server end it must not be
        // told twice: the run machine counts a close as the end of a turn, and
        // a second one would mark an already finished turn as cut short.
        val done = AtomicBoolean(false)
        val handle = agent.stream(
            path,
            onLine = { line -> if (!done.get()) sink.onLine(line) },
            onClosed = { reason -> if (done.compareAndSet(false, true)) sink.onClosed(reason) },
        )
        return Closeable {
            handle.close()
            // Go's own OnClosed follows a cancel, so this is a backstop for
            // the case where it cannot: the channel is being torn down under
            // the stream and nobody would otherwise hear the end.
            if (closed.get() && done.compareAndSet(false, true)) sink.onClosed("")
        }
    }

    /**
     * Tells Go the pushed network facts moved, so the tailcat client built
     * against the old ones is dropped and the next dial pays a cold bring-up
     * against what is true now. Without this a Wi-Fi handover leaves every
     * dial failing on a dead interface until the process is restarted.
     */
    fun networkChanged() {
        agent.networkChanged()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // The credential goes first, so a channel that is closed but still
        // referenced cannot be made to send one.
        runCatching { agent.setBasicAuth("", "") }
        runCatching { agent.close() }
    }

    companion object {
        /** The user half of the basic auth pair. The server is started with it. */
        const val USER = "opencode"

        /** The agent port on the devbox, as `agent-web.service` binds it. */
        const val PORT = 4096

        /**
         * A cold call through a tunnel that has yet to come up is measured at
         * 1.2 to 1.8 seconds on a phone and worse on a bad link, so this is
         * generous. It bounds only requests: the stream is unbounded by
         * design.
         */
        const val REQUEST_TIMEOUT_MS = 30_000L

        /**
         * Dials the devbox at [serverAddr], which is the tailcat address and
         * embeds the pre-shared key. It is a credential in its own right
         * (PRD section 10, lock one of three) and is held in the same private
         * preferences as the passphrase.
         */
        fun open(identity: maiatunnel.Identity?, serverAddr: String, port: Int = PORT): TunnelChannel =
            TunnelChannel(BoundAgent(maiatunnel.Agent(identity, serverAddr, port.toLong()))).also {
                it.timeoutMillis(REQUEST_TIMEOUT_MS)
            }
    }
}
