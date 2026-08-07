package nova

import wgconn "github.com/amnezia-vpn/amneziawg-go/conn"

// ProtectedBind wraps the default warp-plus bind and protects the opened
// sockets with Android's VpnService.protect(fd), while keeping the standard
// batch/offload behavior from the upstream bind implementation.
type ProtectedBind struct {
	inner wgconn.Bind
}

func NewProtectedBind() wgconn.Bind {
	return &ProtectedBind{
		inner: wgconn.NewDefaultBind(),
	}
}

func (b *ProtectedBind) Open(port uint16) ([]wgconn.ReceiveFunc, uint16, error) {
	fns, actualPort, err := b.inner.Open(port)
	if err != nil {
		return nil, 0, err
	}

	protectOpenedSockets(b.inner)
	return fns, actualPort, nil
}

func (b *ProtectedBind) Close() error {
	return b.inner.Close()
}

func (b *ProtectedBind) SetMark(mark uint32) error {
	// Android uses VpnService.protect() instead of SO_MARK.
	return nil
}

func (b *ProtectedBind) Send(bufs [][]byte, ep wgconn.Endpoint) error {
	return b.inner.Send(bufs, ep)
}

func (b *ProtectedBind) ParseEndpoint(s string) (wgconn.Endpoint, error) {
	return b.inner.ParseEndpoint(s)
}

func (b *ProtectedBind) BatchSize() int {
	return b.inner.BatchSize()
}
