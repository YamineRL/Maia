#!/usr/bin/env python3
"""Self-contained test for assistant-web.py.

Starts a FAKE llama-server on an ephemeral loopback port plus the real
assistant-web server code in-process on another, then exercises the contract
the phone will rely on: auth, validation, reply parsing, the never-evict
model rule, busy queueing, and in-process serialization.

Nothing here touches the real :8080, the real :4098, or any running service.
Run it:

    tunnel/scripts/assistant-web-test.py

Prints PASS/FAIL per check and exits nonzero if anything failed.
"""

import base64
import http.client
import http.server
import importlib.util
import json
import os
import sys
import tempfile
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))

spec = importlib.util.spec_from_file_location(
    "assistant_web", os.path.join(HERE, "assistant-web.py")
)
aw = importlib.util.module_from_spec(spec)
spec.loader.exec_module(aw)

PASSWORD = "test-passphrase"
LLAMA_KEY = "fake-llama-key-123"


class FakeLlama(http.server.BaseHTTPRequestHandler):
    """A llama-server lookalike. All knobs live on server.state so tests can
    flip them between requests."""

    def _json(self, obj, status=200):
        data = json.dumps(obj).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        st = self.server.state
        if self.path == "/v1/models":
            data = [
                {"id": m, "status": {"value": st["statuses"].get(m, "loaded")}}
                for m in st["resident"]
            ]
            self._json({"object": "list", "data": data})
        elif self.path == "/slots":
            shape = st.get("slots_shape", "normal")
            if shape == "weird":
                # Defensive rule: an unreadable /slots means idle.
                self._json({"unexpected": True})
            else:
                self._json([
                    {
                        "id": 0,
                        "id_task": 1 if st["busy"] else -1,
                        "is_processing": st["busy"],
                    }
                ])
        else:
            self._json({"error": "nope"}, status=404)

    def do_POST(self):
        st = self.server.state
        if self.path != "/v1/chat/completions":
            self._json({"error": "nope"}, status=404)
            return
        length = int(self.headers.get("Content-Length") or 0)
        body = json.loads(self.rfile.read(length).decode("utf-8"))
        with st["lock"]:
            st["requests"].append(body)
            st["auth"].append(self.headers.get("Authorization"))
        if st["chat_status"] != 200:
            self._json({"error": "broken"}, status=st["chat_status"])
            return
        started = time.monotonic()
        if st["gen_sleep"]:
            time.sleep(st["gen_sleep"])
        with st["lock"]:
            st["spans"].append((started, time.monotonic()))
        self._json({
            "choices": [
                {"message": {"role": "assistant", "content": st["content"]}}
            ]
        })

    def log_message(self, *args):
        pass


def start_fake():
    srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), FakeLlama)
    srv.daemon_threads = True
    srv.state = {
        "resident": ["model-A"],
        "statuses": {},
        "busy": False,
        "gen_sleep": 0.0,
        "chat_status": 200,
        "content": '{"type":"answer","text":"hi"}',
        "requests": [],
        "auth": [],
        "spans": [],
        "lock": threading.Lock(),
    }
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


def start_assistant(fake_port, model="model-B", budget=6.0, poll=0.05,
                    key_file=None):
    env = {
        "ASSISTANT_PASSWORD": PASSWORD,
        "LLAMA_URL": "http://127.0.0.1:%d" % fake_port,
        "PORT": "0",
        "ASSISTANT_BUDGET_SECONDS": str(budget),
        "ASSISTANT_POLL_SECONDS": str(poll),
    }
    if model is not None:
        env["ASSISTANT_MODEL"] = model
    if key_file is not None:
        env["LLAMA_KEY_FILE"] = key_file
    cfg = aw.Config(env)
    srv = aw.make_server(cfg)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv, srv.server_address[1]


def call(port, path="/assistant/chat", body=None, token=PASSWORD,
         method="POST", timeout=20, auth=None):
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=timeout)
    headers = {}
    payload = None
    if body is not None:
        payload = json.dumps(body)
        headers["Content-Type"] = "application/json"
    if auth is not None:
        headers["Authorization"] = auth
    elif token is not None:
        headers["Authorization"] = "Bearer " + token
    conn.request(method, path, body=payload, headers=headers)
    resp = conn.getresponse()
    raw = resp.read()
    conn.close()
    try:
        return resp.status, json.loads(raw)
    except ValueError:
        return resp.status, {}


FAILURES = []


def check(name, cond, detail=""):
    if cond:
        print("PASS %s" % name)
    else:
        print("FAIL %s  %s" % (name, detail))
        FAILURES.append(name)


def main():
    key_file = os.path.join(tempfile.mkdtemp(prefix="assistant-test-"), "api-key")
    with open(key_file, "w", encoding="utf-8") as handle:
        handle.write(LLAMA_KEY + "\n")
    os.chmod(key_file, 0o600)

    fake = start_fake()
    fake_port = fake.server_address[1]
    srv, port = start_assistant(fake_port, key_file=key_file)

    # --- auth and routing -------------------------------------------------
    st, body = call(port, token=None)
    check("401 without bearer", st == 401 and body.get("error") == "unauthorized",
          "got %d %r" % (st, body))
    st, body = call(port, token="wrong")
    check("401 wrong bearer", st == 401, "got %d" % st)
    # The phone's channel can only send Basic: maiatunnel has SetBasicAuth and
    # nothing else. The gateway accepts the password half of the pair.
    basic = "Basic " + base64.b64encode(b"maia:" + PASSWORD.encode()).decode()
    st, body = call(port, auth=basic, body={"utterance": "hi", "history": []})
    check("basic auth accepted", st == 200 and body.get("ok") is True,
          "got %d %r" % (st, body))
    bad_basic = "Basic " + base64.b64encode(b"maia:wrong").decode()
    st, _ = call(port, auth=bad_basic, body={"utterance": "hi"})
    check("401 wrong basic", st == 401, "got %d" % st)
    st, _ = call(port, auth="Basic !!!not-base64!!!", body={"utterance": "hi"})
    check("401 malformed basic", st == 401, "got %d" % st)
    st, _ = call(port, path="/nope", method="GET", token=None)
    check("404 unknown GET path", st == 404)
    st, _ = call(port, path="/nope")
    check("404 unknown POST path", st == 404)
    st, _ = call(port, method="GET")
    check("405 GET on chat route", st == 405)
    st, _ = call(port, path="/healthz")
    check("405 POST on healthz", st == 405)
    st, body = call(port, path="/healthz", method="GET", token=None)
    check("healthz unauthenticated", st == 200 and body.get("ok") is True,
          "got %d %r" % (st, body))

    # --- validation -------------------------------------------------------
    bad_utterances = [
        ("missing", {}),
        ("non-string", {"utterance": 42}),
        ("blank", {"utterance": "   "}),
        ("overlong", {"utterance": "x" * 801}),
        ("non-object body", [1, 2]),
    ]
    for name, payload in bad_utterances:
        st, _ = call(port, body=payload)
        check("400 utterance %s" % name, st == 400, "got %d" % st)

    bad_histories = [
        ("not a list", {"utterance": "hi", "history": "yes"}),
        ("bad role", {"utterance": "hi",
                      "history": [{"role": "system", "text": "x"}]}),
        ("missing text", {"utterance": "hi", "history": [{"role": "user"}]}),
        ("blank text", {"utterance": "hi",
                        "history": [{"role": "user", "text": " "}]}),
        ("over six pairs", {"utterance": "hi", "history": [
            {"role": "user" if i % 2 == 0 else "assistant", "text": "t"}
            for i in range(13)]}),
    ]
    for name, payload in bad_histories:
        st, _ = call(port, body=payload)
        check("400 history %s" % name, st == 400, "got %d" % st)

    # --- happy path ---------------------------------------------------------
    st, body = call(port, body={"utterance": "why is the sky blue"})
    check("answer passes through",
          st == 200 and body.get("ok") is True
          and body["reply"].get("type") == "answer"
          and body["reply"].get("text") == "hi",
          "got %d %r" % (st, body))

    fake.state["content"] = '{"type":"action","action":"timer","duration_seconds":720}'
    st, body = call(port, body={"utterance": "timer for twelve minutes"})
    check("action passes through",
          st == 200 and body["reply"].get("type") == "action"
          and body["reply"].get("action") == "timer"
          and body["reply"].get("duration_seconds") == 720,
          "got %d %r" % (st, body))

    fake.state["content"] = '```json\n{"type":"answer","text":"fenced"}\n```'
    st, body = call(port, body={"utterance": "hi"})
    check("fenced JSON parses",
          st == 200 and body["reply"].get("type") == "answer"
          and body["reply"].get("text") == "fenced",
          "got %d %r" % (st, body))

    fake.state["content"] = "I think the answer is probably something."
    st, body = call(port, body={"utterance": "hi"})
    check("garbage degrades to answer",
          st == 200 and body.get("ok") is True
          and body["reply"].get("type") == "answer"
          and "probably" in body["reply"].get("text", ""),
          "got %d %r" % (st, body))

    # --- the never-evict rule ----------------------------------------------
    fake.state["content"] = '{"type":"answer","text":"ok"}'
    fake.state["resident"] = ["model-A"]
    fake.state["requests"] = []
    st, _ = call(port, body={"utterance": "hi"})
    check("resident model wins over ASSISTANT_MODEL",
          st == 200
          and fake.state["requests"][-1].get("model") == "model-A",
          "forwarded %r" % (fake.state["requests"][-1].get("model"),))

    check("llama key sent to llama",
          fake.state["auth"][-1] == "Bearer " + LLAMA_KEY,
          "got %r" % (fake.state["auth"][-1],))

    # A sleeping or loading model still holds the one model slot: naming
    # ASSISTANT_MODEL over it would autoload and evict it, which is exactly
    # the rule this gateway exists for. Both statuses must count as resident.
    for asleep in ("sleeping", "loading"):
        fake.state["statuses"] = {"model-A": asleep}
        fake.state["requests"] = []
        st, _ = call(port, body={"utterance": "hi"})
        check("%s model counts as resident" % asleep,
              st == 200
              and fake.state["requests"][-1].get("model") == "model-A",
              "forwarded %r" % (fake.state["requests"][-1].get("model"),))
    fake.state["statuses"] = {}

    fake.state["resident"] = []
    fake.state["requests"] = []
    st, _ = call(port, body={"utterance": "hi"})
    check("ASSISTANT_MODEL used when nothing resident",
          st == 200
          and fake.state["requests"][-1].get("model") == "model-B",
          "forwarded %r" % (fake.state["requests"][-1].get("model"),))

    srv2, port2 = start_assistant(fake_port, model=None, key_file=key_file)
    st, body = call(port2, body={"utterance": "hi"})
    check("503 no_model when nothing resident and no default",
          st == 503 and body.get("error") == "no_model",
          "got %d %r" % (st, body))
    srv2.shutdown()

    # --- busy queue ---------------------------------------------------------
    fake.state["resident"] = ["model-A"]
    fake.state["busy"] = True
    srv3, port3 = start_assistant(fake_port, budget=1.0, poll=0.05,
                                  key_file=key_file)
    started = time.monotonic()
    st, body = call(port3, body={"utterance": "hi"})
    elapsed = time.monotonic() - started
    check("busy slot answers 503 busy inside budget",
          st == 503 and body.get("error") == "busy" and elapsed < 4.0,
          "got %d %r after %.2fs" % (st, body, elapsed))
    fake.state["busy"] = False

    # --- unreadable /slots means idle ----------------------------------------
    fake.state["slots_shape"] = "weird"
    st, _ = call(port3, body={"utterance": "hi"})
    check("unparseable /slots treated as idle", st == 200, "got %d" % st)
    fake.state["slots_shape"] = "normal"
    srv3.shutdown()

    # --- in-process serialization --------------------------------------------
    fake.state["gen_sleep"] = 0.4
    fake.state["spans"] = []
    results = []

    def one():
        results.append(call(port, body={"utterance": "hi"})[0])

    t1 = threading.Thread(target=one)
    t2 = threading.Thread(target=one)
    t1.start()
    t2.start()
    t1.join()
    t2.join()
    spans = sorted(fake.state["spans"])
    serialized = (
        len(spans) == 2
        and spans[1][0] >= spans[0][1] - 0.001
    )
    check("concurrent requests serialize at the worker",
          results == [200, 200] and serialized,
          "results=%r spans=%r" % (results, spans))
    fake.state["gen_sleep"] = 0.0

    # --- llama failure -------------------------------------------------------
    fake.state["chat_status"] = 500
    st, body = call(port, body={"utterance": "hi"})
    check("llama 5xx answers 503 unavailable",
          st == 503 and body.get("error") == "unavailable",
          "got %d %r" % (st, body))
    fake.state["chat_status"] = 200

    srv4, port4 = start_assistant(fake_port, budget=0.8, poll=0.05,
                                  key_file=key_file)
    fake.state["gen_sleep"] = 3.0
    st, body = call(port4, body={"utterance": "hi"})
    check("llama timeout answers 503 unavailable",
          st == 503 and body.get("error") == "unavailable",
          "got %d %r" % (st, body))
    fake.state["gen_sleep"] = 0.0
    srv4.shutdown()

    # --- deployment wiring -------------------------------------------------
    # The gateway is only reachable if the tailcat unit forwards its port. A
    # repo-side test cannot see the deployed unit, but it can pin the file the
    # unit is installed from: 22 (SSH), 4096 (agent-web), 4097 (registry) and
    # 4098 (this gateway) must all be on the one ExecStart forward list.
    svc = os.path.join(HERE, "..", "deploy", "tailcat-phone.service")
    with open(svc, encoding="utf-8") as handle:
        svc_text = handle.read()
    exec_start = [line for line in svc_text.splitlines()
                  if line.startswith("ExecStart=")]
    check("tailcat unit has one ExecStart", len(exec_start) == 1,
          "got %d" % len(exec_start))
    forwarded = exec_start[0].rsplit(" ", 1)[-1].split(",") if exec_start else []
    check("tailcat forwards 22,4096,4097,4098",
          forwarded == ["22", "4096", "4097", "4098"],
          "got %r" % forwarded)

    srv.shutdown()
    fake.shutdown()

    print()
    if FAILURES:
        print("FAILED: %d check(s): %s" % (len(FAILURES), ", ".join(FAILURES)))
        return 1
    print("All checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
