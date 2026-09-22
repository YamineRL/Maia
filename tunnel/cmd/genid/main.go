// Command genid generates a tailcat client identity and prints the public key
// to add to a server's --allow list. The private key is written to the file
// named by the first argument (default: identity.key), mode 0600.
package main

import (
	"fmt"
	"os"

	"maia/tunnel/maiatunnel"
)

func main() {
	path := "identity.key"
	if len(os.Args) > 1 {
		path = os.Args[1]
	}
	if _, err := os.Stat(path); err == nil {
		fmt.Fprintf(os.Stderr, "genid: %s already exists, refusing to overwrite\n", path)
		os.Exit(1)
	}
	id := maiatunnel.NewIdentity()
	txt, err := id.PrivateText()
	if err != nil {
		fmt.Fprintln(os.Stderr, "genid:", err)
		os.Exit(1)
	}
	if err := os.WriteFile(path, []byte(txt), 0o600); err != nil {
		fmt.Fprintln(os.Stderr, "genid:", err)
		os.Exit(1)
	}
	fmt.Println(id.PublicKey())
}
