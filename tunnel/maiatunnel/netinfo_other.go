//go:build !android

package maiatunnel

// SetDefaultRoute is a no-op off Android, where netmon reads the routing table
// itself. It exists so the bound API is the same shape on every platform.
func SetDefaultRoute(ifName, gateway string) {}
