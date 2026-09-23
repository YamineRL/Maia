package dev.maia.transport

import java.io.Closeable
import java.net.URLEncoder

/**
 * The OpenCode API, in the handful of calls this feature makes.
 *
 * One server serves every project. Selection is per request, through
 * `?directory=`, which is why there is no notion of connecting to a project
 * here: a [Project] is an argument, not a session with the server. Omitting
 * the directory falls back to the server process's working directory, which
 * `agent-web.service` points at a deliberately empty one so the mistake is
 * loud and harmless rather than quietly running in `$HOME`.
 *
 * Two shapes, learned by exercising the server rather than by reading:
 *
 * The `/api` routes wrap their result in a `data` envelope and the older ones
 * do not. [dataOf] unwraps; [bodyOf] does not. Getting this wrong costs an
 * hour and looks like an empty response.
 *
 * `POST /api/session` takes an optional `agent`, and a session created without
 * one is created without a harness: the request succeeds and the five Fusion
 * agents are simply absent. So [createSession] always names one.
 *
 * It also takes an optional `model`, and this one is not optional in effect:
 * a session created without one gets the server's catalog default, which is
 * OpenCode Zen's free model, not the lead the harness is configured around.
 * The agent's own configured model is never consulted by the session runner.
 * So [createSession] always names that too.
 */
class AgentClient(
    private val channel: AgentChannel,
    /** The harness to run. Never null, for the reason in the class comment. */
    private val defaultAgent: String = "fusion",
    /** The lead model ref, for the reason in the class comment. */
    private val defaultModel: ModelRef = LEAD_MODEL,
) : Closeable {

    /** Creates a session in [directory]. The directory must be absolute. */
    fun createSession(
        directory: String,
        agent: String = defaultAgent,
        model: ModelRef = defaultModel,
    ): Session {
        require(directory.startsWith("/")) { "directory must be absolute: $directory" }
        val body = Json.obj(
            "agent" to agent,
            "model" to mapOf("providerID" to model.providerId, "id" to model.id),
            "location" to mapOf("directory" to directory),
        )
        val data = dataOf(channel.request("POST", "/api/session", body))
        return Session(
            id = data.string("id") ?: throw AgentException(200, "session create returned no id"),
            projectId = data.string("projectID"),
            agent = data.string("agent") ?: agent,
            directory = directory,
        )
    }

    /**
     * Sends an instruction and returns as soon as the server has admitted it.
     *
     * This does not wait for the agent. `POST /api/session/{id}/prompt`
     * answers with `SessionInputAdmitted`, which says the input was accepted
     * and nothing about the work: the reply arrives as
     * `session.next.text.delta` events on the stream, and the turn ends with
     * `session.idle`. A caller that wants the answer subscribes first and
     * prompts second.
     *
     * [delivery] decides what happens to an instruction aimed at an agent that
     * is already working. "steer" interrupts with the new text; "queue" waits
     * for the current turn to end. Null lets the server choose.
     */
    fun prompt(session: Session, text: String, delivery: Delivery? = null): Admitted {
        require(text.isNotBlank()) { "empty prompt" }
        val body = Json.obj(
            "prompt" to mapOf("text" to text),
            "delivery" to delivery?.wire,
        )
        val data = dataOf(channel.request("POST", "/api/session/${session.id}/prompt", body))
        return Admitted(
            messageId = data.string("id") ?: "",
            sessionId = data.string("sessionID") ?: session.id,
            delivery = data.string("delivery") ?: "",
        )
    }

    /** Stops the current turn. Answers 204, so there is nothing to parse. */
    fun interrupt(session: Session) {
        expectOk(channel.request("POST", "/api/session/${session.id}/interrupt"))
    }

    /**
     * Answers a `permission.v2.asked` that stopped an agent.
     *
     * The route is the session-scoped
     * `POST /api/session/{id}/permission/{requestID}/reply`. The session id
     * does the work `?directory=` did on the v1 route: the pending request
     * lives in a map owned by one project instance, and the session scopes
     * the lookup to the instance that holds it. Verified live on this server:
     * a real `external_directory` ask listed through
     * `GET /api/session/{id}/permission`, took `{"reply":"once"}` on this
     * route, and the suspended tool call then completed. The v1
     * `/permission/{id}/reply` route still answers, but it is blind to v2
     * asks: `GET /permission?directory=` returned `[]` while this request was
     * pending, so a reply through it is a reply to nothing.
     *
     * The return value carries the one distinction a phone screen depends on.
     * [ReplyOutcome.ACCEPTED] means the agent has been unblocked.
     * [ReplyOutcome.GONE] means the server answered, and the request is no
     * longer pending: the agent was interrupted, the turn ended, the id was
     * already answered, or the server restarted. A transport failure never
     * returns at all, it throws [java.io.IOException] out of the channel, so
     * "we could not reach the machine" and "the machine no longer cares" are
     * two different code paths and can be two different screens.
     *
     * One ambiguity survives, and it is worth stating rather than hiding: if
     * the reply reaches the server and the response is lost on the way back, a
     * retry of the same id answers 404 and reads as [ReplyOutcome.GONE]
     * when the agent was in fact unblocked. The event stream settles it: a
     * successful reply publishes `permission.v2.replied` carrying this
     * `requestID`, so a caller that was subscribed knows which happened.
     */
    fun replyPermission(
        session: Session,
        requestId: String,
        reply: PermissionReply,
        message: String? = null,
    ): ReplyOutcome {
        val path = "/api/session/${session.id}/permission/$requestId/reply"
        val body = Json.obj("reply" to reply.wire, "message" to message)
        return outcomeOf(channel.request("POST", path, body))
    }

    /**
     * The one reading of an answer to a blocked agent, shared by both paths.
     *
     * 200 means the server took it, 404 means it no longer holds that request,
     * and everything else is ours to fix rather than the user's to read, so it
     * throws. A failure to reach the server never arrives here at all.
     */
    private fun outcomeOf(answer: Reply): ReplyOutcome {
        if (answer.ok) return ReplyOutcome.ACCEPTED
        if (answer.status == 404) return ReplyOutcome.GONE
        expectOk(answer)
        throw AgentException(answer.status, "unreachable")
    }

    /**
     * Lists the permission requests still waiting in [session].
     *
     * This is how a phone that was offline reconciles, and it is the only way:
     * the server publishes `permission.v2.asked` when a request appears and
     * `permission.v2.replied` when one is answered, and publishes nothing at
     * all when a request is abandoned because the turn was interrupted or
     * ended. A notification that was missed, or one whose request has since
     * gone stale, leaves no trace on the stream. The pending list does.
     *
     * `GET /api/session/{id}/permission` is scoped to the session, which is
     * what the reconcile path wants: no directory argument, no filtering by
     * [PendingPermission.sessionId]. Verified live on this server against a
     * suspended `external_directory` ask; the v1 `GET /permission` answered
     * `[]` for the same pending request.
     */
    fun pendingPermissions(session: Session): List<PendingPermission> {
        val body = dataOf(channel.request("GET", "/api/session/${session.id}/permission"))
        val rows = body as? List<*> ?: return emptyList()
        return rows.mapNotNull { row ->
            val id = row.string("id") ?: return@mapNotNull null
            PendingPermission(
                id = id,
                sessionId = row.string("sessionID") ?: session.id,
                // The v2 names: `action` is the rule that stopped the agent,
                // `resources` is what was asked for, `save` is what "always"
                // would remember.
                permission = row.string("action") ?: "",
                patterns = row.list("resources").orEmpty().filterIsInstance<String>(),
                always = row.list("save").orEmpty().filterIsInstance<String>(),
            )
        }
    }

    /**
     * Answers a `question.asked` that stopped an agent.
     *
     * A question is not a prompt, and that is the fact with money attached.
     * The agent is sitting inside a tool call: `Question.ask` publishes
     * `question.asked` and then awaits a deferred, so the turn is suspended
     * mid-tool, not ended. This reply resolves that deferred, the `question`
     * tool returns its output, and the same turn carries on. No new user
     * message is created and `POST /api/session/{id}/prompt` is not involved,
     * so answering does not start a turn. It does let the suspended one
     * continue, which costs whatever the rest of that turn costs.
     *
     * Observed end to end on 2026-09-21. The reply is followed by
     * `question.replied` carrying `{sessionID, requestID, answers}`, then the
     * `question` tool part flips from `running` to `completed` with the output
     * `User has answered your questions: "<question>"="<label>". You can now
     * continue with the user's answers in mind.`, then the session goes busy
     * again and a second assistant message is generated under the same user
     * message. So "the same turn continues" is precisely: a new assistant
     * message id for the step after the tool, and no new user message.
     *
     * [answers] is an array per question, in the order of
     * [PendingQuestion.questions], and each one is the list of labels chosen
     * for that question. The server does not check a label against
     * [AskedQuestion.options]: the tool's own description says that when
     * [AskedQuestion.custom] is set a "Type your own answer" choice is added by
     * the client, so an arbitrary sentence is a legal answer and is passed to
     * the model verbatim. That is what makes the dictated answer in
     * `docs/M8-copy.md` section 5.18 possible at all.
     *
     * A question with no entry, or an empty one, is reported to the model as
     * `Unanswered` and the turn carries on regardless. A blank string is not
     * the same thing and is refused here, because it would reach the model as
     * an answer of `""`.
     *
     * The route is the session-scoped
     * `POST /api/session/{id}/question/{requestID}/reply`, for the reason in
     * [replyPermission]: the session id replaces the `?directory=` the v1
     * route needed to find the right project instance. Answers are
     * [ReplyOutcome], exactly as for a permission.
     */
    fun replyQuestion(
        session: Session,
        requestId: String,
        answers: List<List<String>>,
    ): ReplyOutcome {
        require(answers.all { answer -> answer.all(String::isNotBlank) }) { "blank answer" }
        val path = "/api/session/${session.id}/question/$requestId/reply"
        val body = Json.obj("answers" to answers)
        return outcomeOf(channel.request("POST", path, body))
    }

    /**
     * Dismisses a `question.asked` without answering it.
     *
     * This is not the question's version of [PermissionReply.REJECT] and it is
     * not `Stop` either. The server fails the tool call with
     * `QuestionRejectedError` ("The user dismissed this question") and the turn
     * keeps running: the agent is told nobody wanted to answer and goes on.
     * Section 5.18 offers neither this nor a refusal, because `Not now` leaves
     * the agent waiting and `Stop` ends the run. It is named here because it is
     * the only way to free an agent from a question the user will never answer
     * without also throwing away the work the turn has already done.
     */
    fun rejectQuestion(session: Session, requestId: String): ReplyOutcome {
        val path = "/api/session/${session.id}/question/$requestId/reject"
        return outcomeOf(channel.request("POST", path, null))
    }

    /**
     * Lists the questions still waiting in [session].
     *
     * The reconciliation path, for the reason [pendingPermissions] gives: the
     * server publishes `question.v2.asked`, `question.v2.replied` and
     * `question.v2.rejected` and nothing when a question is simply abandoned,
     * so a phone that was offline cannot infer the current state from the
     * stream. Scoped to the session like the permission list.
     */
    fun pendingQuestions(session: Session): List<PendingQuestion> {
        val body = dataOf(channel.request("GET", "/api/session/${session.id}/question"))
        val rows = body as? List<*> ?: return emptyList()
        return rows.mapNotNull { PendingQuestion.from(it) }
    }

    /**
     * Reads the project a directory resolves to.
     *
     * This is the cheapest proof that a path is real and that the server
     * agrees about it, which is worth doing before a prompt goes anywhere: a
     * typo in a directory does not fail, it silently runs somewhere else.
     *
     * `GET /project` looks similar and is not the same thing at all: it lists
     * every project the server has ever seen, so its first element is not the
     * current one. That mistake reported the same project for two different
     * directories and looked exactly like a working answer.
     */
    fun currentProject(directory: String): Project {
        val body = bodyOf(channel.request("GET", "/project/current" + query(directory)))
        return Project(
            id = body.string("id") ?: "",
            worktree = body.string("worktree") ?: "",
            name = body.string("name"),
        )
    }

    /**
     * Opens the event stream for one session, and hands over parsed events.
     *
     * The contracted way to scope a subscription would be
     * `/api/session/{id}/event`, but on the pinned server (opencode 1.18.31)
     * that route accepts the connection and then never writes a byte: no
     * headers, no frames, not even while the session is emitting events that
     * show on the global stream. Verified against the live server
     * 2026-09-21. The silence rule quite correctly reads that as a dead link
     * and cuts the turn, so scoping happens here instead: the global v2
     * stream emits every session's events plus heartbeats, and anything
     * belonging to a different session is dropped before the listener sees
     * it. Session-agnostic events such as `server.connected` carry no
     * `sessionID` and pass through, which is also what a reconnect wants.
     *
     * [after] is accepted for the signature but is inoperative on the wire:
     * the global route takes no resume cursor. What a dropped stream missed
     * comes back through [history] instead, which the caller replays before
     * reopening. This method itself still replays nothing.
     */
    fun sessionEvents(session: Session, listener: EventListener, after: String? = null): Closeable {
        return subscribe("/api/event", object : EventListener {
            override fun onAlive() = listener.onAlive()
            override fun onEvent(event: AgentEvent) {
                val sid = event.sessionId
                if (sid == null || sid == session.id) listener.onEvent(event)
            }
            override fun onClosed(reason: String) = listener.onClosed(reason)
        })
    }

    /**
     * The events a session has already emitted, oldest first.
     *
     * This is the reconnect path's only source for what a dropped stream
     * missed, and it is needed precisely because [sessionEvents] cannot
     * supply it: the global route takes no resume cursor, so a bare
     * resubscribe loses every event emitted between the drop and the new
     * connection. On the pinned server that gap can hold the
     * `session.next.step.ended` a turn's end is inferred from, which is what
     * makes a healthy finished turn look like a stuck one.
     *
     * Entries carry the same `id`/`type`/`data` shape the live frames do,
     * plus a `durable.seq` this does not read: the list is ordered already,
     * and the only ordering the caller needs is position. Rows without a
     * `type` are skipped, the same rule [AgentEvent.from] applies to stream
     * frames: the union grows, and one row this build does not read is no
     * reason to replay nothing at all.
     *
     * `?limit=` is sent because history is the whole session, not the gap:
     * a reconnect near the end of a long one would otherwise pull every
     * delta it ever emitted. The bound bounds whatever tail the server keeps,
     * so a cursor older than the returned window simply matches nothing and
     * the caller replays nothing rather than guessing at a position.
     */
    fun history(session: Session, limit: Int = HISTORY_LIMIT): List<AgentEvent> {
        val path = "/api/session/${session.id}/history?limit=$limit"
        val rows = dataOf(channel.request("GET", path)) as? List<*> ?: return emptyList()
        return rows.mapNotNull { row ->
            val type = row.string("type") ?: return@mapNotNull null
            AgentEvent(id = row.string("id"), type = type, payload = row.at("data"))
        }
    }

    /** Opens the global stream, optionally narrowed to one project directory. */
    fun events(directory: String? = null, listener: EventListener): Closeable =
        subscribe("/event" + (directory?.let { query(it) } ?: ""), listener)

    private fun subscribe(path: String, listener: EventListener): Closeable {
        val assembler = SseAssembler()
        return channel.stream(path, object : LineSink {
            override fun onLine(line: String) {
                // Liveness first, and before any parsing can decide to drop
                // this line. A heartbeat, a frame that parses and a frame that
                // does not are all equally proof the link is alive, and the
                // stall rule cares about nothing else. The blank line that
                // terminates a frame is the one thing that is not evidence of
                // its own: it is already accounted for by the data line ahead
                // of it, and counting it would double every event.
                if (line.isNotEmpty()) listener.onAlive()
                val frame = assembler.line(line) ?: return
                val event = AgentEvent.from(frame) ?: return
                listener.onEvent(event)
            }

            override fun onClosed(reason: String) = listener.onClosed(reason)
        })
    }

    override fun close() = channel.close()

    // ---- envelopes -------------------------------------------------------

    /** Unwraps the `data` envelope the `/api` routes use. */
    private fun dataOf(reply: Reply): Any? {
        expectOk(reply)
        return parse(reply).at("data")
            ?: throw AgentException(reply.status, "no data envelope in: ${reply.body.take(200)}")
    }

    /** For the older routes, which answer with the object itself. */
    private fun bodyOf(reply: Reply): Any? {
        expectOk(reply)
        return parse(reply)
    }

    private fun parse(reply: Reply): Any? =
        runCatching { Json.parse(reply.body) }.getOrElse {
            throw AgentException(reply.status, "unparseable body: ${reply.body.take(200)}")
        }

    private fun expectOk(reply: Reply) {
        if (reply.ok) return
        // 401 is the one worth naming, because it has exactly one cause here
        // and the generic message sends people looking at the tunnel instead.
        val detail = if (reply.status == 401) {
            "wrong or missing passphrase for the agent server"
        } else {
            reply.body.take(400).ifBlank { "no body" }
        }
        throw AgentException(reply.status, detail)
    }

    private fun query(directory: String) = "?directory=" + enc(directory)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private companion object {
        /**
         * The bound on one history fetch.
         *
         * Five hundred events is far more than the seconds-long gap one
         * reconnect can cover, and far less than a session that has been
         * running all day can grow to. It exists to keep a resubscribe from
         * pulling the whole of one, not to locate the cursor: a cursor older
         * than the window matches nothing and replays nothing, which is the
         * same place a fetch without it would have started from.
         */
        const val HISTORY_LIMIT = 500
    }
}

/** A live session on the devbox, and the project it belongs to. */
data class Session(
    val id: String,
    val projectId: String?,
    val agent: String,
    val directory: String,
)

/**
 * A model as the session API names it: `{"providerID","id"}`.
 *
 * The provider id is `mergegw`, not `merge-gateway`. The builtin
 * `merge-gateway` provider is catalogued with a provider package the v2
 * session runner cannot route, so sessions on it fail with
 * `ModelUnavailableError` and the server silently substitutes Zen's free
 * model. `mergegw` is the same gateway declared as a generic
 * OpenAI-compatible provider in `~/.config/opencode/opencode.json` on the
 * devbox, which is the route the runner supports.
 */
data class ModelRef(val providerId: String, val id: String)

/** The lead model the Fusion profile selects, in the form the runner resolves. */
val LEAD_MODEL = ModelRef("mergegw", "zai/glm-5.3")

/** What the server says when it has accepted an instruction. */
data class Admitted(val messageId: String, val sessionId: String, val delivery: String)

/** A project as the server resolved it, which is the check that a path is real. */
data class Project(val id: String, val worktree: String, val name: String?)

/**
 * The three answers a blocked agent accepts. The server's enum, exactly.
 *
 * [ONCE] and [ALWAYS] both unblock the agent; the difference is what happens
 * the next time. [ALWAYS] adds the request's own [PendingPermission.always]
 * patterns to the approved set for the rest of that session, and then
 * silently approves any other request already waiting in the same session that
 * the new rule now covers. It is not a global setting and it does not outlive
 * the session or the server.
 *
 * [ALWAYS] is only meaningfully different from [ONCE] when the request carries
 * a non-empty [PendingPermission.always]: with nothing to remember it unblocks
 * the agent and changes nothing. Both holes left open by `agent-web.sh` do
 * carry one, so the distinction is real for every permission this feature will
 * actually see: `doom_loop` sends the tool name, `external_directory` sends
 * the directory glob.
 *
 * [REJECT] is not the mirror image of the other two. It fails this request and
 * then rejects every other permission pending in the same session, which ends
 * the turn rather than pausing it. An optional message is passed to the agent
 * as feedback instead of a bare refusal.
 */
enum class PermissionReply(val wire: String) {
    ONCE("once"),
    ALWAYS("always"),
    REJECT("reject"),
}

/**
 * What became of an answer, once the server has answered back.
 *
 * Named for the act and not for the thing being answered, because it is the
 * same two answers on both blocking paths: a permission reply and a question
 * reply are two different bodies posted to two different routes that both come
 * back 200 or 404 and mean exactly this. It was `PermissionOutcome` while only
 * one of them existed.
 *
 * Only these two. A reply that never reached the server is not a value here,
 * it is an [java.io.IOException] out of the channel, and keeping it out of
 * this enum is the point: the phone can say "we could not reach your machine"
 * and "your machine had already moved on" without either sentence being a
 * guess.
 */
enum class ReplyOutcome {
    /** The server took it. The agent is unblocked, or rejected, as asked. */
    ACCEPTED,

    /**
     * The server answered 404: that request is no longer pending. The agent
     * was interrupted, the turn ended, the id was already answered, or the
     * server restarted since it asked. The server does not distinguish these
     * and neither do we; to a person waiting at a phone they are one outcome.
     */
    GONE,
}

/**
 * A permission request still waiting for an answer.
 *
 * [always] is the field worth reading before drawing anything: empty means
 * [PermissionReply.ALWAYS] has nothing to remember and offering it is a lie.
 */
data class PendingPermission(
    val id: String,
    val sessionId: String,
    /** The rule that stopped the agent: `doom_loop`, `external_directory`. */
    val permission: String,
    /** What was asked for: a tool name, or a directory glob. */
    val patterns: List<String>,
    /** What [PermissionReply.ALWAYS] would remember. Can be empty. */
    val always: List<String>,
)

/**
 * A question request still waiting for an answer.
 *
 * One request, several questions. The server's `question` tool takes a list
 * and the answers go back as a list in the same order, so this is not a
 * convenience wrapper around a single question and must not be flattened into
 * one: a request carrying two questions answered as if it carried one lines up
 * the second answer against the first question.
 *
 * [from] parses both shapes this arrives in, because they are the same shape:
 * a row of `GET /question`, and the `data` payload of a `question.asked`
 * event. That is why the phone can render a question off the stream without a
 * second round trip.
 */
data class PendingQuestion(
    val id: String,
    val sessionId: String,
    val questions: List<AskedQuestion>,
) {
    companion object {
        /** Returns null for anything that is not a question request. */
        fun from(node: Any?): PendingQuestion? {
            val id = node.string("id") ?: return null
            val rows = node.list("questions") ?: return null
            return PendingQuestion(
                id = id,
                sessionId = node.string("sessionID") ?: "",
                questions = rows.mapNotNull { AskedQuestion.from(it) },
            )
        }
    }
}

/**
 * One question, with the choices the agent offered for it.
 *
 * This is the finding that section 5.18 of `docs/M8-copy.md` has to be read
 * against: `question.asked` is not a free-text prompt. It is a multiple choice
 * with an escape hatch. [question] is the sentence to show, [header] is the
 * agent's own label for it, at most thirty characters, and [options] are the
 * choices. Dictation is possible because of [custom], not instead of the
 * options.
 */
data class AskedQuestion(
    /** The whole question, and the only field guaranteed to be worth reading. */
    val question: String,
    /** The agent's short label for it. At most thirty characters. */
    val header: String,
    /** The choices offered. Each [AnswerOption.label] is a legal answer. */
    val options: List<AnswerOption>,
    /** Whether more than one label may be sent for this question. */
    val multiple: Boolean,
    /**
     * Whether an answer that is not one of the labels is invited.
     *
     * True when the key is absent, which is the server's own default and not a
     * guess: the tool's schema annotates it "Allow typing a custom answer
     * (default: true)". Reading an absent key as false would hide the free-text
     * answer on every question that did not mention it, which is most of them.
     *
     * Absent is the normal case, not the edge case. Observed on the wire on
     * 2026-09-21: a real `question.asked` from a model explicitly told to allow
     * a custom answer carried neither `custom` nor [multiple]. The frame was
     * exactly `{id, sessionID, questions:[{question, header, options:[{label,
     * description}]}], tool:{messageID, callID}}`. The OpenAPI schema agrees,
     * requiring only `question`, `header` and `options` on QuestionInfo. So
     * these two defaults are the only thing standing between the wire and a
     * dictation control that never appears.
     */
    val custom: Boolean,
) {
    companion object {
        fun from(node: Any?): AskedQuestion? {
            val text = node.string("question") ?: return null
            return AskedQuestion(
                question = text,
                header = node.string("header") ?: "",
                options = node.list("options").orEmpty().mapNotNull { AnswerOption.from(it) },
                multiple = node.at("multiple") as? Boolean ?: false,
                custom = node.at("custom") as? Boolean ?: true,
            )
        }
    }
}

/**
 * One choice on a question.
 *
 * [label] is both what is shown and what is sent back: the reply names labels,
 * not indices, so a screen that renders a shortened label and sends the
 * shortened one is answering a different question from the one on the wire.
 */
data class AnswerOption(val label: String, val description: String) {
    companion object {
        fun from(node: Any?): AnswerOption? {
            val label = node.string("label") ?: return null
            return AnswerOption(label, node.string("description") ?: "")
        }
    }
}

/** What to do with an instruction aimed at an agent that is already working. */
enum class Delivery(val wire: String) {
    STEER("steer"),
    QUEUE("queue"),
}

/**
 * What a subscriber to the event stream is told.
 *
 * Three signals, and the split is the point. [onEvent] is the stream's
 * meaning, [onClosed] is its end, and [onAlive] is the evidence that it is
 * still there at all, which is a different question from whether it has said
 * anything worth acting on.
 */
interface EventListener {
    fun onEvent(event: AgentEvent)
    fun onClosed(reason: String)

    /**
     * Something arrived from the server. Anything at all.
     *
     * This is the primitive PRD section 9's stall rule is written in: "no
     * frame of any kind for 90 seconds, not even a heartbeat". A consumer
     * implements that rule by resetting a timer here and nowhere else.
     *
     * It fires for every non-empty line the server sends, which is broader
     * than "a frame" on purpose and covers all three things a stream can
     * deliver: a heartbeat comment, a line of a frame that will parse into an
     * [AgentEvent], and a line of a frame that will not. Those last two used
     * to be swallowed silently here, which is what made the stall rule
     * impossible to implement through this client and sent a second copy of
     * the SSE parser into another module.
     *
     * Fires before the [onEvent] its frame produces, so a listener that does
     * both sees liveness first and can never time out a turn it was in the
     * middle of receiving.
     *
     * It carries no event id, deliberately. The `?after=` resume cursor comes
     * from [AgentEvent.id] in [onEvent], which is the only place an id
     * actually exists: a heartbeat has none, so an id parameter here would be
     * null on most calls and would invite a consumer to overwrite a good
     * cursor with nothing. Liveness and resumption are two concerns and one
     * callback carrying both would corrupt the second to serve the first.
     *
     * Default no-op, so every existing implementation keeps compiling and a
     * consumer that does not care about stalls need not mention it. Called on
     * the stream's reader thread: do no work here beyond noting the time.
     */
    fun onAlive() {}
}
