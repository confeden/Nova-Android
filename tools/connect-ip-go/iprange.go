package connectip

import "net/netip"

// rangeToPrefixes converts an IP range defined by start and end addresses
// into a slice of CIDR prefixes that exactly cover the range.
// It assumes that the start and end addresses are of the same IP version,
// and that the start address is less than or equal to the end address.
func rangeToPrefixes(start, end netip.Addr) []netip.Prefix {
	var prefixes []netip.Prefix
	for current := start; current.Compare(end) <= 0; {
		// find the largest prefix that fits within our remaining range
		prefix := findLargestPrefix(current, end)
		prefixes = append(prefixes, prefix)

		// move to the next IP address after this prefix
		lastIP := lastIPInPrefix(prefix)
		if lastIP.Compare(end) >= 0 {
			break
		}
		current = lastIP.Next()
	}
	return prefixes
}

// findLargestPrefix finds the largest prefix starting at 'start' that doesn't exceed 'end'
func findLargestPrefix(start, end netip.Addr) netip.Prefix {
	if start == end {
		return netip.PrefixFrom(start, start.BitLen())
	}

	// Start with the smallest possible prefix (/32 for IPv4, /128 for IPv6),
	// and try progressively larger prefixes until we find one that exceeds our range.
	var prefixLen int
	for prefixLen = start.BitLen(); prefixLen > 0; prefixLen-- {
		prefix := netip.PrefixFrom(start, prefixLen-1) // Try one bit larger
		if lastIPInPrefix(prefix).Compare(end) > 0 || !isAligned(start, prefixLen-1) {
			break
		}
	}
	return netip.PrefixFrom(start, prefixLen)
}

// lastIPInPrefix returns the last IP address in a prefix
func lastIPInPrefix(prefix netip.Prefix) netip.Addr {
	addr := prefix.Addr()
	bits := addr.As16()

	hostBits := addr.BitLen() - prefix.Bits()

	// Set all host bits to 1
	for i := len(bits) - 1; i >= 0 && hostBits > 0; i-- {
		bitsInThisByte := min(8, hostBits)
		mask := byte((1 << bitsInThisByte) - 1)
		bits[i] |= mask
		hostBits -= bitsInThisByte
	}

	if addr.Is4() {
		return netip.AddrFrom4([4]byte(bits[12:16]))
	}
	return netip.AddrFrom16(bits)
}

// isAligned checks if an IP address is aligned to the given prefix length
func isAligned(addr netip.Addr, prefixLen int) bool {
	bits := addr.As16()

	hostBits := addr.BitLen() - prefixLen
	for i := len(bits) - 1; i >= 0 && hostBits > 0; i-- {
		bitsInThisByte := min(8, hostBits)
		mask := byte((1 << bitsInThisByte) - 1)
		if bits[i]&mask != 0 {
			return false
		}
		hostBits -= bitsInThisByte
	}
	return true
}
