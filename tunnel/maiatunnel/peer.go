// Package maiatunnel exposes tailcat to Android as a set of local port
// forwards, in an API that gomobile can bind.
//
// gomobile only marshals a restricted set of types (string, int, int64, bool,
// []byte, error and pointers to exported structs), so none of tailcat's
// net.Conn / context.Context / netip.AddrPort surface can cross into Kotlin.
// Everything tunnel-shaped therefore stays on the Go side, and Kotlin sees only
// plain loopback ports plus a JSON status blob.
//
// The loopback part is what makes this useful beyond one app: on Android
// 127.0.0.1 is device-wide, not per-app, so a port opened here is reachable by
// any other app on the phone. Bind the devbox's SSH port to 127.0.0.1:2222 and
// an unmodified SSH client, Termius included, connects to it as if the server
// were local. No VpnService, so no system VPN slot, no always-on tunnel and no
// interference with other traffic.
package maiatunnel

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"github.com/tailscale/tailcat"
)

// forward is one local port mapped to a port on the peer. It is deliberately
// unexported: it holds net.Listener and other types gomobile cannot marshal,
// so it reaches Kotlin only through StatusJSON.
type forward struct {
	name       string
	localPort  int
	remotePort int

	ln net.Listener

	openConns  atomic.Int64
	totalConns atomic.Int64
	bytesIn    atomic.Int64
	bytesOut   atomic.Int64

	errMu   sync.Mutex
	lastErr string
}

func (f *forward) setErr(err error) {
	f.errMu.Lock()
	defer f.errMu.Unlock()
	if err == nil {
		f.lastErr = ""
		return
	}
	f.lastErr = err.Error()
}

func (f *forward) getErr() string {
	f.errMu.Lock()
	defer f.errMu.Unlock()
	return f.lastErr
}

// Peer is one devbox: a tailcat address plus the set of ports forwarded to it.
// Start and Stop are the on/off switch the UI drives. Safe for concurrent use.
type Peer struct {
	mu         sync.Mutex
	serverAddr string
	identity   *Identity
	debug      bool
	forwards   []*forward

	client  *tailcat.Client
	running bool

	lastPingMs   int64
	lastPingUnix int64
	lastErr      string
}

// NewPeer returns a Peer for the given tailcat address. The address embeds a
// WireGuard pre-shared key, so treat the whole string as a secret.
//
// identity may be nil, in which case tailcat generates an ephemeral key per
// connection. That only works against a server with no --allow list.
func NewPeer(identity *Identity, serverAddr string) *Peer {
	return &Peer{identity: identity, serverAddr: serverAddr}
}

// SetDebug enables tailscale's wireguard and magicsock logging. Off by default:
// those loggers emit tens of lines per dial and would flood logcat. It is also
// the only way to tell a direct path from a DERP relay, so the app exposes it
// as a developer toggle. Call before Start.
func (p *Peer) SetDebug(on bool) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.debug = on
}

// AddForward registers a port mapping. localPort 0 asks the OS to choose, which
// is fine for an app talking to itself but useless for Termius, which needs a
// port it can be configured with. Call before Start.
func (p *Peer) AddForward(name string, localPort, remotePort int) error {
	p.mu.Lock()
	defer p.mu.Unlock()

	if p.running {
		return errors.New("maiatunnel: stop the peer before changing forwards")
	}
	if remotePort < 1 || remotePort > 65535 {
		return fmt.Errorf("maiatunnel: remote port %d out of range", remotePort)
	}
	if localPort < 0 || localPort > 65535 {
		return fmt.Errorf("maiatunnel: local port %d out of range", localPort)
	}
	for _, f := range p.forwards {
		if localPort != 0 && f.localPort == localPort {
			return fmt.Errorf("maiatunnel: local port %d already used by %q", localPort, f.name)
		}
	}
	p.forwards = append(p.forwards, &forward{
		name:       name,
		localPort:  localPort,
		remotePort: remotePort,
	})
	return nil
}

// ClearForwards drops every registered mapping. Call before Start.
func (p *Peer) ClearForwards() error {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.running {
		return errors.New("maiatunnel: stop the peer before changing forwards")
	}
	p.forwards = nil
	return nil
}

// newClient builds a tailcat client. Callers must hold p.mu.
func (p *Peer) newClient() *tailcat.Client {
	c := tailcat.NewClient(tailcat.Addr(p.serverAddr))
	if p.identity != nil {
		c.Key = p.identity.priv
	}
	if !p.debug {
		c.Logf = func(format string, args ...any) {}
	}
	return c
}

// Start binds every forward's loopback listener and begins accepting. The
// tunnel itself is established lazily by tailcat on the first connection, so
// Start is cheap and touches no network: bring-up latency is paid by whoever
// connects first, or by calling PingMillis to pay it deliberately up front.
func (p *Peer) Start() error {
	p.mu.Lock()
	defer p.mu.Unlock()

	if p.running {
		return nil
	}
	if p.serverAddr == "" {
		return errors.New("maiatunnel: no tailcat address set, pair the devbox first")
	}
	if len(p.forwards) == 0 {
		return errors.New("maiatunnel: no forwards configured")
	}

	client := p.newClient()

	// Bind everything first, so a port clash fails the whole switch-on rather
	// than leaving half the forwards live.
	for _, f := range p.forwards {
		ln, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", f.localPort))
		if err != nil {
			for _, done := range p.forwards {
				if done.ln != nil {
					done.ln.Close()
					done.ln = nil
				}
			}
			client.Close()
			p.lastErr = err.Error()
			return fmt.Errorf("maiatunnel: %s: listen on 127.0.0.1:%d: %w", f.name, f.localPort, err)
		}
		f.ln = ln
		f.localPort = ln.Addr().(*net.TCPAddr).Port
		f.setErr(nil)
	}

	p.client = client
	p.running = true
	p.lastErr = ""

	for _, f := range p.forwards {
		go p.acceptLoop(f, f.ln, client)
	}
	return nil
}

func (p *Peer) acceptLoop(f *forward, ln net.Listener, client *tailcat.Client) {
	for {
		local, err := ln.Accept()
		if err != nil {
			return // listener closed by Stop
		}
		go p.handle(f, local, client)
	}
}

func (p *Peer) handle(f *forward, local net.Conn, client *tailcat.Client) {
	defer local.Close()

	f.openConns.Add(1)
	f.totalConns.Add(1)
	defer f.openConns.Add(-1)

	// Generous, because this covers cold tunnel bring-up: measured at 1.2 to
	// 1.8 seconds on a phone, and worse on a bad link.
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	remote, err := client.DialTCPPort(ctx, uint16(f.remotePort))
	if err != nil {
		f.setErr(err)
		return // the caller sees a closed connection and can retry
	}
	defer remote.Close()
	f.setErr(nil)

	// Counted by hand rather than with tailcat.ProxyConns, because a
	// WireGuard-style UI is much easier to trust when it shows bytes moving.
	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		n, _ := io.Copy(remote, local)
		f.bytesOut.Add(n)
		if cw, ok := remote.(interface{ CloseWrite() error }); ok {
			cw.CloseWrite()
		}
	}()
	go func() {
		defer wg.Done()
		n, _ := io.Copy(local, remote)
		f.bytesIn.Add(n)
		if cw, ok := local.(interface{ CloseWrite() error }); ok {
			cw.CloseWrite()
		}
	}()
	wg.Wait()
}

// PingMillis measures round-trip time to the peer, establishing the tunnel if
// it is not up yet. Used both as a health gate and, after Start, to pay the
// bring-up cost up front instead of making the first SSH keystroke wait.
func (p *Peer) PingMillis(timeoutMillis int) (int64, error) {
	p.mu.Lock()
	client := p.client
	if client == nil {
		if p.serverAddr == "" {
			p.mu.Unlock()
			return 0, errors.New("maiatunnel: no tailcat address set, pair the devbox first")
		}
		// Not started: use a throwaway client and close it, so a health check
		// before Start does not leak a wireguard device.
		tmp := p.newClient()
		p.mu.Unlock()
		defer tmp.Close()
		client = tmp
	} else {
		p.mu.Unlock()
	}

	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMillis)*time.Millisecond)
	defer cancel()

	start := time.Now()
	if _, err := client.Ping(ctx); err != nil {
		p.mu.Lock()
		p.lastErr = err.Error()
		p.mu.Unlock()
		return 0, fmt.Errorf("maiatunnel: ping: %w", err)
	}
	ms := time.Since(start).Milliseconds()

	p.mu.Lock()
	p.lastPingMs = ms
	p.lastPingUnix = time.Now().Unix()
	p.lastErr = ""
	p.mu.Unlock()
	return ms, nil
}

// PublicKey returns this peer's client node key, or "" if no identity was set.
// This is the string the server operator puts in --allow.
func (p *Peer) PublicKey() string {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.identity == nil {
		return ""
	}
	return p.identity.PublicKey()
}

type statusJSON struct {
	Running    bool            `json:"running"`
	LastPingMs int64           `json:"lastPingMs"`
	LastPingAt int64           `json:"lastPingAt"`
	LastError  string          `json:"lastError"`
	Forwards   []forwardStatus `json:"forwards"`
}

type forwardStatus struct {
	Name       string `json:"name"`
	LocalPort  int    `json:"localPort"`
	RemotePort int    `json:"remotePort"`
	OpenConns  int64  `json:"openConns"`
	TotalConns int64  `json:"totalConns"`
	BytesIn    int64  `json:"bytesIn"`
	BytesOut   int64  `json:"bytesOut"`
	LastError  string `json:"lastError"`
}

// StatusJSON reports the whole peer as JSON, because gomobile cannot marshal a
// slice of structs. The UI polls this.
func (p *Peer) StatusJSON() string {
	p.mu.Lock()
	defer p.mu.Unlock()

	st := statusJSON{
		Running:    p.running,
		LastPingMs: p.lastPingMs,
		LastPingAt: p.lastPingUnix,
		LastError:  p.lastErr,
	}
	for _, f := range p.forwards {
		st.Forwards = append(st.Forwards, forwardStatus{
			Name:       f.name,
			LocalPort:  f.localPort,
			RemotePort: f.remotePort,
			OpenConns:  f.openConns.Load(),
			TotalConns: f.totalConns.Load(),
			BytesIn:    f.bytesIn.Load(),
			BytesOut:   f.bytesOut.Load(),
			LastError:  f.getErr(),
		})
	}
	b, err := json.Marshal(st)
	if err != nil {
		return `{"running":false,"lastError":"status encode failed"}`
	}
	return string(b)
}

// Stop closes every listener and tears down the tunnel. The Peer can be started
// again afterwards, which is what the UI toggle needs.
func (p *Peer) Stop() error {
	p.mu.Lock()
	defer p.mu.Unlock()

	if !p.running {
		return nil
	}
	p.running = false

	var firstErr error
	for _, f := range p.forwards {
		if f.ln != nil {
			if err := f.ln.Close(); err != nil && firstErr == nil {
				firstErr = err
			}
			f.ln = nil
		}
	}
	if p.client != nil {
		if err := p.client.Close(); err != nil && firstErr == nil {
			firstErr = err
		}
		p.client = nil
	}
	return firstErr
}
