package maiatunnel

import (
	"bufio"
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/tailscale/tailcat"
)

// Agent is an HTTP client whose connections go through the tunnel instead of
// through a loopback port.
//
// A Peer forward binds 127.0.0.1:<port> so that a foreign app, Termius above
// all, can dial it. That is the right shape for a port other software owns and
// the wrong shape here: on Android loopback is device-wide, so a bound port is
// a port every other app on the phone may open, and the agent behind it runs
// shell commands. When the app is both ends of the conversation there is no
// reason to pass through the loopback namespace at all, so this type dials the
// peer directly and hands the connection to net/http.
//
// Everything HTTP therefore stays on the Go side, because gomobile cannot
// marshal a net.Conn or a context. Kotlin sees a status code, a body, and a
// line at a time for a stream.
type Agent struct {
	mu      sync.Mutex
	peer    *Peer
	port    int
	auth    string
	client  *tailcat.Client
	http    *http.Client
	timeout time.Duration
	closed  bool
}

// NewAgent returns an Agent that dials port on the devbox at serverAddr.
//
// It holds its own tailcat client rather than sharing the Peer's, so it works
// whether or not the Peer has been started: an app that only talks to the
// agent registers no forwards at all, and Peer.Start rightly refuses to run
// without any. identity may be nil only against a server with no --allow list.
func NewAgent(identity *Identity, serverAddr string, port int) *Agent {
	a := &Agent{
		peer: NewPeer(identity, serverAddr),
		port: port,
	}
	a.http = &http.Client{
		Transport: &http.Transport{
			DialContext:           a.dial,
			MaxIdleConns:          4,
			IdleConnTimeout:       90 * time.Second,
			ResponseHeaderTimeout: 60 * time.Second,
			// The stream endpoint sends its first frame immediately and then
			// nothing for ten seconds at a time, so response bodies must not
			// be buffered on our side.
			DisableCompression: true,
		},
	}
	return a
}

// SetDebug turns on tailscale's wireguard logging for this agent's client.
// Call before the first request.
func (a *Agent) SetDebug(on bool) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.peer.debug = on
}

// SetBasicAuth stores the credential sent with every request. The server runs
// with OPENCODE_SERVER_PASSWORD set and rejects everything else with 401, and
// with tool permissions open that password is the only lock facing the phone.
//
// It goes in the Authorization header and never in the query string. OpenCode
// accepts ?auth_token= at parity with the header; a credential in a URL lands
// in logs, in crash reports and in referrers, so this type offers no way to
// send one that way.
func (a *Agent) SetBasicAuth(user, password string) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if user == "" && password == "" {
		a.auth = ""
		return
	}
	a.auth = "Basic " + base64.StdEncoding.EncodeToString([]byte(user+":"+password))
}

// SetTimeoutMillis bounds a single Request. Zero, the default, means no bound.
//
// Most calls here answer at once: POST /api/session/{id}/prompt returns a
// SessionInputAdmitted as soon as the server has accepted the instruction, and
// the agent's actual reply arrives on the event stream. A bound is still left
// to the caller rather than fixed here, because the one call that can sit for
// a long time is a cold one through a tunnel that has yet to come up. Streams
// ignore this entirely.
func (a *Agent) SetTimeoutMillis(ms int) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.timeout = time.Duration(ms) * time.Millisecond
}

// NetworkChanged drops the tailcat client built against the old network so
// the next dial rebuilds it against the facts the host has just pushed.
//
// A rebuild is the only poke that reaches the engine. The netmon.Monitor
// each tailcat client constructs is private to it, and on Android that
// monitor polls every ten minutes rather than subscribing to netlink, which
// apps may not read: facts pushed through SetInterfacesJSON and
// SetDefaultRoute sit unread while every dial fails on sockets bound to the
// dead interface. tailscale's own app resolves this by calling
// Monitor.InjectEvent on a monitor it owns; there is no way to reach this
// one, so the client holding it goes instead.
//
// Safe mid-use: a dial holding the dropped client fails the way it would on
// the dead network itself, and the transport's idle conns die with it.
func (a *Agent) NetworkChanged() {
	a.mu.Lock()
	if a.closed || a.client == nil {
		a.mu.Unlock()
		return
	}
	client := a.client
	a.client = nil
	a.mu.Unlock()

	a.http.CloseIdleConnections()
	client.Close()
}

// dial opens one tunnelled TCP connection. The address net/http passes is
// discarded: the destination is the peer's port, fixed at construction, and
// there is no name resolution anywhere in this path.
func (a *Agent) dial(ctx context.Context, _, _ string) (net.Conn, error) {
	a.mu.Lock()
	if a.closed {
		a.mu.Unlock()
		return nil, errors.New("maiatunnel: agent closed")
	}
	if a.client == nil {
		if a.peer.serverAddr == "" {
			a.mu.Unlock()
			return nil, errors.New("maiatunnel: no tailcat address set, pair the devbox first")
		}
		a.client = a.peer.newClient()
	}
	client := a.client
	a.mu.Unlock()

	// Generous for the same reason Peer.handle is: this may be paying for cold
	// tunnel bring-up, measured at 1.2 to 1.8 seconds on a phone and worse on
	// a bad link.
	dctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	conn, err := client.DialTCPPort(dctx, uint16(a.port))
	if err != nil {
		// The mark separates "no conn ever came out of the tunnel" from an
		// exchange that broke after one did, which net/http can describe with
		// the same text: a dial timeout and a stalled response both say
		// "context deadline exceeded". Only the first is a dead tunnel, and a
		// host that cannot tell them apart reports tunnel failures that did
		// not happen. See faultOf in AgentDriver.kt.
		return nil, fmt.Errorf("maiatunnel: tunnel dial: %w", err)
	}
	return conn, nil
}

// Reply is one finished HTTP response. Status is the code, so a caller can
// tell 401 (wrong password) from 404 (no such session) from a transport error,
// which arrives as a Go error instead.
type Reply struct {
	Status int
	Body   string
}

// Request performs one request and reads the whole body. path is everything
// after the host, leading slash included, query string and all.
//
// A non-2xx status is not an error: the body carries the server's explanation
// and the caller decides. Only a failure to complete the exchange is an error.
func (a *Agent) Request(method, path, body string) (*Reply, error) {
	a.mu.Lock()
	auth, timeout := a.auth, a.timeout
	a.mu.Unlock()

	ctx := context.Background()
	if timeout > 0 {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, timeout)
		defer cancel()
	}

	req, err := a.newRequest(ctx, method, path, body, auth)
	if err != nil {
		return nil, err
	}
	resp, err := a.http.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	// Bounded so a wrong port answering with something enormous cannot be an
	// out-of-memory kill on the phone. 8 MB is far above any session payload.
	b, err := io.ReadAll(io.LimitReader(resp.Body, 8<<20))
	if err != nil {
		return nil, err
	}
	return &Reply{Status: resp.StatusCode, Body: string(b)}, nil
}

func (a *Agent) newRequest(ctx context.Context, method, path, body, auth string) (*http.Request, error) {
	if !strings.HasPrefix(path, "/") {
		return nil, errors.New("maiatunnel: path must start with /")
	}
	var r io.Reader
	if body != "" {
		r = strings.NewReader(body)
	}
	// The host is a placeholder. dial never looks at it, and the server does
	// not route on it, but net/http insists on a well-formed URL.
	req, err := http.NewRequestWithContext(ctx, method, "http://agent.invalid"+path, r)
	if err != nil {
		return nil, err
	}
	if body != "" {
		req.Header.Set("Content-Type", "application/json")
	}
	if auth != "" {
		req.Header.Set("Authorization", auth)
	}
	return req, nil
}

// LineSink receives a server-sent-event stream one raw line at a time.
// Assembling lines into frames is deliberately left to the caller: it is pure
// string work, and it is worth having where it can be unit tested without a
// network, a tunnel or a phone.
//
// OnLine is called from a Go goroutine, so a Kotlin implementation must not
// assume the main thread. OnClosed is called exactly once, with an empty
// string for a clean end of stream.
type LineSink interface {
	OnLine(line string)
	OnClosed(reason string)
}

// Stream is one open server-sent-event connection. Close ends it; the sink's
// OnClosed follows.
type Stream struct {
	cancel context.CancelFunc
	once   sync.Once
}

// Close ends the stream. Safe to call twice, and safe to call from any thread.
func (s *Stream) Close() {
	s.once.Do(s.cancel)
}

// Stream opens path and delivers every line to sink until the server ends it
// or Close is called. It returns immediately: the reading happens on its own
// goroutine.
//
// No timeout applies. OpenCode's event stream is meant to stay open for as
// long as the app cares about the agent, and it sends an SSE comment as a
// heartbeat roughly every ten seconds, which is what a caller should watch to
// decide the link is gone.
func (a *Agent) Stream(path string, sink LineSink) *Stream {
	a.mu.Lock()
	auth := a.auth
	a.mu.Unlock()

	ctx, cancel := context.WithCancel(context.Background())
	s := &Stream{cancel: cancel}

	go func() {
		defer cancel()
		req, err := a.newRequest(ctx, "GET", path, "", auth)
		if err != nil {
			sink.OnClosed(err.Error())
			return
		}
		req.Header.Set("Accept", "text/event-stream")
		resp, err := a.http.Do(req)
		if err != nil {
			sink.OnClosed(err.Error())
			return
		}
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			b, _ := io.ReadAll(io.LimitReader(resp.Body, 4<<10))
			sink.OnClosed(http.StatusText(resp.StatusCode) + ": " + strings.TrimSpace(string(b)))
			return
		}

		br := bufio.NewReaderSize(resp.Body, 64<<10)
		for {
			line, err := br.ReadString('\n')
			if line != "" {
				sink.OnLine(strings.TrimRight(line, "\r\n"))
			}
			if err != nil {
				if errors.Is(err, io.EOF) || ctx.Err() != nil {
					sink.OnClosed("")
				} else {
					sink.OnClosed(err.Error())
				}
				return
			}
		}
	}()

	return s
}

// Close releases the tunnel. The Agent is unusable afterwards.
func (a *Agent) Close() error {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.closed = true
	if a.client == nil {
		return nil
	}
	c := a.client
	a.client = nil
	a.http.CloseIdleConnections()
	return c.Close()
}
