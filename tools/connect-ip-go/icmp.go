package connectip

import (
	"encoding/binary"
	"errors"
	"fmt"

	"golang.org/x/net/icmp"
	"golang.org/x/net/ipv4"
	"golang.org/x/net/ipv6"
)

func composeICMPTooLargePacket(b []byte, mtu int) ([]byte, error) {
	if len(b) == 0 {
		return nil, errors.New("connect-ip: empty packet")
	}

	var icmpMessage *icmp.Message
	var psh []byte
	switch v := ipVersion(b); v {
	case 4:
		if len(b) < ipv4.HeaderLen {
			return nil, errors.New("connect-ip: IPv4 packet too short")
		}
		icmpMessage = &icmp.Message{
			Type: ipv4.ICMPTypeDestinationUnreachable,
			Code: 4, // Fragmentation Needed and Don't Fragment was Set
			Body: &icmp.PacketTooBig{
				MTU:  mtu,
				Data: b[:min(len(b), ipv4.HeaderLen+8)],
			},
		}
	case 6:
		if len(b) < ipv6.HeaderLen {
			return nil, errors.New("connect-ip: IPv6 packet too short")
		}
		icmpMessage = &icmp.Message{
			Type: ipv6.ICMPTypePacketTooBig,
			Body: &icmp.PacketTooBig{
				MTU:  mtu,
				Data: b[:min(len(b), 1232)],
			},
		}
		psh = icmp.IPv6PseudoHeader(b[24:40], b[8:24])
	default:
		return nil, fmt.Errorf("connect-ip: unknown IP version: %d", v)
	}

	icmp, err := icmpMessage.Marshal(psh)
	if err != nil {
		return nil, fmt.Errorf("connect-ip: failed to marshal ICMP message: %w", err)
	}

	if ipVersion(b) == 4 {
		var header [ipv4.HeaderLen]byte
		header[0] = 4<<4 | ipv4.HeaderLen>>2 // Version and IHL
		ipLen := ipv4.HeaderLen + len(icmp)
		binary.BigEndian.PutUint16(header[2:4], uint16(ipLen)) // Total Length
		header[8] = 64                                         // TTL
		header[9] = 1                                          // Protocol (ICMP)
		copy(header[12:16], b[16:20])                          // Source IP from original packet
		copy(header[16:20], b[12:16])                          // Dest IP from original packet (swapped)
		binary.BigEndian.PutUint16(header[10:12], calculateIPv4Checksum(header))
		return append(header[:], icmp...), nil
	}

	var header [ipv6.HeaderLen]byte
	header[0] = 6 << 4                                         // Version 6
	binary.BigEndian.PutUint16(header[4:6], uint16(len(icmp))) // Payload Length
	header[6] = 58                                             // Next Header (ICMPv6)
	header[7] = 64                                             // Hop Limit
	copy(header[8:24], b[24:40])                               // Source IP from original packet
	copy(header[24:40], b[8:24])                               // Dest IP from original packet (swapped)
	return append(header[:], icmp...), nil
}
