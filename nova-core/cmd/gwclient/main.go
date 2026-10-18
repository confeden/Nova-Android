// gwclient — клиент для проверки сервера раздачи с ПК: читает обычный WireGuard-конфиг
// (тот, что показывает экран раздачи), поднимает туннель в userspace и делает запрос.
//
//	gwclient <file.conf> [url] [endpoint-override]
package main

import (
	"context"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"os"
	"strings"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun/netstack"
)

func main() {
	raw, err := os.ReadFile(os.Args[1])
	if err != nil {
		panic(err)
	}
	kv := map[string]string{}
	for _, ln := range strings.Split(string(raw), "\n") {
		ln = strings.TrimSpace(ln)
		if i := strings.Index(ln, "="); i > 0 && !strings.HasPrefix(ln, "[") {
			kv[strings.TrimSpace(ln[:i])] = strings.TrimSpace(ln[i+1:])
		}
	}
	priv, _ := base64.StdEncoding.DecodeString(kv["PrivateKey"])
	pub, _ := base64.StdEncoding.DecodeString(kv["PublicKey"])
	endpoint := kv["Endpoint"]
	if len(os.Args) > 3 {
		endpoint = os.Args[3]
	}
	addr := strings.TrimSuffix(strings.TrimSpace(strings.Split(kv["Address"], ",")[0]), "/32")
	tdev, tnet, err := netstack.CreateNetTUN(
		[]netip.Addr{netip.MustParseAddr(addr)},
		[]netip.Addr{netip.MustParseAddr("10.66.0.1")}, 1280)
	if err != nil {
		panic(err)
	}
	dev := device.NewDevice(tdev, conn.NewDefaultBind(), device.NewLogger(device.LogLevelError, "client"))
	uapi := "private_key=" + hex.EncodeToString(priv) + "\n" +
		"public_key=" + hex.EncodeToString(pub) + "\n" +
		"endpoint=" + endpoint + "\n" +
		"allowed_ip=0.0.0.0/0\npersistent_keepalive_interval=5\n"
	if err := dev.IpcSet(uapi); err != nil {
		panic(err)
	}
	if err := dev.Up(); err != nil {
		panic(err)
	}
	url := "https://www.cloudflare.com/cdn-cgi/trace"
	if len(os.Args) > 2 && os.Args[2] != "" {
		url = os.Args[2]
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
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 600))
	fmt.Println(resp.Status, time.Since(start))
	fmt.Println(string(body))
}
