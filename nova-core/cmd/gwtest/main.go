// gwtest — стенд для сервера раздачи (engine/gateway_awg.go). Не входит в сборку
// приложения: две копии на двух телефонах, `server` на раздающем, `client` на
// подключённом к его точке доступа.
//
//	gwtest server <listen_port>
//	gwtest client <server_ip:port> [url]
package main

import (
	"context"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"os"
	"strings"
	"time"

	nova "nova-core/engine"

	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun/netstack"
	"golang.org/x/crypto/curve25519"
)

func key(b byte) (priv, pub []byte) {
	priv = make([]byte, 32)
	for i := range priv {
		priv[i] = b
	}
	priv[0] &= 248
	priv[31] = (priv[31] & 127) | 64
	pub, _ = curve25519.X25519(priv, curve25519.Basepoint)
	return
}

func main() {
	serverPriv, serverPub := key(0x11)
	clientPriv, clientPub := key(0x22)
	if os.Args[1] == "conf" {
		// conf <file.conf> [url]: настоящий конфиг из приложения вместо тестовых ключей.
		raw, err := os.ReadFile(os.Args[2])
		if err != nil {
			panic(err)
		}
		kv := map[string]string{}
		for _, ln := range strings.Split(string(raw), "\n") {
			if i := strings.Index(ln, "="); i > 0 && !strings.HasPrefix(ln, "[") {
				kv[strings.TrimSpace(ln[:i])] = strings.TrimSpace(ln[i+1:])
			}
		}
		clientPriv, _ = base64.StdEncoding.DecodeString(kv["PrivateKey"])
		serverPub, _ = base64.StdEncoding.DecodeString(kv["PublicKey"])
		addr := strings.Split(kv["Address"], ",")[0]
		os.Args = append([]string{os.Args[0], "client", kv["Endpoint"]}, os.Args[3:]...)
		os.Setenv("GWTEST_ADDR", strings.TrimSuffix(strings.TrimSpace(addr), "/32"))
	}
	switch os.Args[1] {
	case "server":
		cfg, _ := json.Marshal(map[string]any{
			"listen_port": atoi(os.Args[2]),
			"private_key": base64.StdEncoding.EncodeToString(serverPriv),
			"addr4":       "10.66.0.1",
			"addr6":       "fd66:6e6f:7661::1",
			"mtu":         1280,
			"peers": []map[string]any{{
				"public_key": base64.StdEncoding.EncodeToString(clientPub),
				"allowed":    []string{"10.66.0.2/32", "fd66:6e6f:7661::2/128"},
			}},
		})
		if err := nova.StartGatewayServer(string(cfg)); err != nil {
			fmt.Println("start:", err)
			os.Exit(1)
		}
		fmt.Println("server up")
		for i := 0; i < 40; i++ {
			time.Sleep(3 * time.Second)
			fmt.Print(nova.GatewayServerStats())
			fmt.Println("---")
		}
		nova.StopGatewayServer()
	case "client":
		tdev, tnet, err := netstack.CreateNetTUN(
			[]netip.Addr{netip.MustParseAddr(envOr("GWTEST_ADDR", "10.66.0.2"))},
			[]netip.Addr{netip.MustParseAddr("10.66.0.1")}, 1280)
		if err != nil {
			panic(err)
		}
		dev := device.NewDevice(tdev, conn.NewDefaultBind(), device.NewLogger(device.LogLevelError, "client"))
		uapi := "private_key=" + hex.EncodeToString(clientPriv) + "\n" +
			"public_key=" + hex.EncodeToString(serverPub) + "\n" +
			"endpoint=" + os.Args[2] + "\n" +
			"allowed_ip=0.0.0.0/0\npersistent_keepalive_interval=5\n"
		if err := dev.IpcSet(uapi); err != nil {
			panic(err)
		}
		if err := dev.Up(); err != nil {
			panic(err)
		}
		url := "https://www.cloudflare.com/cdn-cgi/trace"
		if len(os.Args) > 3 {
			url = os.Args[3]
		}
		hc := http.Client{Timeout: 20 * time.Second, Transport: &http.Transport{
			DialContext: func(ctx context.Context, n, a string) (net.Conn, error) { return tnet.DialContext(ctx, n, a) },
		}}
		start := time.Now()
		addrs, derr := tnet.LookupContextHost(context.Background(), "www.cloudflare.com")
		fmt.Println("dns:", addrs, derr, time.Since(start))
		resp, err := hc.Get(url)
		if err != nil {
			fmt.Println("GET failed:", err)
			os.Exit(2)
		}
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 2000))
		fmt.Println(resp.Status, time.Since(start))
		fmt.Println(string(body))
	}
}

func envOr(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}

func atoi(s string) int { var n int; fmt.Sscan(s, &n); return n }
