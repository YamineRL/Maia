package dev.maia.app.answer

import dev.maia.app.agent.TunnelChannel
import dev.maia.transport.AssistantChannel
import dev.maia.transport.AssistantClient
import dev.maia.transport.LineSink
import dev.maia.transport.Reply
import java.io.Closeable

/**
 * The phone's [AssistantChannel]: HTTP to the conversational gateway on the
 * devbox, through the same tunnel as everything else.
 *
 * A wrapper and not a sibling of [TunnelChannel], because the two channels
 * differ in exactly one place: which credential is attached and how. The
 * gateway accepts a bearer token or the password half of a basic pair, and
 * `maiatunnel` can only send basic, so [authorise] pushes the credential as
 * the password half with [USER] as the name (M9 PRD section 8.1). Every
 * other adaptation the tunnel already owns applies unchanged: the Go long
 * status, the empty body, the mapped error, the once-only close.
 *
 * A separate `maiatunnel.Agent` on [AssistantClient.DEFAULT_PORT], not a
 * shared one on the agent's port, for the reason [dev.maia.app.agent.AgentHost]
 * keeps its registry channel unauthorised: the one credential that matters
 * here is sent to exactly one place.
 *
 * **The credential is never held.** It is read out of
 * [dev.maia.app.agent.AgentSecrets] by the host, pushed into Go by
 * [authorise], and there is no field, getter or log line it could pass
 * through here.
 */
class AssistantTunnelChannel internal constructor(
    private val channel: TunnelChannel,
) : AssistantChannel {

    /**
     * Attaches the gateway credential. Separate from the constructor so a
     * channel can outlive a refused credential being re-entered, which is the
     * same recovery the agent's channel is shaped for.
     */
    override fun authorise(credential: String) {
        channel.authorise(USER, credential)
    }

    override fun request(method: String, path: String, body: String?): Reply =
        channel.request(method, path, body)

    /**
     * The gateway has no streaming route in M9. The contract requires the
     * method, so it delegates; a future streamed answer would arrive here
     * with the tunnel's once-only close already applied.
     */
    override fun stream(path: String, sink: LineSink): Closeable =
        channel.stream(path, sink)

    /**
     * Tells Go the pushed network facts moved, so the next dial rebuilds
     * against what is true now. Called from [AnswerHost.networkChanged],
     * which the one process-wide watcher reaches through
     * `AgentHost.onNetworksChanged`: the ConnectivityManager callback is
     * registered once and every channel built on the tunnel hears it.
     */
    fun networkChanged() {
        channel.networkChanged()
    }

    override fun close() = channel.close()

    companion object {
        /**
         * The user half of the basic pair. The gateway reads only the
         * password half, so this is a label, not a secret.
         */
        const val USER = "maia"

        /**
         * Dials the devbox at [serverAddr] on the assistant port, 4098. A
         * third forward in `tailcat-phone.service`, beside the agent's 4096
         * and the registry's 4097.
         */
        fun open(
            identity: maiatunnel.Identity?,
            serverAddr: String,
            port: Int = AssistantClient.DEFAULT_PORT,
        ): AssistantTunnelChannel =
            AssistantTunnelChannel(TunnelChannel.open(identity, serverAddr, port))
    }
}
