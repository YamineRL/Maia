#!/usr/bin/env python3
"""The phone's conversational gateway, on 127.0.0.1:4098.

M9 (docs/M9-conversation-prd.md section 8) gives open questions a devbox route
that is not OpenCode: a loopback-only service the phone reaches through the
tailcat forward in deploy/tailcat-phone.service, holding the llama-server API
key so the phone never has to. It exposes no filesystem, shell, MCP, browser,
or development tools. Its whole vocabulary is one POST plus a health check.

Port. 4097 was the natural neighbour of the agent's 4096 and is already taken:
it is the M8 project registry (deploy/registry-web.service), live on this box.
This service takes 4098, still below the ephemeral floor of 32768 so the
kernel can never hand it out between a reboot and the unit starting.

Auth. ASSISTANT_PASSWORD on the one POST route, accepted as `Bearer <pass>`
or as the password half of a Basic pair (any user): the phone's maiatunnel
channel can only attach Basic, and the token is the same credential either
way. GET /healthz is the only unauthenticated endpoint. Like
scripts/agent-web.sh, no password means
no server: on Android loopback is device-wide, so once tailcat forwards this
port it is reachable by every app on the paired phone, and the password is
the only lock facing the phone itself. The credential file is mode 600 and
is read once at startup.

What a request costs. llama-server runs --models-max 1 --models-autoload
--parallel 1: one inference slot and one resident model, shared with real
work such as OpenCode sessions. Two rules follow, and both are enforced here
rather than trusted to the caller:

  - Never name a model that is not resident while a different model is
    resident. Naming one can autoload it, evicting the resident model and
    killing its generation. Before dispatching, GET /v1/models and submit
    only to the resident id. Only when nothing is resident may
    ASSISTANT_MODEL be named: loading fresh is acceptable, evicting is not.
    The lookup re-runs on every poll, so a model that changed while this
    request queued is re-evaluated rather than named from stale state.

  - Queue behind existing generation. GET /slots exposes per-slot
    processing state; a busy slot means someone else's tokens are flowing.
    This service re-polls until the slot frees or the request's budget
    expires, then answers busy rather than preempting. Requests are also
    serialized in-process: one FIFO worker, so a burst of phone requests
    cannot interleave at llama-server.

The model contract, enforced by the prompt below and validated again on the
phone: strict JSON, either {"type":"answer","text":"..."} or
{"type":"action","action":"<kind>",...} from a closed list of kinds. A reply
that does not parse degrades to a shown answer instead of failing: the phone
always has something to display.

Logging: request sizes and statuses only. Utterances, history, model ids, and
model output never touch the journal.

Config (environment):
    ASSISTANT_PASSWORD        required; no password, no server
    LLAMA_URL                 default http://127.0.0.1:8080
    LLAMA_KEY_FILE            default ~/.config/llama-server/api-key
    ASSISTANT_MODEL           model to load when nothing is resident
    PORT                      default 4098
    ASSISTANT_ENV_FILE        KEY=VALUE credential file, default
                              ~/.config/maia/assistant.env, mode 600
    ASSISTANT_BUDGET_SECONDS  total request budget, default 75
    ASSISTANT_POLL_SECONDS    busy re-poll interval, default 0.35

Usage:
    tunnel/scripts/assistant-web.py            # 127.0.0.1:4098
    PORT=4199 tunnel/scripts/assistant-web.py
"""

import base64
import hmac
import http.server
import json
import os
import queue
import stat
import sys
import threading
import time
import urllib.error
import urllib.request

HOST = "127.0.0.1"
ROUTE = "/assistant/chat"
HEALTH = "/healthz"

# A request body is an utterance plus at most six turn pairs. Anything beyond
# this cap is not a conversation, and reading it anyway is how a gateway gets
# pinned on memory it never wanted.
MAX_BODY_BYTES = 64 << 10
MAX_UTTERANCE_CHARS = 800
MAX_HISTORY_ENTRIES = 12
MAX_HISTORY_ENTRY_CHARS = 4000

# When the model output is not usable JSON, the phone still gets an answer to
# show. This is the most of the raw text it may see.
DEGRADED_ANSWER_CHARS = 500

SYSTEM_PROMPT = """\
You are Maia, the conversational layer of a phone assistant. The user speaks
short requests; you either answer briefly or propose one phone action.

Reply with STRICT JSON only. Plain JSON, no markdown fences, no commentary.
Emit exactly one of two shapes:

  {"type":"answer","text":"<full concise answer>","spoken":"<one or two sentences>"}
  {"type":"action","action":"<kind>",...fields...}

For answers, "text" is what the screen shows (80 words or fewer, plain prose)
and "spoken" is what the voice says: at most two short sentences, and it may
be omitted when it would be the same words as "text".

Allowed action kinds and their fields:

  {"type":"action","action":"timer","duration_seconds":<integer>}
  {"type":"action","action":"alarm","hour":<0-23>,"minute":<0-59>,"label":"<optional string>"}
  {"type":"action","action":"calculate","expression":"<arithmetic expression>"}
  {"type":"action","action":"open_settings","panel":"wifi"|"bluetooth"|"main"}
  {"type":"action","action":"dial","number":"<digits or contact name>"}
  {"type":"action","action":"message","to":"<digits or contact name>","body":"<text>"}
  {"type":"action","action":"navigate","destination":"<place or address>"}
  {"type":"action","action":"open_web","target":"<url or search query>"}
  {"type":"action","action":"open_app","name":"<app name>"}
  {"type":"action","action":"media","command":"play"|"pause"|"next"|"previous"|"volume_up"|"volume_down"}

Rules:
  - Prefer {"type":"answer"} whenever you are unsure which action applies.
  - Keep answer text at 80 words or fewer, plain prose for a small screen.
  - Never emit any other JSON shape. Never invent action kinds or fields.
  - You have no tools and no live data. For current facts such as weather,
    scores, news, prices, or opening hours, answer that live information is
    not connected rather than guessing.
"""


def log(message):
    print("assistant-web: " + message, file=sys.stderr, flush=True)


class Config:
    """Everything the service needs, in one object the tests can build by hand."""

    def __init__(self, env):
        self.password = env.get("ASSISTANT_PASSWORD", "")
        self.llama_url = env.get("LLAMA_URL", "http://127.0.0.1:8080").rstrip("/")
        self.llama_key_file = env.get(
            "LLAMA_KEY_FILE",
            os.path.join(os.path.expanduser("~"), ".config/llama-server/api-key"),
        )
        self.model = env.get("ASSISTANT_MODEL", "")
        self.port = int(env.get("PORT", "4098"))
        self.budget_seconds = float(env.get("ASSISTANT_BUDGET_SECONDS", "75"))
        self.poll_seconds = float(env.get("ASSISTANT_POLL_SECONDS", "0.35"))


class LlamaError(Exception):
    """Any llama-server failure the request cannot continue through."""


def llama_key(cfg):
    try:
        with open(cfg.llama_key_file, "r", encoding="utf-8") as handle:
            return handle.read().strip()
    except OSError:
        return ""


def llama_request(cfg, path, body=None, timeout=10.0):
    """One round trip to llama-server. Raises LlamaError on any failure; the
    response body is returned parsed and is never logged."""
    data = None
    headers = {}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    key = llama_key(cfg)
    if key:
        headers["Authorization"] = "Bearer " + key
    request = urllib.request.Request(
        cfg.llama_url + path,
        data=data,
        headers=headers,
        method="POST" if body is not None else "GET",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        # Drain so the keep-alive socket can be reused, then discard. The body
        # is never inspected or logged.
        try:
            exc.read()
        except OSError:
            pass
        raise LlamaError("http %d from %s" % (exc.code, path))
    except (urllib.error.URLError, OSError, ValueError) as exc:
        raise LlamaError("%s: %s" % (path, type(exc).__name__))


def resident_models(cfg):
    """Ids of models llama-server currently has loaded.

    In router mode /v1/models lists the whole catalogue with a per-model
    status; a plain server lists only what is loaded, so a missing status
    field also means resident. Anything else is treated as not resident.

    `sleeping` counts as resident: --sleep-idle-seconds suspends the model,
    it still occupies the one model slot, and a request naming its own id
    wakes it in place. Treating asleep as absent and naming ASSISTANT_MODEL
    instead autoloads over it, which is the eviction this function exists to
    prevent. `loading` counts for the same reason one step earlier: the slot
    is already spoken for.
    """
    payload = llama_request(cfg, "/v1/models", timeout=10.0)
    data = payload.get("data") if isinstance(payload, dict) else None
    resident = []
    if isinstance(data, list):
        for entry in data:
            if not isinstance(entry, dict):
                continue
            status = entry.get("status")
            if isinstance(status, dict):
                value = status.get("value")
            elif isinstance(status, str) or status is None:
                value = status
            else:
                value = None
            # A missing status is the plain-server shape, where listed means
            # loaded. Everything else names its state, and only these three
            # hold or are taking the slot.
            if (value in (None, "loaded", "sleeping", "loading")
                    and isinstance(entry.get("id"), str)):
                resident.append(entry["id"])
    return resident


def llama_busy(cfg):
    """True when any inference slot is processing someone else's work.

    Defensive by policy: a missing endpoint or a response this code cannot
    read means idle, so a llama-server whose /slots shape differs still lets
    Maia requests through rather than queueing forever.
    """
    try:
        payload = llama_request(cfg, "/slots", timeout=5.0)
    except LlamaError:
        return False
    if isinstance(payload, dict):
        slots = payload.get("slots")
    else:
        slots = payload
    if not isinstance(slots, list):
        return False
    for slot in slots:
        if not isinstance(slot, dict):
            continue
        if slot.get("is_processing"):
            return True
        task = slot.get("id_task", slot.get("task_id", -1))
        if isinstance(task, int) and not isinstance(task, bool) and task >= 0:
            return True
    return False


def chat_completion(cfg, model, job, timeout):
    messages = [{"role": "system", "content": SYSTEM_PROMPT}]
    for entry in job.history:
        messages.append({"role": entry["role"], "content": entry["text"]})
    context = job_context(job)
    messages.append({"role": "user", "content": context + job.utterance})
    payload = llama_request(
        cfg,
        "/v1/chat/completions",
        body={
            "model": model,
            "messages": messages,
            "temperature": 0.3,
            "max_tokens": 300,
            "stream": False,
        },
        timeout=timeout,
    )
    try:
        content = payload["choices"][0]["message"]["content"]
    except (KeyError, IndexError, TypeError):
        raise LlamaError("chat response carried no message content")
    if not isinstance(content, str):
        content = str(content)
    return content


def parse_reply(raw):
    """Model output to the reply object the phone is sent.

    A well-formed answer or action passes through untouched. Anything else
    degrades to a shown answer: a parse failure on the phone would look
    identical to the devbox being broken, and it is not.
    """
    text = raw.strip()
    if text.startswith("```"):
        lines = text.split("\n")
        lines = lines[1:]
        if lines and lines[-1].strip() == "```":
            lines = lines[:-1]
        text = "\n".join(lines).strip()
    try:
        obj = json.loads(text)
    except ValueError:
        obj = None
    if isinstance(obj, dict) and obj.get("type") in ("answer", "action"):
        return obj
    return {"type": "answer", "text": raw[:DEGRADED_ANSWER_CHARS]}


def authorised(header, password):
    """Constant-time check of Bearer or Basic against the one credential."""
    if not header:
        return False
    if hmac.compare_digest(header, "Bearer " + password):
        return True
    if header.startswith("Basic "):
        try:
            decoded = base64.b64decode(header[6:], validate=True).decode("utf-8")
        except (ValueError, UnicodeDecodeError):
            return False
        # Any user name is accepted: the password is the whole credential and
        # maiatunnel sends the pair as <user>:<password>.
        supplied = decoded.split(":", 1)[-1]
        return hmac.compare_digest(supplied, password)
    return False


def validate_request(payload):
    """Body to (utterance, history), or ValueError naming nothing sensitive."""
    if not isinstance(payload, dict):
        raise ValueError("body is not a JSON object")
    utterance = payload.get("utterance")
    if (
        not isinstance(utterance, str)
        or not utterance.strip()
        or len(utterance) > MAX_UTTERANCE_CHARS
    ):
        raise ValueError("bad utterance")
    history = payload.get("history")
    if history is None:
        history = []
    if not isinstance(history, list) or len(history) > MAX_HISTORY_ENTRIES:
        raise ValueError("bad history")
    for entry in history:
        if not isinstance(entry, dict) or entry.get("role") not in (
            "user",
            "assistant",
        ):
            raise ValueError("bad history entry")
        text = entry.get("text")
        if (
            not isinstance(text, str)
            or not text.strip()
            or len(text) > MAX_HISTORY_ENTRY_CHARS
        ):
            raise ValueError("bad history entry")
    # Optional context the phone may attach (M9 section 8.3): checked for
    # shape and length, never trusted as anything but a prompt line.
    context = {}
    for key in ("locale", "timezone"):
        value = payload.get(key)
        if value is None:
            continue
        if not isinstance(value, str) or not value.strip() or len(value) > 64:
            raise ValueError("bad %s" % key)
        context[key] = value.strip()
    return utterance, history, context


def job_context(job):
    """The user-message prefix carrying locale facts, or an empty string."""
    parts = [
        "%s: %s" % (key, job.context[key])
        for key in ("timezone", "locale")
        if key in job.context
    ]
    return ("[" + ", ".join(parts) + "] ") if parts else ""


class Job:
    __slots__ = ("utterance", "history", "context", "deadline", "done", "status", "payload")

    def __init__(self, utterance, history, context, budget_seconds):
        self.utterance = utterance
        self.history = history
        self.context = context
        # The budget starts at receipt, not at worker pickup: queueing behind
        # an earlier Maia request spends the same 75 seconds as queueing
        # behind someone else's generation.
        self.deadline = time.monotonic() + budget_seconds
        self.done = threading.Event()
        self.status = 503
        self.payload = {"error": "unavailable"}


def run_job(cfg, job):
    while True:
        remaining = job.deadline - time.monotonic()
        if remaining <= 0:
            return 503, {"error": "busy"}
        try:
            resident = resident_models(cfg)
        except LlamaError as exc:
            log("models lookup failed: %s" % exc)
            return 503, {"error": "unavailable"}
        if resident:
            if len(resident) > 1:
                log(
                    "%d models resident, expected at most 1; using the first"
                    % len(resident)
                )
            model = resident[0]
        elif cfg.model:
            model = cfg.model
        else:
            return 503, {"error": "no_model"}
        if llama_busy(cfg):
            time.sleep(min(cfg.poll_seconds, max(0.05, remaining)))
            continue
        try:
            content = chat_completion(
                cfg, model, job,
                timeout=max(1.0, job.deadline - time.monotonic()),
            )
        except LlamaError as exc:
            log("chat completion failed: %s" % exc)
            return 503, {"error": "unavailable"}
        return 200, {"ok": True, "reply": parse_reply(content)}


def worker_main(cfg, work_q):
    """The one worker. Every Maia request serializes here, so two phones or a
    tapped-twice button cannot interleave at llama-server's single slot."""
    while True:
        job = work_q.get()
        try:
            job.status, job.payload = run_job(cfg, job)
        except Exception as exc:
            # A bug answers 503 like any other failure; the worker itself
            # must never die, because every queued request dies with it.
            log("worker error: %s" % type(exc).__name__)
            job.status, job.payload = 503, {"error": "unavailable"}
        job.done.set()


class Handler(http.server.BaseHTTPRequestHandler):
    server_version = "maia-assistant/1"
    sys_version = ""
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path == HEALTH:
            self._json(200, {"ok": True})
        elif path == ROUTE:
            self._json(405, {"error": "method_not_allowed"}, allow="POST")
        else:
            self._json(404, {"error": "not_found"})

    def do_HEAD(self):
        path = self.path.split("?", 1)[0]
        if path == HEALTH:
            self._json(200, {"ok": True}, with_body=False)
        elif path == ROUTE:
            self._json(405, {"error": "method_not_allowed"}, allow="POST",
                       with_body=False)
        else:
            self._json(404, {"error": "not_found"}, with_body=False)

    def do_POST(self):
        body = self._read_body()
        if body is None:
            return
        path = self.path.split("?", 1)[0]
        if path == HEALTH:
            self._json(405, {"error": "method_not_allowed"}, allow="GET, HEAD")
            return
        if path != ROUTE:
            self._json(404, {"error": "not_found"})
            return
        cfg = self.server.cfg
        if not authorised(self.headers.get("Authorization"), cfg.password):
            self._json(401, {"error": "unauthorized"})
            return
        try:
            payload = json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, ValueError):
            self._json(400, {"error": "bad_request"})
            return
        try:
            utterance, history, context = validate_request(payload)
        except ValueError:
            self._json(400, {"error": "bad_request"})
            return
        job = Job(utterance, history, context, cfg.budget_seconds)
        self.server.work_q.put(job)
        wait = max(0.5, job.deadline - time.monotonic()) + 5.0
        if not job.done.wait(wait):
            self._json(503, {"error": "busy"})
            return
        log(
            "chat: %d utterance chars, %d history entries -> %d"
            % (len(utterance), len(history), job.status)
        )
        self._json(job.status, job.payload)

    def do_PUT(self):
        self._refuse_method()

    def do_PATCH(self):
        self._refuse_method()

    def do_DELETE(self):
        self._refuse_method()

    def _refuse_method(self):
        self._read_body()
        path = self.path.split("?", 1)[0]
        if path == HEALTH:
            allow = "GET, HEAD"
        elif path == ROUTE:
            allow = "POST"
        else:
            allow = None
        self._json(405, {"error": "method_not_allowed"}, allow=allow)

    def _read_body(self):
        """Drain the request body, bounded. Returns the bytes, or None when
        there was no honest way to find the next request boundary and the
        connection has been closed instead. Same reasoning as
        registry-serve.py: unread bytes would resurface as a phantom request
        line on the next keep-alive parse."""
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = -1
        if length == 0 and "chunked" not in (
            self.headers.get("Transfer-Encoding") or ""
        ).lower():
            return b""
        if 0 < length <= MAX_BODY_BYTES:
            return self.rfile.read(length)
        if length > MAX_BODY_BYTES:
            self.close_connection = True
            self._json(400, {"error": "bad_request"})
            return None
        self.close_connection = True
        return None

    def _json(self, status, obj, with_body=True, allow=None):
        payload = json.dumps(obj).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        if allow:
            self.send_header("Allow", allow)
        if self.close_connection:
            self.send_header("Connection", "close")
        self.end_headers()
        if with_body:
            self.wfile.write(payload)

    def log_message(self, fmt, *args):
        # The request line is a path, never a body, so it is safe to keep.
        # The timestamp is dropped because systemd adds its own.
        log(fmt % args)


def load_env_file(path):
    """Source KEY=VALUE lines into os.environ, the agent-web.sh pattern: the
    file must be mode 600 or 400 so the secret stays out of shell history and
    process environments, and it only fills variables not already set."""
    try:
        mode = stat.S_IMODE(os.stat(path).st_mode)
    except OSError:
        return
    if mode not in (0o600, 0o400):
        print(
            "assistant-web: %s is mode %o; needs 600. Run: chmod 600 %s"
            % (path, mode, path),
            file=sys.stderr,
        )
        sys.exit(1)
    try:
        with open(path, "r", encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, _, value = line.partition("=")
                os.environ.setdefault(
                    key.strip(), value.strip().strip('"').strip("'")
                )
    except OSError:
        return


def make_server(cfg, host=HOST):
    server = http.server.ThreadingHTTPServer((host, cfg.port), Handler)
    server.cfg = cfg
    server.work_q = queue.Queue()
    server.daemon_threads = True
    worker = threading.Thread(
        target=worker_main,
        args=(cfg, server.work_q),
        name="assistant-worker",
        daemon=True,
    )
    worker.start()
    return server


def main():
    if not os.environ.get("ASSISTANT_PASSWORD"):
        env_file = os.environ.get(
            "ASSISTANT_ENV_FILE",
            os.path.join(
                os.path.expanduser("~"), ".config/maia/assistant.env"
            ),
        )
        load_env_file(env_file)
    cfg = Config(os.environ)
    if not cfg.password:
        print(
            """\
assistant-web: refusing to start without a password.

Once tailcat forwards this port it is reachable by every app on the paired
phone, and every request spends inference time shared with real work. The
password is the only lock facing the phone.

    mkdir -p ~/.config/maia
    printf 'ASSISTANT_PASSWORD=%s\\n' "$(openssl rand -base64 24)" \\
        > ~/.config/maia/assistant.env
    chmod 600 ~/.config/maia/assistant.env""",
            file=sys.stderr,
        )
        sys.exit(1)
    server = make_server(cfg)
    log("serving %s at http://%s:%d%s" % (ROUTE, HOST, cfg.port, ROUTE))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
