// Command tunneltest exercises the maiatunnel API the Android app will drive:
// a persistent identity, a peer with several forwards, start, ping, traffic,
// status, stop, and start again (the UI toggle).
package main

import (
	"fmt"
	"io"
	"net/http"
	"os"
	"time"

	"maia/tunnel/maiatunnel"
)

func main() {
	if len(os.Args) < 2 {
		fmt.Println("usage: tunneltest <tailcat-addr> [identity-file]")
		os.Exit(2)
	}
	addr := os.Args[1]
	idFile := "identity.key"
	if len(os.Args) > 2 {
		idFile = os.Args[2]
	}

	// Identity: load if present, else generate and save. Exactly what the app
	// does on first run.
	var id *maiatunnel.Identity
	if b, err := os.ReadFile(idFile); err == nil {
		id, err = maiatunnel.LoadIdentity(string(b))
		if err != nil {
			fmt.Println("FAIL load identity:", err)
			os.Exit(1)
		}
		fmt.Println("PASS identity     loaded from", idFile)
	} else {
		id = maiatunnel.NewIdentity()
		txt, _ := id.PrivateText()
		if err := os.WriteFile(idFile, []byte(txt), 0o600); err != nil {
			fmt.Println("FAIL save identity:", err)
			os.Exit(1)
		}
		fmt.Println("PASS identity     generated and saved to", idFile)
	}
	fmt.Println("     public key   ", id.PublicKey())

	p := maiatunnel.NewPeer(id, addr)
	if os.Getenv("MAIA_DEBUG") != "" {
		p.SetDebug(true)
	}
	if err := p.AddForward("http", 0, 9911); err != nil {
		fmt.Println("FAIL AddForward:", err)
		os.Exit(1)
	}
	if err := p.AddForward("dup", 0, 9911); err != nil {
		fmt.Println("FAIL AddForward second:", err)
		os.Exit(1)
	}

	if err := p.Start(); err != nil {
		fmt.Println("FAIL Start:", err)
		os.Exit(1)
	}
	fmt.Println("PASS start       ", p.StatusJSON())

	ms, err := p.PingMillis(20000)
	if err != nil {
		fmt.Println("FAIL ping:", err)
		os.Exit(1)
	}
	fmt.Printf("PASS ping         %d ms\n", ms)

	// Find the port the first forward landed on, via the same JSON the UI reads.
	var lp int
	fmt.Sscanf(portFromStatus(p.StatusJSON()), "%d", &lp)
	if lp == 0 {
		fmt.Println("FAIL could not read local port from status")
		os.Exit(1)
	}

	t := time.Now()
	resp, err := http.Get(fmt.Sprintf("http://127.0.0.1:%d/probe.txt", lp))
	if err != nil {
		fmt.Println("FAIL http:", err)
		os.Exit(1)
	}
	b, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	fmt.Printf("PASS http         %s in %v, body=%q\n", resp.Status, time.Since(t).Round(time.Millisecond), string(b))

	fmt.Println("     status       ", p.StatusJSON())

	if err := p.Stop(); err != nil {
		fmt.Println("FAIL Stop:", err)
		os.Exit(1)
	}
	fmt.Println("PASS stop        ", p.StatusJSON())

	// The toggle: off then on again must work, which is the whole point of the app.
	if err := p.Start(); err != nil {
		fmt.Println("FAIL restart:", err)
		os.Exit(1)
	}
	ms2, err := p.PingMillis(20000)
	if err != nil {
		fmt.Println("FAIL ping after restart:", err)
		os.Exit(1)
	}
	fmt.Printf("PASS restart      ping %d ms\n", ms2)
	p.Stop()
}

// portFromStatus pulls the first forward's localPort out of the status JSON
// without pulling in a JSON dependency here.
func portFromStatus(s string) string {
	const k = `"localPort":`
	i := len(k)
	for j := 0; j+i <= len(s); j++ {
		if s[j:j+i] == k {
			rest := s[j+i:]
			end := 0
			for end < len(rest) && rest[end] >= '0' && rest[end] <= '9' {
				end++
			}
			return rest[:end]
		}
	}
	return ""
}
