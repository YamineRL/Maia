package dev.maia.transport

import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The conversational gateway on the devbox: one POST, one strict JSON answer.
 *
 * `assistant-web` is a separate service from OpenCode (M9 PRD section 8). It
 * exposes no filesystem, shell or tools; it owns the llama-server key the
 * phone never sees; and it serialises Maia requests behind whatever the one
 * inference slot is doing, answering `busy` rather than waiting forever. This
 * client is the phone half of that contract and nothing more: it sends the
 * utterance and the history the caller chose to keep, and it reads the reply
 * into [AssistantReply]. What an action payload *means* is decided elsewhere,
 * because strict validation of a remote proposal is a policy decision and
 * this module carries data, not verdicts.
 *
 * **Credential.** The gateway authenticates with a bearer token, a separate
 * mode-600 credential from the agent's basic pair. And like the agent's, it
 * is a property of the channel rather than of a request: [AgentChannel.request]
 * carries no headers by design, so the credential cannot ride one call and is
 * pushed to the channel through [AssistantChannel.authorise] instead, the
 * same way `TunnelChannel.authorise` pushes the passphrase into Go. It is
 * never placed in the path, the body or a log, for the reason
 * `maiatunnel.Agent.SetBasicAuth` gives for refusing a query string.
 *
 * **Caps.** Enforced here, before anything is sent, rather than reported back
 * by a server that has to defend the same limits. Section 7 bounds a
 * conversation at six turn pairs and the request schema bounds each text at
 * [MAX_TEXT]. The choice is truncate, not reject: [history] keeps its newest
 * [MAX_HISTORY] entries and drops the oldest, which is exactly how the
 * session itself ages pairs out, and an over-long text is cut at the cap. A
 * truncated utterance still gets an answer; a rejected one is a dead end the
 * user can only retry by chance.
 *
 * **Outcomes.** [chat] never throws for a shape the exchange can produce.
 * `Answer` and `Action` are the two replies the contract names; `Busy` is the
 * gateway saying its one slot is held, by status or by body; `Unavailable` is
 * every other ending: auth refused, tunnel down, reply unreadable, or the
 * bound hit. Cancellation is the one deliberate exception: a cancelled call
 * throws [CancellationException] like any other coroutine, because the
 * cancel is the user's own "stop", not a failure to report.
 */
class AssistantClient(
    private val channel: AssistantChannel,
    /** The bearer credential. Held, pushed per request, never rendered. */
    private val credential: String,
    /**
     * The bound on the whole exchange, dialling included. Seventy-five
     * seconds sits under the gateway's ninety-second queue wait: a request
     * that is still queued at the bound is better reported than held, because
     * the screen has `Thinking` and a retry rather than a silent wait.
     * Injectable so a test can prove the bound without waiting for it.
     */
    private val timeoutMillis: Long = TIMEOUT_MS,
) : Closeable {

    /**
     * Asks one question and waits for the whole reply.
     *
     * Suspending because the wait is real: the channel's request is blocking
     * and the gateway's answer can legitimately be a minute away behind its
     * queue, so the call runs on [Dispatchers.IO] where it cannot hold a main
     * thread, under a [withTimeoutOrNull] so it cannot hold anything forever.
     * Cancelling the coroutine stops waiting: the stale request is left to
     * finish alone on its thread and its reply, when it lands, is dropped.
     */
    suspend fun chat(
        utterance: String,
        history: List<Turn> = emptyList(),
        locale: String? = null,
        timezone: String? = null,
    ): AssistantReply {
        require(utterance.isNotBlank()) { "empty utterance" }
        val body = Json.obj(
            "utterance" to utterance.take(MAX_TEXT),
            "history" to history.takeLast(MAX_HISTORY).map { turn ->
                mapOf("role" to turn.role.wire, "text" to turn.text.take(MAX_TEXT))
            },
            // Section 8.3: the facts the model needs to answer well and the
            // closed list of what it may propose, and nothing else. No device
            // id, no registry, no contacts, no calendar.
            "locale" to locale,
            "timezone" to timezone,
            "capabilities" to CAPABILITIES,
        )
        val reply = try {
            // Push first, inside the try: a channel that cannot take the
            // credential is the same failure as one that cannot dial.
            channel.authorise(credential)
            withTimeoutOrNull(timeoutMillis) {
                withContext(Dispatchers.IO) {
                    channel.request("POST", PATH, body)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return AssistantReply.Unavailable(
                "cannot reach the assistant: ${e.message ?: e.javaClass.simpleName}"
            )
        } ?: return AssistantReply.Unavailable("no answer after ${timeoutMillis / 1000} seconds")
        return replyOf(reply)
    }

    override fun close() = channel.close()

    /**
     * Reads one response into the four things it can mean.
     *
     * `busy` is checked before and after parsing: the gateway may say it with
     * a status, with a body, or both, and to the user it is one thing. A
     * non-2xx that is not 503 is `Unavailable`, with 401 and 403 named because
     * they have exactly one cause here. An `answer` with blank or missing
     * text is malformed, not empty: it is `Unavailable` for the same reason a
     * broken body is. An `action` hands over the `action` object as it came,
     * or the whole reply when the server sent the fields flat; the raw map is
     * the deliverable and strict validation happens above this module.
     */
    private fun replyOf(reply: Reply): AssistantReply {
        if (reply.status == BUSY_STATUS) return AssistantReply.Busy
        if (!reply.ok) {
            val detail = if (reply.status == 401 || reply.status == 403) {
                "wrong or missing credential for the assistant"
            } else {
                "HTTP ${reply.status}: " + reply.body.take(200).ifBlank { "no body" }
            }
            return AssistantReply.Unavailable(detail)
        }
        val parsed = runCatching { Json.parse(reply.body) }.getOrElse {
            return AssistantReply.Unavailable("unparseable reply: ${reply.body.take(200)}")
        }
        if (parsed.string("error") == "busy") return AssistantReply.Busy
        // The live gateway wraps the reply as {"ok":true,"reply":{...}} while
        // older and stub servers send the fields flat; accept both.
        val root = parsed.at("reply") as? Map<*, *> ?: parsed
        return when (root.string("type")) {
            "answer" -> {
                val text = root.string("text")
                if (text.isNullOrBlank()) {
                    AssistantReply.Unavailable("an answer with no text")
                } else {
                    AssistantReply.Answer(text, root.string("spoken")?.take(MAX_SPOKEN))
                }
            }
            "action" -> AssistantReply.Action(actionOf(root))
            "busy" -> AssistantReply.Busy
            "unavailable" -> AssistantReply.Unavailable(
                root.string("reason") ?: "the gateway said unavailable"
            )
            else -> AssistantReply.Unavailable("an unknown reply: ${reply.body.take(200)}")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun actionOf(root: Any?): Map<String, Any?> =
        (root.at("action") as? Map<String, Any?>)
            ?: (root as? Map<String, Any?>)
            ?: emptyMap()

    companion object {
        /** The one route the gateway serves. */
        const val PATH = "/assistant/chat"

        /**
         * The port the gateway binds on the devbox, forwarded over tailcat.
         *
         * 4097 was the first number on the spec and is already taken by
         * `registry-web.service`; 4098 is the new forward in
         * `tailcat-phone.service`.
         */
        const val DEFAULT_PORT = 4098

        /** See [timeoutMillis]. */
        const val TIMEOUT_MS = 75_000L

        /** The most text one field may carry, utterance or history entry. */
        const val MAX_TEXT = 800

        /** The most turns of history one request may carry. */
        const val MAX_HISTORY = 6

        /** The most of an answer the voice may say (section 3.5). */
        const val MAX_SPOKEN = 400

        /** The closed action list of section 8.3, sent so the model may only ever propose inside it. */
        val CAPABILITIES = listOf(
            "answer", "timer", "alarm", "agenda", "availability", "calculate",
            "settings", "torch", "dial", "message", "navigate", "web", "app", "media",
        )

        private const val BUSY_STATUS = 503
    }
}

/**
 * An [AgentChannel] that can carry the assistant credential.
 *
 * The parent interface's `request` has no header parameter on purpose: the
 * registry needs none, and the agent's `Authorization` is set on the whole
 * channel once. The assistant is the same shape under a different scheme, so
 * the contract is widened by one verb rather than by threading a header
 * argument through every existing implementation. The app satisfies this
 * with a second channel over the tunnel, one `maiatunnel` instance bound to
 * the assistant port, which attaches `Bearer` where the agent's attaches
 * `Basic`.
 */
interface AssistantChannel : AgentChannel {
    /**
     * Attaches the credential to every request made. The gateway accepts it
     * as `Bearer` or as the password half of a `Basic` pair, and which one
     * travels is the channel's business: `maiatunnel` can only send Basic,
     * so the app satisfies this with `setBasicAuth` on a second tunnel
     * channel where a stub sends `Bearer` in a test.
     */
    fun authorise(credential: String)
}

/**
 * One exchange the user already saw, sent back so a follow-up has context.
 *
 * Only shown text may ever be a [Turn] (section 7): the model's own working
 * and the server's metadata never return to the phone, so there is nothing
 * else to put here.
 */
data class Turn(val role: Role, val text: String)

/** Who spoke a [Turn]. The wire strings, exactly. */
enum class Role(val wire: String) {
    USER("user"),
    ASSISTANT("assistant"),
}

/**
 * What one question came to.
 *
 * A sealed hierarchy for the reason [Lookup] is: "answered" and "could not
 * answer" want different screens, and a caller that can collapse them into a
 * nullable will eventually show an empty answer as though it were the truth.
 * Nothing here throws and nothing here retries; those decisions belong to the
 * caller, which has the user in front of it.
 */
sealed class AssistantReply {

    /**
     * The gateway answered in words. [text] is the whole reply as sent, and
     * [spoken] its optional short form (section 3.5: two sentences, 400
     * characters, which the field is cut to again here because the sender is
     * untrusted). Null means the display text is spoken whole.
     */
    data class Answer(val text: String, val spoken: String? = null) : AssistantReply()

    /**
     * The gateway proposed an action. [payload] is the raw object, checked
     * for shape only: mapping it onto the intent union is strict validation,
     * and that happens where the intents live, not in a transport client.
     */
    data class Action(val payload: Map<String, Any?>) : AssistantReply()

    /**
     * The gateway's one slot is held by other work. Keep the question and
     * offer retry; this is not a failure of anything on the phone.
     */
    data object Busy : AssistantReply()

    /**
     * There is no answer to show. [detail] is the one line of cause, kept for
     * diagnostics rather than composed for the screen.
     */
    data class Unavailable(val detail: String) : AssistantReply()
}
