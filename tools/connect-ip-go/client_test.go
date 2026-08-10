package connectip

import (
	"context"
	"crypto/tls"
	"net"
	"net/http"
	"testing"
	"time"

	"github.com/quic-go/quic-go"
	"github.com/quic-go/quic-go/http3"
	"github.com/stretchr/testify/require"
	"github.com/yosida95/uritemplate/v3"
)

func TestClientInvalidTemplate(t *testing.T) {
	_, _, err := Dial(
		context.Background(),
		nil,
		uritemplate.MustNew("https://example.org/.well-known/masque/ip/{target}/{ipproto}/"),
		"connect-ip",
		http.Header{},
		false,
	)
	require.ErrorContains(t, err, "connect-ip: IP flow forwarding not supported")
}

func TestClientWaitForSettings(t *testing.T) {
	conn, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	require.NoError(t, err)
	ln, err := quic.Listen(conn, tlsConf, nil)
	require.NoError(t, err)
	defer ln.Close()

	tr := &http3.Transport{}
	defer tr.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()
	cconn, err := quic.DialAddr(
		ctx,
		conn.LocalAddr().String(),
		&tls.Config{ServerName: "localhost", RootCAs: certPool, NextProtos: []string{http3.NextProtoH3}},
		&quic.Config{EnableDatagrams: true},
	)
	require.NoError(t, err)
	// We're connecting to a QUIC, not an HTTP/3 server.
	// We'll never receive any HTTP/3 settings.
	_, _, err = Dial(
		ctx,
		tr.NewClientConn(cconn),
		uritemplate.MustNew("https://example.org/.well-known/masque/ip/"),
		"connect-ip",
		http.Header{},
		false,
	)
	require.ErrorIs(t, err, context.DeadlineExceeded)
}

func TestClientDatagramCheck(t *testing.T) {
	s := http3.Server{
		TLSConfig:       tlsConf,
		EnableDatagrams: false,
	}
	ln, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	require.NoError(t, err)
	go func() { s.Serve(ln) }()
	defer s.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	cconn, err := quic.DialAddr(
		ctx,
		ln.LocalAddr().String(),
		&tls.Config{ServerName: "localhost", RootCAs: certPool, NextProtos: []string{http3.NextProtoH3}},
		&quic.Config{EnableDatagrams: true},
	)
	require.NoError(t, err)
	defer cconn.CloseWithError(0, "")

	// Create a HTTP/3 client and dial the server
	tr := &http3.Transport{}
	defer tr.Close()

	// Now use the QUIC connection in the Dial call
	_, _, err = Dial(
		context.Background(),
		tr.NewClientConn(cconn),
		uritemplate.MustNew("https://example.org/.well-known/masque/ip/"),
		"connect-ip",
		http.Header{},
		false,
	)
	require.ErrorContains(t, err, "connect-ip: server didn't enable datagrams")
}
