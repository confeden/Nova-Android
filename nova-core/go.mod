module nova-core

go 1.26.3

require (
	github.com/Diniboy1123/connect-ip-go v0.0.0-20251011145655-7be32d5976d9
	github.com/Diniboy1123/usque v0.0.0
	github.com/amnezia-vpn/amneziawg-go/v3 v3.1.20260828
	github.com/bepass-org/warp-plus v1.2.6
	github.com/pion/transport/v4 v4.0.1
	github.com/quic-go/quic-go v0.61.0
	github.com/refraction-networking/utls v1.8.2
	github.com/yosida95/uritemplate/v3 v3.0.2
	gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/goptlib v1.6.0
	gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird v0.0.0-20260312101154-fc105a03c0e0
	gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/snowflake/v2 v2.14.1
	golang.org/x/crypto v0.54.0
	golang.org/x/mobile v0.0.0-20260120165949-40bd9ace6ce4
	golang.org/x/net v0.57.0
)

// Форк ради одной правки: quic-go 0.61 заменила http3.ParseCapsule на
// потоковый CapsuleParser. Без неё вся ветка MASQUE держит quic-go на 0.55.
replace github.com/Diniboy1123/connect-ip-go => ../tools/connect-ip-go

replace github.com/Diniboy1123/usque => ../build/deps/usque

replace github.com/amnezia-vpn/amneziawg-go/v3 => ../tools/amneziawg-go

replace github.com/bepass-org/warp-plus => ../tools/warp-plus

replace gvisor.dev/gvisor => ../build/deps/gvisor

// Форк ради одной правки: anet писал в приватный кэш зон IPv6 стандартной
// библиотеки через `go:linkname`, а Go 1.23 такие ссылки запретил — компоновка
// `gomobile bind` падала на `invalid reference to net.zoneCache`. Обе переменные
// там только писались, поэтому они удалены, а не заменены. Патч:
// tools/deps/patches/anet.patch.
replace github.com/wlynxg/anet => ../tools/anet

// Форк ради двух ловушек, а не ради поведения: snowflake собирает SettingEngine
// внутри себя и наружу его не отдаёт, поэтому подменить сеть для pion и дозвон
// до брокера больше негде. Без этого сокеты ICE и рандеву уходят в тот самый
// туннель, к которому snowflake и пробивается (G154). Патч:
// tools/deps/patches/snowflake.patch.
replace gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/snowflake/v2 => ../tools/snowflake

require golang.org/x/mod v0.37.0 // indirect

require golang.org/x/sync v0.22.0 // indirect

require (
	filippo.io/edwards25519 v1.1.0 // indirect
	github.com/andybalholm/brotli v1.1.1 // indirect
	github.com/avast/retry-go v3.0.0+incompatible // indirect
	github.com/aws/aws-sdk-go-v2 v1.40.0 // indirect
	github.com/aws/aws-sdk-go-v2/config v1.32.1 // indirect
	github.com/aws/aws-sdk-go-v2/credentials v1.19.1 // indirect
	github.com/aws/aws-sdk-go-v2/feature/ec2/imds v1.18.14 // indirect
	github.com/aws/aws-sdk-go-v2/internal/configsources v1.4.14 // indirect
	github.com/aws/aws-sdk-go-v2/internal/endpoints/v2 v2.7.14 // indirect
	github.com/aws/aws-sdk-go-v2/internal/ini v1.8.4 // indirect
	github.com/aws/aws-sdk-go-v2/service/internal/accept-encoding v1.13.3 // indirect
	github.com/aws/aws-sdk-go-v2/service/internal/presigned-url v1.13.14 // indirect
	github.com/aws/aws-sdk-go-v2/service/signin v1.0.1 // indirect
	github.com/aws/aws-sdk-go-v2/service/sqs v1.42.16 // indirect
	github.com/aws/aws-sdk-go-v2/service/sso v1.30.4 // indirect
	github.com/aws/aws-sdk-go-v2/service/ssooidc v1.35.9 // indirect
	github.com/aws/aws-sdk-go-v2/service/sts v1.41.1 // indirect
	github.com/aws/smithy-go v1.23.2 // indirect
	github.com/dchest/siphash v1.2.3 // indirect
	github.com/dunglas/httpsfv v1.1.0 // indirect
	github.com/flynn/noise v1.1.0 // indirect
	github.com/golang/mock v1.6.0 // indirect
	github.com/google/btree v1.1.3 // indirect
	github.com/google/uuid v1.6.0 // indirect
	github.com/klauspost/compress v1.18.0 // indirect
	github.com/klauspost/cpuid/v2 v2.2.9 // indirect
	github.com/klauspost/reedsolomon v1.12.4 // indirect
	github.com/miekg/dns v1.1.65 // indirect
	github.com/noql-net/certpool v0.0.0-20250417123926-688b52c002ee // indirect
	github.com/patrickmn/go-cache v2.1.0+incompatible // indirect
	github.com/pion/datachannel v1.6.0 // indirect
	github.com/pion/dtls/v3 v3.1.2 // indirect
	github.com/pion/ice/v4 v4.2.0 // indirect
	github.com/pion/interceptor v0.1.43 // indirect
	github.com/pion/logging v0.2.4 // indirect
	github.com/pion/mdns/v2 v2.1.0 // indirect
	github.com/pion/randutil v0.1.0 // indirect
	github.com/pion/rtcp v1.2.16 // indirect
	github.com/pion/rtp v1.10.1 // indirect
	github.com/pion/sctp v1.9.2 // indirect
	github.com/pion/sdp/v3 v3.0.17 // indirect
	github.com/pion/srtp/v3 v3.0.10 // indirect
	github.com/pion/stun/v3 v3.1.1 // indirect
	github.com/pion/turn/v4 v4.1.4 // indirect
	github.com/pion/webrtc/v4 v4.2.3-securityfix // indirect
	github.com/pkg/errors v0.9.1 // indirect
	github.com/quic-go/qpack v0.6.0 // indirect
	github.com/realclientip/realclientip-go v1.0.0 // indirect
	github.com/songgao/water v0.0.0-20200317203138-2b4b6d7c09d8 // indirect
	github.com/theodorsm/covert-dtls v1.5.0 // indirect
	github.com/tjfoc/gmsm v1.4.1 // indirect
	github.com/txthinking/runnergroup v0.0.0-20241229123329-7b873ad00768 // indirect
	github.com/txthinking/socks5 v0.0.0-20251011041537-5c31f201a10e // indirect
	github.com/wlynxg/anet v0.0.5 // indirect
	github.com/xtaci/kcp-go/v5 v5.6.24 // indirect
	github.com/xtaci/smux v1.5.56 // indirect
	gitlab.com/yawning/edwards25519-extra v0.0.0-20231005122941-2149dcafc266 // indirect
	gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/ptutil v0.0.0-20250815012447-418f76dcf315 // indirect
	gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/webtunnel v0.0.3 // indirect
	golang.org/x/sys v0.47.0 // indirect
	golang.org/x/text v0.40.0 // indirect
	golang.org/x/time v0.15.0 // indirect
	golang.org/x/tools v0.47.0 // indirect
	golang.zx2c4.com/wintun v0.0.0-20230126152724-0fa3db229ce2 // indirect
	golang.zx2c4.com/wireguard v0.0.0-20250521234502-f333402bd9cb // indirect
	gvisor.dev/gvisor v0.0.0-20251011013117-af7a19336e55 // indirect
)
