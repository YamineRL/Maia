package dev.maia.app.agent

import dev.maia.transport.AgentEvent
import dev.maia.transport.AskedQuestion
import dev.maia.transport.PendingQuestion
import dev.maia.transport.at
import dev.maia.transport.list
import dev.maia.transport.long
import dev.maia.transport.string

/**
 * Reading the fields off an event, defensively.
 *
 * The event types are contracted, from the `V2Event` union in the server's own
 * OpenAPI document. The field names inside each payload are not: the PRD marks
 * them unmeasured, no agent has been run against this stream in anger, and the
 * union will grow. So every accessor here tries the names in the order they
 * are likely and returns null rather than guessing, and every caller in
 * [reduceRun] treats null as "render nothing" rather than as "render
 * something vaguer". A tool line with an invented target is worse than a tool
 * line with no target, and a plan the user cannot count is worth no line at
 * all (`docs/M8-copy.md` section 1.6).
 *
 * Everything this returns is agent-supplied text. It goes into [RunState],
 * which is drawn, and it never reaches a [RunEffect]: see [AgentAck].
 */
internal object EventPayload {

    /** The text of one `session.next.text.delta`. */
    fun delta(event: AgentEvent): String? {
        val p = event.payload
        val text = p.string("text") ?: p.string("delta") ?: p.string("content")
            ?: p.string("part", "text") ?: p.string("delta", "text")
        return text?.takeIf { it.isNotEmpty() }
    }

    /**
     * One `session.next.tool.called` as a name and a short target.
     *
     * The target is a single short thing and null when nothing usable is
     * there, which renders as `m8_run_tool_line_bare`. It is trimmed to the
     * last path segment because a tool line is one line at 200 percent font
     * scale and an absolute path is not.
     */
    fun tool(event: AgentEvent): Pair<String, String?>? {
        val p = event.payload
        val name = p.string("tool") ?: p.string("name") ?: p.string("tool", "name") ?: return null
        val raw = p.string("path") ?: p.string("filePath") ?: p.string("file_path")
            ?: p.string("command") ?: p.string("pattern")
            ?: p.string("input", "path") ?: p.string("input", "filePath")
            ?: p.string("input", "command") ?: p.string("input", "pattern")
        return name to raw?.let(::shorten)
    }

    /** `todo.updated`, only when it carries counts that can be shown as counts. */
    fun plan(event: AgentEvent): Plan? {
        val p = event.payload
        val items = p.at("todos") as? List<*> ?: p.at("items") as? List<*>
        if (items != null) {
            val total = items.size
            if (total == 0) return null
            val done = items.count { it.string("status") == "completed" || it.string("status") == "done" }
            return Plan(done, total)
        }
        val total = p.long("total")?.toInt() ?: return null
        val done = p.long("completed")?.toInt() ?: p.long("done")?.toInt() ?: return null
        if (total <= 0) return null
        return Plan(done.coerceIn(0, total), total)
    }

    /**
     * The id a `permission.asked` or `question.asked` is answered with.
     *
     * Empty is a valid answer here in the sense that the screen still shows
     * the block: a user who can see an agent is waiting and cannot answer it
     * from the phone is better served than one shown nothing at all.
     */
    fun requestId(event: AgentEvent): String? {
        val p = event.payload
        return p.string("requestID") ?: p.string("requestId") ?: p.string("id")
            ?: p.string("permissionID") ?: p.string("callID")
    }

    /**
     * What [dev.maia.transport.PermissionReply.ALWAYS] would remember.
     *
     * Empty means there is nothing to remember, and section 5.3 then draws no
     * `m8_run_allow_session` control at all: not a greyed one, not a line
     * explaining its absence. Offering to stop asking about a request that
     * carries no rule would be a lie, and the copy would rather the control
     * were simply not there.
     *
     * Two field names, in this order. `always` is the one the server fills
     * with the rule it would keep, and [dev.maia.transport.PendingPermission]
     * says so; `patterns` is what was asked for and is the wider of the two.
     * A v1 `permission.asked` carries both and for the two permissions this
     * feature actually meets, `doom_loop` and `external_directory`, they carry
     * the same thing. Reading `always` first means the control appears exactly
     * when pressing it would change something.
     */
    fun patterns(event: AgentEvent): List<String> {
        val p = event.payload
        val rule = p.list("always")?.filterIsInstance<String>()?.takeIf { it.isNotEmpty() }
        return rule ?: p.list("patterns")?.filterIsInstance<String>().orEmpty()
    }

    /**
     * The questions a `question.asked` carries, or empty for anything else.
     *
     * Parsed by [dev.maia.transport.PendingQuestion.from] rather than by hand,
     * and that is the point: the same function reads a row of `GET /question`
     * and the payload of the event, because they are the same shape. A phone
     * that parsed the event itself would have two readers of one frame and
     * would find out they had drifted on the day a question was reconciled
     * rather than streamed.
     *
     * Empty is a valid answer, exactly as an empty request id is: the block is
     * still shown, because knowing an agent is waiting beats knowing nothing.
     * It simply has no options to draw and no answer to send.
     */
    fun questions(event: AgentEvent): List<AskedQuestion> =
        PendingQuestion.from(event.payload)?.questions.orEmpty()

    /**
     * Whether a `session.next.step.ended` is the turn's last word.
     *
     * The field is `finish`, a model finish reason. `stop` is the model done
     * talking; anything else the server knows (`tool_calls`, `length`) means
     * the turn continues and the next events are already on their way, so
     * only `stop` and a missing field count as maybe-over. Missing counts
     * because a payload that omits the field tells us nothing about whether
     * the turn continues, and the grace window that consumes this answer
     * makes a wrong yes cheap and self-correcting.
     */
    fun stepStopped(event: AgentEvent): Boolean {
        val finish = event.payload.string("finish") ?: return true
        return finish == "stop"
    }

    /** The last segment, and never more than fits a line. */
    private fun shorten(raw: String): String? {
        val one = raw.trim().substringBefore('\n')
        if (one.isEmpty()) return null
        val tail = if (one.contains('/') && !one.contains(' ')) one.substringAfterLast('/') else one
        return if (tail.length <= TARGET_MAX) tail else tail.take(TARGET_MAX - 1) + "…"
    }

    private const val TARGET_MAX = 32
}
