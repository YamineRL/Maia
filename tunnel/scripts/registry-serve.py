#!/usr/bin/env python3
"""Serve ~/.config/maia/projects.json on 127.0.0.1:4097, and nothing else.

The phone needs the project registry over the tunnel. PRD section 8 said it
would arrive through OpenCode's `GET /file/content`, and on 2026-09-18 that
route was found to be broken in opencode 1.18.31: a path that exists answers
500, a path that does not exist answers 200 with an empty body, so the failure
mode is inverted and an empty 200 can never be trusted. The whole file family
fails the same way, from a shared location resolver, before any disk access.
It is a defect in the server build and the server is in use, so the registry
needs a carrier that does not depend on it. This is that carrier.

What it is, deliberately:

  - One route. `GET /projects.json` and nothing else. The path is compared
    literally against one string, so there is no directory to escape from and
    no traversal to defend against: `../../etc/passwd` is simply not that
    string, and gets the same 404 as any other typo.
  - No write path. GET and HEAD are the only methods with an implementation.
    Every other verb answers 405 without reading a request body.
  - Read-only to the world, but not frozen at boot. Each request refreshes the
    registry first (see below), because a registry that cannot show a project
    created this morning is a registry nobody trusts, and the failure is
    silent: the phone shows a short list and looks correct.

Refresh, and why it is throttled:

  A new project only appears if something runs maia-projects.sh, so this
  server runs it, rather than re-reading a file that no longer matches the
  disk. That script is idempotent and allocates numbers monotonically, so
  running it often is safe; it is not free, though, being a directory scan and
  three jq invocations. MIN_REFRESH_SECONDS collapses a burst of requests onto
  one regeneration, which keeps a warm fetch inside the PRD section 13 budget
  and means no caller can turn this endpoint into a fork bomb.

  If regeneration fails and a registry is already on disk, the stale file is
  served and the failure is logged. A stale registry is the answer PRD
  section 9 wants: numbers that were allocated yesterday are still correct
  today, and the one thing missing is a project created since. If there is no
  file at all, that is a 503 naming the cause, never an empty project list.

Auth: none, on purpose.

  Port 4096 wears basic auth because that port is an agent with every tool
  pre-approved: reaching it is arbitrary code execution on this machine, and
  the password is the only lock in front of it. None of that reasoning carries
  over here. This process runs no commands, opens no session and writes
  nothing a caller can influence: the single byte stream it can emit is a file
  that is already mode 644 on this disk, listing directory names that any
  agent response would disclose anyway.

  Two locks it does have, and they are the ones that matter: it binds
  127.0.0.1 only, so nothing off this machine can reach it except through the
  tunnel, and the tunnel's `--allow=nodekey:` pins one client by key while the
  tailcat address embeds the pre-shared key. Adding a third credential would
  not add a third lock. It would add a second place the passphrase lives, a
  second thing to rotate in step with scripts/new-agent-password.sh, and a 401
  on the phone that looks identical to the agent's 401 while meaning something
  different. Worst of all it would read as permission to bind this on a real
  interface some day, on the grounds that it is authenticated now.

  What that costs is worth stating plainly: any local process on this box can
  read the registry over 127.0.0.1:4097. It could already read the file.

Usage:
    tunnel/scripts/registry-serve.py                 # 127.0.0.1:4097
    MAIA_REGISTRY_PORT=4198 tunnel/scripts/registry-serve.py
"""

import http.server
import os
import subprocess
import sys
import threading
import time

HOST = os.environ.get("MAIA_REGISTRY_HOST", "127.0.0.1")
PORT = int(os.environ.get("MAIA_REGISTRY_PORT", "4097"))

HOME = os.path.expanduser("~")
REGISTRY = os.environ.get("MAIA_PROJECTS_FILE", os.path.join(HOME, ".config/maia/projects.json"))
GENERATOR = os.environ.get(
    "MAIA_PROJECTS_SCRIPT",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "maia-projects.sh"),
)

# The one route. Compared literally, so it is also the whole of the security
# model for paths.
ROUTE = "/projects.json"

# A burst of requests regenerates once. Long enough that repeated fetches are a
# file read, short enough that a project created a minute ago is already there.
MIN_REFRESH_SECONDS = float(os.environ.get("MAIA_REGISTRY_MIN_REFRESH", "10"))

# Regeneration is a directory scan and three jq runs. If it has not finished in
# this long, something is wrong with the box, and a stale file beats a hung
# phone request.
REFRESH_TIMEOUT_SECONDS = 20

# A refused write still has its body drained, so the connection stays in sync.
# Past this, the connection is closed instead: nothing here wants the bytes.
MAX_DRAIN_BYTES = 1 << 20

_refresh_lock = threading.Lock()
_last_refresh = 0.0


def log(message):
    print("registry-serve: " + message, file=sys.stderr, flush=True)


def refresh():
    """Regenerate the registry, at most once per MIN_REFRESH_SECONDS.

    Returns None on success, or a one-line reason. A reason is not necessarily
    fatal: the caller serves whatever is on disk and only fails if there is
    nothing there.
    """
    global _last_refresh
    with _refresh_lock:
        if time.monotonic() - _last_refresh < MIN_REFRESH_SECONDS:
            return None
        try:
            done = subprocess.run(
                [GENERATOR],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                timeout=REFRESH_TIMEOUT_SECONDS,
            )
        except FileNotFoundError:
            return "no generator at " + GENERATOR
        except subprocess.TimeoutExpired:
            return "the generator did not finish in %ds" % REFRESH_TIMEOUT_SECONDS
        except OSError as exc:
            return "cannot run the generator: %s" % exc

        # Only a clean run counts as a refresh. A failing one is retried on the
        # next request rather than being throttled out for ten seconds.
        if done.returncode != 0:
            detail = done.stdout.decode("utf-8", "replace").strip().splitlines()
            return "the generator exited %d: %s" % (
                done.returncode,
                detail[-1] if detail else "no output",
            )
        _last_refresh = time.monotonic()
        return None


class Handler(http.server.BaseHTTPRequestHandler):
    server_version = "maia-registry/1"
    sys_version = ""
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        self._serve(with_body=True)

    def do_HEAD(self):
        self._serve(with_body=False)

    # Every other verb, including the ones that would imply a write. Answered
    # without touching the request body: there is nothing here to send one to.
    def do_POST(self):
        self._refuse_method()

    def do_PUT(self):
        self._refuse_method()

    def do_PATCH(self):
        self._refuse_method()

    def do_DELETE(self):
        self._refuse_method()

    def _refuse_method(self):
        # Drain whatever was sent before answering, then close.
        #
        # Refusing a write without reading its body leaves those bytes in the
        # socket, and on a keep-alive connection the next parse reads "x=1" as
        # a request line: a 400 nobody sent, followed by a BrokenPipeError
        # traceback in the journal once the client has gone. The body is
        # discarded either way. This is only about not leaving the connection
        # lying about where it is.
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = -1
        if 0 < length <= MAX_DRAIN_BYTES:
            remaining = length
            while remaining > 0:
                chunk = self.rfile.read(min(remaining, 65536))
                if not chunk:
                    # The client hung up mid-body. Nothing left to sync to.
                    self.close_connection = True
                    break
                remaining -= len(chunk)
        elif length != 0:
            # Chunked, malformed, or larger than this endpoint will ever read.
            # There is no honest way to find the next request boundary, and
            # reading an unbounded body to look for one is how a read-only
            # endpoint gets pinned on memory it never wanted. Close instead.
            self.close_connection = True

        self._text(405, "this endpoint is read-only: GET %s\n" % ROUTE, allow="GET, HEAD")

    def _serve(self, with_body):
        # A query string is ignored rather than refused, so a caller that adds
        # a cache-buster is not punished for it.
        path = self.path.split("?", 1)[0]
        if path != ROUTE:
            self._text(404, "this endpoint serves only GET %s\n" % ROUTE, with_body=with_body)
            return

        failure = refresh()
        if failure:
            log("refresh failed: " + failure)

        try:
            with open(REGISTRY, "rb") as handle:
                body = handle.read()
        except OSError as exc:
            # No registry at all. Never an empty document: on the phone an
            # empty project list is indistinguishable from having lost every
            # project, and this is the moment that distinction is made.
            reason = failure or str(exc)
            self._text(503, "no registry to serve: %s\n" % reason, with_body=with_body)
            return

        if not body.strip():
            self._text(503, "the registry file is empty\n", with_body=with_body)
            return

        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        # The registry changes under the phone by design, so nothing between
        # here and it may decide it already knows the answer.
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if with_body:
            self.wfile.write(body)

    def _text(self, status, message, with_body=True, allow=None):
        payload = message.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        if allow:
            self.send_header("Allow", allow)
        if self.close_connection:
            self.send_header("Connection", "close")
        self.end_headers()
        if with_body:
            self.wfile.write(payload)

    def log_message(self, fmt, *args):
        # The default writes a line per request to stderr with a timestamp
        # systemd would only add again. Keep the line, drop the timestamp.
        log(fmt % args)


def main():
    server = http.server.ThreadingHTTPServer((HOST, PORT), Handler)
    server.daemon_threads = True
    log("serving %s at http://%s:%d%s" % (REGISTRY, HOST, PORT, ROUTE))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
