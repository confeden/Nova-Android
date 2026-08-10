package connectip

import (
	"encoding/binary"

	"golang.org/x/net/ipv4"
)

func calculateIPv4Checksum(header [ipv4.HeaderLen]byte) uint16 {
	// add every 16-bit word in the header, skipping the checksum field (bytes 10 and 11)
	var sum uint32
	for i := 0; i < len(header); i += 2 {
		if i == 10 {
			continue // skip checksum field
		}
		sum += uint32(binary.BigEndian.Uint16(header[i : i+2]))
	}
	for (sum >> 16) > 0 {
		sum = (sum & 0xffff) + (sum >> 16)
	}
	return ^uint16(sum)
}
