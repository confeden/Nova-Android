package connectip

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"sync"

	"github.com/quic-go/quic-go"
	"github.com/quic-go/quic-go/quicvarint"
	"github.com/yosida95/uritemplate/v3"
)

const h2DatagramCapsuleType uint64 = 0

// DialH2 dials a proxied connection over HTTP/2 CONNECT-IP.
//
// This transport carries proxied packets inside HTTP capsule DATAGRAM frames.
func DialH2(ctx context.Context, client *http.Client, template *uritemplate.Template, additionalHeaders http.Header) (*Conn, *http.Response, error) {
	if len(template.Varnames()) > 0 {
		return nil, nil, errors.New("connect-ip: IP flow forwarding not supported")
	}

	u, err := url.Parse(template.Raw())
	if err != nil {
		return nil, nil, fmt.Errorf("connect-ip: failed to parse URI: %w", err)
	}

	pr, pw := io.Pipe()
	req, err := http.NewRequestWithContext(ctx, http.MethodConnect, u.String(), pr)
	if err != nil {
		_ = pr.Close()
		_ = pw.Close()
		return nil, nil, fmt.Errorf("connect-ip: failed to create request: %w", err)
	}
	req.Host = authorityFromURL(u)
	req.ContentLength = -1
	req.Header = make(http.Header)
	for k, v := range additionalHeaders {
		req.Header[k] = v
	}

	rsp, err := client.Do(req)
	if err != nil {
		_ = pr.Close()
		_ = pw.Close()
		return nil, nil, fmt.Errorf("connect-ip: failed to send request: %w", err)
	}
	if rsp.StatusCode < 200 || rsp.StatusCode > 299 {
		_ = pr.Close()
		_ = pw.Close()
		_ = rsp.Body.Close()
		return nil, rsp, fmt.Errorf("connect-ip: server responded with %d", rsp.StatusCode)
	}

	stream := &h2DatagramStream{
		requestBody:  pw,
		responseBody: rsp.Body,
		recvBuf:      make([]byte, 0, 4096),
	}
	return newDatagramOnlyConn(stream), rsp, nil
}

func authorityFromURL(u *url.URL) string {
	if u.Port() != "" {
		return u.Host
	}
	host := u.Hostname()
	if host == "" {
		return u.Host
	}
	return host + ":443"
}

type h2DatagramStream struct {
	requestBody  *io.PipeWriter
	responseBody io.ReadCloser

	readMu  sync.Mutex
	writeMu sync.Mutex
	recvBuf []byte
}

func (s *h2DatagramStream) ReceiveDatagram(_ context.Context) ([]byte, error) {
	s.readMu.Lock()
	defer s.readMu.Unlock()

	for {
		capsuleType, payload, consumed, ok, err := parseCapsule(s.recvBuf)
		if err != nil {
			return nil, err
		}
		if ok {
			if capsuleType != h2DatagramCapsuleType {
				s.recvBuf = s.recvBuf[consumed:]
				continue
			}
			payloadOffset := consumed - len(payload)
			if payloadOffset < len(contextIDZero) {
				return nil, errors.New("connect-ip: malformed datagram capsule")
			}
			copy(s.recvBuf[payloadOffset-len(contextIDZero):payloadOffset], contextIDZero)
			data := s.recvBuf[payloadOffset-len(contextIDZero) : consumed]
			s.recvBuf = s.recvBuf[consumed:]
			return data, nil
		}

		buf := make([]byte, 4096)
		n, readErr := s.responseBody.Read(buf)
		if n > 0 {
			s.recvBuf = append(s.recvBuf, buf[:n]...)
			continue
		}
		if readErr != nil {
			return nil, readErr
		}
	}
}

func (s *h2DatagramStream) SendDatagram(data []byte) error {
	contextID, n, err := quicvarint.Parse(data)
	if err != nil {
		return fmt.Errorf("connect-ip: malformed datagram: %w", err)
	}
	if contextID != 0 {
		return fmt.Errorf("connect-ip: unsupported datagram context ID: %d", contextID)
	}

	frame := make([]byte, 0, 2*quicvarint.Len(0)+len(data[n:]))
	frame = quicvarint.Append(frame, h2DatagramCapsuleType)
	frame = quicvarint.Append(frame, uint64(len(data[n:])))
	frame = append(frame, data[n:]...)

	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	_, err = s.requestBody.Write(frame)
	if err != nil {
		return fmt.Errorf("connect-ip: failed to send datagram capsule: %w", err)
	}
	return nil
}

func (s *h2DatagramStream) CancelRead(quic.StreamErrorCode) {}

func (s *h2DatagramStream) Read(_ []byte) (int, error) {
	return 0, io.EOF
}

func (s *h2DatagramStream) Write(_ []byte) (int, error) {
	return 0, errors.New("connect-ip: control capsules are not supported by this transport")
}

func (s *h2DatagramStream) Close() error {
	_ = s.requestBody.Close()
	return s.responseBody.Close()
}

func parseCapsule(buf []byte) (capsuleType uint64, payload []byte, consumed int, ok bool, err error) {
	capsuleType, typeLen, ok := parseVarint(buf)
	if !ok {
		return 0, nil, 0, false, nil
	}
	payloadLen, payloadLenLen, ok := parseVarint(buf[typeLen:])
	if !ok {
		return 0, nil, 0, false, nil
	}
	headerLen := typeLen + payloadLenLen
	totalLen := headerLen + int(payloadLen)
	if totalLen < headerLen {
		return 0, nil, 0, false, errors.New("connect-ip: malformed capsule length")
	}
	if len(buf) < totalLen {
		return 0, nil, 0, false, nil
	}
	return capsuleType, buf[headerLen:totalLen], totalLen, true, nil
}

func parseVarint(buf []byte) (v uint64, n int, ok bool) {
	if len(buf) == 0 {
		return 0, 0, false
	}
	prefix := buf[0] >> 6
	n = 1 << prefix
	if len(buf) < n {
		return 0, 0, false
	}
	v = uint64(buf[0] & 0x3f)
	for i := 1; i < n; i++ {
		v = (v << 8) | uint64(buf[i])
	}
	return v, n, true
}
