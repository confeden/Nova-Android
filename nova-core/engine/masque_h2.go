package nova

import (
	"bufio"
	"context"
	"crypto/tls"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strings"
	"sync"
	"syscall"

	connectip "github.com/Diniboy1123/connect-ip-go"
	"github.com/quic-go/quic-go/http3"
	"github.com/quic-go/quic-go/quicvarint"
	"golang.org/x/net/http2"
	"golang.org/x/net/ipv4"
	"golang.org/x/net/ipv6"
)

const (
	capsuleTypeDatagram           http3.CapsuleType = 0
	capsuleTypeAddressAssign      http3.CapsuleType = 1
	capsuleTypeAddressRequest     http3.CapsuleType = 2
	capsuleTypeRouteAdvertisement http3.CapsuleType = 3
	h2CapsuleProtocolHeaderValue                    = "?1"
)

var contextIDZero = quicvarint.Append([]byte{}, 0)

type masquePacketConn interface {
	Close() error
	ReadPacket([]byte, bool) (int, error)
	WritePacket([]byte) ([]byte, error)
	LocalPrefixes(context.Context) ([]netip.Prefix, error)
	Routes(context.Context) ([]connectip.IPRoute, error)
}

type masqueCloseError struct {
	Remote bool
}

func (e *masqueCloseError) Error() string        { return net.ErrClosed.Error() }
func (e *masqueCloseError) Is(target error) bool { return target == net.ErrClosed }

type h2ConnectIPConn struct {
	body    io.ReadCloser
	writer  *io.PipeWriter
	cc      *http2.ClientConn
	netConn net.Conn

	writes chan h2WriteReq
	pkts   chan []byte

	assignedAddressNotify chan struct{}
	availableRoutesNotify chan struct{}

	mu                sync.Mutex
	assignedAddresses []netip.Prefix
	availableRoutes   []connectip.IPRoute
	closeErr          error
	closeCh           chan struct{}
	closeOnce         sync.Once
}

type h2WriteReq struct {
	payload []byte
	result  chan error
}

func newH2ConnectIPConn(body io.ReadCloser, writer *io.PipeWriter, cc *http2.ClientConn, netConn net.Conn) *h2ConnectIPConn {
	c := &h2ConnectIPConn{
		body:                  body,
		writer:                writer,
		cc:                    cc,
		netConn:               netConn,
		writes:                make(chan h2WriteReq),
		pkts:                  make(chan []byte, 32),
		assignedAddressNotify: make(chan struct{}, 1),
		availableRoutesNotify: make(chan struct{}, 1),
		closeCh:               make(chan struct{}),
	}
	go func() {
		if err := c.readLoop(); err != nil {
			c.closeWithError(&masqueCloseError{Remote: true})
		}
	}()
	go func() {
		if err := c.writeLoop(); err != nil {
			c.closeWithError(&masqueCloseError{Remote: true})
		}
	}()
	return c
}

func (c *h2ConnectIPConn) closeWithError(err error) {
	c.closeOnce.Do(func() {
		if err == nil {
			err = &masqueCloseError{Remote: false}
		}
		c.mu.Lock()
		c.closeErr = err
		c.mu.Unlock()
		close(c.closeCh)
		if c.writer != nil {
			_ = c.writer.Close()
		}
		if c.body != nil {
			_ = c.body.Close()
		}
		if c.cc != nil {
			_ = c.cc.Close()
		}
		if c.netConn != nil {
			_ = c.netConn.Close()
		}
	})
}

func (c *h2ConnectIPConn) Close() error {
	c.closeWithError(&masqueCloseError{Remote: false})
	return nil
}

func (c *h2ConnectIPConn) ReadPacket(b []byte, _ bool) (int, error) {
	select {
	case <-c.closeCh:
		c.mu.Lock()
		defer c.mu.Unlock()
		if c.closeErr != nil {
			return 0, c.closeErr
		}
		return 0, net.ErrClosed
	case pkt := <-c.pkts:
		return copy(b, pkt), nil
	}
}

func (c *h2ConnectIPConn) WritePacket(b []byte) ([]byte, error) {
	data, err := composeMasqueDatagram(b)
	if err != nil {
		return nil, nil
	}
	err = c.sendCapsule(capsuleTypeDatagram, data)
	return nil, err
}

func (c *h2ConnectIPConn) LocalPrefixes(ctx context.Context) ([]netip.Prefix, error) {
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-c.closeCh:
		c.mu.Lock()
		defer c.mu.Unlock()
		return nil, c.closeErr
	case <-c.assignedAddressNotify:
		c.mu.Lock()
		defer c.mu.Unlock()
		return append([]netip.Prefix(nil), c.assignedAddresses...), nil
	}
}

func (c *h2ConnectIPConn) Routes(ctx context.Context) ([]connectip.IPRoute, error) {
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-c.closeCh:
		c.mu.Lock()
		defer c.mu.Unlock()
		return nil, c.closeErr
	case <-c.availableRoutesNotify:
		c.mu.Lock()
		defer c.mu.Unlock()
		return append([]connectip.IPRoute(nil), c.availableRoutes...), nil
	}
}

func (c *h2ConnectIPConn) sendCapsule(ct http3.CapsuleType, payload []byte) error {
	res := make(chan error, 1)
	select {
	case <-c.closeCh:
		c.mu.Lock()
		defer c.mu.Unlock()
		if c.closeErr != nil {
			return c.closeErr
		}
		return net.ErrClosed
	case c.writes <- h2WriteReq{payload: buildCapsuleBytes(ct, payload), result: res}:
		select {
		case <-c.closeCh:
			c.mu.Lock()
			defer c.mu.Unlock()
			if c.closeErr != nil {
				return c.closeErr
			}
			return net.ErrClosed
		case err := <-res:
			return err
		}
	}
}

func (c *h2ConnectIPConn) writeLoop() error {
	for {
		select {
		case <-c.closeCh:
			c.mu.Lock()
			defer c.mu.Unlock()
			if c.closeErr != nil {
				return c.closeErr
			}
			return net.ErrClosed
		case req, ok := <-c.writes:
			if !ok {
				return nil
			}
			_, err := c.writer.Write(req.payload)
			req.result <- err
			if err != nil {
				return err
			}
		}
	}
}

func (c *h2ConnectIPConn) readLoop() error {
	reader := bufio.NewReader(c.body)
	for {
		ct, capsuleReader, err := http3.ParseCapsule(reader)
		if err != nil {
			return err
		}
		switch ct {
		case capsuleTypeDatagram:
			payload, err := io.ReadAll(capsuleReader)
			if err != nil {
				return err
			}
			if len(payload) == 0 {
				continue
			}
			contextID, n, err := quicvarint.Parse(payload)
			if err != nil {
				return err
			}
			if contextID != 0 || n >= len(payload) {
				continue
			}
			pkt := append([]byte(nil), payload[n:]...)
			select {
			case c.pkts <- pkt:
			default:
			}
		case capsuleTypeAddressAssign:
			capsule, err := parseAddressAssignCapsule(capsuleReader)
			if err != nil {
				return err
			}
			prefixes := make([]netip.Prefix, 0, len(capsule.AssignedAddresses))
			for _, assigned := range capsule.AssignedAddresses {
				prefixes = append(prefixes, assigned.IPPrefix)
			}
			c.mu.Lock()
			c.assignedAddresses = prefixes
			c.mu.Unlock()
			select {
			case c.assignedAddressNotify <- struct{}{}:
			default:
			}
		case capsuleTypeRouteAdvertisement:
			capsule, err := parseRouteAdvertisementCapsule(capsuleReader)
			if err != nil {
				return err
			}
			c.mu.Lock()
			c.availableRoutes = capsule.IPAddressRanges
			c.mu.Unlock()
			select {
			case c.availableRoutesNotify <- struct{}{}:
			default:
			}
		default:
			if _, err := io.Copy(io.Discard, capsuleReader); err != nil {
				return err
			}
		}
	}
}

func connectMasqueTunnelTCP(
	ctx context.Context,
	tlsConfig *tls.Config,
	endpointHost string,
	endpointPort int,
) (masquePacketConn, *http.Response, error) {
	if endpointPort <= 0 {
		endpointPort = 443
	}
	dialAddr := net.JoinHostPort(strings.Trim(endpointHost, "[]"), fmt.Sprintf("%d", endpointPort))
	tlsDialer := &tls.Dialer{
		NetDialer: &net.Dialer{
			Control: dialerControlProtectTCP(),
		},
		Config: cloneMasqueTLSForH2(tlsConfig),
	}
	netConn, err := tlsDialer.DialContext(ctx, "tcp", dialAddr)
	if err != nil {
		return nil, nil, fmt.Errorf("failed to dial MASQUE TLS/TCP: %w", err)
	}

	cc, err := (&http2.Transport{}).NewClientConn(netConn)
	if err != nil {
		_ = netConn.Close()
		return nil, nil, fmt.Errorf("failed to create HTTP/2 client conn: %w", err)
	}

	reqBodyReader, reqBodyWriter := io.Pipe()
	req, err := http.NewRequestWithContext(ctx, http.MethodConnect, masqueConnectURI, reqBodyReader)
	if err != nil {
		_ = reqBodyWriter.Close()
		_ = cc.Close()
		_ = netConn.Close()
		return nil, nil, fmt.Errorf("failed to create MASQUE HTTP/2 request: %w", err)
	}
	if parsedURL, parseErr := url.Parse(masqueConnectURI); parseErr == nil && parsedURL.Host != "" {
		req.Host = parsedURL.Host
	}
	req.Header.Set(":protocol", "cf-connect-ip")
	req.Header.Set(http3.CapsuleProtocolHeader, h2CapsuleProtocolHeaderValue)
	req.Header.Set("User-Agent", "")

	resp, err := cc.RoundTrip(req)
	if err != nil {
		_ = reqBodyWriter.Close()
		_ = cc.Close()
		_ = netConn.Close()
		return nil, nil, fmt.Errorf("failed to open MASQUE HTTP/2 CONNECT-IP: %w", err)
	}

	ipConn := newH2ConnectIPConn(resp.Body, reqBodyWriter, cc, netConn)
	return ipConn, resp, nil
}

func cloneMasqueTLSForH2(cfg *tls.Config) *tls.Config {
	clone := cfg.Clone()
	clone.NextProtos = []string{"h2", "http/1.1"}
	return clone
}

func dialerControlProtectTCP() func(string, string, syscall.RawConn) error {
	return func(_, _ string, c syscall.RawConn) error {
		if GlobalProtector == nil {
			return nil
		}
		var protectErr error
		if err := c.Control(func(fd uintptr) {
			if !GlobalProtector(int(fd)) {
				protectErr = fmt.Errorf("protect(%d) returned false", fd)
			}
		}); err != nil {
			return err
		}
		return protectErr
	}
}

func buildCapsuleBytes(ct http3.CapsuleType, value []byte) []byte {
	buf := make([]byte, 0, 16+len(value))
	buf = quicvarint.Append(buf, uint64(ct))
	buf = quicvarint.Append(buf, uint64(len(value)))
	buf = append(buf, value...)
	return buf
}

func composeMasqueDatagram(b []byte) ([]byte, error) {
	if len(b) == 0 {
		return nil, nil
	}
	switch v := b[0] >> 4; v {
	default:
		return nil, fmt.Errorf("connect-ip: unknown IP version: %d", v)
	case 4:
		if len(b) < ipv4.HeaderLen {
			return nil, fmt.Errorf("connect-ip: IPv4 packet too short")
		}
		ttl := b[8]
		if ttl <= 1 {
			return nil, fmt.Errorf("connect-ip: IPv4 TTL too small: %d", ttl)
		}
		packet := append([]byte(nil), b...)
		packet[8]--
		binary.BigEndian.PutUint16(packet[10:12], calculateIPv4Checksum(([ipv4.HeaderLen]byte)(packet[:ipv4.HeaderLen])))
		data := make([]byte, 0, len(contextIDZero)+len(packet))
		data = append(data, contextIDZero...)
		data = append(data, packet...)
		return data, nil
	case 6:
		if len(b) < ipv6.HeaderLen {
			return nil, fmt.Errorf("connect-ip: IPv6 packet too short")
		}
		hopLimit := b[7]
		if hopLimit <= 1 {
			return nil, fmt.Errorf("connect-ip: IPv6 Hop Limit too small: %d", hopLimit)
		}
		packet := append([]byte(nil), b...)
		packet[7]--
		data := make([]byte, 0, len(contextIDZero)+len(packet))
		data = append(data, contextIDZero...)
		data = append(data, packet...)
		return data, nil
	}
}

func calculateIPv4Checksum(header [ipv4.HeaderLen]byte) uint16 {
	var csum uint32
	for i := 0; i < len(header); i += 2 {
		csum += uint32(binary.BigEndian.Uint16(header[i : i+2]))
	}
	for csum > 0xffff {
		csum = (csum >> 16) + (csum & 0xffff)
	}
	return ^uint16(csum)
}

type addressAssignCapsule struct {
	AssignedAddresses []assignedAddress
}

type assignedAddress struct {
	RequestID uint64
	IPPrefix  netip.Prefix
}

type routeAdvertisementCapsule struct {
	IPAddressRanges []connectip.IPRoute
}

func parseAddressAssignCapsule(r io.Reader) (*addressAssignCapsule, error) {
	var assigned []assignedAddress
	for {
		requestID, prefix, err := parseAddress(r)
		if err != nil {
			if err == io.EOF {
				break
			}
			return nil, err
		}
		assigned = append(assigned, assignedAddress{RequestID: requestID, IPPrefix: prefix})
	}
	return &addressAssignCapsule{AssignedAddresses: assigned}, nil
}

func parseAddress(r io.Reader) (uint64, netip.Prefix, error) {
	vr := quicvarint.NewReader(r)
	requestID, err := quicvarint.Read(vr)
	if err != nil {
		return 0, netip.Prefix{}, err
	}
	ipVersion, err := vr.ReadByte()
	if err != nil {
		return 0, netip.Prefix{}, err
	}
	var ip netip.Addr
	switch ipVersion {
	case 4:
		var ipv4Addr [4]byte
		if _, err := io.ReadFull(r, ipv4Addr[:]); err != nil {
			return 0, netip.Prefix{}, err
		}
		ip = netip.AddrFrom4(ipv4Addr)
	case 6:
		var ipv6Addr [16]byte
		if _, err := io.ReadFull(r, ipv6Addr[:]); err != nil {
			return 0, netip.Prefix{}, err
		}
		ip = netip.AddrFrom16(ipv6Addr)
	default:
		return 0, netip.Prefix{}, fmt.Errorf("invalid IP version: %d", ipVersion)
	}
	prefixLen, err := vr.ReadByte()
	if err != nil {
		return 0, netip.Prefix{}, err
	}
	return requestID, netip.PrefixFrom(ip, int(prefixLen)), nil
}

func parseRouteAdvertisementCapsule(r io.Reader) (*routeAdvertisementCapsule, error) {
	var routes []connectip.IPRoute
	for {
		ipRange, err := parseIPAddressRange(r)
		if err != nil {
			if err == io.EOF {
				break
			}
			return nil, err
		}
		routes = append(routes, ipRange)
	}
	return &routeAdvertisementCapsule{IPAddressRanges: routes}, nil
}

func parseIPAddressRange(r io.Reader) (connectip.IPRoute, error) {
	var ipVersion uint8
	if err := binary.Read(r, binary.LittleEndian, &ipVersion); err != nil {
		return connectip.IPRoute{}, err
	}
	var startIP, endIP netip.Addr
	switch ipVersion {
	case 4:
		var start, end [4]byte
		if _, err := io.ReadFull(r, start[:]); err != nil {
			return connectip.IPRoute{}, err
		}
		if _, err := io.ReadFull(r, end[:]); err != nil {
			return connectip.IPRoute{}, err
		}
		startIP = netip.AddrFrom4(start)
		endIP = netip.AddrFrom4(end)
	case 6:
		var start, end [16]byte
		if _, err := io.ReadFull(r, start[:]); err != nil {
			return connectip.IPRoute{}, err
		}
		if _, err := io.ReadFull(r, end[:]); err != nil {
			return connectip.IPRoute{}, err
		}
		startIP = netip.AddrFrom16(start)
		endIP = netip.AddrFrom16(end)
	default:
		return connectip.IPRoute{}, fmt.Errorf("invalid IP version: %d", ipVersion)
	}
	var ipProtocol uint8
	if err := binary.Read(r, binary.LittleEndian, &ipProtocol); err != nil {
		return connectip.IPRoute{}, err
	}
	return connectip.IPRoute{
		StartIP:    startIP,
		EndIP:      endIP,
		IPProtocol: ipProtocol,
	}, nil
}
