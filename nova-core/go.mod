module nova-core

go 1.26.3

require (
	github.com/Diniboy1123/connect-ip-go v0.0.0-20251011145655-7be32d5976d9
	github.com/Diniboy1123/usque v0.0.0
	github.com/amnezia-vpn/amneziawg-go v0.0.0
	github.com/bepass-org/warp-plus v1.2.6
	github.com/quic-go/quic-go v0.61.0
	github.com/refraction-networking/utls v1.8.2
	github.com/yosida95/uritemplate/v3 v3.0.2
	golang.org/x/crypto v0.54.0
	golang.org/x/mobile v0.0.0-20260120165949-40bd9ace6ce4
	golang.org/x/net v0.57.0
)

// Форк ради одной правки: quic-go 0.61 заменила http3.ParseCapsule на
// потоковый CapsuleParser. Без неё вся ветка MASQUE держит quic-go на 0.55.
replace github.com/Diniboy1123/connect-ip-go => ../tools/connect-ip-go

replace github.com/Diniboy1123/usque => ../build/deps/usque

replace github.com/amnezia-vpn/amneziawg-go => ../tools/amneziawg-go

replace github.com/bepass-org/warp-plus => ../tools/warp-plus

replace gvisor.dev/gvisor => ../build/deps/gvisor

require golang.org/x/mod v0.37.0 // indirect

require golang.org/x/sync v0.22.0 // indirect

require (
	github.com/andybalholm/brotli v1.1.1 // indirect
	github.com/avast/retry-go v3.0.0+incompatible // indirect
	github.com/cloudflare/circl v1.6.1 // indirect
	github.com/dunglas/httpsfv v1.1.0 // indirect
	github.com/flynn/noise v1.1.0 // indirect
	github.com/google/btree v1.1.3 // indirect
	github.com/klauspost/compress v1.18.0 // indirect
	github.com/noql-net/certpool v0.0.0-20250417123926-688b52c002ee // indirect
	github.com/quic-go/qpack v0.6.0 // indirect
	github.com/songgao/water v0.0.0-20200317203138-2b4b6d7c09d8 // indirect
	golang.org/x/sys v0.47.0 // indirect
	golang.org/x/text v0.40.0 // indirect
	golang.org/x/time v0.15.0 // indirect
	golang.org/x/tools v0.47.0 // indirect
	golang.zx2c4.com/wintun v0.0.0-20230126152724-0fa3db229ce2 // indirect
	golang.zx2c4.com/wireguard v0.0.0-20250521234502-f333402bd9cb // indirect
	gvisor.dev/gvisor v0.0.0-20251011013117-af7a19336e55 // indirect
)
