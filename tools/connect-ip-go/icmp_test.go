package connectip

import (
	"net"
	"net/netip"
	"testing"

	"golang.org/x/net/icmp"
	"golang.org/x/net/ipv4"
	"golang.org/x/net/ipv6"

	"github.com/stretchr/testify/require"
)

func TestICMPTooLargeIPv4(t *testing.T) {
	src := netip.MustParseAddr("192.168.1.1")
	dst := netip.MustParseAddr("8.8.8.8")
	origHdr := &ipv4.Header{
		Version:  4,
		Len:      ipv4.HeaderLen,
		TotalLen: 60,
		TTL:      64,
		Protocol: 6, // TCP
		Src:      src.AsSlice(),
		Dst:      dst.AsSlice(),
	}
	origBytes, err := origHdr.Marshal()
	require.NoError(t, err)
	data, err := composeICMPTooLargePacket(origBytes, 1200)
	require.NoError(t, err)

	// parse the resulting IPv4 header
	hdr, err := ipv4.ParseHeader(data)
	require.NoError(t, err)
	require.Equal(t, 4, hdr.Version)
	require.Equal(t, ipProtoICMP, hdr.Protocol)
	require.Equal(t, dst.String(), hdr.Src.String())
	require.Equal(t, src.String(), hdr.Dst.String())
	require.Equal(t, uint16(hdr.Checksum), calculateIPv4Checksum(([20]byte)(data)))
	// parse ICMP message
	icmpMsg, err := icmp.ParseMessage(ipProtoICMP, data[ipv4.HeaderLen:])
	require.NoError(t, err)
	require.Equal(t, ipv4.ICMPTypeDestinationUnreachable, icmpMsg.Type)
	require.Equal(t, 4, icmpMsg.Code)
	// TODO: validate content
}

func TestICMPTooLargeIPv6(t *testing.T) {
	const mtu = 1337
	src := netip.MustParseAddr("2001:db8::1")
	dst := netip.MustParseAddr("1:2:3:4::5")
	orig := []byte{
		0x60, 0x00, 0x00, 0x00, // Version, Traffic Class, Flow Label
		0x00, 0x00, // Payload Length
		0x00, 0x2a, // Next Header, Hop Limit (42)
	}
	orig = append(orig, src.AsSlice()...)
	orig = append(orig, dst.AsSlice()...)
	orig = append(orig, []byte("foobar")...)
	data, err := composeICMPTooLargePacket(orig, mtu)
	require.NoError(t, err)

	// parse the resulting IPv6 header
	hdr, err := ipv6.ParseHeader(data)
	require.NoError(t, err)
	require.Equal(t, 6, hdr.Version)
	require.Equal(t, ipProtoICMPv6, hdr.NextHeader)
	require.Equal(t, dst.String(), hdr.Src.String())
	require.Equal(t, src.String(), hdr.Dst.String())
	// parse ICMPv6 message
	icmpMsg, err := icmp.ParseMessage(ipProtoICMPv6, data[ipv6.HeaderLen:])
	require.NoError(t, err)
	require.Equal(t, ipv6.ICMPTypePacketTooBig, icmpMsg.Type)
	icmpBody, ok := icmpMsg.Body.(*icmp.PacketTooBig)
	require.True(t, ok)
	require.Equal(t, mtu, icmpBody.MTU)
	require.Equal(t, orig, icmpBody.Data)
}

func TestICMPFailures(t *testing.T) {
	t.Run("empty packet", func(t *testing.T) {
		_, err := composeICMPTooLargePacket([]byte{}, 1)
		require.EqualError(t, err, "connect-ip: empty packet")
	})

	t.Run("too short IPv4 header", func(t *testing.T) {
		origHdr := &ipv4.Header{
			Version:  4,
			Len:      ipv4.HeaderLen,
			TotalLen: 60,
			Src:      net.IPv4(1, 2, 3, 4),
			Dst:      net.IPv4(5, 6, 7, 8),
		}
		data, err := origHdr.Marshal()
		require.NoError(t, err)
		_, err = composeICMPTooLargePacket(data[:ipv4.HeaderLen-1], 1)
		require.EqualError(t, err, "connect-ip: IPv4 packet too short")
	})

	t.Run("too short IPv6 header", func(t *testing.T) {
		data := []byte{
			0x60, 0x00, 0x00, 0x00, // Version, Traffic Class, Flow Label
			0x00, 0x00, // Payload Length
			0x00, 0x40, // Next Header, Hop Limit
		}
		data = append(data, net.ParseIP("2001:db8::1").To16()...)
		data = append(data, net.ParseIP("2001:db8::2").To16()...)
		_, err := composeICMPTooLargePacket(data[:ipv6.HeaderLen-1], 1)
		require.EqualError(t, err, "connect-ip: IPv6 packet too short")
	})

	t.Run("unknown IP version", func(t *testing.T) {
		data := []byte{
			0x30, 0x00, 0x00, 0x00, // what is IPv3?
		}
		_, err := composeICMPTooLargePacket(data, 1)
		require.EqualError(t, err, "connect-ip: unknown IP version: 3")
	})
}
