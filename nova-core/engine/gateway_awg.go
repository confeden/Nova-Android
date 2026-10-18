package nova

// Сервер раздачи: WireGuard (он же AmneziaWG без обфускации — настоящий WireGuard
// совместим с любым клиентом) на адресе раздачи телефона, без root.
//
// Почему так. Android не отдаёт приложению интерфейс точки доступа, а пакет,
// который телефон форвардит от клиента, не принадлежит ни одному UID и потому
// мимо VPN (docs/gateway-sharing.md). Зато UDP-сокет приложения на адресе раздачи
// работает: клиент шлёт зашифрованные пакеты на телефон, сервер расшифровывает их
// у себя в userspace-стеке (gVisor), и дальше каждое TCP-соединение и каждый UDP-обмен
// клиента заново открывается **сокетами этого процесса**, то есть идёт в туннель Nova
// на общих основаниях — как сокеты самого телефона. TTL клиентских пакетов не
// трогается по той же причине: до интернета доходит не форвард, а новое соединение.
//
// Клиенту нужен только обычный WireGuard-конфиг: адрес 10.66.0.N, DNS 10.66.0.1
// (отвечаем сами, по правилам DNS Nova), AllowedIPs 0.0.0.0/0, ::/0.

import (
	"context"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/netip"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	wgconn "github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun"

	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/icmp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"
)

const (
	gwNIC            = 1
	gwDefaultMTU     = 1280
	gwTCPDialTimeout = 10 * time.Second
	gwUDPIdle        = 90 * time.Second
	gwMaxConns       = 4096
)

type gatewayPeerConfig struct {
	PublicKey string   `json:"public_key"` // base64
	Allowed   []string `json:"allowed"`    // "10.66.0.2/32", "fd66:6e6f:7661::2/128"
}

type gatewayServerConfig struct {
	ListenPort int                 `json:"listen_port"`
	PrivateKey string              `json:"private_key"` // base64
	Addr4      string              `json:"addr4"`       // "10.66.0.1"
	Addr6      string              `json:"addr6"`       // "fd66:6e6f:7661::1"
	MTU        int                 `json:"mtu"`
	Peers      []gatewayPeerConfig `json:"peers"`
}

// Ошибка и пустой список — не одно и то же: IpcSet с `replace_peers=true` и без
// пиров законно снимает всех, поэтому разбираем явно.
func gatewayPeersUAPI(peers []gatewayPeerConfig) (string, error) {
	var sb strings.Builder
	sb.WriteString("replace_peers=true\n")
	for _, p := range peers {
		raw, err := base64.StdEncoding.DecodeString(strings.TrimSpace(p.PublicKey))
		if err != nil || len(raw) != 32 {
			return "", fmt.Errorf("gateway: bad peer public key %q", p.PublicKey)
		}
		sb.WriteString("public_key=" + hex.EncodeToString(raw) + "\n")
		for _, a := range p.Allowed {
			if _, err := netip.ParsePrefix(a); err != nil {
				return "", fmt.Errorf("gateway: bad allowed ip %q: %w", a, err)
			}
			sb.WriteString("allowed_ip=" + a + "\n")
		}
	}
	return sb.String(), nil
}

type gatewayServer struct {
	dev    *device.Device
	gw     *gwTun
	addr4  netip.Addr
	addr6  netip.Addr
	closed atomic.Bool
	conns  atomic.Int64
}

var (
	gatewayMu       sync.Mutex
	gatewayActive   *gatewayServer
	gatewayEgressOK atomic.Bool
)

func init() {
	gatewayEgressOK.Store(true)
}

// SetGatewayEgressAllowed — выпускать ли клиентов раздачи наружу. Решение принимает
// Kotlin (туннель поднят либо разрешён выход напрямую), мы только исполняем: при
// запрете новые соединения получают отказ, а не молчание.
func SetGatewayEgressAllowed(allowed bool) {
	gatewayEgressOK.Store(allowed)
}

// StartGatewayServer поднимает WireGuard-сервер раздачи. Идемпотентен: повторный вызов
// с тем же содержимым перезапускает сервер.
func StartGatewayServer(configJSON string) (err error) {
	defer func() {
		if r := recover(); r != nil {
			log.Printf("CRITICAL PANIC in StartGatewayServer: %v", r)
			err = fmt.Errorf("gateway panic: %v", r)
		}
	}()
	var cfg gatewayServerConfig
	if e := json.Unmarshal([]byte(configJSON), &cfg); e != nil {
		return fmt.Errorf("gateway: bad config: %w", e)
	}
	if cfg.ListenPort <= 0 || cfg.ListenPort > 65535 {
		return fmt.Errorf("gateway: bad listen port %d", cfg.ListenPort)
	}
	priv, e := base64.StdEncoding.DecodeString(strings.TrimSpace(cfg.PrivateKey))
	if e != nil || len(priv) != 32 {
		return errors.New("gateway: bad private key")
	}
	addr4, e := netip.ParseAddr(cfg.Addr4)
	if e != nil || !addr4.Is4() {
		return fmt.Errorf("gateway: bad addr4 %q", cfg.Addr4)
	}
	addr6, e := netip.ParseAddr(cfg.Addr6)
	if e != nil || !addr6.Is6() {
		return fmt.Errorf("gateway: bad addr6 %q", cfg.Addr6)
	}
	mtu := cfg.MTU
	if mtu < 1000 || mtu > 1420 {
		mtu = gwDefaultMTU
	}
	peersUAPI, e := gatewayPeersUAPI(cfg.Peers)
	if e != nil {
		return e
	}

	StopGatewayServer()

	srv := &gatewayServer{addr4: addr4, addr6: addr6}
	gw, e := newGwTun(srv, mtu)
	if e != nil {
		return e
	}
	srv.gw = gw

	logger := device.NewLogger(device.LogLevelError, "NovaGateway")
	// Обычный Bind: сокет не защищается от VPN, он должен отвечать клиенту по
	// интерфейсу раздачи. Проверено на Pixel 4a при живом туннеле: ответ UDP с
	// 0.0.0.0 и с адреса раздачи доходит до клиента.
	dev := device.NewDevice(gw, wgconn.NewDefaultBind(), logger)
	uapi := "private_key=" + hex.EncodeToString(priv) + "\n" +
		"listen_port=" + strconv.Itoa(cfg.ListenPort) + "\n" + peersUAPI
	if e := dev.IpcSet(uapi); e != nil {
		dev.Close()
		return fmt.Errorf("gateway: ipc set: %w", e)
	}
	if e := dev.Up(); e != nil {
		dev.Close()
		return fmt.Errorf("gateway: device up: %w", e)
	}
	srv.dev = dev

	gatewayMu.Lock()
	gatewayActive = srv
	gatewayMu.Unlock()
	log.Printf("Gateway WireGuard server listening on UDP %d (%d peers, mtu %d)", cfg.ListenPort, len(cfg.Peers), mtu)
	return nil
}

// UpdateGatewayPeers заменяет список пиров на лету, не трогая слушающий сокет и
// уже идущие соединения остальных клиентов.
func UpdateGatewayPeers(peersJSON string) error {
	var peers []gatewayPeerConfig
	if e := json.Unmarshal([]byte(peersJSON), &peers); e != nil {
		return fmt.Errorf("gateway: bad peers: %w", e)
	}
	uapi, e := gatewayPeersUAPI(peers)
	if e != nil {
		return e
	}
	gatewayMu.Lock()
	srv := gatewayActive
	gatewayMu.Unlock()
	if srv == nil || srv.dev == nil {
		return errors.New("gateway: server is not running")
	}
	return srv.dev.IpcSet(uapi)
}

func StopGatewayServer() {
	gatewayMu.Lock()
	srv := gatewayActive
	gatewayActive = nil
	gatewayMu.Unlock()
	if srv == nil {
		return
	}
	srv.closed.Store(true)
	if srv.dev != nil {
		srv.dev.Close()
	}
	log.Printf("Gateway WireGuard server stopped")
}

// GatewayServerStats — UAPI-дамп устройства (rx/tx и рукопожатия по пирам) плюс
// число живых соединений. Пустая строка: сервер не запущен.
func GatewayServerStats() string {
	gatewayMu.Lock()
	srv := gatewayActive
	gatewayMu.Unlock()
	if srv == nil || srv.dev == nil {
		return ""
	}
	dump, err := srv.dev.IpcGet()
	if err != nil {
		return ""
	}
	return dump + "conns=" + strconv.FormatInt(srv.conns.Load(), 10) + "\n"
}

// ---- userspace-стек -------------------------------------------------------

type gwTun struct {
	srv          *gatewayServer
	ep           *channel.Endpoint
	stack        *stack.Stack
	events       chan tun.Event
	notifyHandle *channel.NotificationHandle
	incoming     chan *buffer.View
	mtu          int
	closeOnce    sync.Once
}

func newGwTun(srv *gatewayServer, mtu int) (*gwTun, error) {
	s := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol, ipv6.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol, icmp.NewProtocol4, icmp.NewProtocol6},
	})
	g := &gwTun{
		srv:      srv,
		ep:       channel.New(1024, uint32(mtu), ""),
		stack:    s,
		events:   make(chan tun.Event, 4),
		incoming: make(chan *buffer.View, 1024),
		mtu:      mtu,
	}
	sack := tcpip.TCPSACKEnabled(true)
	if e := s.SetTransportProtocolOption(tcp.ProtocolNumber, &sack); e != nil {
		return nil, fmt.Errorf("gateway: tcp sack: %v", e)
	}
	g.notifyHandle = g.ep.AddNotify(g)
	if e := s.CreateNIC(gwNIC, g.ep); e != nil {
		return nil, fmt.Errorf("gateway: create nic: %v", e)
	}
	// Пакет приходит со своим исходным адресом назначения — «любой адрес мой», и
	// ответ уходит с адресом, к которому клиент обращался.
	if e := s.SetPromiscuousMode(gwNIC, true); e != nil {
		return nil, fmt.Errorf("gateway: promiscuous: %v", e)
	}
	if e := s.SetSpoofing(gwNIC, true); e != nil {
		return nil, fmt.Errorf("gateway: spoofing: %v", e)
	}
	for _, a := range []netip.Addr{srv.addr4, srv.addr6} {
		proto := ipv4.ProtocolNumber
		if a.Is6() {
			proto = ipv6.ProtocolNumber
		}
		pa := tcpip.ProtocolAddress{Protocol: proto, AddressWithPrefix: tcpip.AddrFromSlice(a.AsSlice()).WithPrefix()}
		if e := s.AddProtocolAddress(gwNIC, pa, stack.AddressProperties{}); e != nil {
			return nil, fmt.Errorf("gateway: add address %v: %v", a, e)
		}
	}
	s.AddRoute(tcpip.Route{Destination: header.IPv4EmptySubnet, NIC: gwNIC})
	s.AddRoute(tcpip.Route{Destination: header.IPv6EmptySubnet, NIC: gwNIC})

	tcpFwd := tcp.NewForwarder(s, 0, gwMaxConns, func(r *tcp.ForwarderRequest) {
		go srv.handleTCP(r)
	})
	s.SetTransportProtocolHandler(tcp.ProtocolNumber, tcpFwd.HandlePacket)
	udpFwd := udp.NewForwarder(s, func(r *udp.ForwarderRequest) bool {
		return srv.handleUDP(r)
	})
	s.SetTransportProtocolHandler(udp.ProtocolNumber, udpFwd.HandlePacket)

	g.events <- tun.EventUp
	return g, nil
}

func (g *gwTun) Name() (string, error)    { return "novagw", nil }
func (g *gwTun) File() *os.File           { return nil }
func (g *gwTun) Events() <-chan tun.Event { return g.events }
func (g *gwTun) MTU() (int, error)        { return g.mtu, nil }
func (g *gwTun) BatchSize() int           { return 1 }
func (g *gwTun) Flush() error             { return nil }

func (g *gwTun) Read(buf [][]byte, sizes []int, offset int) (int, error) {
	view, ok := <-g.incoming
	if !ok {
		return 0, os.ErrClosed
	}
	n, err := view.Read(buf[0][offset:])
	view.Release()
	if err != nil {
		return 0, err
	}
	sizes[0] = n
	return 1, nil
}

func (g *gwTun) Write(buf [][]byte, offset int) (int, error) {
	for _, b := range buf {
		packet := b[offset:]
		if len(packet) == 0 {
			continue
		}
		pkb := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(packet)})
		switch packet[0] >> 4 {
		case 4:
			g.ep.InjectInbound(header.IPv4ProtocolNumber, pkb)
		case 6:
			g.ep.InjectInbound(header.IPv6ProtocolNumber, pkb)
		default:
			pkb.DecRef()
			return 0, syscall.EAFNOSUPPORT
		}
		pkb.DecRef()
	}
	return len(buf), nil
}

// WriteNotify вызывает стек, когда у него есть пакет для клиента.
func (g *gwTun) WriteNotify() {
	pkt := g.ep.Read()
	if pkt == nil {
		return
	}
	view := pkt.ToView()
	pkt.DecRef()
	select {
	case g.incoming <- view:
	default:
		view.Release() // очередь к WireGuard переполнена — режем, как сеть
	}
}

func (g *gwTun) Close() error {
	g.closeOnce.Do(func() {
		g.stack.RemoveNIC(gwNIC)
		g.stack.Close()
		g.ep.RemoveNotify(g.notifyHandle)
		g.ep.Close()
		close(g.events)
		close(g.incoming)
	})
	return nil
}

// ---- пересылка ---------------------------------------------------------------

func (s *gatewayServer) isServerAddr(a tcpip.Address) bool {
	ip, ok := netip.AddrFromSlice(a.AsSlice())
	return ok && (ip.Unmap() == s.addr4 || ip == s.addr6)
}

func gwDest(id stack.TransportEndpointID) string {
	return net.JoinHostPort(id.LocalAddress.String(), strconv.Itoa(int(id.LocalPort)))
}

func (s *gatewayServer) handleTCP(r *tcp.ForwarderRequest) {
	defer func() {
		if rec := recover(); rec != nil {
			log.Printf("gateway tcp panic: %v", rec)
		}
	}()
	id := r.ID()
	toServer := s.isServerAddr(id.LocalAddress)
	if toServer && id.LocalPort != 53 {
		r.Complete(true)
		return
	}
	if !gatewayEgressOK.Load() && !toServer {
		r.Complete(true)
		return
	}
	if s.closed.Load() || s.conns.Load() >= gwMaxConns {
		r.Complete(true)
		return
	}
	var remote net.Conn
	if !toServer {
		d := net.Dialer{Timeout: gwTCPDialTimeout}
		c, err := d.Dial("tcp", gwDest(id))
		if err != nil {
			// Клиент видит отказ сразу, а не таймаут: рукопожатие не завершаем.
			r.Complete(true)
			return
		}
		remote = c
	}
	var wq waiter.Queue
	ep, terr := r.CreateEndpoint(&wq)
	if terr != nil {
		r.Complete(true)
		if remote != nil {
			remote.Close()
		}
		return
	}
	r.Complete(false)
	local := gonet.NewTCPConn(&wq, ep)
	s.conns.Add(1)
	defer s.conns.Add(-1)
	if toServer {
		s.serveDNSOverTCP(local)
		return
	}
	pipeConns(local, remote)
}

func pipeConns(a, b net.Conn) {
	done := make(chan struct{}, 2)
	cp := func(dst, src net.Conn) {
		_, _ = io.Copy(dst, src)
		if c, ok := dst.(interface{ CloseWrite() error }); ok {
			_ = c.CloseWrite()
		}
		done <- struct{}{}
	}
	go cp(a, b)
	go cp(b, a)
	<-done
	// Одна сторона закрылась: даём второй немного дочитать, потом рубим обе.
	select {
	case <-done:
	case <-time.After(15 * time.Second):
	}
	_ = a.Close()
	_ = b.Close()
}

func (s *gatewayServer) handleUDP(r *udp.ForwarderRequest) bool {
	id := r.ID()
	isDNS := id.LocalPort == 53
	if !isDNS && (!gatewayEgressOK.Load() || s.isServerAddr(id.LocalAddress)) {
		return false
	}
	if s.closed.Load() || s.conns.Load() >= gwMaxConns {
		return false
	}
	var wq waiter.Queue
	ep, terr := r.CreateEndpoint(&wq)
	if terr != nil {
		return false
	}
	local := gonet.NewUDPConn(&wq, ep)
	s.conns.Add(1)
	go func() {
		defer s.conns.Add(-1)
		defer func() {
			if rec := recover(); rec != nil {
				log.Printf("gateway udp panic: %v", rec)
			}
		}()
		defer local.Close()
		if isDNS {
			s.serveDNSOverUDP(local)
			return
		}
		remote, err := net.Dial("udp", gwDest(id))
		if err != nil {
			return
		}
		defer remote.Close()
		var last atomic.Int64
		last.Store(time.Now().UnixNano())
		stop := make(chan struct{})
		go func() { // клиент → интернет
			buf := make([]byte, 65535)
			for {
				_ = local.SetReadDeadline(time.Now().Add(gwUDPIdle))
				n, err := local.Read(buf)
				if err != nil {
					break
				}
				last.Store(time.Now().UnixNano())
				if !gatewayEgressOK.Load() {
					continue
				}
				_, _ = remote.Write(buf[:n])
			}
			close(stop)
			_ = remote.Close()
		}()
		buf := make([]byte, 65535) // интернет → клиент
		for {
			_ = remote.SetReadDeadline(time.Now().Add(gwUDPIdle))
			n, err := remote.Read(buf)
			if err != nil {
				select {
				case <-stop:
					return
				default:
				}
				if ne, ok := err.(net.Error); ok && ne.Timeout() &&
					time.Since(time.Unix(0, last.Load())) < gwUDPIdle {
					continue
				}
				return
			}
			last.Store(time.Now().UnixNano())
			_, _ = local.Write(buf[:n])
		}
	}()
	return true
}

// ---- DNS ---------------------------------------------------------------------

var gwFallbackDNS = []string{"1.1.1.1:53", "8.8.8.8:53"}

// gwResolve отвечает на запрос клиента по правилам DNS Nova (зашифрованные
// апстримы, маршруты через туннель), а если правил нет — обычным UDP до публичного
// резолвера: сокет этого процесса и так идёт в туннель.
func gwResolve(query []byte) ([]byte, error) {
	cfg := getDNSInterceptConfig()
	if cfg.enabled {
		upstreams, _, intercept, err := selectDNSInterceptUpstreams(query, cfg)
		if err == nil && intercept {
			resp, _, rerr := resolveDNSPayload(query, upstreams, cfg.timeout)
			if rerr == nil {
				return resp, nil
			}
		}
	}
	var lastErr error
	for _, up := range gwFallbackDNS {
		ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
		var d net.Dialer
		c, err := d.DialContext(ctx, "udp", up)
		cancel()
		if err != nil {
			lastErr = err
			continue
		}
		_ = c.SetDeadline(time.Now().Add(4 * time.Second))
		if _, err = c.Write(query); err == nil {
			buf := make([]byte, 4096)
			var n int
			if n, err = c.Read(buf); err == nil {
				c.Close()
				return buf[:n], nil
			}
		}
		c.Close()
		lastErr = err
	}
	return nil, lastErr
}

func (s *gatewayServer) serveDNSOverUDP(c *gonet.UDPConn) {
	buf := make([]byte, 4096)
	for {
		_ = c.SetReadDeadline(time.Now().Add(gwUDPIdle))
		n, err := c.Read(buf)
		if err != nil {
			return
		}
		q := append([]byte(nil), buf[:n]...)
		go func() {
			if !gatewayEgressOK.Load() {
				return
			}
			if resp, err := gwResolve(q); err == nil {
				_, _ = c.Write(resp)
			}
		}()
	}
}

func (s *gatewayServer) serveDNSOverTCP(c net.Conn) {
	defer c.Close()
	for {
		_ = c.SetReadDeadline(time.Now().Add(30 * time.Second))
		var lenBuf [2]byte
		if _, err := io.ReadFull(c, lenBuf[:]); err != nil {
			return
		}
		n := int(lenBuf[0])<<8 | int(lenBuf[1])
		if n < 12 {
			return
		}
		q := make([]byte, n)
		if _, err := io.ReadFull(c, q); err != nil {
			return
		}
		if !gatewayEgressOK.Load() {
			return
		}
		resp, err := gwResolve(q)
		if err != nil {
			return
		}
		out := append([]byte{byte(len(resp) >> 8), byte(len(resp))}, resp...)
		if _, err := c.Write(out); err != nil {
			return
		}
	}
}
