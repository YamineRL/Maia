//go:build android

package maiatunnel

import "tailscale.com/net/netmon"

// SetDefaultRoute tells tailscale which interface carries the default route and
// the gateway on it. Android exposes both through ConnectivityManager, and
// netmon cannot work them out for itself inside an app. Either argument may be
// empty if the app does not know it.
//
// The setters it calls exist only in tailscale's android build, hence the split
// with netinfo_other.go, which keeps the desktop test binaries compiling.
func SetDefaultRoute(ifName, gateway string) {
	if ifName != "" {
		netmon.UpdateLastKnownDefaultRouteInterface(ifName)
	}
	if gateway != "" {
		netmon.UpdateLastKnownDefaultGateway(gateway)
	}
}
