// Command appdial reproduces the maia app's tunnel dial under run-as, where
// the app-domain SELinux rules apply: netlink route dumps are denied, so the
// interface list and default route must be pushed exactly as NetFacts does.
//
//	appdial ifaces <out.json>                     (run under shell, which may read netlink)
//	appdial dial <addrFile> <idFile> <ifaces.json> <ifName> <gateway> <port> <method> <path> [authFile]
package main

import (
	"encoding/json"
	"fmt"
	"net"
	"os"
	"strconv"
	"strings"
	"time"

	"maia/tunnel/maiatunnel"
)

type ifaceJSON struct {
	Name         string   `json:"name"`
	Index        int      `json:"index"`
	MTU          int      `json:"mtu"`
	Up           bool     `json:"up"`
	Loopback     bool     `json:"loopback"`
	PointToPoint bool     `json:"pointToPoint"`
	Multicast    bool     `json:"multicast"`
	Addrs        []string `json:"addrs"`
}

func main() {
	if len(os.Args) < 2 {
		fmt.Println("usage: appdial ifaces <out> | dial <addrFile> <idFile> <ifaces.json> <ifName> <gateway> <port> <method> <path> [authFile]")
		os.Exit(2)
	}
	switch os.Args[1] {
	case "ifaces":
		dumpIfaces(os.Args[2])
	case "dial":
		authFile := ""
		if len(os.Args) > 10 {
			authFile = os.Args[10]
		}
		dial(os.Args[2], os.Args[3], os.Args[4], os.Args[5], os.Args[6],
			os.Args[7], os.Args[8], os.Args[9], authFile)
	}
}

func dumpIfaces(out string) {
	ifs, err := net.Interfaces()
	if err != nil {
		fmt.Println("FAIL interfaces:", err)
		os.Exit(1)
	}
	list := make([]ifaceJSON, 0, len(ifs))
	for _, f := range ifs {
		j := ifaceJSON{
			Name:         f.Name,
			Index:        f.Index,
			MTU:          f.MTU,
			Up:           f.Flags&net.FlagUp != 0,
			Loopback:     f.Flags&net.FlagLoopback != 0,
			PointToPoint: f.Flags&net.FlagPointToPoint != 0,
			Multicast:    f.Flags&net.FlagMulticast != 0,
		}
		addrs, _ := f.Addrs()
		for _, a := range addrs {
			j.Addrs = append(j.Addrs, a.String())
		}
		list = append(list, j)
	}
	b, _ := json.Marshal(list)
	if err := os.WriteFile(out, b, 0o600); err != nil {
		fmt.Println("FAIL write:", err)
		os.Exit(1)
	}
	fmt.Println("PASS wrote", len(list), "interfaces")
}

func dial(addrFile, idFile, ifacesFile, ifName, gateway, portS, method, path, authFile string) {
	ifaces, err := os.ReadFile(ifacesFile)
	if err != nil {
		fmt.Println("FAIL read ifaces:", err)
		os.Exit(1)
	}
	if err := maiatunnel.SetInterfacesJSON(string(ifaces)); err != nil {
		fmt.Println("FAIL SetInterfacesJSON:", err)
		os.Exit(1)
	}
	maiatunnel.SetDefaultRoute(ifName, gateway)
	fmt.Println("PASS facts pushed")

	addrB, err := os.ReadFile(addrFile)
	if err != nil {
		fmt.Println("FAIL read addr:", err)
		os.Exit(1)
	}
	idB, err := os.ReadFile(idFile)
	if err != nil {
		fmt.Println("FAIL read id:", err)
		os.Exit(1)
	}
	id, err := maiatunnel.LoadIdentity(strings.TrimSpace(string(idB)))
	if err != nil {
		fmt.Println("FAIL load identity:", err)
		os.Exit(1)
	}
	fmt.Println("PASS identity  ", id.PublicKey())

	port, err := strconv.Atoi(portS)
	if err != nil {
		fmt.Println("FAIL port:", err)
		os.Exit(1)
	}
	a := maiatunnel.NewAgent(id, strings.TrimSpace(string(addrB)), port)
	a.SetDebug(true)
	a.SetTimeoutMillis(30000)
	if authFile != "" {
		auth, err := os.ReadFile(authFile)
		if err != nil {
			fmt.Println("FAIL read auth:", err)
			os.Exit(1)
		}
		a.SetBasicAuth("maia", strings.TrimSpace(string(auth)))
	}

	body := ""
	if method == "POST" {
		body = `{"utterance":"ping"}`
	}
	start := time.Now()
	reply, err := a.Request(method, path, body)
	if err != nil {
		fmt.Printf("FAIL request after %s: %v\n", time.Since(start).Round(time.Millisecond), err)
		os.Exit(1)
	}
	fmt.Printf("PASS request in %s: status %d, %d body bytes\n",
		time.Since(start).Round(time.Millisecond), reply.Status, len(reply.Body))
}
