package dev.maia.transport

/**
 * Assembles server-sent-event lines into frames.
 *
 * Written against what the server actually sends, which is not what was
 * written down first. A frame from OpenCode looks like this, and the shape is
 * verified rather than assumed:
 *
 * ```
 * data: {"id":"evt_...","type":"server.connected","data":{}}
 *
 * : heartbeat
 * ```
 *
 * There is no `event:` line, so the type lives inside the JSON payload and not
 * in the SSE envelope, and the payload key is `data` rather than `properties`.
 * A client written against the earlier description parses nothing at all.
 * Heartbeats are SSE comments, roughly every ten seconds, and they are the
 * thing to watch to decide the link is gone: they are surfaced here rather
 * than swallowed, because a stream that goes quiet is indistinguishable from a
 * healthy one otherwise.
 *
 * Not thread safe. Feed it from one reader.
 */
class SseAssembler {

    private val data = StringBuilder()
    private var id: String? = null
    private var event: String? = null

    /**
     * Feeds one line, without its terminator.
     *
     * Returns a frame when a blank line completes one, null otherwise. A blank
     * line with nothing accumulated completes nothing, which is what keeps a
     * run of heartbeats from producing empty frames.
     */
    fun line(raw: String): SseFrame? {
        if (raw.isEmpty()) return flush()
        if (raw.startsWith(":")) {
            comments++
            return null
        }
        val colon = raw.indexOf(':')
        val field = if (colon < 0) raw else raw.substring(0, colon)
        // "Optionally, a single space" after the colon, per the SSE grammar.
        var value = if (colon < 0) "" else raw.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)

        when (field) {
            "data" -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(value)
            }
            "id" -> id = value
            "event" -> event = value
            // "retry" and anything unknown are ignored, per the grammar.
        }
        return null
    }

    /** How many comment lines, heartbeats included, have arrived. */
    var comments: Int = 0
        private set

    private fun flush(): SseFrame? {
        if (data.isEmpty() && id == null && event == null) return null
        val frame = SseFrame(id = id, event = event, data = data.toString())
        data.setLength(0)
        id = null
        event = null
        return frame
    }
}

/** One complete SSE frame. [data] is the raw payload, usually JSON. */
data class SseFrame(val id: String?, val event: String?, val data: String)

/**
 * One event off the agent stream, reduced to what a phone acts on.
 *
 * [type] is the wire string, dotted and lower case: `session.idle`,
 * `permission.asked`, `session.error`, `session.next.text.delta`. Those come
 * from the `V2Event` union in the server's own OpenAPI document, so they are
 * contracted rather than guessed, but no agent has been run against the stream
 * yet, so nothing here has been seen arriving in anger.
 *
 * [payload] is the parsed `data` object, kept whole. This module does not
 * model eighty event types; it hands callers the fields they name.
 */
data class AgentEvent(
    val id: String?,
    val type: String,
    val payload: Any?,
) {
    val sessionId: String? get() = payload.string("sessionID")

    companion object {
        /** Returns null for a frame that is not a JSON object with a type. */
        fun from(frame: SseFrame): AgentEvent? {
            val root = runCatching { Json.parse(frame.data) }.getOrNull() ?: return null
            val type = root.string("type") ?: return null
            return AgentEvent(
                id = root.string("id") ?: frame.id,
                type = type,
                payload = root.at("data"),
            )
        }
    }
}

/**
 * The event types this feature acts on, named once so a typo is a compile
 * error rather than a notification that never fires.
 */
object EventType {
    const val SERVER_CONNECTED = "server.connected"
    const val SESSION_IDLE = "session.idle"
    const val SESSION_ERROR = "session.error"
    const val PERMISSION_ASKED = "permission.asked"
    const val QUESTION_ASKED = "question.asked"

    /**
     * The names the v2 session runner actually emits on `/api/event`.
     *
     * Observed live: a suspended `external_directory` ask publishes
     * `permission.v2.asked` carrying `{id, sessionID, action, resources,
     * save}`, and the answered request publishes `permission.v2.replied`.
     * The undotted `permission.asked` is the v1 event this build does not
     * send for sessions on the v2 API. Both spellings stay in [BLOCKING]:
     * the union grows, and either one wants a human.
     */
    const val PERMISSION_V2_ASKED = "permission.v2.asked"
    const val QUESTION_V2_ASKED = "question.v2.asked"
    const val TODO_UPDATED = "todo.updated"
    const val TEXT_DELTA = "session.next.text.delta"
    const val TEXT_ENDED = "session.next.text.ended"
    const val STEP_FAILED = "session.next.step.failed"

    /**
     * One assistant step ended.
     *
     * The last event a turn emits on this server: `session.idle` is in the
     * `V2Event` union but is never sent on the live stream, so a turn's end
     * has to be inferred and this is the event it is inferred from. It is
     * deliberately not in [TERMINAL]: a step that ends to make a tool call is
     * followed by more work, so the run machine gives the stream a short
     * grace window and only closes the turn if nothing continues it.
     */
    const val STEP_ENDED = "session.next.step.ended"

    /**
     * The agent invoked a tool.
     *
     * In neither [BLOCKING] nor [TERMINAL], and both exclusions are
     * deliberate. It is not terminal: a tool call is the middle of a turn, and
     * treating it as an ending would close a turn that is about to produce
     * most of its output. It is not blocking either, which is the easier
     * mistake to make, because `agent-web.sh` pre-approves every tool and so
     * the overwhelming majority of tool calls need nobody. The two holes left
     * open in that script surface as [PERMISSION_ASKED], which is a separate
     * event and the one that actually wants a human. Reading this as blocking
     * would notify a phone on every file read.
     *
     * It earns a name because it is what a progress display is made of: it is
     * the difference between an agent that looks hung and an agent visibly
     * working through a task.
     */
    const val TOOL_CALLED = "session.next.tool.called"

    /** The ones that mean an agent has stopped and wants a human. */
    val BLOCKING = setOf(PERMISSION_ASKED, QUESTION_ASKED, PERMISSION_V2_ASKED, QUESTION_V2_ASKED)

    /** The ones that end a turn, whether or not it went well. */
    val TERMINAL = setOf(SESSION_IDLE, SESSION_ERROR, STEP_FAILED)
}
