package main

import (
	"encoding/binary"
	"errors"
	"fmt"
	"math/rand"
	"strings"
)

// DNS type constants.
const (
	TypeA     uint16 = 1
	TypeNS    uint16 = 2
	TypeCNAME uint16 = 5
	TypeTXT   uint16 = 16
	TypeAAAA  uint16 = 28
	TypeOPT   uint16 = 41
	TypeSVCB  uint16 = 64
	TypeHTTPS uint16 = 65
	TypeNULL  uint16 = 10
)

const ClassIN uint16 = 1

// DNS header flag bits.
const (
	FlagQR uint16 = 0x8000
	FlagAA uint16 = 0x0400
	FlagTC uint16 = 0x0200
	FlagRD uint16 = 0x0100
	FlagRA uint16 = 0x0080
)

// RCODE names.
var rcodeNames = map[uint16]string{
	0: "NOERROR",
	1: "FORMERR",
	2: "SERVFAIL",
	3: "NXDOMAIN",
	4: "NOTIMP",
	5: "REFUSED",
}

func rcodeName(rc uint16) string {
	if s, ok := rcodeNames[rc]; ok {
		return s
	}
	return fmt.Sprintf("RCODE%d", rc)
}

func typeName(t uint16) string {
	switch t {
	case TypeA:
		return "A"
	case TypeNS:
		return "NS"
	case TypeCNAME:
		return "CNAME"
	case TypeTXT:
		return "TXT"
	case TypeAAAA:
		return "AAAA"
	case TypeOPT:
		return "OPT"
	case TypeSVCB:
		return "SVCB"
	case TypeHTTPS:
		return "HTTPS"
	case TypeNULL:
		return "NULL"
	default:
		return fmt.Sprintf("TYPE%d", t)
	}
}

func typeFromName(s string) (uint16, error) {
	switch strings.ToUpper(s) {
	case "A":
		return TypeA, nil
	case "NS":
		return TypeNS, nil
	case "CNAME":
		return TypeCNAME, nil
	case "TXT":
		return TypeTXT, nil
	case "AAAA":
		return TypeAAAA, nil
	case "SVCB":
		return TypeSVCB, nil
	case "HTTPS":
		return TypeHTTPS, nil
	case "NULL":
		return TypeNULL, nil
	default:
		return 0, fmt.Errorf("unknown DNS type: %s", s)
	}
}

// DNSHeader is a 12-byte DNS message header.
type DNSHeader struct {
	ID      uint16
	Flags   uint16
	QDCount uint16
	ANCount uint16
	NSCount uint16
	ARCount uint16
}

func (h *DNSHeader) RCode() uint16     { return h.Flags & 0x000F }
func (h *DNSHeader) IsResponse() bool   { return h.Flags&FlagQR != 0 }
func (h *DNSHeader) IsTruncated() bool  { return h.Flags&FlagTC != 0 }
func (h *DNSHeader) RecursionAvail() bool { return h.Flags&FlagRA != 0 }

func (h *DNSHeader) encode() []byte {
	b := make([]byte, 12)
	binary.BigEndian.PutUint16(b[0:], h.ID)
	binary.BigEndian.PutUint16(b[2:], h.Flags)
	binary.BigEndian.PutUint16(b[4:], h.QDCount)
	binary.BigEndian.PutUint16(b[6:], h.ANCount)
	binary.BigEndian.PutUint16(b[8:], h.NSCount)
	binary.BigEndian.PutUint16(b[10:], h.ARCount)
	return b
}

func decodeHeader(b []byte) (DNSHeader, error) {
	if len(b) < 12 {
		return DNSHeader{}, errors.New("dns: message too short for header")
	}
	return DNSHeader{
		ID:      binary.BigEndian.Uint16(b[0:]),
		Flags:   binary.BigEndian.Uint16(b[2:]),
		QDCount: binary.BigEndian.Uint16(b[4:]),
		ANCount: binary.BigEndian.Uint16(b[6:]),
		NSCount: binary.BigEndian.Uint16(b[8:]),
		ARCount: binary.BigEndian.Uint16(b[10:]),
	}, nil
}

// DNSQuestion represents a question section entry.
type DNSQuestion struct {
	Name  string
	Type  uint16
	Class uint16
}

// DNSRR represents a resource record in answer/authority/additional.
type DNSRR struct {
	Name     string
	Type     uint16
	Class    uint16
	TTL      uint32
	RDLength uint16
	RData    []byte

	// Parsed convenience fields (populated by parseRData).
	AAddr    string   // for A
	AAAAAddr string   // for AAAA
	TXTData  []string // for TXT (each character-string)
	CName    string   // for CNAME
	// OPT fields.
	UDPSize     uint16 // CLASS field of OPT = UDP payload size
	ExtRCode    uint8  // upper 8 bits of extended rcode
	EDNSVersion uint8
}

// DNSMessage is a complete DNS message.
type DNSMessage struct {
	Header     DNSHeader
	Questions  []DNSQuestion
	Answers    []DNSRR
	Authority  []DNSRR
	Additional []DNSRR
}

// FullRCode returns the full rcode combining header and OPT extended rcode.
func (m *DNSMessage) FullRCode() uint16 {
	rc := m.Header.RCode()
	for _, rr := range m.Additional {
		if rr.Type == TypeOPT {
			rc |= uint16(rr.ExtRCode) << 4
			break
		}
	}
	return rc
}

// NewQuery builds a query message with RD set.
func NewQuery(name string, qtype uint16) *DNSMessage {
	return &DNSMessage{
		Header: DNSHeader{
			ID:      uint16(rand.Intn(0xFFFF)),
			Flags:   FlagRD,
			QDCount: 1,
		},
		Questions: []DNSQuestion{{Name: name, Type: qtype, Class: ClassIN}},
	}
}

// AddEDNS adds an OPT pseudo-RR to the additional section.
func (m *DNSMessage) AddEDNS(udpSize uint16) {
	m.Additional = append(m.Additional, DNSRR{
		Name:    ".",
		Type:    TypeOPT,
		Class:   udpSize,
		TTL:     0,
		RData:   nil,
		UDPSize: udpSize,
	})
	m.Header.ARCount = uint16(len(m.Additional))
}

// Encode serializes the message to wire format.
func (m *DNSMessage) Encode() ([]byte, error) {
	// Update counts.
	m.Header.QDCount = uint16(len(m.Questions))
	m.Header.ANCount = uint16(len(m.Answers))
	m.Header.NSCount = uint16(len(m.Authority))
	m.Header.ARCount = uint16(len(m.Additional))

	buf := m.Header.encode()

	for _, q := range m.Questions {
		qn, err := encodeName(q.Name)
		if err != nil {
			return nil, fmt.Errorf("dns: encode question name %q: %w", q.Name, err)
		}
		buf = append(buf, qn...)
		buf = binary.BigEndian.AppendUint16(buf, q.Type)
		buf = binary.BigEndian.AppendUint16(buf, q.Class)
	}

	for _, rr := range m.Additional {
		b, err := encodeRR(rr)
		if err != nil {
			return nil, err
		}
		buf = append(buf, b...)
	}

	return buf, nil
}

func encodeRR(rr DNSRR) ([]byte, error) {
	var buf []byte
	if rr.Type == TypeOPT {
		buf = append(buf, 0) // root name
	} else {
		qn, err := encodeName(rr.Name)
		if err != nil {
			return nil, err
		}
		buf = append(buf, qn...)
	}
	buf = binary.BigEndian.AppendUint16(buf, rr.Type)
	buf = binary.BigEndian.AppendUint16(buf, rr.Class)
	buf = binary.BigEndian.AppendUint32(buf, rr.TTL)
	rdLen := len(rr.RData)
	buf = binary.BigEndian.AppendUint16(buf, uint16(rdLen))
	buf = append(buf, rr.RData...)
	return buf, nil
}

// encodeName encodes a domain name to wire format (uncompressed).
func encodeName(name string) ([]byte, error) {
	if name == "." || name == "" {
		return []byte{0}, nil
	}
	name = strings.TrimSuffix(name, ".")
	labels := strings.Split(name, ".")
	var buf []byte
	for _, label := range labels {
		if len(label) == 0 {
			return nil, errors.New("dns: empty label in name")
		}
		if len(label) > 63 {
			return nil, fmt.Errorf("dns: label %q exceeds 63 bytes", label)
		}
		buf = append(buf, byte(len(label)))
		buf = append(buf, []byte(label)...)
	}
	buf = append(buf, 0)
	if len(buf) > 255 {
		return nil, fmt.Errorf("dns: encoded name exceeds 255 bytes (%d)", len(buf))
	}
	return buf, nil
}

// ParseMessage parses a wire-format DNS message.
func ParseMessage(data []byte) (*DNSMessage, error) {
	if len(data) < 12 {
		return nil, errors.New("dns: message too short")
	}
	hdr, err := decodeHeader(data)
	if err != nil {
		return nil, err
	}
	msg := &DNSMessage{Header: hdr}
	off := 12

	// Parse questions.
	for i := 0; i < int(hdr.QDCount); i++ {
		name, newOff, err := decodeName(data, off)
		if err != nil {
			return nil, fmt.Errorf("dns: parse question name: %w", err)
		}
		if newOff+4 > len(data) {
			return nil, errors.New("dns: question section truncated")
		}
		q := DNSQuestion{
			Name:  name,
			Type:  binary.BigEndian.Uint16(data[newOff:]),
			Class: binary.BigEndian.Uint16(data[newOff+2:]),
		}
		msg.Questions = append(msg.Questions, q)
		off = newOff + 4
	}

	// Parse RR sections.
	parseRRs := func(count uint16) ([]DNSRR, int, error) {
		var rrs []DNSRR
		o := off
		for i := 0; i < int(count); i++ {
			rr, newOff, err := decodeRR(data, o)
			if err != nil {
				return nil, o, fmt.Errorf("dns: parse RR[%d]: %w", i, err)
			}
			rrs = append(rrs, rr)
			o = newOff
		}
		return rrs, o, nil
	}

	msg.Answers, off, err = parseRRs(hdr.ANCount)
	if err != nil {
		return nil, err
	}
	msg.Authority, off, err = parseRRs(hdr.NSCount)
	if err != nil {
		return nil, err
	}
	msg.Additional, _, err = parseRRs(hdr.ARCount)
	if err != nil {
		return nil, err
	}

	return msg, nil
}

// decodeName decodes a domain name from wire format, handling compression pointers.
func decodeName(data []byte, offset int) (string, int, error) {
	var labels []string
	visited := make(map[int]bool) // pointer loop detection
	newOffset := -1
	off := offset

	for {
		if off >= len(data) {
			return "", 0, errors.New("dns: name decode: unexpected end")
		}
		length := int(data[off])
		if length == 0 {
			off++
			break
		}
		// Compression pointer: top 2 bits set.
		if length&0xC0 == 0xC0 {
			if off+1 >= len(data) {
				return "", 0, errors.New("dns: name decode: pointer truncated")
			}
			ptr := int(binary.BigEndian.Uint16(data[off:]) & 0x3FFF)
			if visited[ptr] {
				return "", 0, errors.New("dns: name decode: pointer loop")
			}
			visited[ptr] = true
			if newOffset == -1 {
				newOffset = off + 2
			}
			off = ptr
			continue
		}
		if length > 63 {
			return "", 0, fmt.Errorf("dns: label length %d > 63", length)
		}
		off++
		if off+length > len(data) {
			return "", 0, errors.New("dns: name decode: label truncated")
		}
		labels = append(labels, string(data[off:off+length]))
		off += length
	}

	if newOffset == -1 {
		newOffset = off
	}
	if len(labels) == 0 {
		return ".", newOffset, nil
	}
	return strings.Join(labels, "."), newOffset, nil
}

// decodeRR decodes a resource record starting at offset.
func decodeRR(data []byte, offset int) (DNSRR, int, error) {
	name, off, err := decodeName(data, offset)
	if err != nil {
		return DNSRR{}, 0, err
	}
	if off+10 > len(data) {
		return DNSRR{}, 0, errors.New("dns: RR header truncated")
	}
	rr := DNSRR{
		Name:     name,
		Type:     binary.BigEndian.Uint16(data[off:]),
		Class:    binary.BigEndian.Uint16(data[off+2:]),
		TTL:      binary.BigEndian.Uint32(data[off+4:]),
		RDLength: binary.BigEndian.Uint16(data[off+8:]),
	}
	off += 10
	rdEnd := off + int(rr.RDLength)
	if rdEnd > len(data) {
		return DNSRR{}, 0, errors.New("dns: RDATA truncated")
	}
	rr.RData = make([]byte, rr.RDLength)
	copy(rr.RData, data[off:rdEnd])

	// Parse known types.
	switch rr.Type {
	case TypeA:
		if rr.RDLength == 4 {
			rr.AAddr = fmt.Sprintf("%d.%d.%d.%d", rr.RData[0], rr.RData[1], rr.RData[2], rr.RData[3])
		}
	case TypeAAAA:
		if rr.RDLength == 16 {
			rr.AAAAAddr = fmt.Sprintf("%02x%02x:%02x%02x:%02x%02x:%02x%02x:%02x%02x:%02x%02x:%02x%02x:%02x%02x",
				rr.RData[0], rr.RData[1], rr.RData[2], rr.RData[3],
				rr.RData[4], rr.RData[5], rr.RData[6], rr.RData[7],
				rr.RData[8], rr.RData[9], rr.RData[10], rr.RData[11],
				rr.RData[12], rr.RData[13], rr.RData[14], rr.RData[15])
		}
	case TypeTXT:
		rr.TXTData = parseTXTRData(rr.RData)
	case TypeCNAME:
		// CNAME rdata is a domain name; decode it from the original message.
		cname, _, err := decodeName(data, off)
		if err == nil {
			rr.CName = cname
		}
	case TypeOPT:
		rr.UDPSize = rr.Class
		rr.ExtRCode = uint8(rr.TTL >> 24)
		rr.EDNSVersion = uint8(rr.TTL >> 16)
	}

	return rr, rdEnd, nil
}

// parseTXTRData parses TXT RDATA as a sequence of character-strings.
func parseTXTRData(rdata []byte) []string {
	var strs []string
	off := 0
	for off < len(rdata) {
		slen := int(rdata[off])
		off++
		if off+slen > len(rdata) {
			break
		}
		strs = append(strs, string(rdata[off:off+slen]))
		off += slen
	}
	return strs
}

// Base32LabelSplit encodes payload as base32 (no padding) and splits into
// DNS labels of at most 63 bytes, appended under zone. Returns the QNAME.
// Uses a custom base32 alphabet (lowercase, no padding) suitable for DNS labels.
func Base32LabelSplit(payload []byte, zone string) (string, error) {
	encoded := base32HexEncode(payload)
	zone = strings.TrimSuffix(zone, ".")

	// Split encoded string into labels of max 63 chars.
	var labels []string
	for len(encoded) > 0 {
		n := 63
		if n > len(encoded) {
			n = len(encoded)
		}
		labels = append(labels, encoded[:n])
		encoded = encoded[n:]
	}

	// Append zone labels.
	if zone != "" {
		labels = append(labels, strings.Split(zone, ".")...)
	}

	// Validate total QNAME length (each label: 1 byte length + content, plus trailing 0).
	totalLen := 1 // trailing zero
	for _, l := range labels {
		totalLen += 1 + len(l)
	}
	if totalLen > 255 {
		return "", fmt.Errorf("dns: QNAME length %d exceeds 255", totalLen)
	}

	return strings.Join(labels, "."), nil
}

// base32HexEncode uses a lowercase hex-alphabet base32 encoding without padding,
// suitable for DNS labels.
func base32HexEncode(data []byte) string {
	const alphabet = "0123456789abcdefghijklmnopqrstuv"
	if len(data) == 0 {
		return ""
	}
	var sb strings.Builder
	sb.Grow((len(data)*8 + 4) / 5)

	var buffer uint64
	var bitsLeft int

	for _, b := range data {
		buffer = (buffer << 8) | uint64(b)
		bitsLeft += 8
		for bitsLeft >= 5 {
			bitsLeft -= 5
			idx := (buffer >> uint(bitsLeft)) & 0x1F
			sb.WriteByte(alphabet[idx])
		}
	}
	if bitsLeft > 0 {
		idx := (buffer << uint(5-bitsLeft)) & 0x1F
		sb.WriteByte(alphabet[idx])
	}
	return sb.String()
}

// RandomLabel returns a random lowercase alphanumeric string of length n.
func RandomLabel(n int) string {
	const chars = "abcdefghijklmnopqrstuvwxyz0123456789"
	b := make([]byte, n)
	for i := range b {
		b[i] = chars[rand.Intn(len(chars))]
	}
	return string(b)
}
