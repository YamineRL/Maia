// Command pubkey prints the public node key for a stored private identity, so
// a device's key can be put in a server's --allow list without the device
// having to display it.
package main

import (
	"fmt"
	"os"

	"maia/tunnel/maiatunnel"
)

func main() {
	if len(os.Args) != 2 {
		fmt.Fprintln(os.Stderr, "usage: pubkey <identity-file>")
		os.Exit(2)
	}
	b, err := os.ReadFile(os.Args[1])
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	id, err := maiatunnel.LoadIdentity(string(b))
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	fmt.Println(id.PublicKey())
}
