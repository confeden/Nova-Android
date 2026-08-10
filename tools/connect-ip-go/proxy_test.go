package connectip

import (
	"context"
	"crypto/tls"
	"fmt"
	"net"
	"net/http"
	"net/netip"
	"testing"
	"time"

	"github.com/quic-go/quic-go"
	"github.com/quic-go/quic-go/http3"
	"github.com/yosida95/uritemplate/v3"
	"golang.org/x/net/ipv4"
	"golang.org/x/net/ipv6"

	"github.com/stretchr/testify/require"
)

func setupConns(t *testing.T) (client, server *Conn) {
	t.Helper()

	p := &Proxy{}
	conn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	require.NoError(t, err)
	t.Cleanup(func() { conn.Close() })

	template := uritemplate.MustNew(fmt.Sprintf("https://localhost:%d/connect-ip", conn.LocalAddr().(*net.UDPAddr).Port))
	connChan := make(chan *Conn, 1)
	mux := http.NewServeMux()
	mux.HandleFunc("/connect-ip", func(w http.ResponseWriter, r *http.Request) {
		mreq, err := ParseRequest(r, template, "connect-ip")
		require.NoError(t, err)

		conn, err := p.Proxy(w, mreq)
		require.NoError(t, err)
		connChan <- conn
	})
	s := http3.Server{
		Handler:         mux,
		Addr:            ":0",
		EnableDatagrams: true,
		TLSConfig:       tlsConf,
	}
	go func() { s.Serve(conn) }()
	t.Cleanup(func() { s.Close() })

	udpConn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	require.NoError(t, err)
	t.Cleanup(func() { udpConn.Close() })
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	cconn, err := quic.Dial(
		ctx,
		udpConn,
		conn.LocalAddr(),
		&tls.Config{ServerName: "localhost", RootCAs: certPool, NextProtos: []string{http3.NextProtoH3}},
		&quic.Config{EnableDatagrams: true},
	)
	require.NoError(t, err)
	tr := &http3.Transport{EnableDatagrams: true}
	t.Cleanup(func() { tr.Close() })

	client, rsp, err := Dial(ctx, tr.NewClientConn(cconn), template, "connect-ip", http.Header{}, false)
	require.NoError(t, err)
	require.Equal(t, rsp.StatusCode, http.StatusOK)

	select {
	case <-time.After(time.Second):
		t.Fatal("timed out")
	case conn := <-connChan:
		return client, conn
	}
	return client, server
}

func TestAddressAssignment(t *testing.T) {
	client, server := setupConns(t)

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Millisecond)
	defer cancel()
	_, err := server.Routes(ctx)
	require.ErrorIs(t, err, context.DeadlineExceeded)

	ctx, cancel = context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	pref1 := netip.MustParsePrefix("1.1.1.0/24")
	pref2 := netip.MustParsePrefix("2001:db8::/64")
	require.NoError(t, client.AssignAddresses(ctx, []netip.Prefix{pref1, pref2}))
	routes, err := server.LocalPrefixes(ctx)
	require.NoError(t, err)
	require.Equal(t, []netip.Prefix{pref1, pref2}, routes)

	// addresses are replaced once a new capsule is received
	require.NoError(t, client.AssignAddresses(ctx, []netip.Prefix{}))
	routes, err = server.LocalPrefixes(ctx)
	require.NoError(t, err)
	require.Empty(t, routes)
}

func TestRouteAdvertisement(t *testing.T) {
	client, server := setupConns(t)

	// no routes available
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Millisecond)
	defer cancel()
	_, err := server.Routes(ctx)
	require.ErrorIs(t, err, context.DeadlineExceeded)

	ctx, cancel = context.WithTimeout(context.Background(), time.Second)
	defer cancel()

	// refuse to advertise invalid routes
	require.ErrorContains(t,
		client.AdvertiseRoute(ctx, []IPRoute{
			{StartIP: netip.MustParseAddr("1.1.1.2"), EndIP: netip.MustParseAddr("1.1.1.1"), IPProtocol: 42},
		}),
		"invalid route advertising start_ip: 1.1.1.2 larger than 1.1.1.1",
	)

	// advertise some routes and make sure they're received
	require.NoError(t, client.AdvertiseRoute(ctx, []IPRoute{
		{StartIP: netip.MustParseAddr("1.1.1.1"), EndIP: netip.MustParseAddr("2.2.2.2"), IPProtocol: 42},
		{StartIP: netip.MustParseAddr("2001:db8::1"), EndIP: netip.MustParseAddr("2001:db8::100"), IPProtocol: 24},
	}))
	routes, err := server.Routes(ctx)
	require.NoError(t, err)
	require.Equal(t, []IPRoute{
		{StartIP: netip.MustParseAddr("1.1.1.1"), EndIP: netip.MustParseAddr("2.2.2.2"), IPProtocol: 42},
		{StartIP: netip.MustParseAddr("2001:db8::1"), EndIP: netip.MustParseAddr("2001:db8::100"), IPProtocol: 24},
	}, routes)

	// routes are replaced once a new capsule is received
	require.NoError(t, client.AdvertiseRoute(ctx, []IPRoute{}))
	routes, err = server.Routes(ctx)
	require.NoError(t, err)
	require.Empty(t, routes)
}

func TestTTLs(t *testing.T) {
	t.Run("IPv4", func(t *testing.T) {
		client, server := setupConns(t)
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Millisecond)
		defer cancel()
		require.NoError(t, server.AssignAddresses(ctx, []netip.Prefix{netip.MustParsePrefix("192.168.1.1/32")}))
		require.NoError(t, server.AdvertiseRoute(ctx, []IPRoute{
			{StartIP: netip.MustParseAddr("0.0.0.0"), EndIP: netip.MustParseAddr("255.255.255.255")},
		}))

		// First send a packet with TTL 1.
		// We expect the packet to be dropped silently.
		hdrTTL1 := &ipv4.Header{
			Len: 20,
			TTL: 1,
			Src: net.IPv4(192, 168, 1, 1),
			Dst: net.IPv4(8, 8, 8, 8),
		}
		packetTTL1, err := hdrTTL1.Marshal()
		require.NoError(t, err)
		icmp, err := client.WritePacket(packetTTL1)
		require.NoError(t, err)
		require.Empty(t, icmp)

		// now send a packet with TTL 42
		hdr := &ipv4.Header{
			Len: 20,
			TTL: 42,
			Src: net.IPv4(192, 168, 1, 1),
			Dst: net.IPv4(8, 8, 8, 8),
		}
		packet, err := hdr.Marshal()
		require.NoError(t, err)
		icmp, err = client.WritePacket(packet)
		require.NoError(t, err)
		require.Empty(t, icmp)

		receivedPacket := make([]byte, 1500)
		n, err := server.ReadPacket(receivedPacket, false)
		require.NoError(t, err)
		receivedPacket = receivedPacket[:n]

		receivedHdr, err := ipv4.ParseHeader(receivedPacket)
		require.NoError(t, err)
		require.Equal(t, uint16(receivedHdr.Checksum), calculateIPv4Checksum(([ipv4.HeaderLen]byte)(receivedPacket[:ipv4.HeaderLen])))
		// check that the TTL has been decremented
		require.Equal(t, 41, receivedHdr.TTL)
	})

	t.Run("IPv6", func(t *testing.T) {
		client, server := setupConns(t)
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Millisecond)
		defer cancel()
		require.NoError(t, server.AssignAddresses(ctx, []netip.Prefix{netip.MustParsePrefix("2001:db8::1/128")}))
		require.NoError(t, server.AdvertiseRoute(ctx, []IPRoute{
			{StartIP: netip.MustParseAddr("::"), EndIP: netip.MustParseAddr("ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff")},
		}))

		// First send a packet with Hop Limit 1.
		// We expect the packet to be dropped silently.
		packetHopLimit1 := []byte{
			0x60, 0x00, 0x00, 0x00, // Version, Traffic Class, Flow Label
			0x00, 0x00, // Payload Length
			0x00, 0x01, // Next Header, Hop Limit (1)
			0x20, 0x01, 0x0d, 0xb8, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, // Source IP
			0x20, 0x01, 0x48, 0x60, 0x48, 0x60, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x88, 0x88, // Destination IP
		}
		icmp, err := client.WritePacket(packetHopLimit1)
		require.NoError(t, err)
		require.Empty(t, icmp)

		// now send a packet with Hop Limit 42
		packet := []byte{
			0x60, 0x00, 0x00, 0x00, // Version, Traffic Class, Flow Label
			0x00, 0x00, // Payload Length
			0x00, 0x2A, // Next Header, Hop Limit (42)
			0x20, 0x01, 0x0d, 0xb8, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, // Source IP
			0x20, 0x01, 0x48, 0x60, 0x48, 0x60, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x88, 0x88, // Destination IP
		}
		icmp, err = client.WritePacket(packet)
		require.NoError(t, err)
		require.Empty(t, icmp)

		receivedPacket := make([]byte, 1500)
		n, err := server.ReadPacket(receivedPacket, false)
		require.NoError(t, err)
		receivedPacket = receivedPacket[:n]

		receivedHdr, err := ipv6.ParseHeader(receivedPacket)
		require.NoError(t, err)
		// check that the Hop Limit has been decremented
		require.Equal(t, 41, receivedHdr.HopLimit)
	})
}

func TestClosing(t *testing.T) {
	ipv6Packet := []byte{
		0x60, 0x00, 0x00, 0x00, // Version, Traffic Class, Flow Label
		0x00, 0x00, // Payload Length
		0x00, 0x2A, // Next Header, Hop Limit (42)
		0x20, 0x01, 0x0d, 0xb8, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, // Source IP
		0x20, 0x01, 0x48, 0x60, 0x48, 0x60, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x88, 0x88, // Destination IP
	}

	client, server := setupConns(t)
	routeErrChan := make(chan error, 1)
	prefixErrChan := make(chan error, 1)
	go func() {
		_, err := server.Routes(context.Background())
		routeErrChan <- err
	}()
	go func() {
		_, err := server.LocalPrefixes(context.Background())
		prefixErrChan <- err
	}()

	require.NoError(t, client.Close())
	_, err := client.LocalPrefixes(context.Background())
	require.ErrorIs(t, err, net.ErrClosed)
	var closeErr *CloseError
	require.ErrorAs(t, err, &closeErr)
	require.False(t, closeErr.Remote)
	_, err = client.Routes(context.Background())
	require.ErrorIs(t, err, net.ErrClosed)
	require.ErrorIs(t,
		client.AssignAddresses(context.Background(), []netip.Prefix{netip.MustParsePrefix("1.1.1.0/24")}),
		net.ErrClosed,
	)
	require.ErrorIs(t,
		client.AdvertiseRoute(context.Background(), []IPRoute{
			{StartIP: netip.MustParseAddr("1.1.1.0"), EndIP: netip.MustParseAddr("1.1.1.1"), IPProtocol: 42},
		}),
		net.ErrClosed,
	)
	_, err = client.ReadPacket([]byte{0}, false)
	require.ErrorIs(t, err, net.ErrClosed)
	_, err = client.WritePacket(ipv6Packet)
	require.ErrorIs(t, err, net.ErrClosed)

	select {
	case err := <-routeErrChan:
		require.ErrorIs(t, err, net.ErrClosed)
	case <-time.After(time.Second):
		t.Fatal("timeout")
	}

	select {
	case err := <-prefixErrChan:
		require.ErrorIs(t, err, net.ErrClosed)
	case <-time.After(time.Second):
		t.Fatal("timeout")
	}

	_, err = server.ReadPacket([]byte{0}, false)
	require.ErrorIs(t, err, net.ErrClosed)
	_, err = server.WritePacket(ipv6Packet)
	require.ErrorIs(t, err, net.ErrClosed)
}
