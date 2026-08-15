package nova

import (
	"context"
	"fmt"
	"net"
	"strings"
	"time"

	usqueapi "github.com/Diniboy1123/usque/api"
)

// ProbeMasqueHandshake доводит соединение до ответа на запрос туннеля и сразу его
// закрывает. Датаплейн не поднимается, TUN не нужен.
//
// Нужна, чтобы отделить «служба не обслуживает ключ» от «мешает наш собственный
// процесс». Тот же путь, что и у боевого подключения (`connectMasqueTunnel` — та же
// обёртка сокета, та же длина Connection ID, та же защита сокета от собственного
// VPN), но без установки туннеля. Запускается отладочным действием службы, поэтому
// исполняется в процессе `:vpn` — ровно там, где живёт боевая попытка.
//
// Возвращает человекочитаемую строку: код ответа на CONNECT-IP либо текст ошибки.
func ProbeMasqueHandshake(identityJSON string, endpointHost string, endpointPort int, sni string) string {
	identity, ok := parseMasqueIdentity(identityJSON)
	if !ok {
		return "invalid MASQUE config"
	}

	endpointHost = strings.TrimSpace(strings.Trim(endpointHost, "[]"))
	if endpointHost == "" {
		endpointHost = identity.EndpointV4
		if endpointHost == "" {
			endpointHost = identity.EndpointV6
		}
	}
	if endpointHost == "" {
		return "MASQUE endpoint host is missing"
	}
	if endpointPort <= 0 || endpointPort > 65535 {
		if len(identity.Ports) > 0 {
			endpointPort = identity.Ports[0]
		} else {
			endpointPort = 443
		}
	}

	privKey, peerPubKey, cert, err := prepareMasqueCrypto(identity)
	if err != nil {
		return fmt.Sprintf("crypto: %v", err)
	}

	connectSNI := strings.TrimSpace(sni)
	if connectSNI == "" {
		connectSNI = masqueConnectSNI
	}
	tlsConfig, err := usqueapi.PrepareTlsConfig(privKey, peerPubKey, cert, connectSNI)
	if err != nil {
		return fmt.Sprintf("tls config: %v", err)
	}

	endpointIP := net.ParseIP(endpointHost)
	if endpointIP == nil {
		return fmt.Sprintf("endpoint must be an IP address, got %q", endpointHost)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()

	udpConn, quicTransport, tr, ipConn, rsp, err := connectMasqueTunnel(
		ctx,
		tlsConfig,
		&net.UDPAddr{IP: endpointIP, Port: endpointPort},
	)
	if err != nil {
		return fmt.Sprintf("handshake failed: %v", err)
	}
	if ipConn != nil {
		ipConn.Close()
	}
	if udpConn != nil {
		udpConn.Close()
	}
	if tr != nil {
		tr.Close()
	}
	if quicTransport != nil {
		quicTransport.Close()
	}
	return fmt.Sprintf("tunnel request status: %s", rsp.Status)
}
