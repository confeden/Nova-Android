package connectip

import (
	"bytes"
	"context"
	"io"
	"testing"

	"github.com/quic-go/quic-go/quicvarint"
	"github.com/stretchr/testify/require"
)

func TestParseVarint(t *testing.T) {
	for _, tc := range []uint64{0, 1, 63, 64, 16383, 16384, 1_073_741_823} {
		enc := quicvarint.Append(nil, tc)
		v, n, ok := parseVarint(enc)
		require.True(t, ok)
		require.Equal(t, tc, v)
		require.Equal(t, len(enc), n)
	}
}

func TestH2DatagramStreamSendDatagram(t *testing.T) {
	reader, writer := io.Pipe()
	stream := &h2DatagramStream{
		requestBody:  writer,
		responseBody: io.NopCloser(bytes.NewReader(nil)),
	}

	payload := []byte{0x45, 0x00, 0x00, 0x14}
	datagram := append(append([]byte{}, contextIDZero...), payload...)

	errCh := make(chan error, 1)
	go func() {
		defer close(errCh)
		buf, err := io.ReadAll(reader)
		if err != nil {
			errCh <- err
			return
		}
		capType, capPayload, consumed, ok, err := parseCapsule(buf)
		require.NoError(t, err)
		require.True(t, ok)
		require.Equal(t, len(buf), consumed)
		require.Equal(t, h2DatagramCapsuleType, capType)
		require.Equal(t, payload, capPayload)
	}()

	err := stream.SendDatagram(datagram)
	require.NoError(t, err)
	require.NoError(t, writer.Close())
	require.NoError(t, <-errCh)
}

func TestH2DatagramStreamReceiveDatagram(t *testing.T) {
	nonDatagramCapsule := buildCapsule(7, []byte("ignore"))
	payload := []byte{0x60, 0x00, 0x00, 0x00}
	datagramCapsule := buildCapsule(h2DatagramCapsuleType, payload)

	_, writer := io.Pipe()
	stream := &h2DatagramStream{
		requestBody:  writer,
		responseBody: io.NopCloser(bytes.NewReader(append(nonDatagramCapsule, datagramCapsule...))),
		recvBuf:      make([]byte, 0, 64),
	}

	got, err := stream.ReceiveDatagram(context.Background())
	require.NoError(t, err)
	require.Equal(t, append(append([]byte{}, contextIDZero...), payload...), got)
}

func buildCapsule(capsuleType uint64, payload []byte) []byte {
	buf := make([]byte, 0, 16+len(payload))
	buf = quicvarint.Append(buf, capsuleType)
	buf = quicvarint.Append(buf, uint64(len(payload)))
	buf = append(buf, payload...)
	return buf
}
