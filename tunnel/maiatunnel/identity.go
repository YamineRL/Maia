package maiatunnel

import (
	"errors"
	"strings"

	"tailscale.com/types/key"
)

// Identity is this device's persistent tailcat client key.
//
// Without one, tailcat generates a fresh ephemeral key on every connection,
// which works only against a server that allows all clients. A server started
// with --allow=nodekey:... checks the client's public key, so the phone needs a
// key that survives restarts: generate once, store PrivateText() somewhere
// private, and hand the matching PublicKey() to the server operator.
type Identity struct {
	priv key.NodePrivate
}

// NewIdentity generates a fresh client key. Persist PrivateText() immediately;
// a key that is not saved is a key the server will stop recognising.
func NewIdentity() *Identity {
	return &Identity{priv: key.NewNode()}
}

// LoadIdentity restores an identity from a string produced by PrivateText.
func LoadIdentity(text string) (*Identity, error) {
	text = strings.TrimSpace(text)
	if text == "" {
		return nil, errors.New("maiatunnel: empty identity")
	}
	var priv key.NodePrivate
	if err := priv.UnmarshalText([]byte(text)); err != nil {
		return nil, errors.New("maiatunnel: identity is not a valid node key")
	}
	return &Identity{priv: priv}, nil
}

// PrivateText serialises the key for storage. This is secret material: on
// Android it belongs in the app's own files directory, never in shared storage
// or a log line.
func (i *Identity) PrivateText() (string, error) {
	b, err := i.priv.MarshalText()
	if err != nil {
		return "", err
	}
	return string(b), nil
}

// PublicKey returns the "nodekey:..." string to put in the server's --allow
// list. Safe to display, copy and send.
func (i *Identity) PublicKey() string {
	return i.priv.Public().String()
}
