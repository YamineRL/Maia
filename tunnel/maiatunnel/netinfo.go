package maiatunnel

import (
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"sync"

	"tailscale.com/net/netmon"
)

// Android's network stack is not readable the way Linux's is.
//
// Since SDK 30 an ordinary app may not dump the kernel routing table over
// netlink, so Go's net.Interfaces() fails with "route ip+net: netlinkrib:
// permission denied" and tailscale's netmon cannot start. This does not show up
// in a command line binary pushed to /data/local/tmp, because the shell user
// still has the permission: it appears only once the same code runs inside an
// APK.
//
// tailscale solves this by letting the host app supply the information it can
// legally obtain through the Java APIs, and these two functions are that seam.
// Kotlin reads java.net.NetworkInterface (which goes through bionic's
// getifaddrs and is permitted) and ConnectivityManager, then pushes the result
// down here before the tunnel starts and again whenever the network changes.

type ifaceJSON struct {
	Name         string   `json:"name"`
	Index        int      `json:"index"`
	MTU          int      `json:"mtu"`
	Up           bool     `json:"up"`
	Loopback     bool     `json:"loopback"`
	PointToPoint bool     `json:"pointToPoint"`
	Multicast    bool     `json:"multicast"`
	Addrs        []string `json:"addrs"` // CIDR notation
}

var (
	ifaceMu     sync.Mutex
	cachedIface []netmon.Interface
)

func init() {
	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		ifaceMu.Lock()
		defer ifaceMu.Unlock()
		if cachedIface == nil {
			// Nothing pushed yet. On a desktop this is the correct answer; on
			// Android it is the failure the host app is meant to prevent, and
			// the error says so rather than surfacing "netlinkrib" again.
			ifs, err := net.Interfaces()
			if err != nil {
				return nil, fmt.Errorf("no interfaces pushed from the host app, and reading them directly failed: %w", err)
			}
			out := make([]netmon.Interface, len(ifs))
			for i := range ifs {
				out[i].Interface = &ifs[i]
			}
			return out, nil
		}
		return cachedIface, nil
	})
}

// SetInterfacesJSON hands tailscale the interface list the app read through
// Java. The argument is a JSON array; see ifaceJSON for the shape. Call it
// before Start and again on every network change.
func SetInterfacesJSON(jsonText string) error {
	var list []ifaceJSON
	if err := json.Unmarshal([]byte(jsonText), &list); err != nil {
		return fmt.Errorf("maiatunnel: interface list is not valid JSON: %w", err)
	}
	if len(list) == 0 {
		return errors.New("maiatunnel: interface list is empty")
	}

	out := make([]netmon.Interface, 0, len(list))
	for _, in := range list {
		var flags net.Flags
		if in.Up {
			flags |= net.FlagUp | net.FlagRunning
		}
		if in.Loopback {
			flags |= net.FlagLoopback
		}
		if in.PointToPoint {
			flags |= net.FlagPointToPoint
		}
		if in.Multicast {
			flags |= net.FlagMulticast
		}

		iface := netmon.Interface{
			Interface: &net.Interface{
				Index: in.Index,
				MTU:   in.MTU,
				Name:  in.Name,
				Flags: flags,
			},
		}
		for _, a := range in.Addrs {
			ip, ipnet, err := net.ParseCIDR(a)
			if err != nil {
				continue // one unparseable address must not lose the interface
			}
			iface.AltAddrs = append(iface.AltAddrs, &net.IPNet{IP: ip, Mask: ipnet.Mask})
		}
		out = append(out, iface)
	}

	ifaceMu.Lock()
	cachedIface = out
	ifaceMu.Unlock()
	return nil
}
